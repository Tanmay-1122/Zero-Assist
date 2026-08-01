/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.model.VoiceModel
import com.zeroclaw.android.model.VoiceModelSource
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.produceIn
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withTimeoutOrNull

class CustomVoiceSpeechOutputDriver(
    private val resolver: CustomVoicePackageResolver = CustomVoicePackageResolver(),
    private val runtime: CustomVoiceRuntime = MissingCustomVoiceRuntime,
    private val audioPlayer: CustomVoiceAudioPlayer = AndroidPcmAudioPlayer(),
    private val phraseCache: CustomVoicePhraseCache = CustomVoicePhraseCache(),
    private val deviceTier: VoiceDeviceTier = VoiceDeviceTier.MID,
    private val firstAudioTimeoutMs: Long = defaultFirstAudioTimeoutMs(VoiceDeviceTier.MID),
    private val firstAudioTimeoutCooldownMs: Long = DEFAULT_FIRST_AUDIO_TIMEOUT_COOLDOWN_MS,
) : LocalSpeechOutputDriver {
    private val resolvedPackages = ConcurrentHashMap<String, ResolvedCustomVoicePackage>()
    private val packageByManifestUri = ConcurrentHashMap<String, ResolvedCustomVoicePackage>()
    private val firstAudioTimeoutCooldownUntilByVoice = ConcurrentHashMap<String, Long>()

    override val status: StateFlow<LocalSpeechEngineStatus> = runtime.status
    override val engine: LocalSpeechOutputEngine = LocalSpeechOutputEngine.CUSTOM_VOICE

    override fun findVoice(voice: VoiceModel): LocalSpeechOutputVoice? {
        if (voice.source != VoiceModelSource.IMPORTED &&
            !voice.modelUri.orEmpty().isLocalCustomVoiceUri()
        ) {
            return null
        }
        val manifestUri = voice.modelUri?.trim().orEmpty()
        if (manifestUri.isBlank()) return null
        if (manifestUri.startsWith(AndroidLocalSpeechOutputDriver.ANDROID_TTS_URI_PREFIX)) {
            return null
        }

        val voicePackage = resolvePackage(manifestUri) ?: return null
        if (!runtime.supports(voicePackage.manifest.runtime.type)) {
            return null
        }

        val outputVoiceId = outputVoiceId(voice.id)
        resolvedPackages[outputVoiceId] = voicePackage
        return LocalSpeechOutputVoice(
            id = outputVoiceId,
            localeTag = voicePackage.manifest.localeTag,
            requiresNetwork = false,
        )
    }

    override suspend fun speak(
        text: String,
        voice: LocalSpeechOutputVoice,
    ): SpeechSynthesisResult =
        speak(text, voice, VoiceTurnTrace.noop())

    @OptIn(ExperimentalCoroutinesApi::class)
    override suspend fun speak(
        text: String,
        voice: LocalSpeechOutputVoice,
        trace: VoiceTurnTrace,
    ): SpeechSynthesisResult {
        val voicePackage =
            resolvedPackages[voice.id]
                ?: return SpeechSynthesisResult.Failed(
                    "Custom voice package is not ready for playback.",
                )
        trace.mark(
            "custom_tts_runtime_selected",
            "voice=${voice.id} runtime=${voicePackage.manifest.runtime.type}",
        )
        val nowEpochMs = System.currentTimeMillis()
        val cooldownUntilEpochMs = firstAudioTimeoutCooldownUntilByVoice[voice.id] ?: 0L
        if (nowEpochMs < cooldownUntilEpochMs) {
            trace.mark(
                "custom_tts_circuit_open",
                "voice=${voice.id} retryInMs=${cooldownUntilEpochMs - nowEpochMs}",
            )
            return SpeechSynthesisResult.Failed(CUSTOM_VOICE_FIRST_AUDIO_TIMEOUT_MESSAGE)
        }
        val phraseCacheKey = phraseCache.keyFor(voice.id, text)
        phraseCacheKey?.let { key ->
            phraseCache.get(key)?.let { cachedAudio ->
                trace.mark(
                    "custom_tts_phrase_cache_hit",
                    "voice=${voice.id} chunks=${cachedAudio.size}",
                )
                return playAudioChunks(cachedAudio.asFlow(), trace)
            }
        }
        val cacheableChunks =
            if (phraseCacheKey != null) {
                mutableListOf<CustomVoicePcmAudio>()
            } else {
                null
            }
        val audioChunks =
            flow {
                coroutineScope {
                    val firstAudioTimeoutForUtterance =
                        firstAudioTimeoutForText(
                            text = text,
                            cacheable = phraseCacheKey != null,
                        )
                    val streamChannel =
                        runtime
                            .synthesizeStream(text, voicePackage, trace)
                            .produceIn(this)
                    val firstEvent =
                        withTimeoutOrNull(firstAudioTimeoutForUtterance) {
                            streamChannel.receiveCatching()
                        } ?: run {
                            throw CustomVoiceSynthesisStreamException(
                                CUSTOM_VOICE_FIRST_AUDIO_TIMEOUT_MESSAGE,
                            )
                        }
                    firstEvent.getOrNull()?.let { event ->
                        emitSynthesisEvent(
                            event = event,
                            trace = trace,
                            cacheableChunks = cacheableChunks,
                            emitAudio = { audio -> emit(audio) },
                        )
                    }
                    while (true) {
                        val event =
                            streamChannel
                                .receiveCatching()
                                .getOrNull()
                                ?: break
                        emitSynthesisEvent(
                            event = event,
                            trace = trace,
                            cacheableChunks = cacheableChunks,
                            emitAudio = { audio -> emit(audio) },
                        )
                    }
                }
            }

        val playbackResult = playAudioChunks(audioChunks, trace)
        if (playbackResult is SpeechSynthesisResult.Failed &&
            playbackResult.message == CUSTOM_VOICE_FIRST_AUDIO_TIMEOUT_MESSAGE
        ) {
            firstAudioTimeoutCooldownUntilByVoice[voice.id] =
                System.currentTimeMillis() + firstAudioTimeoutCooldownMs.coerceAtLeast(0L)
        }
        if (
            playbackResult == SpeechSynthesisResult.Completed &&
            phraseCacheKey != null &&
            cacheableChunks != null
        ) {
            phraseCache.put(phraseCacheKey, cacheableChunks)
        }
        return playbackResult
    }

    private suspend fun playAudioChunks(
        audioChunks: Flow<CustomVoicePcmAudio>,
        trace: VoiceTurnTrace,
    ): SpeechSynthesisResult =
        try {
            val playbackResult = audioPlayer.playStream(audioChunks, trace)
            when (playbackResult) {
                SpeechSynthesisResult.Completed ->
                    trace.markOnce("playback_completed", "driver=custom_pcm_stream")
                SpeechSynthesisResult.Cancelled ->
                    trace.mark("playback_cancelled", "driver=custom_pcm_stream")
                is SpeechSynthesisResult.Failed ->
                    trace.mark("playback_failed", playbackResult.message)
            }
            playbackResult
        } catch (error: CustomVoiceSynthesisStreamException) {
            trace.markOnce("tts_synthesis_failed", error.message.orEmpty())
            SpeechSynthesisResult.Failed(error.message ?: "Custom voice synthesis failed.")
        }

    private fun firstAudioTimeoutForText(
        text: String,
        cacheable: Boolean,
    ): Long {
        if (cacheable) {
            return firstAudioTimeoutMs
        }
        return if (text.length >= LONG_TEXT_FIRST_AUDIO_THRESHOLD_CHARS) {
            max(firstAudioTimeoutMs, longTextFirstAudioTimeoutMs(deviceTier))
        } else {
            firstAudioTimeoutMs
        }
    }

    private suspend fun emitSynthesisEvent(
        event: CustomVoiceSynthesisStreamEvent,
        trace: VoiceTurnTrace,
        cacheableChunks: MutableList<CustomVoicePcmAudio>?,
        emitAudio: suspend (CustomVoicePcmAudio) -> Unit,
    ) {
        when (event) {
            is CustomVoiceSynthesisStreamEvent.Failed ->
                throw CustomVoiceSynthesisStreamException(event.message)
            is CustomVoiceSynthesisStreamEvent.Audio -> {
                val audio = event.audio
                trace.markOnce(
                    "first_tts_audio_ready",
                    "driver=custom_pcm_stream sampleRate=${audio.sampleRateHz} " +
                        "pcmBytes=${audio.pcm16Mono.size} " +
                        "pcmDurationMs=${audio.durationMs()} " +
                        "segment=${event.segmentIndex + 1}/${event.segmentCount}",
                )
                if (event.segmentIndex > 0) {
                    trace.mark(
                        "tts_audio_chunk_ready",
                        "driver=custom_pcm_stream sampleRate=${audio.sampleRateHz} " +
                            "pcmBytes=${audio.pcm16Mono.size} " +
                            "pcmDurationMs=${audio.durationMs()} " +
                            "segment=${event.segmentIndex + 1}/${event.segmentCount}",
                    )
                }
                cacheableChunks?.add(audio.copyForCache())
                emitAudio(audio)
            }
        }
    }

    override suspend fun prepare(voice: LocalSpeechOutputVoice): SpeechSynthesisResult {
        return prepare(voice, VoiceTurnTrace.noop())
    }

    override suspend fun prepare(
        voice: LocalSpeechOutputVoice,
        trace: VoiceTurnTrace,
    ): SpeechSynthesisResult {
        val voicePackage =
            resolvedPackages[voice.id]
                ?: return SpeechSynthesisResult.Failed(
                    "Custom voice package is not ready for playback.",
                )
        trace.mark(
            "custom_tts_prepare_runtime",
            "voice=${voice.id} runtime=${voicePackage.manifest.runtime.type}",
        )
        return runtime.prepare(voicePackage)
    }

    override fun stop() {
        // Stop is called from UI events such as mic taps and popup dismissal.
        // Keep the ONNX runtime warm here; closing it can block behind an
        // in-flight Piper load/synthesis and stall the main thread.
        audioPlayer.stop()
    }

    /**
     * Pre-cache common assistant phrases (Phase 11 optimization).
     * Call this asynchronously after voice is prepared to reduce latency for
     * common responses like "Done", "I did not catch that", etc.
     * Does not block on completion; caching happens in background.
     */
    suspend fun preCacheCommonPhrases(
        voice: LocalSpeechOutputVoice,
    ) {
        val voicePackage = resolvedPackages[voice.id] ?: return
        for (phrase in COMMON_PHRASES_FOR_CACHE) {
            val cacheKey = phraseCache.keyFor(voice.id, phrase)
            if (cacheKey != null && phraseCache.get(cacheKey) == null) {
                // Only cache if not already cached
                when (val result = runtime.synthesize(phrase, voicePackage, VoiceTurnTrace.noop())) {
                    is CustomVoiceSynthesisResult.Success -> {
                        val chunks = listOf(result.audio)
                        phraseCache.put(cacheKey, chunks)
                    }
                    is CustomVoiceSynthesisResult.Failed -> {
                        // Silently skip; caching is optional optimization
                    }
                }
            }
        }
    }

    private fun outputVoiceId(voiceId: String): String = "$CUSTOM_VOICE_PREFIX$voiceId"

    private fun resolvePackage(manifestUri: String): ResolvedCustomVoicePackage? {
        packageByManifestUri[manifestUri]?.let { cached -> return cached }
        return when (val result = resolver.resolve(manifestUri)) {
            is CustomVoicePackageResolveResult.Failure -> null
            is CustomVoicePackageResolveResult.Success -> {
                packageByManifestUri[manifestUri] = result.voicePackage
                result.voicePackage
            }
        }
    }

    companion object {
        const val CUSTOM_VOICE_PREFIX = "custom-voice:"
    }
}

