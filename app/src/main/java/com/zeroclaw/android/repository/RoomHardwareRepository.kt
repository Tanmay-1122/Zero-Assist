/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.repository

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import com.zeroclaw.android.data.db.hardware.HardwareDeviceDao
import com.zeroclaw.android.data.db.hardware.GpioPinDao
import com.zeroclaw.android.data.db.hardware.SensorReadingDao
import com.zeroclaw.android.data.db.hardware.SensorAlertDao
import com.zeroclaw.android.data.db.hardware.ActuatorCommandDao
import com.zeroclaw.android.data.db.hardware.HardwareAuditLogDao
import com.zeroclaw.android.model.HardwareDevice
import com.zeroclaw.android.model.GpioPin
import com.zeroclaw.android.model.SensorReading
import com.zeroclaw.android.model.SensorAlert
import com.zeroclaw.android.model.ActuatorCommand
import com.zeroclaw.android.model.HardwareCommand
import com.zeroclaw.android.model.HardwareCommandResult
import com.zeroclaw.android.model.HardwareAuditLog
import com.zeroclaw.android.model.DeviceStats
import com.zeroclaw.android.model.HardwareTransportCandidate
import com.zeroclaw.android.model.ScheduledActuatorCommandExecutionSummary
import com.zeroclaw.android.service.hardware.HardwareCommandExecutor
import com.zeroclaw.android.service.hardware.HardwareRuntimeCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.Instant
import java.util.UUID
import org.json.JSONObject

/**
 * Room-backed implementation of HardwareRepository.
 *
 * Manages Raspberry Pi, Pico, and other microcontroller persistence.
 */
