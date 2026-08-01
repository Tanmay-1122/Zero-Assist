/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.model.VoiceModel
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class CompositeLocalSpeechOutputDriver(
    private val drivers: List<LocalSpeechOutputDriver>,
    scope: CoroutineScope,
    private val routingPolicy: VoiceOutputRoutingPolicy = VoiceOutputRoutingPolicy(),
) : LocalSpeechOutputDriver {
    private val resolutionByOutputVoiceId = ConcurrentHashMap<String, VoiceDriverResolution>()
    private val _status = MutableStateFlow(resolveStatus())

    override val status: StateFlow<LocalSpeechEngineStatus> = _status
    override val engine: LocalSpeechOutputEngine = LocalSpeechOutputEngine.OTHER

    init {
        drivers.forEach { driver ->
            scope.launch {
                driver.status.collect {
                    _status.value = resolveStatus()
                }
            }
        }
    }

    override fun findVoice(voice: VoiceModel): LocalSpeechOutputVoice? {
        val candidates =
            routingPolicy
                .orderedDrivers(voice, drivers)
                .mapNotNull { driver ->
                    driver.findVoice(voice)?.let { outputVoice ->
                        VoiceDriverCandidate(
                            driver = driver,
                            voice = outputVoice,
                        )
                    }
                }
        val primary = candidates.firstOrNull() ?: return null
        resolutionByOutputVoiceId[primary.voice.id] =
            VoiceDriverResolution(
                primary = primary,
                fallback =
                    candidates
                        .drop(1)
                        .firstOrNull { candidate ->
                            candidate.driver.engine == LocalSpeechOutputEngine.ANDROID_TTS &&
                                !candidate.voice.requiresNetwork
                        },
            )
        return primary.voice
    }

    override suspend fun speak(
        text: String,
        voice: LocalSpeechOutputVoice,
    ): SpeechSynthesisResult =
        speak(text, voice, VoiceTurnTrace.noop())

    override suspend fun speak(
        text: String,
        voice: LocalSpeechOutputVoice,
        trace: VoiceTurnTrace,
    ): SpeechSynthesisResult {
        val resolution =
            resolutionByOutputVoiceId[voice.id]
                ?: return SpeechSynthesisResult.Failed(
                    "Local speech output driver is unavailable for this voice.",
                )
        trace.markOnce("tts_driver_selected", resolution.primary.driver.javaClass.simpleName)
        val primaryResult = resolution.primary.driver.speak(text, resolution.primary.voice, trace)
        if (primaryResult !is SpeechSynthesisResult.Failed) {
            return primaryResult
        }
        val fallback = resolution.fallback
        if (fallback != null && shouldFallbackAfter(primaryResult, resolution)) {
            trace.mark(
                "tts_driver_fallback",
                "from=${resolution.primary.driver.javaClass.simpleName} " +
                    "to=${fallback.driver.javaClass.simpleName} reason=${primaryResult.message}",
            )
            return fallback.driver.speak(text, fallback.voice, trace)
        }
        return primaryResult
    }

    override suspend fun prepare(voice: LocalSpeechOutputVoice): SpeechSynthesisResult {
        return prepare(voice, VoiceTurnTrace.noop())
    }

    override suspend fun prepare(
        voice: LocalSpeechOutputVoice,
        trace: VoiceTurnTrace,
    ): SpeechSynthesisResult {
        val resolution =
            resolutionByOutputVoiceId[voice.id]
                ?: return SpeechSynthesisResult.Failed(
                    "Local speech output driver is unavailable for this voice.",
                )
        trace.mark("tts_prepare_driver_selected", resolution.primary.driver.javaClass.simpleName)
        return resolution.primary.driver.prepare(resolution.primary.voice, trace)
    }

    override fun stop() {
        drivers.forEach { driver -> driver.stop() }
    }

    private fun resolveStatus(): LocalSpeechEngineStatus {
        val statuses = drivers.map { driver -> driver.status.value }
        return when {
            statuses.any { it == LocalSpeechEngineStatus.Ready } ->
                LocalSpeechEngineStatus.Ready
            statuses.any { it == LocalSpeechEngineStatus.Initializing } ->
                LocalSpeechEngineStatus.Initializing
            statuses.any { it == LocalSpeechEngineStatus.PermissionRequired } ->
                LocalSpeechEngineStatus.PermissionRequired
            statuses.all { it == LocalSpeechEngineStatus.MissingModel } ->
                LocalSpeechEngineStatus.MissingModel
            statuses.any { it == LocalSpeechEngineStatus.MissingModel } ->
                LocalSpeechEngineStatus.MissingModel
            else ->
                statuses
                    .filterIsInstance<LocalSpeechEngineStatus.Unavailable>()
                    .firstOrNull()
                    ?: LocalSpeechEngineStatus.Unavailable("Local speech output is unavailable.")
        }
    }

    private fun shouldFallbackAfter(
        result: SpeechSynthesisResult.Failed,
        resolution: VoiceDriverResolution,
    ): Boolean {
        val fallback = resolution.fallback ?: return false
        return result.message == CUSTOM_VOICE_FIRST_AUDIO_TIMEOUT_MESSAGE &&
            resolution.primary.driver.engine == LocalSpeechOutputEngine.CUSTOM_VOICE &&
            fallback.driver.engine == LocalSpeechOutputEngine.ANDROID_TTS &&
            !fallback.voice.requiresNetwork
    }

    private data class VoiceDriverCandidate(
        val driver: LocalSpeechOutputDriver,
        val voice: LocalSpeechOutputVoice,
    )

    private data class VoiceDriverResolution(
        val primary: VoiceDriverCandidate,
        val fallback: VoiceDriverCandidate?,
    )
}