private fun String.isLocalCustomVoiceUri(): Boolean =
    startsWith("file:", ignoreCase = true) ||
        startsWith("content://", ignoreCase = true)

class CustomVoicePhraseCache(
    private val maxEntries: Int = 16,
    private val maxTextLength: Int = 120,
    private val maxEntryBytes: Int = 384 * 1024,
) {
    data class Key(
        val voiceId: String,
        val text: String,
    )

    private val entries =
        object : LinkedHashMap<Key, List<CustomVoicePcmAudio>>(maxEntries, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<Key, List<CustomVoicePcmAudio>>,
            ): Boolean = size > maxEntries
        }

    fun keyFor(
        voiceId: String,
        text: String,
    ): Key? {
        val normalized = text.normalizePhraseText()
        if (normalized.isBlank() || normalized.length > maxTextLength) return null
        return Key(voiceId = voiceId, text = normalized)
    }

    fun get(key: Key): List<CustomVoicePcmAudio>? =
        synchronized(entries) {
            entries[key]?.map { audio -> audio.copyForCache() }
        }

    fun put(
        key: Key,
        audioChunks: List<CustomVoicePcmAudio>,
    ) {
        if (audioChunks.isEmpty()) return
        val totalBytes = audioChunks.sumOf { audio -> audio.pcm16Mono.size }
        if (totalBytes > maxEntryBytes) return
        synchronized(entries) {
            entries[key] = audioChunks.map { audio -> audio.copyForCache() }
        }
    }
}

