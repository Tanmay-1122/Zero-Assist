/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.cron

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CronTranslatorTest {
    private val translator = CronTranslator()

    @Test
    fun `translates recurring minute schedules`() {
        assertEquals(
            Pair("*/15 * * * *", "recurring"),
            translator.translateSchedule("every 15 minutes"),
        )
    }

    @Test
    fun `translates one shot delay schedules`() {
        assertEquals(
            Pair("10m", "oneshot"),
            translator.translateSchedule("in 10m"),
        )
    }

    @Test
    fun `uses deterministic default for unclear schedules`() {
        assertEquals(
            Pair("*/5 * * * *", "recurring"),
            translator.translateSchedule("eventually"),
        )
    }
}
