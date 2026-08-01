/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.hardware

import com.zeroclaw.android.model.HardwareDevice
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("HardwareDevicesScreen summary")
class HardwareDevicesScreenSummaryTest {
    @Test
    fun `counts only connected devices in header summary`() {
        val summary = hardwareDeviceConnectionSummary(
            listOf(
                device("one", "connected"),
                device("two", "disconnected"),
                device("three", "error"),
            ),
        )

        assertEquals("1 of 3 devices connected", summary)
    }

    @Test
    fun `uses singular device label for one registered device`() {
        val summary = hardwareDeviceConnectionSummary(
            listOf(device("one", "disconnected")),
        )

        assertEquals("0 of 1 device connected", summary)
    }

    private fun device(
        id: String,
        status: String,
    ): HardwareDevice =
        HardwareDevice(
            id = id,
            workspaceId = "default",
            name = id,
            type = "esp32",
            firmwareVersion = "test",
            connectionStatus = status,
            capabilities = "{}",
        )
}
