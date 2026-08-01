/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.model.VoiceModelStatus
import com.zeroclaw.android.model.VoicePreviewState

/** Coordinates local voice preview playback without permitting cloud fallbacks. */
class VoicePreviewController(
    private val voiceCatalogRepository: LocalVoiceCatalogRepository,
    private val synthesizer: LocalSpeechSynthesizer,
) {
    suspend fun preview(voiceId: String): VoicePreviewState {
        val voice =
            voiceCatalogRepository.voices.value.firstOrNull { it.id == voiceId }
                ?: return VoicePreviewState.Failed("Voice is not in the local catalog.")

        if (voice.status != VoiceModelStatus.Installed) {
            return VoicePreviewState.Unavailable("Install this voice before previewing it.")
        }
        if (voice.sampleText.isBlank()) {
            return VoicePreviewState.Failed("Voice preview text is missing.")
        }

        return when (val status = synthesizer.status.value) {
            LocalSpeechEngineStatus.Ready -> {
                when (val result = synthesizer.speak(voice.sampleText, voice)) {
                    SpeechSynthesisResult.Completed ->
                        VoicePreviewState.Completed(voiceId)
                    SpeechSynthesisResult.Cancelled ->
                        VoicePreviewState.Idle
                    is SpeechSynthesisResult.Failed ->
                        VoicePreviewState.Failed(result.message)
                }
            }
            LocalSpeechEngineStatus.Initializing ->
                VoicePreviewState.Unavailable("Local TTS runtime is still starting.")
            LocalSpeechEngineStatus.MissingModel ->
                VoicePreviewState.Unavailable("Local TTS model is not installed for this voice.")
            LocalSpeechEngineStatus.PermissionRequired ->
                VoicePreviewState.Unavailable("Audio permission is required for local speech.")
            is LocalSpeechEngineStatus.Unavailable ->
                VoicePreviewState.Unavailable(status.reason)
        }
    }

    fun stop() {
        synthesizer.stop()
    }
}
