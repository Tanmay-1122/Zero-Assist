/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("VoiceMicSessionCoordinator")
class VoiceMicSessionCoordinatorTest {
    @Test
    fun `popup dictation pauses and resumes wake listening`() {
        val coordinator = VoiceMicSessionCoordinator()
        var stopCalls = 0
        var startCalls = 0

        coordinator.noteWakeupStarted()
        val startResult =
            coordinator.beginPopupDictation(
                wakeupRequested = true,
                wakeupServiceStarted = true,
                stopWakeup = {
                    stopCalls += 1
                    VoiceWakeupServiceCommandResult.Accepted
                },
            )

        assertTrue(startResult.wakeupPaused)
        assertFalse(startResult.wakeupServiceStarted)
        assertEquals(1, stopCalls)
        assertEquals(VoiceMicSessionState.PopupListening, coordinator.state.value)

        val finishResult =
            coordinator.finishPopupDictation(
                resumeWakeup = true,
                wakeupRequested = true,
                startWakeup = {
                    startCalls += 1
                    VoiceWakeupServiceCommandResult.Accepted
                },
            )

        assertTrue(finishResult.wakeupServiceStarted)
        assertEquals(1, startCalls)
        assertEquals(VoiceMicSessionState.WakeListening, coordinator.state.value)
    }

    @Test
    fun `popup dictation without requested wakeup does not touch wake service`() {
        val coordinator = VoiceMicSessionCoordinator()

        val startResult =
            coordinator.beginPopupDictation(
                wakeupRequested = false,
                wakeupServiceStarted = false,
                stopWakeup = { error("stopWakeup should not be called") },
            )
        val finishResult =
            coordinator.finishPopupDictation(
                resumeWakeup = true,
                wakeupRequested = false,
                startWakeup = { error("startWakeup should not be called") },
            )

        assertFalse(startResult.wakeupPaused)
        assertFalse(finishResult.wakeupServiceStarted)
        assertEquals(VoiceMicSessionState.Idle, coordinator.state.value)
    }

    @Test
    fun `popup dictation pause failure enters recovering state`() {
        val coordinator = VoiceMicSessionCoordinator()

        val result =
            coordinator.beginPopupDictation(
                wakeupRequested = true,
                wakeupServiceStarted = true,
                stopWakeup = {
                    VoiceWakeupServiceCommandResult.Failed("Mic is busy.")
                },
            )

        assertFalse(result.wakeupPaused)
        assertEquals("Mic is busy.", result.failureMessage)
        assertEquals(VoiceMicSessionState.Recovering("Mic is busy."), coordinator.state.value)
    }

    @Test
    fun `repeated popup begin reuses current owner without stopping wake twice`() {
        val coordinator = VoiceMicSessionCoordinator()
        var stopCalls = 0

        repeat(2) {
            coordinator.beginPopupDictation(
                wakeupRequested = true,
                wakeupServiceStarted = it == 0,
                stopWakeup = {
                    stopCalls += 1
                    VoiceWakeupServiceCommandResult.Accepted
                },
            )
        }

        assertEquals(1, stopCalls)
        assertEquals(VoiceMicSessionState.PopupListening, coordinator.state.value)
    }

    @Test
    fun `wakeup can be deferred when enabled during popup dictation`() {
        val coordinator = VoiceMicSessionCoordinator()
        var startCalls = 0

        coordinator.beginPopupDictation(
            wakeupRequested = false,
            wakeupServiceStarted = false,
            stopWakeup = { error("stopWakeup should not be called") },
        )
        coordinator.deferWakeupUntilPopupEnds()

        val finishResult =
            coordinator.finishPopupDictation(
                resumeWakeup = true,
                wakeupRequested = true,
                startWakeup = {
                    startCalls += 1
                    VoiceWakeupServiceCommandResult.Accepted
                },
            )

        assertTrue(finishResult.wakeupServiceStarted)
        assertEquals(1, startCalls)
        assertEquals(VoiceMicSessionState.WakeListening, coordinator.state.value)
    }
}
