/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.hardware

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Hardware command response parser")
class HardwareCommandResponseParserTest {
    @Test
    fun `parses current data envelope`() {
        val result = HardwareCommandResponseParser.parse(
            transportType = "usb_serial",
            rawResponse = """{"ok":true,"data":{"pin":25,"value":1}}""",
        )

        assertTrue(result.ok)
        assertEquals("usb_serial", result.transportType)
        assertEquals("""{"pin":25,"value":1}""", result.dataJson)
        assertNull(result.error)
        assertNull(result.id)
    }

    @Test
    fun `parses legacy result envelope`() {
        val result = HardwareCommandResponseParser.parse(
            transportType = "bluetooth_serial",
            rawResponse = """{"id":"1","ok":true,"result":"done"}""",
        )

        assertTrue(result.ok)
        assertEquals("done", result.dataJson)
        assertNull(result.error)
        assertEquals("1", result.id)
    }

    @Test
    fun `parses object error message`() {
        val result = HardwareCommandResponseParser.parse(
            transportType = "http",
            rawResponse = """{"ok":false,"error":{"code":"bad_pin","message":"Pin is not available"}}""",
        )

        assertFalse(result.ok)
        assertEquals("Pin is not available", result.error)
    }

    @Test
    fun `reports invalid response`() {
        val result = HardwareCommandResponseParser.parse(
            transportType = "usb_serial",
            rawResponse = "not-json",
        )

        assertFalse(result.ok)
        assertEquals("Invalid hardware response: not-json", result.error)
    }
}
