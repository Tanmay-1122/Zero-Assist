/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("VoiceWakeupForegroundStartGuard")
class VoiceWakeupForegroundServiceTest {
    @Test
    fun `evaluate blocks missing detector`() {
        val decision =
            VoiceWakeupForegroundStartGuard.evaluate(
                status = VoiceWakeupDetectorStatus.Unavailable,
                hasRecordAudioPermission = true,
            )

        assertEquals(
            VoiceWakeupForegroundStartDecision.Blocked(
                "Background wake word is unavailable until a local detector is bundled.",
            ),
            decision,
        )
    }

    @Test
    fun `evaluate blocks ready detector without required microphone permission`() {
        val decision =
            VoiceWakeupForegroundStartGuard.evaluate(
                status =
                    VoiceWakeupDetectorStatus(
                        available = true,
                        foregroundServiceReady = true,
                        requiresRecordAudioPermission = true,
                        message = "Local wake-word detector is available.",
                    ),
                hasRecordAudioPermission = false,
            )

        assertEquals(
            VoiceWakeupForegroundStartDecision.Blocked(
                "Microphone permission is required for wake-up mode.",
            ),
            decision,
        )
    }

    @Test
    fun `evaluate blocks detector that is not foreground-service ready`() {
        val decision =
            VoiceWakeupForegroundStartGuard.evaluate(
                status =
                    VoiceWakeupDetectorStatus(
                        available = true,
                        foregroundServiceReady = false,
                        requiresRecordAudioPermission = true,
                        message = "Local detector is installed but foreground service is unavailable.",
                    ),
                hasRecordAudioPermission = true,
            )

        assertEquals(
            VoiceWakeupForegroundStartDecision.Blocked(
                "Local detector is installed but foreground service is unavailable.",
            ),
            decision,
        )
    }

    @Test
    fun `evaluate allows ready detector with microphone permission`() {
        val decision =
            VoiceWakeupForegroundStartGuard.evaluate(
                status =
                    VoiceWakeupDetectorStatus(
                        available = true,
                        foregroundServiceReady = true,
                        requiresRecordAudioPermission = true,
                        message = "Local wake-word detector is available.",
                    ),
                hasRecordAudioPermission = true,
            )

        assertEquals(VoiceWakeupForegroundStartDecision.Ready, decision)
    }

    @Test
    fun `currentStatusOrUnavailable times out fail closed`() =
        runTest {
            val status =
                VoiceWakeupForegroundStartGuard.currentStatusOrUnavailable(
                    detector = EmptyStatusWakeupDetector,
                    timeoutMs = 1L,
                )

            assertEquals(
                VoiceWakeupDetectorStatus.Unavailable.copy(
                    message = "Wake-up detector status is unavailable.",
                ),
                status,
            )
        }

    private data object EmptyStatusWakeupDetector : VoiceWakeupDetector {
        override val status: Flow<VoiceWakeupDetectorStatus> = emptyFlow()

        override suspend fun startForegroundWakeup(): VoiceWakeupStartResult =
            VoiceWakeupStartResult.Started

        override suspend fun stopForegroundWakeup() = Unit
    }
}
