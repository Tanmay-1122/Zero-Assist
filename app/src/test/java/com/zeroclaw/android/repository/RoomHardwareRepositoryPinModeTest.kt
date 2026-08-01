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
import com.zeroclaw.android.model.GpioPin
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("RoomHardwareRepository pin mode")
class RoomHardwareRepositoryPinModeTest {
    private lateinit var deviceDao: HardwareDeviceDao
    private lateinit var pinDao: GpioPinDao
    private lateinit var sensorDao: SensorReadingDao
    private lateinit var alertDao: SensorAlertDao
    private lateinit var commandDao: ActuatorCommandDao
    private lateinit var auditDao: HardwareAuditLogDao
    private lateinit var repository: RoomHardwareRepository

    @BeforeEach
    fun setUp() {
        deviceDao = mockk(relaxUnitFun = true)
        pinDao = mockk(relaxUnitFun = true)
        sensorDao = mockk(relaxUnitFun = true)
        alertDao = mockk(relaxUnitFun = true)
        commandDao = mockk(relaxUnitFun = true)
        auditDao = mockk(relaxUnitFun = true)
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
            )
    }

    @Test
    fun `updatePinMode persists normalized mode and writes audit entry`() =
        runTest {
            val pin = outputPin().copy(label = "Relay")
            coEvery { pinDao.getById(pin.id) } returns pin

            repository.updatePinMode(pin.id, " PWM ")

            coVerify { pinDao.updatePinMode(pin.id, "pwm", any()) }
            coVerify {
                auditDao.insert(
                    match {
                        it.deviceId == pin.deviceId &&
                            it.workspaceId == pin.workspaceId &&
                            it.action == "pin_set" &&
                            it.status == "success" &&
                            it.details.contains("Relay mode set to pwm")
                    },
                )
            }
        }

    @Test
    fun `updatePinMode rejects missing pins`() =
        runTest {
            coEvery { pinDao.getById("missing-pin") } returns null

            val error = kotlin.runCatching {
                repository.updatePinMode("missing-pin", "output")
            }.exceptionOrNull()

            assertEquals("GPIO pin not found", error?.message)
            coVerify(exactly = 0) { pinDao.updatePinMode(any(), any(), any()) }
        }

    private fun outputPin(): GpioPin =
        GpioPin(
            id = "pin-1",
            deviceId = "device-1",
            workspaceId = "default",
            pinNumber = 17,
            mode = "output",
        )
}
