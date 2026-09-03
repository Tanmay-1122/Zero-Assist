/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.hardware

import androidx.work.ExistingPeriodicWorkPolicy
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("HardwareScheduledCommandScheduler")
class HardwareScheduledCommandSchedulerTest {
    @Test
    fun `keeps existing periodic work instead of rescheduling on startup`() {
        assertEquals(
            ExistingPeriodicWorkPolicy.KEEP,
            HardwareScheduledCommandScheduler.existingWorkPolicy,
        )
    }

    @Test
    fun `delays first sweep out of cold start`() {
        assertEquals(
            15L,
            HardwareScheduledCommandScheduler.HARDWARE_COMMAND_INITIAL_DELAY_MINUTES,
        )
    }
}
