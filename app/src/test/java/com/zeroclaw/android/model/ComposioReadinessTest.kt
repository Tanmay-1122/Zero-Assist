/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Composio readiness")
class ComposioReadinessTest {
    @Test
    fun `disabled Composio is inactive`() {
        val readiness = ComposioReadiness.from(enabled = false, apiKey = "ck_test")

        assertEquals(ComposioKeyMode.Disabled, readiness.mode)
        assertFalse(readiness.isActive)
    }

    @Test
    fun `blank key is inactive`() {
        val readiness = ComposioReadiness.from(enabled = true, apiKey = "   ")

        assertEquals(ComposioKeyMode.Missing, readiness.mode)
        assertFalse(readiness.isActive)
        assertTrue(readiness.inactiveReason.contains("ck_"))
    }

    @Test
    fun `sessions key is active and ignores entity id`() {
        val readiness = ComposioReadiness.from(enabled = true, apiKey = " ck_test ")

        assertEquals(ComposioKeyMode.Sessions, readiness.mode)
        assertTrue(readiness.isActive)
        assertTrue(readiness.usesSessionsKey)
        assertFalse(readiness.usesLegacyRestKey)
    }

    @Test
    fun `cli user key is inactive`() {
        val readiness = ComposioReadiness.from(enabled = true, apiKey = "uak_test")

        assertEquals(ComposioKeyMode.CliUser, readiness.mode)
        assertFalse(readiness.isActive)
        assertTrue(readiness.usesCliUserKey)
    }

    @Test
    fun `legacy rest key remains active`() {
        val readiness = ComposioReadiness.from(enabled = true, apiKey = "ak_test")

        assertEquals(ComposioKeyMode.LegacyRest, readiness.mode)
        assertTrue(readiness.isActive)
        assertTrue(readiness.usesLegacyRestKey)
    }
}