class RoomHardwareRepository(
    private val deviceDao: HardwareDeviceDao,
    private val pinDao: GpioPinDao,
    private val sensorDao: SensorReadingDao,
    private val alertDao: SensorAlertDao,
    private val commandDao: ActuatorCommandDao,
    private val auditDao: HardwareAuditLogDao,
    context: Context,
    private val runtimeCoordinator: HardwareCommandExecutor = HardwareRuntimeCoordinator(context),
) : HardwareRepository {

    private val context = context

    // ==================== Device Management ====================

    override suspend fun registerDevice(
        name: String,
        type: String,
        ipAddress: String?,
        configJson: String,
        workspaceId: String,
    ): String = withContext(Dispatchers.IO) {
        val deviceId = UUID.randomUUID().toString()
        val capabilities = getCapabilitiesJson(type)

        val device = HardwareDevice(
            id = deviceId,
            workspaceId = workspaceId,
            name = name,
            type = type,
            firmwareVersion = "unknown",
            connectionStatus = "configuring",
            ipAddress = ipAddress,
            macAddress = null,
            serialNumber = null,
            capabilities = capabilities,
            configJson = configJson,
            createdAt = System.currentTimeMillis().toString(),
        )

        deviceDao.insert(device)

        // Log registration
        auditLog(deviceId, workspaceId, "connect", "Device registered: $name")

        deviceId
    }

    override fun observeDevices(workspaceId: String): Flow<List<HardwareDevice>> {
        return deviceDao.observeByWorkspace(workspaceId)
    }

    override suspend fun getConnectedDevices(workspaceId: String): List<HardwareDevice> =
        withContext(Dispatchers.IO) {
            deviceDao.getConnectedDevices(workspaceId)
        }

    override suspend fun getDevice(deviceId: String): HardwareDevice? =
        withContext(Dispatchers.IO) {
            deviceDao.getById(deviceId)
        }

    override suspend fun updateDeviceStatus(deviceId: String, status: String) =
        withContext(Dispatchers.IO) {
            val timestamp = System.currentTimeMillis().toString()
            deviceDao.updateConnectionStatus(deviceId, status, timestamp)
        }

    override suspend fun probeDeviceConnection(deviceId: String): HardwareDevice? =
        withContext(Dispatchers.IO) {
            val device = deviceDao.getById(deviceId) ?: return@withContext null
            val timestamp = System.currentTimeMillis().toString()
            val (availabilityStatus, availabilityDetail) = probeDeviceTransport(device)
            if (availabilityStatus != "configured") {
                deviceDao.updateConnectionStatus(deviceId, availabilityStatus, timestamp)
                auditLog(
                    deviceId = device.id,
                    workspaceId = device.workspaceId,
                    action = "connect",
                    details = availabilityDetail,
                    status = "error",
                )
                return@withContext device.copy(connectionStatus = availabilityStatus, lastSeen = timestamp)
            }

            val handshake = runDeviceHandshake(device)
            val status = if (handshake.ok) "connected" else "error"
            val updatedDevice = device.copy(
                firmwareVersion = handshake.firmwareVersion ?: device.firmwareVersion,
                connectionStatus = status,
                lastSeen = timestamp,
                capabilities = handshake.capabilitiesJson ?: device.capabilities,
            )

            deviceDao.update(updatedDevice)
            auditLog(
                deviceId = device.id,
                workspaceId = device.workspaceId,
                action = "connect",
                details = "$availabilityDetail; ${handshake.detail}",
                status = if (status == "connected") "success" else "error",
            )

            updatedDevice
        }

    override suspend fun unregisterDevice(deviceId: String) = withContext(Dispatchers.IO) {
        val device = deviceDao.getById(deviceId) ?: return@withContext
        deviceDao.delete(device)
        auditLog(deviceId, device.workspaceId, "disconnect", "Device unregistered")
    }

    override suspend fun getDeviceStats(deviceId: String): DeviceStats = withContext(Dispatchers.IO) {
        val device = deviceDao.getById(deviceId) ?: return@withContext DeviceStats()

        val outputCount = pinDao.getOutputPinCount(deviceId)
        val inputCount = pinDao.getInputPinCount(deviceId)
        val commandCount = commandDao.getCompletedCommandCount(deviceId)

        DeviceStats(
            uptime = 0,
            cpuUsage = 0f,
            memoryUsage = 0f,
            temperature = 0f,
            gpioToggleCount = outputCount,
            sensorReadingCount = inputCount,
            lastReboot = device.lastSeen,
        )
    }

    // ==================== Transport Discovery ====================

    override suspend fun discoverTransportCandidates(): List<HardwareTransportCandidate> =
        withContext(Dispatchers.IO) {
            discoverUsbCandidates() + discoverBluetoothCandidates()
        }

    // ==================== GPIO Management ====================

    override suspend fun configurePin(
        deviceId: String,
        pinNumber: Int,
        mode: String,
        label: String?,
        sensorType: String?,
        workspaceId: String,
    ): String = withContext(Dispatchers.IO) {
        val pinId = UUID.randomUUID().toString()

        val pin = GpioPin(
            id = pinId,
            deviceId = deviceId,
            workspaceId = workspaceId,
            pinNumber = pinNumber,
            label = label,
            mode = mode,
            state = 0,
            sensorType = sensorType,
            lastUpdated = System.currentTimeMillis().toString(),
        )

        pinDao.insert(pin)

        auditLog(deviceId, workspaceId, "pin_set", "Pin $pinNumber configured as $mode")

        pinId
    }

    override fun observeDevicePins(deviceId: String): Flow<List<GpioPin>> {
        return pinDao.observeByDevice(deviceId)
    }

    override suspend fun updatePinMode(pinId: String, mode: String) = withContext(Dispatchers.IO) {
        val targetPinId = pinId.trim()
        val targetMode = mode.trim().lowercase()
        require(targetMode.isNotBlank()) { "GPIO pin mode is required" }
        val pin = pinDao.getById(targetPinId) ?: throw IllegalArgumentException("GPIO pin not found")
        val timestamp = System.currentTimeMillis().toString()
        pinDao.updatePinMode(targetPinId, targetMode, timestamp)

        val pinLabel = pin.label ?: "Pin ${pin.pinNumber}"
        auditLog(pin.deviceId, pin.workspaceId, "pin_set", "$pinLabel mode set to $targetMode")
        Unit
    }

    override suspend fun setDigitalPin(pinId: String, state: Boolean) = withContext(Dispatchers.IO) {
        val pin = pinDao.getById(pinId) ?: return@withContext
        val stateValue = if (state) 1 else 0
        val result = executePinHardwareCommand(
            pin = pin,
            command = HardwareCommand(
                commandName = "gpio_write",
                params = JSONObject()
                    .put("pin", pin.pinNumber)
                    .put("value", stateValue),
            ),
        )
        if (!result.ok) {
            auditLog(
                pin.deviceId,
                pin.workspaceId,
                "pin_set",
                result.error ?: "Digital pin command failed",
                status = "error",
            )
            throw IllegalStateException(result.error ?: "Digital pin command failed")
        }

        pinDao.updatePinState(pinId, stateValue, System.currentTimeMillis().toString())

        val pinLabel = pin.label ?: "Pin ${pin.pinNumber}"
        val stateStr = if (state) "HIGH" else "LOW"
        auditLog(pin.deviceId, pin.workspaceId, "pin_set", "$pinLabel set to $stateStr")
    }

    override suspend fun setPwmPin(pinId: String, value: Int, frequency: Int?) = withContext(Dispatchers.IO) {
        val pin = pinDao.getById(pinId) ?: return@withContext
        val targetFrequency = frequency ?: pin.frequency
        val params = JSONObject()
            .put("pin", pin.pinNumber)
            .put("value", value)
        if (targetFrequency != null) {
            params.put("frequency", targetFrequency)
        }
        val result = executePinHardwareCommand(
            pin = pin,
            command = HardwareCommand(
                commandName = "pwm_set",
                params = params,
            ),
        )
        if (!result.ok) {
            auditLog(
                pin.deviceId,
                pin.workspaceId,
                "pin_set",
                result.error ?: "PWM command failed",
                status = "error",
            )
            throw IllegalStateException(result.error ?: "PWM command failed")
        }

        pinDao.updatePwmPin(pinId, value, frequency ?: pin.frequency, System.currentTimeMillis().toString())

        val pinLabel = pin.label ?: "Pin ${pin.pinNumber}"
        auditLog(pin.deviceId, pin.workspaceId, "pin_set", "$pinLabel set to PWM $value")
    }

    override suspend fun getPinState(pinId: String): GpioPin? = withContext(Dispatchers.IO) {
        pinDao.getById(pinId)
    }

    // ==================== Sensor Operations ====================

    override suspend fun recordSensorReading(
        deviceId: String,
        pinId: String,
        sensorType: String,
        value: Float,
        unit: String,
        workspaceId: String,
    ): String = withContext(Dispatchers.IO) {
        val readingId = UUID.randomUUID().toString()

        val reading = SensorReading(
            id = readingId,
            deviceId = deviceId,
            pinId = pinId,
            workspaceId = workspaceId,
            sensorType = sensorType,
            value = value,
            unit = unit,
            timestamp = System.currentTimeMillis().toString(),
            isAlert = false,
        )

        sensorDao.insert(reading)

        auditLog(deviceId, workspaceId, "reading", "$sensorType: $value$unit")

        readingId
    }

    override suspend fun pollDeviceSensors(deviceId: String): List<SensorReading> =
        withContext(Dispatchers.IO) {
            val pins = pinDao.getSensorPins(deviceId).filter { it.isActive }
            if (pins.isEmpty()) {
                val device = deviceDao.getById(deviceId)
                if (device != null) {
                    auditLog(device.id, device.workspaceId, "reading", "No sensor pins configured", status = "error")
                }
                return@withContext emptyList()
            }

            val readings = mutableListOf<SensorReading>()
            val failures = mutableListOf<String>()
            pins.forEach { pin ->
                runCatching {
                    pollSensorPinInternal(pin)
                }.onSuccess { reading ->
                    readings += reading
                }.onFailure { error ->
                    val pinLabel = pin.label ?: "Pin ${pin.pinNumber}"
                    failures += "$pinLabel: ${error.message ?: "Sensor read failed"}"
                }
            }

            if (readings.isEmpty() && failures.isNotEmpty()) {
                throw IllegalStateException(failures.joinToString("; "))
            }
            readings
        }

    override suspend fun pollSensorPin(pinId: String): SensorReading? =
        withContext(Dispatchers.IO) {
            val pin = pinDao.getById(pinId) ?: return@withContext null
            pollSensorPinInternal(pin)
        }

    override suspend fun getLatestReadings(deviceId: String, limit: Int): List<SensorReading> =
        withContext(Dispatchers.IO) {
            sensorDao.getLatestReadings(deviceId, limit)
        }

    override suspend fun getReadingsBySensorType(
        sensorType: String,
        workspaceId: String,
        sinceTimestamp: String,
    ): List<SensorReading> = withContext(Dispatchers.IO) {
        sensorDao.getReadingsSince(sensorType, workspaceId, sinceTimestamp)
    }

    override suspend fun getSensorStats(pinId: String): Map<String, Float> = withContext(Dispatchers.IO) {
        mapOf(
            "average" to (sensorDao.getAverageSensorValue(pinId) ?: 0f),
            "max" to (sensorDao.getMaxSensorValue(pinId) ?: 0f),
            "min" to (sensorDao.getMinSensorValue(pinId) ?: 0f),
        )
    }

    // ==================== Alert Management ====================

    override suspend fun createAlert(
        deviceId: String,
        sensorType: String,
        alertName: String,
        thresholdType: String,
        thresholdValue: Float,
        thresholdMax: Float?,
        workspaceId: String,
    ): String = withContext(Dispatchers.IO) {
        val alertId = UUID.randomUUID().toString()

        val alert = SensorAlert(
            id = alertId,
            deviceId = deviceId,
            workspaceId = workspaceId,
            sensorType = sensorType,
            alertName = alertName,
            thresholdType = thresholdType,
            thresholdValue = thresholdValue,
            thresholdMax = thresholdMax,
            isActive = true,
            createdAt = System.currentTimeMillis().toString(),
        )

        alertDao.insert(alert)

        auditLog(deviceId, workspaceId, "alert", "Alert created: $alertName")

        alertId
    }

    override fun observeActiveAlerts(deviceId: String, workspaceId: String): Flow<List<SensorAlert>> {
        return alertDao.observeActiveAlerts(deviceId, workspaceId)
    }

    override suspend fun getTriggeredAlerts(workspaceId: String, limit: Int): List<SensorReading> =
        withContext(Dispatchers.IO) {
            sensorDao.getAlertReadings(workspaceId, limit)
        }

    override suspend fun disableAlert(alertId: String) = withContext(Dispatchers.IO) {
        val alert = alertDao.getById(alertId) ?: return@withContext
        alertDao.update(alert.copy(isActive = false))
    }

    // ==================== Actuator Control ====================

    override suspend fun sendActuatorCommand(
        deviceId: String,
        pinId: String,
        commandType: String,
        value: Int?,
        durationMs: Int?,
        workspaceId: String,
    ): String = withContext(Dispatchers.IO) {
        val commandId = UUID.randomUUID().toString()

        val command = ActuatorCommand(
            id = commandId,
            deviceId = deviceId,
            workspaceId = workspaceId,
            pinId = pinId,
            commandType = commandType,
            value = value,
            durationMs = durationMs,
            status = "executing",
            createdAt = System.currentTimeMillis().toString(),
        )

        commandDao.insert(command)
        val pin = pinDao.getById(pinId)
        if (pin == null) {
            val message = "Actuator pin not found"
            commandDao.complete(commandId, "failed", System.currentTimeMillis().toString(), message)
            auditLog(deviceId, workspaceId, "command", message, status = "error")
            throw IllegalArgumentException(message)
        }

        val result = executePinHardwareCommand(
            pin = pin,
            command = actuatorCommandToHardwareCommand(commandType, pin, value, durationMs),
        )
        val resultStatus = if (result.ok) "completed" else "failed"
        commandDao.complete(
            commandId,
            resultStatus,
            System.currentTimeMillis().toString(),
            result.toResultText(),
        )
        auditLog(
            deviceId,
            workspaceId,
            "command",
            "Actuator command $commandType: ${result.toResultText()}",
            status = if (result.ok) "success" else "error",
        )
        if (!result.ok) {
            throw IllegalStateException(result.error ?: "Actuator command failed")
        }

        commandId
    }

    override suspend fun scheduleActuatorCommand(
        deviceId: String,
        pinId: String,
        commandType: String,
        scheduledTime: String,
        value: Int?,
        durationMs: Int?,
        workspaceId: String,
    ): String = withContext(Dispatchers.IO) {
        val commandId = UUID.randomUUID().toString()

        val command = ActuatorCommand(
            id = commandId,
            deviceId = deviceId,
            workspaceId = workspaceId,
            pinId = pinId,
            commandType = commandType,
            value = value,
            durationMs = durationMs,
            status = "pending",
            isScheduled = true,
            scheduledTime = scheduledTime,
            createdAt = System.currentTimeMillis().toString(),
        )

        commandDao.insert(command)

        auditLog(deviceId, workspaceId, "command", "Actuator scheduled: $commandType @ $scheduledTime")

        commandId
    }

    override fun observePendingCommands(workspaceId: String): Flow<List<ActuatorCommand>> {
        return commandDao.observePendingCommands(workspaceId)
    }

    override suspend fun executeDueScheduledActuatorCommands(
        nowMillis: Long,
    ): ScheduledActuatorCommandExecutionSummary = withContext(Dispatchers.IO) {
        var attempted = 0
        var completed = 0
        var failed = 0
        var skipped = 0
        val timestamp = nowMillis.toString()

        commandDao.getPendingScheduledCommands().forEach { command ->
            val scheduledAt = command.scheduledTime.toScheduledMillisOrNull()
            if (scheduledAt == null) {
                attempted += 1
                failed += 1
                val message = "Invalid scheduled actuator time: ${command.scheduledTime.orEmpty()}"
                commandDao.complete(command.id, "failed", timestamp, message)
                auditLog(command.deviceId, command.workspaceId, "command", message, status = "error")
                return@forEach
            }
            if (scheduledAt > nowMillis) {
                return@forEach
            }

            attempted += 1
            val claimed = commandDao.markExecuting(command.id)
            if (claimed == 0) {
                skipped += 1
                return@forEach
            }

            val result = executeScheduledActuatorCommand(command)
            val resultStatus = if (result.ok) "completed" else "failed"
            commandDao.complete(command.id, resultStatus, timestamp, result.toResultText())
            auditLog(
                command.deviceId,
                command.workspaceId,
                "command",
                "Scheduled actuator command ${command.commandType}: ${result.toResultText()}",
                status = if (result.ok) "success" else "error",
            )
            if (result.ok) {
                completed += 1
            } else {
                failed += 1
            }
        }

        ScheduledActuatorCommandExecutionSummary(
            attempted = attempted,
            completed = completed,
            failed = failed,
            skipped = skipped,
        )
    }

    override suspend fun getCommandHistory(
        deviceId: String,
        workspaceId: String,
        limit: Int,
    ): List<ActuatorCommand> = withContext(Dispatchers.IO) {
        commandDao.getCommandHistory(deviceId, workspaceId, limit)
    }

    override suspend fun completeCommand(commandId: String, status: String, result: String?) =
        withContext(Dispatchers.IO) {
            val command = commandDao.getById(commandId) ?: return@withContext
            commandDao.complete(commandId, status, System.currentTimeMillis().toString(), result)
        }

    // ==================== Audit & Diagnostics ====================

    override suspend fun getDeviceAuditLog(deviceId: String, limit: Int): List<HardwareAuditLog> =
        withContext(Dispatchers.IO) {
            auditDao.getDeviceAuditLog(deviceId, limit)
        }

    override suspend fun getErrorLogs(workspaceId: String, limit: Int): List<HardwareAuditLog> =
        withContext(Dispatchers.IO) {
            auditDao.getErrorLogs(workspaceId, limit)
        }

    override suspend fun getDeviceHealth(deviceId: String): Map<String, Any> = withContext(Dispatchers.IO) {
        val device = deviceDao.getById(deviceId) ?: return@withContext emptyMap()
        val errorCount = auditDao.getErrorCount(deviceId)
        val commandCount = commandDao.getCompletedCommandCount(deviceId)

        mapOf(
            "deviceName" to device.name,
            "status" to device.connectionStatus,
            "type" to device.type,
            "uptime" to (device.lastSeen ?: "unknown"),
            "errorCount" to errorCount,
            "commandsExecuted" to commandCount,
            "isHealthy" to (errorCount < 5),
        )
    }

    // ==================== Helper Methods ====================

    private data class DeviceHandshakeResult(
        val ok: Boolean,
        val detail: String,
        val capabilitiesJson: String? = null,
        val firmwareVersion: String? = null,
    )

    private suspend fun executeScheduledActuatorCommand(
        command: ActuatorCommand,
    ): HardwareCommandResult {
        val pin = pinDao.getById(command.pinId)
            ?: return failedHardwareCommandResult("Actuator pin not found")
        if (pin.deviceId != command.deviceId || pin.workspaceId != command.workspaceId) {
            return failedHardwareCommandResult("Actuator pin does not belong to scheduled command device")
        }
        return executePinHardwareCommand(
            pin = pin,
            command = actuatorCommandToHardwareCommand(
                command.commandType,
                pin,
                command.value,
                command.durationMs,
            ),
        )
    }

    private fun String?.toScheduledMillisOrNull(): Long? {
        val value = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return value.toLongOrNull()
            ?: runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()
    }

    private fun failedHardwareCommandResult(error: String): HardwareCommandResult =
        HardwareCommandResult(
            ok = false,
            transportType = "unknown",
            error = error,
        )

    private suspend fun auditLog(
        deviceId: String,
        workspaceId: String,
        action: String,
        details: String,
        status: String = "success",
    ) = withContext(Dispatchers.IO) {
        val log = HardwareAuditLog(
            id = UUID.randomUUID().toString(),
            deviceId = deviceId,
            workspaceId = workspaceId,
            action = action,
            details = JSONObject().apply {
                put("message", details)
                put("timestamp", System.currentTimeMillis())
            }.toString(),
            status = status,
            timestamp = System.currentTimeMillis().toString(),
        )

        auditDao.insert(log)
    }

    private fun probeDeviceTransport(device: HardwareDevice): Pair<String, String> {
        val config = runCatching { JSONObject(device.configJson) }.getOrNull() ?: JSONObject()
        val transportType = config.optString("transportType", "")
        val address = config.optString("address", "")

        return when (transportType) {
            "usb_serial" -> probeUsbTransport(address)
            "bluetooth_serial" -> probeBluetoothTransport(address)
            else -> {
                if (device.ipAddress.isNullOrBlank()) {
                    "configuring" to "No transport metadata available for ${device.name}"
                } else {
                    "configured" to "Network hardware endpoint configured at ${device.ipAddress}"
                }
            }
        }
    }

    private fun probeUsbTransport(address: String): Pair<String, String> {
        val usbManager = context.getSystemService(UsbManager::class.java)
            ?: return "error" to "USB manager unavailable"
        val device = usbManager.deviceList.values.firstOrNull { it.deviceName == address }
            ?: return "unavailable" to "USB device is not attached"

        return if (usbManager.hasPermission(device)) {
            "configured" to "USB device visible and permission granted"
        } else {
            "configuring" to "USB device visible but permission is not granted"
        }
    }

    private fun probeBluetoothTransport(address: String): Pair<String, String> {
        if (!hasBluetoothConnectPermission()) {
            return "configuring" to "Bluetooth connect permission is not granted"
        }
        val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
            ?: return "error" to "Bluetooth manager unavailable"
        val adapter = bluetoothManager.adapter ?: return "unavailable" to "Bluetooth adapter unavailable"

        return try {
            val device = adapter.bondedDevices.firstOrNull { it.address == address }
            if (device != null) {
                "configured" to "Bluetooth device is paired and available"
            } else {
                "unavailable" to "Bluetooth device is not paired"
            }
        } catch (e: SecurityException) {
            "configuring" to "Bluetooth connect permission is not granted"
        }
    }

    private suspend fun runDeviceHandshake(device: HardwareDevice): DeviceHandshakeResult {
        val failures = mutableListOf<String>()
        val pingResult = runtimeCoordinator.execute(device, HardwareCommand("ping"))
        if (pingResult.ok) {
            val capabilitiesResult = firstSuccessfulCapabilitiesCommand(device)
            return DeviceHandshakeResult(
                ok = true,
                detail = if (capabilitiesResult != null) {
                    "Hardware handshake succeeded with capabilities"
                } else {
                    "Hardware handshake succeeded with ping"
                },
                capabilitiesJson = normalizedCapabilitiesJson(capabilitiesResult),
                firmwareVersion = firmwareVersionFrom(capabilitiesResult) ?: firmwareVersionFrom(pingResult),
            )
        }
        failures += "ping: ${pingResult.error ?: pingResult.toResultText()}"

        val capabilitiesResult = firstSuccessfulCapabilitiesCommand(device)
        if (capabilitiesResult != null) {
            return DeviceHandshakeResult(
                ok = true,
                detail = "Hardware handshake succeeded with capabilities",
                capabilitiesJson = normalizedCapabilitiesJson(capabilitiesResult),
                firmwareVersion = firmwareVersionFrom(capabilitiesResult),
            )
        }
        failures += "capabilities: no supported capabilities response"

        return DeviceHandshakeResult(
            ok = false,
            detail = "Hardware handshake failed (${failures.joinToString("; ")})",
        )
    }

    private suspend fun firstSuccessfulCapabilitiesCommand(device: HardwareDevice): HardwareCommandResult? {
        for (commandName in listOf("capabilities", "hardware_capabilities")) {
            val result = runtimeCoordinator.execute(device, HardwareCommand(commandName))
            if (result.ok) {
                return result
            }
        }
        return null
    }

    private suspend fun executePinHardwareCommand(
        pin: GpioPin,
        command: HardwareCommand,
    ): HardwareCommandResult {
        val device = deviceDao.getById(pin.deviceId)
            ?: return HardwareCommandResult(
                ok = false,
                transportType = "unknown",
                error = "Hardware device not found",
            )
        if (device.connectionStatus != "connected") {
            return HardwareCommandResult(
                ok = false,
                transportType = configuredTransportType(device),
                error = "Hardware device is not connected",
            )
        }
        return runtimeCoordinator.execute(device, command)
    }

    private fun normalizedCapabilitiesJson(result: HardwareCommandResult?): String? {
        val data = result?.dataJson?.toJsonObjectOrNull() ?: return null
        val capabilities = data.optJSONObject("capabilities") ?: data
        return capabilities.takeIf { it.length() > 0 }?.toString()
    }

    private fun firmwareVersionFrom(result: HardwareCommandResult?): String? {
        val data = result?.dataJson?.toJsonObjectOrNull() ?: return null
        return data.optString("firmware_version")
            .ifBlank { data.optString("firmwareVersion") }
            .ifBlank { data.optString("version") }
            .takeIf { it.isNotBlank() }
    }

    private fun String.toJsonObjectOrNull(): JSONObject? {
        return runCatching { JSONObject(trim()) }.getOrNull()
    }

    private suspend fun pollSensorPinInternal(pin: GpioPin): SensorReading {
        val sensorType = pin.sensorType?.takeIf { it.isNotBlank() } ?: pin.mode
        val result = executePinHardwareCommand(
            pin = pin,
            command = HardwareCommand(
                commandName = "gpio_read",
                params = JSONObject().put("pin", pin.pinNumber),
            ),
        )
        if (!result.ok) {
            val message = result.error ?: "Sensor read failed"
            auditLog(pin.deviceId, pin.workspaceId, "reading", message, status = "error")
            throw IllegalStateException(message)
        }

        val data = hardwareDataObject(result.dataJson)
        val value = sensorValue(data)
        if (value == null) {
            val message = "Sensor response did not include a numeric value"
            auditLog(pin.deviceId, pin.workspaceId, "reading", message, status = "error")
            throw IllegalStateException(message)
        }

        val unit = data.optString("unit").ifBlank { defaultSensorUnit(sensorType) }
        val timestamp = System.currentTimeMillis().toString()
        val reading = SensorReading(
            id = UUID.randomUUID().toString(),
            deviceId = pin.deviceId,
            pinId = pin.id,
            workspaceId = pin.workspaceId,
            sensorType = sensorType,
            value = value,
            unit = unit,
            timestamp = timestamp,
            isAlert = false,
        )

        sensorDao.insert(reading)
        pinDao.updatePinState(pin.id, value.toInt(), timestamp)
        auditLog(pin.deviceId, pin.workspaceId, "reading", "$sensorType: $value$unit")
        return reading
    }

    private fun hardwareDataObject(dataJson: String?): JSONObject {
        val rawData = dataJson?.trim().orEmpty()
        if (rawData.isBlank()) {
            return JSONObject()
        }
        return runCatching {
            JSONObject(rawData)
        }.getOrElse {
            JSONObject().put("value", rawData)
        }
    }

    private fun sensorValue(data: JSONObject): Float? {
        return when {
            data.has("value") -> data.opt("value").toFloatOrNull()
            data.has("reading") -> data.opt("reading").toFloatOrNull()
            data.has("state") -> sensorStateValue(data.optString("state"))
            else -> null
        }
    }

    private fun Any?.toFloatOrNull(): Float? {
        return when (this) {
            is Number -> toFloat()
            is String -> runCatching { trim().toFloat() }.getOrNull()
            is Boolean -> if (this) 1f else 0f
            else -> null
        }
    }

    private fun sensorStateValue(state: String): Float? {
        return when (state.trim().lowercase()) {
            "1", "high", "true", "on" -> 1f
            "0", "low", "false", "off" -> 0f
            else -> runCatching { state.toFloat() }.getOrNull()
        }
    }

    private fun defaultSensorUnit(sensorType: String): String {
        return when (sensorType.lowercase()) {
            "temperature", "temp", "dht22", "dht11", "bmp280", "bme280" -> "C"
            "humidity", "soil_moisture", "moisture" -> "%"
            "distance", "ultrasonic", "hc-sr04" -> "cm"
            "light", "ldr" -> "lux"
            "pressure" -> "hPa"
            else -> ""
        }
    }

    private fun actuatorCommandToHardwareCommand(
        commandType: String,
        pin: GpioPin,
        value: Int?,
        durationMs: Int?,
    ): HardwareCommand {
        val params = JSONObject().put("pin", pin.pinNumber)
        return when (commandType) {
            "on" -> HardwareCommand("gpio_write", params.put("value", 1))
            "off" -> HardwareCommand("gpio_write", params.put("value", 0))
            "toggle" -> HardwareCommand("gpio_toggle", params)
            "pulse" -> {
                if (durationMs != null) {
                    params.put("duration_ms", durationMs)
                }
                HardwareCommand("gpio_pulse", params)
            }
            "pwm_set" -> {
                params.put("value", value ?: pin.state)
                pin.frequency?.let { params.put("frequency", it) }
                HardwareCommand("pwm_set", params)
            }
            else -> {
                value?.let { params.put("value", it) }
                durationMs?.let { params.put("duration_ms", it) }
                HardwareCommand(commandType, params)
            }
        }
    }

    private fun configuredTransportType(device: HardwareDevice): String {
        val config = runCatching { JSONObject(device.configJson) }.getOrNull() ?: return "unknown"
        return config.optString("transportType", "unknown")
    }

    private fun discoverUsbCandidates(): List<HardwareTransportCandidate> {
        val usbManager = context.getSystemService(UsbManager::class.java) ?: return emptyList()
        return usbManager.deviceList.values.map { device ->
            HardwareTransportCandidate(
                id = "usb:${device.deviceName}",
                displayName = device.displayName(),
                transportType = "usb_serial",
                address = device.deviceName,
                suggestedDeviceType = device.inferDeviceType(),
                isPermissionGranted = usbManager.hasPermission(device),
                metadata = mapOf(
                    "vendorId" to device.vendorId.toString(),
                    "productId" to device.productId.toString(),
                    "deviceClass" to device.deviceClass.toString(),
                    "deviceSubclass" to device.deviceSubclass.toString(),
                    "manufacturerName" to device.manufacturerName.orEmpty(),
                    "productName" to device.productName.orEmpty(),
                ),
            )
        }
    }

    private fun discoverBluetoothCandidates(): List<HardwareTransportCandidate> {
        if (!hasBluetoothConnectPermission()) {
            return emptyList()
        }
        val bluetoothManager = context.getSystemService(BluetoothManager::class.java) ?: return emptyList()
        val adapter = bluetoothManager.adapter ?: return emptyList()
        return try {
            adapter.bondedDevices.map { device ->
                val name = device.name ?: device.address
                HardwareTransportCandidate(
                    id = "bluetooth:${device.address}",
                    displayName = name,
                    transportType = "bluetooth_serial",
                    address = device.address,
                    suggestedDeviceType = name.inferDeviceType(),
                    isPermissionGranted = true,
                    metadata = mapOf(
                        "bondState" to device.bondState.toString(),
                        "type" to device.type.toString(),
                        "serviceUuid" to DEFAULT_SPP_UUID,
                    ),
                )
            }
        } catch (e: SecurityException) {
            emptyList()
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun UsbDevice.displayName(): String {
        return productName
            ?: manufacturerName
            ?: "USB ${vendorId.toString(16)}:${productId.toString(16)}"
    }

    private fun UsbDevice.inferDeviceType(): String = displayName().inferDeviceType()

    private fun String.inferDeviceType(): String {
        val value = lowercase()
        return when {
            "pico" in value || "rp2040" in value -> "pico"
            "arduino" in value || "uno" in value || "mega" in value -> "arduino"
            "esp32" in value || "espressif" in value -> "esp32"
            else -> "microcontroller"
        }
    }

    private companion object {
        const val DEFAULT_SPP_UUID = "00001101-0000-1000-8000-00805f9b34fb"
    }

    private fun getCapabilitiesJson(type: String) = when (type) {
        "raspberry_pi" -> """{"hasGpio":true,"gpioCount":28,"hasUart":true,"hasSpi":true,"hasI2c":true,"hasAdc":false,"pwmChannels":2,"maxPwmFrequency":1000000,"ram":4096,"storage":32768}"""
        "pico" -> """{"hasGpio":true,"gpioCount":28,"hasUart":true,"hasSpi":true,"hasI2c":true,"hasAdc":true,"pwmChannels":16,"maxPwmFrequency":125000000,"ram":264,"storage":2048}"""
        else -> "{}"
    }
}
