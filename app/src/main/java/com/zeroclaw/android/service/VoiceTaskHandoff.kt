/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import kotlinx.coroutines.channels.SendChannel

data class VoiceTaskRequest(
    val text: String,
    val source: String = "voice_assistant",
)

sealed interface VoiceTaskHandoffResult {
    data object Accepted : VoiceTaskHandoffResult

    data object IgnoredBlank : VoiceTaskHandoffResult

    data class Failed(val message: String) : VoiceTaskHandoffResult
}

interface VoiceTaskHandoff {
    suspend fun submitTranscript(transcript: String): VoiceTaskHandoffResult
}

class ChannelVoiceTaskHandoff(
    private val requests: SendChannel<VoiceTaskRequest>,
) : VoiceTaskHandoff {
    override suspend fun submitTranscript(transcript: String): VoiceTaskHandoffResult {
        val trimmedTranscript = transcript.trim()
        if (trimmedTranscript.isBlank()) {
            return VoiceTaskHandoffResult.IgnoredBlank
        }

        val result = requests.trySend(VoiceTaskRequest(text = trimmedTranscript))
        return if (result.isSuccess) {
            VoiceTaskHandoffResult.Accepted
        } else {
            VoiceTaskHandoffResult.Failed("Could not queue voice task for the main app.")
        }
    }
}

object MissingVoiceTaskHandoff : VoiceTaskHandoff {
    override suspend fun submitTranscript(transcript: String): VoiceTaskHandoffResult =
        VoiceTaskHandoffResult.Failed("Voice task handoff is not connected.")
}
