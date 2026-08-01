/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.cron

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CronIntentParserTest {
    @Test
    fun `detects scheduling requests`() {
        val parser = CronIntentParser()

        assertTrue(parser.isSchedulingRequest("check status every 5 minutes"))
        assertTrue(parser.isSchedulingRequest("send a report daily"))
        assertFalse(parser.isSchedulingRequest("what is the project status"))
    }

    @Test
    fun `fallback parser safely handles quoted user text`() =
        runTest {
            val parser = CronIntentParser()

            val intent = parser.parseUserInput("say \"hello\" every 5 minutes")

            assertNotNull(intent)
            assertEquals("say \"hello\" every 5 minutes", intent?.task)
        }
}
