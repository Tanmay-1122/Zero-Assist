/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AppVersionBridgeTest {
    @Test
    fun crateVersionOrFallbackReturnsProviderVersion() {
        val version = AppVersionBridge.crateVersionOrFallback { "0.0.37" }

        assertEquals("0.0.37", version)
    }

    @Test
    fun crateVersionOrFallbackReturnsUnknownWhenProviderFails() {
        val version =
            AppVersionBridge.crateVersionOrFallback {
                throw IllegalStateException("native unavailable")
            }

        assertEquals(AppVersionBridge.UNKNOWN_VERSION, version)
    }
}
