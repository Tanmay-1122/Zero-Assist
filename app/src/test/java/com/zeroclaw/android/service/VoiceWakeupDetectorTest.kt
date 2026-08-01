/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("VoiceWakeupDetector")
class VoiceWakeupDetectorTest {
    @Test
    fun `missing detector reports unavailable and refuses foreground startup`() =
        runTest {
            val detector = MissingVoiceWakeupDetector()

            val status = detector.status.first()
            val startResult = detector.startForegroundWakeup()

            assertFalse(status.available)
            assertFalse(status.foregroundServiceReady)
            assertTrue(status.requiresRecordAudioPermission)
            assertEquals(
                "Background wake word is unavailable until a local detector is bundled.",
                status.message,
            )
            assertEquals(
                VoiceWakeupStartResult.Unavailable(status.message),
                startResult,
            )
        }
}
