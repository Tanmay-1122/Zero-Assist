/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.model.VoiceModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf

/** Shared readiness state for local speech engines. */
sealed interface LocalSpeechEngineStatus {
    data object Initializing : LocalSpeechEngineStatus
    data object Ready : LocalSpeechEngineStatus
    data object MissingModel : LocalSpeechEngineStatus
    data object PermissionRequired : LocalSpeechEngineStatus
    data class Unavailable(val reason: String) : LocalSpeechEngineStatus
}

/** Events produced by an on-phone speech recognizer. */
sealed interface SpeechRecognitionEvent {
    data class PartialTranscript(val text: String) : SpeechRecognitionEvent
    data class FinalTranscript(val text: String) : SpeechRecognitionEvent
    data class Error(val message: String) : SpeechRecognitionEvent
}

/** Result from an on-phone speech synthesizer. */
sealed interface SpeechSynthesisResult {
    data object Completed : SpeechSynthesisResult
    data object Cancelled : SpeechSynthesisResult
    data class Failed(val message: String) : SpeechSynthesisResult
}

interface LocalSpeechRecognizer {
    val status: StateFlow<LocalSpeechEngineStatus>

    fun listen(): Flow<SpeechRecognitionEvent>

    fun listen(trace: VoiceTurnTrace): Flow<SpeechRecognitionEvent> = listen()

    /**
     * Ask the recognizer to finalize the current utterance and deliver final
     * results. Implementations that cannot distinguish finish from cancel may
     * fall back to [stop].
     */
    fun finish() {
        stop()
    }

    fun stop()
}

/** Default strict recognizer used until a real on-phone STT runtime is available. */
class MissingLocalSpeechRecognizer(
    private val reason: String =
        "Local speech recognition runtime is not installed on this phone yet.",
) : LocalSpeechRecognizer {
    override val status: StateFlow<LocalSpeechEngineStatus> =
        MutableStateFlow(LocalSpeechEngineStatus.Unavailable(reason))

    override fun listen(): Flow<SpeechRecognitionEvent> =
        flowOf(SpeechRecognitionEvent.Error(reason))

    override fun stop() = Unit
}

interface LocalSpeechSynthesizer {
    val status: StateFlow<LocalSpeechEngineStatus>

    suspend fun prepare(voice: VoiceModel): SpeechSynthesisResult =
        SpeechSynthesisResult.Completed

    suspend fun prepare(
        voice: VoiceModel,
        trace: VoiceTurnTrace,
    ): SpeechSynthesisResult =
        prepare(voice)

    suspend fun speak(
        text: String,
        voice: VoiceModel,
    ): SpeechSynthesisResult

    suspend fun speak(
        text: String,
        voice: VoiceModel,
        trace: VoiceTurnTrace,
    ): SpeechSynthesisResult =
        speak(text, voice)

    fun stop()
}

/** Default strict implementation used until a real on-phone TTS runtime is installed. */
class MissingLocalSpeechSynthesizer(
    private val reason: String =
        "Local TTS runtime is not installed for this voice yet.",
) : LocalSpeechSynthesizer {
    override val status: StateFlow<LocalSpeechEngineStatus> =
        MutableStateFlow(LocalSpeechEngineStatus.Unavailable(reason))

    override suspend fun prepare(voice: VoiceModel): SpeechSynthesisResult =
        SpeechSynthesisResult.Failed(reason)

    override suspend fun speak(
        text: String,
        voice: VoiceModel,
    ): SpeechSynthesisResult =
        SpeechSynthesisResult.Failed(reason)

    override fun stop() = Unit
}
