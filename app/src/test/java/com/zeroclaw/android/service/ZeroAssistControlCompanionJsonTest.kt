/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Zero-Assist control companion JSON")
class ZeroAssistControlCompanionJsonTest {
    @Test
    fun `packages payload uses portal compatible array shape`() {
        val payload =
            ZeroAssistControlCompanionJson.packages(
                listOf(
                    ZeroAssistPackageInfo(
                        packageName = "com.whatsapp",
                        label = "WhatsApp",
                        isSystemApp = false,
                    ),
                ),
            )

        val result = JSONObject(payload).getJSONArray("result")

        assertEquals("com.whatsapp", result.getJSONObject(0).getString("packageName"))
        assertEquals("WhatsApp", result.getJSONObject(0).getString("label"))
        assertFalse(result.getJSONObject(0).getBoolean("isSystemApp"))
    }

    @Test
    fun `auth token endpoint reports no token until TCP bridge exists`() {
        val payload = JSONObject(ZeroAssistControlCompanionJson.authTokenUnavailable())

        assertEquals("success", payload.getString("status"))
        assertTrue(payload.isNull("result"))
    }
}