private fun String.normalizePhraseText(): String =
    trim()
        .replace(Regex("""\s+"""), " ")

private fun CustomVoicePcmAudio.copyForCache(): CustomVoicePcmAudio =
    copy(pcm16Mono = pcm16Mono.copyOf())

private fun CustomVoicePcmAudio.durationMs(): Long =
    if (sampleRateHz <= 0) {
        0L
    } else {
        ((pcm16Mono.size / PCM_16_MONO_BYTES_PER_FRAME).toLong() * 1_000L) / sampleRateHz
    }

private const val PCM_16_MONO_BYTES_PER_FRAME = 2

/**
 * Per-tier first-audio timeouts for the custom (Piper/ONNX) voice driver.
 *
 * These account for cold ONNX session creation + first inference on each tier:
 *  - HIGH  : model already warm after pre-warm, so 900 ms is enough.
 *  - MID   : pre-warm still takes a few seconds; 5 s gives inference headroom.
 *  - LOW   : slow single-core devices can take 15–25 s for first inference;
 *            12 s lets the model at least finish before we fall back to TTS.
 *
 * After a timeout the circuit opens for COOLDOWN_MS, during which every speak()
 * call immediately returns Failed so the composite driver's fallback to Android
 * TTS fires without waiting again.
 */
internal fun defaultFirstAudioTimeoutMs(tier: VoiceDeviceTier): Long =
    when (tier) {
        VoiceDeviceTier.HIGH -> 900L
        VoiceDeviceTier.MID  -> 5_000L
        VoiceDeviceTier.LOW  -> 12_000L
    }

