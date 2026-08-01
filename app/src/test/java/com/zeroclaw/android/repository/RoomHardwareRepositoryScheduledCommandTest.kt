/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.repository

import android.content.Context
import com.zeroclaw.android.data.db.hardware.ActuatorCommandDao
import com.zeroclaw.android.data.db.hardware.GpioPinDao
import com.zeroclaw.android.data.db.hardware.HardwareAuditLogDao
import com.zeroclaw.android.data.db.hardware.HardwareDeviceDao
import com.zeroclaw.android.data.db.hardware.SensorAlertDao
import com.zeroclaw.android.data.db.hardware.SensorReadingDao
import com.zeroclaw.android.model.ActuatorCommand
import com.zeroclaw.android.model.GpioPin
import com.zeroclaw.android.model.HardwareCommand
import com.zeroclaw.android.model.HardwareCommandResult
import com.zeroclaw.android.model.HardwareDevice
import com.zeroclaw.android.service.hardware.HardwareCommandExecutor
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import java.time.Instant
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RoomHardwareRepositoryScheduledCommandTest {
    private lateinit var deviceDao: HardwareDeviceDao
    private lateinit var pinDao: GpioPinDao
    private lateinit var sensorDao: SensorReadingDao
    private lateinit var alertDao: SensorAlertDao
    private lateinit var commandDao: ActuatorCommandDao
    private lateinit var auditDao: HardwareAuditLogDao
    private lateinit var executor: FakeHardwareCommandExecutor
    private lateinit var repository: RoomHardwareRepository

    @BeforeEach
    fun setUp() {
        deviceDao = mockk(relaxUnitFun = true)
        pinDao = mockk(relaxUnitFun = true)
        sensorDao = mockk(relaxUnitFun = true)
        alertDao = mockk(relaxUnitFun = true)
        commandDao = mockk(relaxUnitFun = true)
        auditDao = mockk(relaxUnitFun = true)
        executor = FakeHardwareCommandExecutor()
        coEvery { auditDao.insert(any()) } returns 1L
        repository =
            RoomHardwareRepository(
                deviceDao = deviceDao,
                pinDao = pinDao,
                sensorDao = sensorDao,
                alertDao = alertDao,
                commandDao = commandDao,
                auditDao = auditDao,
                context = mockk<Context>(relaxed = true),
                runtimeCoordinator = executor,
            )
    }

    @Test
    fun `executes due scheduled commands and leaves future commands pending`() =
        runTest {
            val now = Instant.parse("2026-05-13T00:00:00Z").toEpochMilli()
            val due = scheduledCommand("due-command", Instant.ofEpochMilli(now).toString())
            val future = scheduledCommand("future-command", Instant.ofEpochMilli(now + 60_000L).toString())
            val pin = outputPin()
            val device = connectedDevice()
            coEvery { commandDao.getPendingScheduledCommands() } returns listOf(due, future)
            coEvery { commandDao.markExecuting(due.id) } returns 1
            coEvery { pinDao.getById(pin.id) } returns pin
            coEvery { deviceDao.getById(device.id) } returns device

            val summary = repository.executeDueScheduledActuatorCommands(now)

            assertEquals(1, summary.attempted)
            assertEquals(1, summary.completed)
            assertEquals(0, summary.failed)
            assertEquals(0, summary.skipped)
            val executedCommand = executor.commands.single()
            assertEquals("gpio_write", executedCommand.commandName)
            assertEquals(17, executedCommand.params.getInt("pin"))
            assertEquals(1, executedCommand.params.getInt("value"))
            coVerify {
                commandDao.complete(
                    due.id,
                    "completed",
                    now.toString(),
                    "Hardware command completed",
                )
            }
            coVerify(exactly = 0) { commandDao.markExecuting(future.id) }
        }

    @Test
    fun `fails invalid scheduled commands without touching hardware`() =
        runTest {
            val now = Instant.parse("2026-05-13T00:00:00Z").toEpochMilli()
            val invalid = scheduledCommand("invalid-command", "not-a-date")
            coEvery { commandDao.getPendingScheduledCommands() } returns listOf(invalid)

            val summary = repository.executeDueScheduledActuatorCommands(now)

            assertEquals(1, summary.attempted)
            assertEquals(0, summary.completed)
            assertEquals(1, summary.failed)
            assertTrue(executor.commands.isEmpty())
            coVerify {
                commandDao.complete(
                    invalid.id,
                    "failed",
                    now.toString(),
                    "Invalid scheduled actuator time: not-a-date",
                )
            }
            coVerify(exactly = 0) { commandDao.markExecuting(any()) }
        }

    @Test
    fun `skips due command when another runner already claimed it`() =
        runTest {
            val now = Instant.parse("2026-05-13T00:00:00Z").toEpochMilli()
            val due = scheduledCommand("claimed-command", now.toString())
            coEvery { commandDao.getPendingScheduledCommands() } returns listOf(due)
            coEvery { commandDao.markExecuting(due.id) } returns 0

            val summary = repository.executeDueScheduledActuatorCommands(now)

            assertEquals(1, summary.attempted)
            assertEquals(0, summary.completed)
            assertEquals(0, summary.failed)
            assertEquals(1, summary.skipped)
            assertTrue(executor.commands.isEmpty())
            coVerify(exactly = 0) { pinDao.getById(any()) }
            coVerify(exactly = 0) { commandDao.complete(any(), any(), any(), any()) }
        }

    private fun scheduledCommand(
        id: String,
        scheduledTime: String,
    ): ActuatorCommand =
        ActuatorCommand(
            id = id,
            deviceId = "device-1",
            workspaceId = "default",
            pinId = "pin-1",
            commandType = "on",
            status = "pending",
            isScheduled = true,
            scheduledTime = scheduledTime,
            createdAt = "2026-05-12T00:00:00Z",
        )

    private fun outputPin(): GpioPin =
        GpioPin(
            id = "pin-1",
            deviceId = "device-1",
            workspaceId = "default",
            pinNumber = 17,
            mode = "output",
        )

    private fun connectedDevice(): HardwareDevice =
        HardwareDevice(
            id = "device-1",
            workspaceId = "default",
            name = "Bench ESP32",
            type = "esp32",
            firmwareVersion = "test",
            connectionStatus = "connected",
            capabilities = "{}",
            configJson = """{"transportType":"network_http","commandEndpoint":"http://example.test/hardware"}""",
        )

    private class FakeHardwareCommandExecutor : HardwareCommandExecutor {
        val commands = mutableListOf<HardwareCommand>()

        override suspend fun execute(
            device: HardwareDevice,
            command: HardwareCommand,
        ): HardwareCommandResult {
            commands += command
            return HardwareCommandResult(ok = true, transportType = "test")
        }
    }
}
