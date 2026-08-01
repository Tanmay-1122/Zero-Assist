/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.viewmodel

import com.zeroclaw.android.ZeroClawApplication
import com.zeroclaw.android.model.HardwareDevice
import com.zeroclaw.android.repository.HardwareRepository
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.unmockkAll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
@DisplayName("HardwareViewModel route hydration and pin mode")
class HardwareViewModelRouteAndPinModeTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var repository: HardwareRepository
    private lateinit var viewModel: HardwareViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true) {
            every { observeDevices(any()) } returns flowOf(emptyList())
            every { observeDevicePins(any()) } returns flowOf(emptyList())
            every { observeActiveAlerts(any(), any()) } returns flowOf(emptyList())
            every { observePendingCommands(any()) } returns flowOf(emptyList())
            coEvery { getConnectedDevices(any()) } returns emptyList()
            coEvery { getLatestReadings(any(), any()) } returns emptyList()
        }
        val app = mockk<ZeroClawApplication>(relaxed = true) {
            every { hardwareRepository } returns repository
        }
        viewModel = HardwareViewModel(app)
        viewModel.selectDevice(testDevice())
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
        unmockkAll()
    }

    @Test
    fun `selectDeviceById rejects blank route id without repository lookup`() {
        viewModel.selectDeviceById(" ")

        assertNull(viewModel.selectedDevice.value)
        assertEquals(emptyList<Any>(), viewModel.devicePins.value)
        assertEquals("Hardware device route is missing", viewModel.errorMessage.value)
        coVerify(exactly = 0) { repository.getDevice(any()) }
    }

    @Test
    fun `selectDeviceById hydrates selected device from route`() {
        val routedDevice = testDevice(id = "device-2", workspaceId = "lab")
        coEvery { repository.getDevice("device-2") } returns routedDevice

        viewModel.selectDeviceById(" device-2 ")

        assertEquals("device-2", viewModel.selectedDevice.value?.id)
        assertEquals("lab", viewModel.selectedDevice.value?.workspaceId)
        assertNull(viewModel.errorMessage.value)
        coVerify { repository.getDevice("device-2") }
        coVerify { repository.getLatestReadings("device-2", 100) }
    }

    @Test
    fun `configurePin rejects blank mode without repository update`() {
        viewModel.configurePin(pinId = "pin-1", mode = " ")

        assertEquals("Select a pin mode before configuring pin", viewModel.errorMessage.value)
        coVerify(exactly = 0) { repository.updatePinMode(any(), any()) }
    }

    @Test
    fun `configurePin normalizes mode before repository update`() {
        viewModel.configurePin(pinId = "pin-1", mode = " PWM ")

        coVerify { repository.updatePinMode("pin-1", "pwm") }
    }

    private fun testDevice(
        id: String = "device-1",
        workspaceId: String = "default",
    ): HardwareDevice =
        HardwareDevice(
            id = id,
            workspaceId = workspaceId,
            name = "Bench ESP32",
            type = "esp32",
            firmwareVersion = "test",
            connectionStatus = "connected",
            capabilities = "{}",
        )
}
