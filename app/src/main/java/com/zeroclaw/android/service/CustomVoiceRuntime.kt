/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow

data class CustomVoicePcmAudio(
    val sampleRateHz: Int,
    val pcm16Mono: ByteArray,
)

sealed interface CustomVoiceSynthesisResult {
    data class Success(val audio: CustomVoicePcmAudio) : CustomVoiceSynthesisResult

    data class Failed(val message: String) : CustomVoiceSynthesisResult
}

sealed interface CustomVoiceSynthesisStreamEvent {
    data class Audio(
        val audio: CustomVoicePcmAudio,
        val segmentIndex: Int,
        val segmentCount: Int,
    ) : CustomVoiceSynthesisStreamEvent

    data class Failed(
        val message: String,
    ) : CustomVoiceSynthesisStreamEvent
}

interface CustomVoiceRuntime {
    val status: StateFlow<LocalSpeechEngineStatus>

    fun supports(runtimeType: String): Boolean

    suspend fun prepare(voicePackage: ResolvedCustomVoicePackage): SpeechSynthesisResult =
        SpeechSynthesisResult.Completed

    suspend fun synthesize(
        text: String,
        voicePackage: ResolvedCustomVoicePackage,
    ): CustomVoiceSynthesisResult

    suspend fun synthesize(
        text: String,
        voicePackage: ResolvedCustomVoicePackage,
        trace: VoiceTurnTrace,
    ): CustomVoiceSynthesisResult =
        synthesize(text, voicePackage)

    fun synthesizeStream(
        text: String,
        voicePackage: ResolvedCustomVoicePackage,
        trace: VoiceTurnTrace,
    ): Flow<CustomVoiceSynthesisStreamEvent> =
        flow {
            when (val result = synthesize(text, voicePackage, trace)) {
                is CustomVoiceSynthesisResult.Failed ->
                    emit(CustomVoiceSynthesisStreamEvent.Failed(result.message))
                is CustomVoiceSynthesisResult.Success ->
                    emit(
                        CustomVoiceSynthesisStreamEvent.Audio(
                            audio = result.audio,
                            segmentIndex = 0,
                            segmentCount = 1,
                        ),
                    )
            }
        }

    fun stop()
}

object MissingCustomVoiceRuntime : CustomVoiceRuntime {
    override val status: StateFlow<LocalSpeechEngineStatus> =
        MutableStateFlow(LocalSpeechEngineStatus.MissingModel)

    override fun supports(runtimeType: String): Boolean = false

    override suspend fun prepare(voicePackage: ResolvedCustomVoicePackage): SpeechSynthesisResult =
        SpeechSynthesisResult.Failed(
            "Install a local Piper or ONNX voice runtime before playing imported custom voices.",
        )

    override suspend fun synthesize(
        text: String,
        voicePackage: ResolvedCustomVoicePackage,
    ): CustomVoiceSynthesisResult =
        CustomVoiceSynthesisResult.Failed(
            "Install a local Piper or ONNX voice runtime before playing imported custom voices.",
        )

    override fun stop() = Unit
}
