/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.model.VoiceModel
import com.zeroclaw.android.model.VoiceModelSource
import com.zeroclaw.android.model.VoiceModelStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/** Offline voice selected by the platform/local speech output driver. */
data class LocalSpeechOutputVoice(
    val id: String,
    val localeTag: String,
    val requiresNetwork: Boolean = false,
)

enum class LocalSpeechOutputEngine {
    CUSTOM_VOICE,
    ANDROID_TTS,
    OTHER,
}

/** Driver boundary for phone-local speech output engines. */
interface LocalSpeechOutputDriver {
    val status: StateFlow<LocalSpeechEngineStatus>

    val engine: LocalSpeechOutputEngine
        get() = LocalSpeechOutputEngine.OTHER

    fun findVoice(voice: VoiceModel): LocalSpeechOutputVoice?

    suspend fun prepare(voice: LocalSpeechOutputVoice): SpeechSynthesisResult =
        SpeechSynthesisResult.Completed

    suspend fun prepare(
        voice: LocalSpeechOutputVoice,
        trace: VoiceTurnTrace,
    ): SpeechSynthesisResult =
        prepare(voice)

    suspend fun speak(
        text: String,
        voice: LocalSpeechOutputVoice,
    ): SpeechSynthesisResult

    suspend fun speak(
        text: String,
        voice: LocalSpeechOutputVoice,
        trace: VoiceTurnTrace,
    ): SpeechSynthesisResult =
        speak(text, voice)

    fun stop()
}

/**
 * Popup-scoped speech output adapter.
 *
 * This adapter enforces the app's local-first voice contract before delegating
 * to a platform or model-backed driver. It must not call cloud TTS endpoints.
 */
class LocalSpeechOutputAdapter(
    private val driver: LocalSpeechOutputDriver,
) : LocalSpeechSynthesizer {
    override val status: StateFlow<LocalSpeechEngineStatus> = driver.status

    override suspend fun prepare(voice: VoiceModel): SpeechSynthesisResult =
        prepare(voice, VoiceTurnTrace.noop())

    override suspend fun prepare(
        voice: VoiceModel,
        trace: VoiceTurnTrace,
    ): SpeechSynthesisResult {
        val outputVoice =
            resolveReadyOutputVoice(voice)
                ?: return missingOutputVoiceFailure(voice)
        trace.mark("tts_prepare_voice", "voice=${voice.id} output=${outputVoice.id}")
        return withContext(Dispatchers.IO) {
            driver.prepare(outputVoice, trace)
        }
    }

    override suspend fun speak(
        text: String,
        voice: VoiceModel,
    ): SpeechSynthesisResult =
        speak(text, voice, VoiceTurnTrace.noop())

    override suspend fun speak(
        text: String,
        voice: VoiceModel,
        trace: VoiceTurnTrace,
    ): SpeechSynthesisResult {
        val normalizedText = SpeechTextNormalizer.normalize(text)
        if (normalizedText.isBlank()) {
            trace.mark("tts_text_rejected", "blank=true")
            return SpeechSynthesisResult.Failed("Speech text is blank.")
        }
        if (normalizedText != text.trim()) {
            trace.mark(
                "tts_text_normalized",
                "beforeChars=${text.trim().length} afterChars=${normalizedText.length}",
            )
        }
        val outputVoice =
            resolveReadyOutputVoice(voice)
                ?: return missingOutputVoiceFailure(voice)

        trace.markOnce(
            "first_tts_synthesis_start",
            "voice=${voice.id} source=${voice.source} output=${outputVoice.id}",
        )
        val result =
            withContext(Dispatchers.IO) {
                driver.speak(normalizedText, outputVoice, trace)
            }
        if (result is SpeechSynthesisResult.Failed) {
            trace.markOnce("tts_synthesis_failed", result.message)
        }
        return result
    }

    override fun stop() {
        driver.stop()
    }

    private fun readinessFailure(status: LocalSpeechEngineStatus): SpeechSynthesisResult.Failed? =
        when (status) {
            LocalSpeechEngineStatus.Initializing ->
                SpeechSynthesisResult.Failed("Local speech output is still initializing.")
            LocalSpeechEngineStatus.Ready -> null
            LocalSpeechEngineStatus.MissingModel ->
                SpeechSynthesisResult.Failed("Local speech output model is missing.")
            LocalSpeechEngineStatus.PermissionRequired ->
                SpeechSynthesisResult.Failed("Speech output permission is required.")
            is LocalSpeechEngineStatus.Unavailable ->
                SpeechSynthesisResult.Failed(status.reason)
        }

    private suspend fun resolveReadyOutputVoice(voice: VoiceModel): LocalSpeechOutputVoice? =
        withContext(Dispatchers.IO) {
            readyOutputVoice(voice)
        }

    private fun readyOutputVoice(voice: VoiceModel): LocalSpeechOutputVoice? {
        if (!voice.isEnglish) {
            return null
        }
        if (voice.status != VoiceModelStatus.Installed) {
            return null
        }
        if (voice.modelUri.isNullOrBlank()) {
            return null
        }

        val readinessFailure = readinessFailure(status.value)
        if (readinessFailure != null) {
            return null
        }

        val outputVoice = driver.findVoice(voice) ?: return null
        return outputVoice.takeUnless { it.requiresNetwork }
    }

    private suspend fun missingOutputVoiceFailure(voice: VoiceModel): SpeechSynthesisResult.Failed =
        withContext(Dispatchers.IO) {
            when {
                !voice.isEnglish ->
                    SpeechSynthesisResult.Failed("Only English local voices are supported first.")
                voice.status != VoiceModelStatus.Installed ->
                    SpeechSynthesisResult.Failed("Install the voice before speaking.")
                voice.modelUri.isNullOrBlank() ->
                    SpeechSynthesisResult.Failed("Installed voice is missing a local model URI.")
                readinessFailure(status.value) != null ->
                    requireNotNull(readinessFailure(status.value))
                driver.findVoice(voice)?.requiresNetwork == true ->
                    SpeechSynthesisResult.Failed(
                        "Cloud or network speech synthesis is blocked for local voice mode.",
                    )
                else ->
                    SpeechSynthesisResult.Failed(
                        if (voice.source == VoiceModelSource.IMPORTED) {
                            "Imported custom voice playback needs a local model runtime."
                        } else {
                            "Offline speech output voice is unavailable on this phone."
                        },
                    )
            }
        }
}
