/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.model

import org.json.JSONObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Hardware command")
class HardwareCommandTest {
    @Test
    fun `serializes canonical firmware envelope and Android params envelope`() {
        val command = HardwareCommand(
            commandName = "gpio_write",
            params = JSONObject()
                .put("pin", 13)
                .put("value", 1),
            id = "cmd-1",
        )

        val line = command.toJsonLine()
        val json = JSONObject(line)

        assertTrue(line.endsWith("\n"))
        assertEquals("cmd-1", json.getString("id"))
        assertEquals("gpio_write", json.getString("cmd"))
        assertEquals(13, json.getJSONObject("args").getInt("pin"))
        assertEquals(1, json.getJSONObject("args").getInt("value"))
        assertEquals(13, json.getJSONObject("params").getInt("pin"))
        assertEquals(1, json.getJSONObject("params").getInt("value"))
    }

    @Test
    fun `generates command ids when caller does not provide one`() {
        val command = HardwareCommand("ping")
        val json = JSONObject(command.toJsonLine())

        assertFalse(json.getString("id").isBlank())
        assertTrue(json.getString("id").length <= 15)
        assertTrue(json.getString("id").startsWith("a-"))
        assertEquals("ping", json.getString("cmd"))
        assertEquals(0, json.getJSONObject("args").length())
        assertEquals(0, json.getJSONObject("params").length())
    }
}
