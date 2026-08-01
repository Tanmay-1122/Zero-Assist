/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.startup

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("AppStartupTasks")
class AppStartupTasksTest {
    @Test
    fun `database warmup is delayed out of cold start`() {
        assertEquals(30_000L, AppStartupTasks.DEFERRED_DATABASE_WARMUP_DELAY_MS)
    }

    @Test
    fun `cold start critical window lasts through first minute`() {
        assertEquals(60_000L, COLD_START_CRITICAL_PATH_MS)
        assertTrue(
            isColdStartCriticalWindow(
                processStartElapsedRealtimeMs = 1_000L,
                nowElapsedRealtimeMs = 60_999L,
            ),
        )
        assertFalse(
            isColdStartCriticalWindow(
                processStartElapsedRealtimeMs = 1_000L,
                nowElapsedRealtimeMs = 61_000L,
            ),
        )
    }
}