private const val DEFAULT_FIRST_AUDIO_TIMEOUT_MS = 5_000L   // MID-tier default when tier unknown
private const val DEFAULT_FIRST_AUDIO_TIMEOUT_COOLDOWN_MS = 5_000L  // was 15_000L – fall back faster
private const val LONG_TEXT_FIRST_AUDIO_THRESHOLD_CHARS = 160

/** Long-text extra headroom per tier. */
private fun longTextFirstAudioTimeoutMs(tier: VoiceDeviceTier): Long =
    when (tier) {
        VoiceDeviceTier.HIGH -> 1_800L
        VoiceDeviceTier.MID  -> 8_000L
        VoiceDeviceTier.LOW  -> 20_000L
    }

/**
 * Common assistant phrases to pre-synthesize on first voice use.
 * Reduces latency for frequently repeated responses.
 */
private val COMMON_PHRASES_FOR_CACHE = listOf(
    "Done.",
    "One moment.",
    "I did not catch that.",
    "Voice request cancelled.",
    "Opening WhatsApp.",
    "Opening Gmail.",
    "Opening Messages.",
    "What else can I help you with?",
)

internal const val CUSTOM_VOICE_FIRST_AUDIO_TIMEOUT_MESSAGE =
    "Custom voice synthesis timed out before audio was ready."

private class CustomVoiceSynthesisStreamException(message: String) : RuntimeException(message)
