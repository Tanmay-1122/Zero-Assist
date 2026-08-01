/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.startup

import android.util.Log
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AppWorkManagerConfigurationFactoryTest {
    @Test
    fun createUsesDebugLoggingForDebugBuilds() {
        val configuration = AppWorkManagerConfigurationFactory.create(debug = true)

        assertEquals(Log.DEBUG, configuration.minimumLoggingLevel)
    }

    @Test
    fun createUsesErrorLoggingForReleaseBuilds() {
        val configuration = AppWorkManagerConfigurationFactory.create(debug = false)

        assertEquals(Log.ERROR, configuration.minimumLoggingLevel)
    }
}
