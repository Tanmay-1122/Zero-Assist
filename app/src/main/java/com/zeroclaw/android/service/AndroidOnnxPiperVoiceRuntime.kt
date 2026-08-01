/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import android.os.Process
import android.util.Log
import com.zeroclaw.android.model.CustomVoiceRuntimeType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtException
import ai.onnxruntime.OrtSession
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/**
 * Phone-local Piper runtime backed by ONNX Runtime Android.
 *
 * This runtime supports Piper packages whose config uses `phoneme_type = text`,
 * plus English eSpeak Piper packages through a local package lexicon, rule
 * phonemizer, or native bridge. It never falls back to a cloud phoneme or TTS
 * service.
 */
internal class AndroidOnnxPiperVoiceRuntime(
    private val configParser: PiperVoiceConfigParser = PiperVoiceConfigParser(),
    private val phonemizer: PiperPhonemizer = LocalPiperPhonemizer(),
    private val threadPolicy: PiperOnnxRuntimeThreadPolicy = PiperOnnxRuntimeThreadPolicy.default(),
) : CustomVoiceRuntime {
    private val environment = runCatching { OrtEnvironment.getEnvironment() }.getOrNull()
    private val loadedModels = ConcurrentHashMap<String, LoadedPiperModel>()
    private val modelLifecycleLock = ReentrantLock()
    private val runtimeDispatcher =
        Executors
            .newSingleThreadExecutor { runnable ->
                Thread(
                    {
                        // Use FOREGROUND priority so the scheduler does not starve
                        // Piper synthesis behind app-visible UI work. BACKGROUND was
                        // causing 20+ second synthesis times on low-end devices.
                        Process.setThreadPriority(Process.THREAD_PRIORITY_FOREGROUND)
                        runnable.run()
                    },
                    "PiperOnnxRuntime",
                ).apply {
                    isDaemon = true
                    priority = Thread.NORM_PRIORITY
                }
            }.asCoroutineDispatcher()
    private val _status =
        MutableStateFlow<LocalSpeechEngineStatus>(
            if (environment == null) {
                LocalSpeechEngineStatus.Unavailable("ONNX Runtime Android is unavailable.")
            } else {
                LocalSpeechEngineStatus.Ready
            },
        )

    override val status: StateFlow<LocalSpeechEngineStatus> = _status

    override fun supports(runtimeType: String): Boolean =
        runtimeType.trim().equals(CustomVoiceRuntimeType.PIPER_V1, ignoreCase = true) &&
            environment != null

    override suspend fun prepare(voicePackage: ResolvedCustomVoicePackage): SpeechSynthesisResult =
        withContext(runtimeDispatcher) {
            val env =
                environment
                    ?: return@withContext SpeechSynthesisResult.Failed(
                        "ONNX Runtime Android is unavailable on this phone.",
                    )
            if (!supports(voicePackage.manifest.runtime.type)) {
                return@withContext SpeechSynthesisResult.Failed(
                    "This custom voice runtime is not supported locally.",
                )
            }
            when (val result = loadOrGetModel(env, voicePackage)) {
                is LoadPiperModelResult.Failure ->
                    SpeechSynthesisResult.Failed(result.message)
                is LoadPiperModelResult.Success ->
                    SpeechSynthesisResult.Completed
            }
        }

    override suspend fun synthesize(
        text: String,
        voicePackage: ResolvedCustomVoicePackage,
    ): CustomVoiceSynthesisResult =
        synthesize(text, voicePackage, VoiceTurnTrace.noop())

    override suspend fun synthesize(
        text: String,
        voicePackage: ResolvedCustomVoicePackage,
        trace: VoiceTurnTrace,
    ): CustomVoiceSynthesisResult =
        withContext(runtimeDispatcher) {
            val env =
                environment
                    ?: return@withContext CustomVoiceSynthesisResult.Failed(
                        "ONNX Runtime Android is unavailable on this phone.",
                    )
            if (!supports(voicePackage.manifest.runtime.type)) {
                return@withContext CustomVoiceSynthesisResult.Failed(
                    "This custom voice runtime is not supported locally.",
                )
            }

            val loaded =
                when (val result = loadOrGetModel(env, voicePackage)) {
                    is LoadPiperModelResult.Failure ->
                        return@withContext CustomVoiceSynthesisResult.Failed(result.message)
                    is LoadPiperModelResult.Success -> result.model
                }

            val encodedSegments = mutableListOf<EncodedPiperSpeechSegment>()
            for (segment in text.toHumanizedSpeechSegments()) {
                val inputIds =
                    when (val encoded = phonemizer.encode(segment.text, loaded.config, voicePackage)) {
                        is PiperPhonemeEncodeResult.Failure ->
                            return@withContext CustomVoiceSynthesisResult.Failed(encoded.message)
                        is PiperPhonemeEncodeResult.Success -> encoded.inputIds
                    }
                encodedSegments +=
                    EncodedPiperSpeechSegment(
                        inputIds = inputIds,
                        pauseAfterMs = segment.pauseAfterMs,
                    )
            }
            trace.mark(
                "piper_input_ids_encoded",
                "segments=${encodedSegments.size} inputIds=${encodedSegments.sumOf { it.inputIds.size }}",
            )

            runCatching {
                loaded.sessionLock.withLock {
                    loaded.session.runPiperSegments(env, encodedSegments, loaded.config)
                }
            }.fold(
                onSuccess = { audio ->
                    CustomVoiceSynthesisResult.Success(
                        CustomVoicePcmAudio(
                            sampleRateHz = loaded.config.sampleRateHz,
                            pcm16Mono = audio.toPcm16Mono(),
                        ),
                    )
                },
                onFailure = { error ->
                    CustomVoiceSynthesisResult.Failed(
                        "Piper ONNX synthesis failed: ${error.message ?: error::class.java.simpleName}",
                    )
                },
            )
        }

    override fun synthesizeStream(
        text: String,
        voicePackage: ResolvedCustomVoicePackage,
        trace: VoiceTurnTrace,
    ): Flow<CustomVoiceSynthesisStreamEvent> =
        flow {
            val prepared =
                withContext(runtimeDispatcher) {
                    preparePiperSynthesis(text, voicePackage)
                }
            val success =
                when (prepared) {
                    is PreparePiperSynthesisResult.Failure -> {
                        emit(CustomVoiceSynthesisStreamEvent.Failed(prepared.message))
                        return@flow
                    }
                    is PreparePiperSynthesisResult.Success -> prepared
                }
            trace.mark(
                "piper_input_ids_encoded",
                "segments=${success.encodedSegments.size} " +
                    "inputIds=${success.encodedSegments.sumOf { it.inputIds.size }}",
            )

            val segmentCount = success.encodedSegments.size
            success.encodedSegments.forEachIndexed { index, segment ->
                val audio =
                    withContext(runtimeDispatcher) {
                        runCatching {
                            trace.mark(
                                "piper_stream_segment_started",
                                "index=${index + 1}/$segmentCount inputIds=${segment.inputIds.size}",
                            )
                            val rendered =
                                success.loaded.sessionLock.withLock {
                                    success.loaded.session.runPiper(
                                        success.env,
                                        segment.inputIds,
                                        success.loaded.config,
                                    )
                                }
                            val withPause =
                                if (index < segmentCount - 1 && segment.pauseAfterMs > 0) {
                                    rendered.withTrailingSilence(
                                        success.loaded.config.pauseFrameCount(segment.pauseAfterMs),
                                    )
                                } else {
                                    rendered
                                }
                            withPause.toPcm16Mono()
                        }
                    }
                val error = audio.exceptionOrNull()
                if (error != null) {
                    emit(
                        CustomVoiceSynthesisStreamEvent.Failed(
                            "Piper ONNX synthesis failed: " +
                                (error.message ?: error::class.java.simpleName),
                        ),
                    )
                    return@flow
                }
                emit(
                    CustomVoiceSynthesisStreamEvent.Audio(
                        audio =
                            CustomVoicePcmAudio(
                                sampleRateHz = success.loaded.config.sampleRateHz,
                                pcm16Mono = audio.getOrThrow(),
                            ),
                        segmentIndex = index,
                        segmentCount = segmentCount,
                    ),
                )
            }
        }

    override fun stop() {
        val models =
            modelLifecycleLock.withLock {
                loadedModels.values.toList().also {
                    loadedModels.clear()
                }
            }
        models.forEach { model ->
            model.sessionLock.withLock {
                runCatching { model.session.close() }
            }
        }
    }

    private fun parseConfig(
        voicePackage: ResolvedCustomVoicePackage,
    ): LoadPiperConfigResult {
        val configFile =
            voicePackage.configFile
                ?: return LoadPiperConfigResult.Failure(
                    "Piper custom voice package is missing its local config file.",
                )
        val config =
            when (val parsed = configParser.parse(configFile.readText(Charsets.UTF_8))) {
                is PiperVoiceConfigParseResult.Failure ->
                    return LoadPiperConfigResult.Failure(parsed.message)
                is PiperVoiceConfigParseResult.Success -> parsed.config
            }
        if (config.sampleRateHz != voicePackage.manifest.runtime.sampleRateHz) {
            return LoadPiperConfigResult.Failure(
                "Piper config sample rate does not match the voice package manifest.",
            )
        }
        return LoadPiperConfigResult.Success(config)
    }

    private fun loadOrGetModel(
        env: OrtEnvironment,
        voicePackage: ResolvedCustomVoicePackage,
    ): LoadPiperModelResult =
        modelLifecycleLock.withLock {
            val cacheKey = voicePackage.modelFile.absolutePath
            val cachedModel = loadedModels[cacheKey]
            val config =
                cachedModel?.config
                    ?: when (val result = parseConfig(voicePackage)) {
                        is LoadPiperConfigResult.Failure ->
                            return@withLock LoadPiperModelResult.Failure(result.message)
                        is LoadPiperConfigResult.Success -> result.config
                    }
            when (val readiness = phonemizer.readiness(config, voicePackage)) {
                PiperPhonemizerReadiness.Ready -> Unit
                is PiperPhonemizerReadiness.Unavailable ->
                    return@withLock LoadPiperModelResult.Failure(readiness.message)
            }
            cachedModel?.let { LoadPiperModelResult.Success(it) }
                ?: loadModel(env, voicePackage, config)
        }

    private fun loadModel(
        env: OrtEnvironment,
        voicePackage: ResolvedCustomVoicePackage,
        config: PiperVoiceConfig,
    ): LoadPiperModelResult {
        val session =
            try {
                OrtSession.SessionOptions().use { options ->
                    options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                    options.setIntraOpNumThreads(threadPolicy.intraOpNumThreads)
                    options.setInterOpNumThreads(threadPolicy.interOpNumThreads)
                    Log.d(
                        TAG,
                        "Loading Piper ONNX session intraOpThreads=${threadPolicy.intraOpNumThreads} " +
                            "interOpThreads=${threadPolicy.interOpNumThreads}",
                    )
                    env.createSession(voicePackage.modelFile.absolutePath, options)
                }
            } catch (e: OrtException) {
                return LoadPiperModelResult.Failure(
                    "Piper ONNX model could not be loaded locally: ${e.message}",
            )
        }
        val loaded = LoadedPiperModel(config = config, session = session)
        val cacheKey = voicePackage.modelFile.absolutePath
        val existing = loadedModels.putIfAbsent(cacheKey, loaded)
        if (existing != null) {
            runCatching { session.close() }
            return LoadPiperModelResult.Success(existing)
        }
        return LoadPiperModelResult.Success(loaded)
    }

    private fun preparePiperSynthesis(
        text: String,
        voicePackage: ResolvedCustomVoicePackage,
    ): PreparePiperSynthesisResult {
        val env =
            environment
                ?: return PreparePiperSynthesisResult.Failure(
                    "ONNX Runtime Android is unavailable on this phone.",
                )
        if (!supports(voicePackage.manifest.runtime.type)) {
            return PreparePiperSynthesisResult.Failure(
                "This custom voice runtime is not supported locally.",
            )
        }

        val loaded =
            when (val result = loadOrGetModel(env, voicePackage)) {
                is LoadPiperModelResult.Failure ->
                    return PreparePiperSynthesisResult.Failure(result.message)
                is LoadPiperModelResult.Success -> result.model
            }

        val encodedSegments = mutableListOf<EncodedPiperSpeechSegment>()
        for (segment in text.toHumanizedSpeechSegments()) {
            val inputIds =
                when (val encoded = phonemizer.encode(segment.text, loaded.config, voicePackage)) {
                    is PiperPhonemeEncodeResult.Failure ->
                        return PreparePiperSynthesisResult.Failure(encoded.message)
                    is PiperPhonemeEncodeResult.Success -> encoded.inputIds
                }
            encodedSegments +=
                EncodedPiperSpeechSegment(
                    inputIds = inputIds,
                    pauseAfterMs = segment.pauseAfterMs,
                )
        }
        if (encodedSegments.isEmpty()) {
            return PreparePiperSynthesisResult.Failure("Piper synthesis has no speech segments.")
        }
        return PreparePiperSynthesisResult.Success(
            env = env,
            loaded = loaded,
            encodedSegments = encodedSegments,
        )
    }

    private fun OrtSession.runPiper(
        env: OrtEnvironment,
        inputIds: LongArray,
        config: PiperVoiceConfig,
    ): FloatArray {
        validatePiperInputIdsForOnnx(inputIds)?.let { reason ->
            throw IllegalArgumentException(reason)
        }
        val sessionInputNames = inputNames
        val playbackScales = config.playbackScales
        Log.d(
            TAG,
            "Running Piper synthesis inputIds=${inputIds.size} " +
                "noiseScale=${playbackScales.noiseScale} " +
                "lengthScale=${playbackScales.lengthScale} noiseW=${playbackScales.noiseW}",
        )
        val tensors = mutableListOf<OnnxTensor>()
        try {
            val inputs = linkedMapOf<String, OnnxTensor>()
            val inputTensor =
                OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(inputIds),
                    longArrayOf(1L, inputIds.size.toLong()),
                )
            tensors += inputTensor
            inputs["input"] = inputTensor

            val lengthTensor =
                OnnxTensor.createTensor(
                    env,
                    LongBuffer.wrap(longArrayOf(inputIds.size.toLong())),
                    longArrayOf(1L),
                )
            tensors += lengthTensor
            inputs["input_lengths"] = lengthTensor

            val scalesTensor =
                OnnxTensor.createTensor(
                    env,
                    FloatBuffer.wrap(
                        floatArrayOf(
                            playbackScales.noiseScale,
                            playbackScales.lengthScale,
                            playbackScales.noiseW,
                        ),
                    ),
                    longArrayOf(3L),
                )
            tensors += scalesTensor
            inputs["scales"] = scalesTensor

            if ("sid" in sessionInputNames) {
                val sidTensor =
                    OnnxTensor.createTensor(
                        env,
                        LongBuffer.wrap(longArrayOf(config.speakerId ?: 0L)),
                        longArrayOf(1L),
                    )
                tensors += sidTensor
                inputs["sid"] = sidTensor
            }

            run(inputs).use { result ->
                return result.firstFloatOutput()
            }
        } finally {
            tensors.forEach { tensor -> runCatching { tensor.close() } }
        }
    }

    private fun OrtSession.runPiperSegments(
        env: OrtEnvironment,
        segments: List<EncodedPiperSpeechSegment>,
        config: PiperVoiceConfig,
    ): FloatArray {
        if (segments.isEmpty()) {
            throw IllegalStateException("Piper synthesis has no speech segments.")
        }
        if (segments.size == 1) {
            return runPiper(env, segments.first().inputIds, config)
        }

        val rendered = mutableListOf<FloatArray>()
        var totalFrames = 0
        segments.forEachIndexed { index, segment ->
            val audio = runPiper(env, segment.inputIds, config)
            rendered += audio
            totalFrames += audio.size
            if (index < segments.lastIndex && segment.pauseAfterMs > 0) {
                totalFrames += config.pauseFrameCount(segment.pauseAfterMs)
            }
        }

        val combined = FloatArray(totalFrames)
        var offset = 0
        rendered.forEachIndexed { index, audio ->
            audio.copyInto(combined, offset)
            offset += audio.size
            val pauseMs = segments[index].pauseAfterMs
            if (index < rendered.lastIndex && pauseMs > 0) {
                offset += config.pauseFrameCount(pauseMs)
            }
        }
        return combined
    }

    private fun OrtSession.Result.firstFloatOutput(): FloatArray {
        if (size() == 0) {
            throw IllegalStateException("Piper model returned no outputs.")
        }
        val value = get(0) ?: throw IllegalStateException("Piper model output is missing.")
        return value.toFloatArray()
            ?: throw IllegalStateException("Piper model output is not float audio.")
    }

    private fun OnnxValue.toFloatArray(): FloatArray? {
        val collected = mutableListOf<Float>()
        value.collectFloats(collected)
        return collected.takeIf { it.isNotEmpty() }?.toFloatArray()
    }

    private fun Any?.collectFloats(destination: MutableList<Float>) {
        when (this) {
            is Float -> destination += this
            is FloatArray -> destination += asIterable()
            is Array<*> -> forEach { item -> item.collectFloats(destination) }
        }
    }

    private fun FloatArray.toPcm16Mono(): ByteArray {
        val buffer =
            ByteBuffer
                .allocate(size * 2)
                .order(ByteOrder.LITTLE_ENDIAN)
        forEach { sample ->
            val clamped = sample.coerceIn(-1f, 1f)
            buffer.putShort((clamped * Short.MAX_VALUE).toInt().toShort())
        }
        return buffer.array()
    }

    private fun FloatArray.withTrailingSilence(frames: Int): FloatArray {
        if (frames <= 0) {
            return this
        }
        val combined = FloatArray(size + frames)
        copyInto(combined)
        return combined
    }

    private data class LoadedPiperModel(
        val config: PiperVoiceConfig,
        val session: OrtSession,
        val sessionLock: ReentrantLock = ReentrantLock(),
    )

    private data class HumanizedSpeechSegment(
        val text: String,
        val pauseAfterMs: Int,
    )

    private data class EncodedPiperSpeechSegment(
        val inputIds: LongArray,
        val pauseAfterMs: Int,
    )

    private data class PiperPlaybackScales(
        val noiseScale: Float,
        val lengthScale: Float,
        val noiseW: Float,
    )

    private sealed interface LoadPiperConfigResult {
        data class Success(val config: PiperVoiceConfig) : LoadPiperConfigResult

        data class Failure(val message: String) : LoadPiperConfigResult
    }

    private sealed interface LoadPiperModelResult {
        data class Success(val model: LoadedPiperModel) : LoadPiperModelResult

        data class Failure(val message: String) : LoadPiperModelResult
    }

    private sealed interface PreparePiperSynthesisResult {
        data class Success(
            val env: OrtEnvironment,
            val loaded: LoadedPiperModel,
            val encodedSegments: List<EncodedPiperSpeechSegment>,
        ) : PreparePiperSynthesisResult

        data class Failure(val message: String) : PreparePiperSynthesisResult
    }

    private val PiperVoiceConfig.playbackScales: PiperPlaybackScales
        get() =
            PiperPlaybackScales(
                noiseScale =
                    (noiseScale * PIPER_NOISE_SCALE_VARIATION)
                        .coerceIn(MIN_PIPER_NOISE_SCALE, MAX_PIPER_NOISE_SCALE),
                lengthScale =
                    (lengthScale * PIPER_LENGTH_SCALE_SLOWDOWN)
                        .coerceIn(MIN_PIPER_LENGTH_SCALE, MAX_PIPER_LENGTH_SCALE),
                noiseW =
                    (noiseW * PIPER_NOISE_W_VARIATION)
                        .coerceIn(MIN_PIPER_NOISE_W, MAX_PIPER_NOISE_W),
            )

    private fun PiperVoiceConfig.pauseFrameCount(pauseMs: Int): Int =
        ((sampleRateHz * pauseMs) / 1000).coerceAtLeast(0)

    private fun String.toHumanizedSpeechSegments(): List<HumanizedSpeechSegment> {
        val normalized = trim().replace(Regex("\\s+"), " ")
        if (normalized.isBlank()) {
            return emptyList()
        }
        val segments =
            HUMAN_SPEECH_SEGMENT_PATTERN
                .findAll(normalized)
                .flatMap { match ->
                    val rawSegment = match.value.trim()
                    if (rawSegment.isBlank()) {
                        emptyList()
                    } else {
                        val pauseAfterMs = rawSegment.speechPauseAfterMs()
                        rawSegment.splitLongSpeechSegment(pauseAfterMs)
                    }
                }.toList()
        return segments.ifEmpty {
            listOf(HumanizedSpeechSegment(normalized, pauseAfterMs = 0))
        }
    }

    private fun String.speechPauseAfterMs(): Int =
        when (lastOrNull()) {
            '.', '!', '?' -> SENTENCE_PAUSE_MS
            ';', ':' -> CLAUSE_PAUSE_MS
            ',' -> COMMA_PAUSE_MS
            else -> SHORT_PHRASE_PAUSE_MS
        }

    private fun String.splitLongSpeechSegment(finalPauseAfterMs: Int): List<HumanizedSpeechSegment> {
        val words = split(Regex("\\s+")).filter { word -> word.isNotBlank() }
        if (words.size <= MAX_WORDS_PER_SPEECH_SEGMENT) {
            return listOf(HumanizedSpeechSegment(this, finalPauseAfterMs))
        }
        val chunks = words.chunked(MAX_WORDS_PER_SPEECH_SEGMENT)
        return chunks
            .mapIndexed { index, chunk ->
                HumanizedSpeechSegment(
                    text = chunk.joinToString(" "),
                    pauseAfterMs =
                        if (index == chunks.lastIndex) {
                            finalPauseAfterMs
                        } else {
                            SHORT_PHRASE_PAUSE_MS
                        },
                )
            }
    }

    private companion object {
        private const val TAG = "AndroidOnnxPiperVoiceRuntime"
        private const val PIPER_LENGTH_SCALE_SLOWDOWN = 1.0f
        private const val MIN_PIPER_LENGTH_SCALE = 0.75f
        private const val MAX_PIPER_LENGTH_SCALE = 2.0f
        private const val PIPER_NOISE_SCALE_VARIATION = 1.08f
        private const val MIN_PIPER_NOISE_SCALE = 0.55f
        private const val MAX_PIPER_NOISE_SCALE = 0.82f
        private const val PIPER_NOISE_W_VARIATION = 1.10f
        private const val MIN_PIPER_NOISE_W = 0.70f
        private const val MAX_PIPER_NOISE_W = 1.05f
        private const val SENTENCE_PAUSE_MS = 320
        private const val CLAUSE_PAUSE_MS = 230
        private const val COMMA_PAUSE_MS = 150
        private const val SHORT_PHRASE_PAUSE_MS = 90
        private const val MAX_WORDS_PER_SPEECH_SEGMENT = 15
        private val HUMAN_SPEECH_SEGMENT_PATTERN = Regex("[^.!?;:,]+[.!?;:,]?")
    }
}

