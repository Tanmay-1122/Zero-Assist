/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.viewmodel

import com.zeroclaw.android.ZeroClawApplication
import com.zeroclaw.android.model.HardwareDevice
import com.zeroclaw.android.model.HardwareTransportCandidate
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
@DisplayName("HardwareViewModel")
class HardwareViewModelTest {
    private val testDispatcher = UnconfinedTestDispatcher()
    private lateinit var repository: HardwareRepository
    private lateinit var viewModel: HardwareViewModel

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        repository = mockk(relaxed = true) {
            every { observeDevicePins(any()) } returns flowOf(emptyList())
            every { observeActiveAlerts(any(), any()) } returns flowOf(emptyList())
            every { observePendingCommands(any()) } returns flowOf(emptyList())
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
    fun `sendActuatorCommand requires explicit pin`() {
        viewModel.sendActuatorCommand(commandType = "on", pinId = null)

        assertEquals(
            "Select a pin before sending an actuator command",
            viewModel.errorMessage.value,
        )
        coVerify(exactly = 0) {
            repository.sendActuatorCommand(any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `scheduleCommand requires explicit pin`() {
        viewModel.scheduleCommand(
            commandType = "on",
            scheduledTime = "2026-05-18T13:30:00Z",
            pinId = " ",
        )

        assertEquals(
            "Select a pin before scheduling an actuator command",
            viewModel.errorMessage.value,
        )
        coVerify(exactly = 0) {
            repository.scheduleActuatorCommand(any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `setPwmPin passes value without inventing frequency`() {
        viewModel.setPwmPin(pinId = "pin-1", value = 2048)

        coVerify {
            repository.setPwmPin("pin-1", 2048, null)
        }
    }

    @Test
    fun `createPin delegates to selected device workspace`() {
        viewModel.createPin(
            pinNumber = 13,
            mode = "output",
            label = " Relay ",
            sensorType = " ",
        )

        coVerify {
            repository.configurePin(
                deviceId = "device-1",
                pinNumber = 13,
                mode = "output",
                label = "Relay",
                sensorType = null,
                workspaceId = "default",
            )
        }
    }

    @Test
    fun `discoverHardwareTransports exposes repository candidates`() {
        val candidate = transportCandidate()
        coEvery { repository.discoverTransportCandidates() } returns listOf(candidate)

        viewModel.discoverHardwareTransports()

        assertEquals(listOf(candidate), viewModel.transportCandidates.value)
        assertNull(viewModel.errorMessage.value)
    }

    @Test
    fun `discoverHardwareTransports reports empty scan result`() {
        coEvery { repository.discoverTransportCandidates() } returns emptyList()

        viewModel.discoverHardwareTransports()

        assertEquals(emptyList<HardwareTransportCandidate>(), viewModel.transportCandidates.value)
        assertEquals(
            "No USB or paired Bluetooth hardware found",
            viewModel.errorMessage.value,
        )
    }

    @Test
    fun `registerTransportCandidate stores candidate config and removes it from state`() {
        val candidate = transportCandidate()
        coEvery { repository.discoverTransportCandidates() } returns listOf(candidate)
        coEvery {
            repository.registerDevice(any(), any(), any(), any(), any())
        } returns "device-2"
        viewModel.discoverHardwareTransports()

        viewModel.registerTransportCandidate(candidate)

        assertEquals(emptyList<HardwareTransportCandidate>(), viewModel.transportCandidates.value)
        coVerify {
            repository.registerDevice(
                name = "Bench ESP32 USB",
                type = "esp32",
                ipAddress = null,
                configJson = match {
                    it.contains("usb_serial") &&
                        it.contains("/dev/bus/usb/001/002")
                },
                workspaceId = "default",
            )
        }
    }

    @Test
    fun `probeDeviceConnection refreshes selected device status`() {
        val connected = testDevice().copy(connectionStatus = "connected", firmwareVersion = "1.2.3")
        coEvery { repository.probeDeviceConnection("device-1") } returns connected

        viewModel.probeDeviceConnection("device-1")

        assertEquals(connected, viewModel.selectedDevice.value)
        assertNull(viewModel.errorMessage.value)
    }

    private fun testDevice(): HardwareDevice =
        HardwareDevice(
            id = "device-1",
            workspaceId = "default",
            name = "Bench ESP32",
            type = "esp32",
            firmwareVersion = "test",
            connectionStatus = "connected",
            capabilities = "{}",
        )

    private fun transportCandidate(): HardwareTransportCandidate =
        HardwareTransportCandidate(
            id = "usb:/dev/bus/usb/001/002",
            displayName = "Bench ESP32 USB",
            transportType = "usb_serial",
            address = "/dev/bus/usb/001/002",
            suggestedDeviceType = "esp32",
            isPermissionGranted = true,
        )
}