internal fun validatePiperInputIdsForOnnx(inputIds: LongArray): String? {
    if (inputIds.isEmpty()) {
        return "Piper ONNX input_ids is empty."
    }
    if (inputIds.size > MAX_PIPER_ONNX_INPUT_IDS) {
        return "Piper ONNX input_ids is too large for phone-local synthesis (${inputIds.size} > $MAX_PIPER_ONNX_INPUT_IDS)."
    }
    val invalid = inputIds.firstOrNull { id -> id < 0L || id > Int.MAX_VALUE.toLong() }
    if (invalid != null) {
        return "Piper ONNX input_ids contains an invalid phoneme id: $invalid."
    }
    return null
}

private const val MAX_PIPER_ONNX_INPUT_IDS = 256

internal data class PiperOnnxRuntimeThreadPolicy(
    val intraOpNumThreads: Int,
    val interOpNumThreads: Int,
) {
    companion object {
        fun default(): PiperOnnxRuntimeThreadPolicy {
            val processors = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
            return default(
                VoiceDeviceProfile(
                    tier =
                        VoiceDeviceProfileClassifier.classify(
                            totalRamMb = null,
                            availableProcessors = processors,
                        ),
                    totalRamMb = null,
                    availableProcessors = processors,
                ),
            )
        }

        fun default(deviceProfile: VoiceDeviceProfile): PiperOnnxRuntimeThreadPolicy {
            val processors = deviceProfile.availableProcessors.coerceAtLeast(1)
            val intraOpThreads =
                when (deviceProfile.tier) {
                    VoiceDeviceTier.LOW -> 1
                    VoiceDeviceTier.MID -> 2
                    VoiceDeviceTier.HIGH ->
                        (processors / 2).coerceIn(
                            minimumValue = 2,
                            maximumValue = MAX_HIGH_TIER_INTRA_OP_THREADS,
                        )
                }.coerceAtMost(processors)
                    .coerceAtLeast(1)
            return PiperOnnxRuntimeThreadPolicy(
                intraOpNumThreads = intraOpThreads,
                interOpNumThreads = 1,
            )
        }

        private const val MAX_HIGH_TIER_INTRA_OP_THREADS = 2
    }
}
