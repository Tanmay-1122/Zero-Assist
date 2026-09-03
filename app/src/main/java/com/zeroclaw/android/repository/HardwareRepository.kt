/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.repository

import com.zeroclaw.android.model.HardwareDevice
import com.zeroclaw.android.model.GpioPin
import com.zeroclaw.android.model.SensorReading
import com.zeroclaw.android.model.SensorAlert
import com.zeroclaw.android.model.ActuatorCommand
import com.zeroclaw.android.model.HardwareAuditLog
import com.zeroclaw.android.model.DeviceStats
import com.zeroclaw.android.model.HardwareTransportCandidate
import com.zeroclaw.android.model.ScheduledActuatorCommandExecutionSummary
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for hardware device management.
 *
 * Manages Raspberry Pi, Pico, and other microcontroller integration.
 */
interface HardwareRepository {

    // ==================== Device Management ====================

    /**
     * Register a new hardware device.
     *
     * @param name Device display name.
     * @param type Device type (raspberry_pi, pico, arduino, esp32).
     * @param ipAddress Network address (if applicable).
     * @param configJson Device-specific settings.
     * @param workspaceId Workspace context.
     * @return Device ID.
     */
    suspend fun registerDevice(
        name: String,
        type: String,
        ipAddress: String? = null,
        configJson: String = "{}",
        workspaceId: String = "default",
    ): String

    /**
     * Get all devices in a workspace.
     */
    fun observeDevices(workspaceId: String): Flow<List<HardwareDevice>>

    /**
     * Get connected devices only.
     */
    suspend fun getConnectedDevices(workspaceId: String): List<HardwareDevice>

    /**
     * Get one registered device.
     */
    suspend fun getDevice(deviceId: String): HardwareDevice?

    /**
     * Update device connection status.
     */
    suspend fun updateDeviceStatus(deviceId: String, status: String)

    /**
     * Probe the persisted transport for a registered device and update status.
     */
    suspend fun probeDeviceConnection(deviceId: String): HardwareDevice?

    /**
     * Remove a device.
     */
    suspend fun unregisterDevice(deviceId: String)

    /**
     * Get device statistics.
     */
    suspend fun getDeviceStats(deviceId: String): DeviceStats

    // ==================== Transport Discovery ====================

    /**
     * Discover Android-visible hardware transport endpoints.
     */
    suspend fun discoverTransportCandidates(): List<HardwareTransportCandidate>

    // ==================== GPIO Management ====================

    /**
     * Configure a GPIO pin.
     *
     * @param deviceId Device owning pin.
     * @param pinNumber Physical pin number.
     * @param mode Pin mode (input, output, pwm, analog).
     * @param label Human-readable label.
     * @param sensorType If sensor-attached, type (dht22, bmp280, etc).
     * @return Pin ID.
     */
    suspend fun configurePin(
        deviceId: String,
        pinNumber: Int,
        mode: String,
        label: String? = null,
        sensorType: String? = null,
        workspaceId: String = "default",
    ): String

    /**
     * Get pins configured on a device.
     */
    fun observeDevicePins(deviceId: String): Flow<List<GpioPin>>

    /**
     * Update an existing GPIO pin mode.
     */
    suspend fun updatePinMode(pinId: String, mode: String)

    /**
     * Set digital pin state (HIGH/LOW).
     */
    suspend fun setDigitalPin(pinId: String, state: Boolean)

    /**
     * Set PWM pin value (0-4095).
     */
    suspend fun setPwmPin(pinId: String, value: Int, frequency: Int? = null)

    /**
     * Get pin state.
     */
    suspend fun getPinState(pinId: String): GpioPin?

    // ==================== Sensor Operations ====================

    /**
     * Record a sensor reading.
     */
    suspend fun recordSensorReading(
        deviceId: String,
        pinId: String,
        sensorType: String,
        value: Float,
        unit: String,
        workspaceId: String = "default",
    ): String

    /**
     * Poll all configured sensor pins on a device from the hardware runtime.
     */
    suspend fun pollDeviceSensors(deviceId: String): List<SensorReading>

    /**
     * Poll one sensor pin from the hardware runtime.
     */
    suspend fun pollSensorPin(pinId: String): SensorReading?

    /**
     * Get latest sensor readings for device.
     */
    suspend fun getLatestReadings(deviceId: String, limit: Int = 100): List<SensorReading>

    /**
     * Get readings by sensor type.
     */
    suspend fun getReadingsBySensorType(
        sensorType: String,
        workspaceId: String,
        sinceTimestamp: String,
    ): List<SensorReading>

    /**
     * Get sensor statistics (min, max, avg).
     */
    suspend fun getSensorStats(pinId: String): Map<String, Float>

    // ==================== Alert Management ====================

    /**
     * Create sensor alert threshold.
     */
    suspend fun createAlert(
        deviceId: String,
        sensorType: String,
        alertName: String,
        thresholdType: String, // high, low, range
        thresholdValue: Float,
        thresholdMax: Float? = null,
        workspaceId: String = "default",
    ): String

    /**
     * Get active alerts for device.
     */
    fun observeActiveAlerts(deviceId: String, workspaceId: String): Flow<List<SensorAlert>>

    /**
     * Get triggered alerts (recent).
     */
    suspend fun getTriggeredAlerts(workspaceId: String, limit: Int = 50): List<SensorReading>

    /**
     * Disable alert.
     */
    suspend fun disableAlert(alertId: String)

    // ==================== Actuator Control ====================

    /**
     * Send command to actuator/relay.
     */
    suspend fun sendActuatorCommand(
        deviceId: String,
        pinId: String,
        commandType: String, // on, off, toggle, pulse, pwm_set
        value: Int? = null,
        durationMs: Int? = null,
        workspaceId: String = "default",
    ): String

    /**
     * Schedule actuator command.
     */
    suspend fun scheduleActuatorCommand(
        deviceId: String,
        pinId: String,
        commandType: String,
        scheduledTime: String, // ISO 8601
        value: Int? = null,
        durationMs: Int? = null,
        workspaceId: String = "default",
    ): String

    /**
     * Get pending commands.
     */
    fun observePendingCommands(workspaceId: String): Flow<List<ActuatorCommand>>

    /**
     * Execute pending scheduled actuator commands that are due at or before [nowMillis].
     */
    suspend fun executeDueScheduledActuatorCommands(
        nowMillis: Long = System.currentTimeMillis(),
    ): ScheduledActuatorCommandExecutionSummary

    /**
     * Get command execution history.
     */
    suspend fun getCommandHistory(deviceId: String, workspaceId: String, limit: Int = 100): List<ActuatorCommand>

    /**
     * Complete actuator command.
     */
    suspend fun completeCommand(commandId: String, status: String, result: String? = null)

    // ==================== Audit & Diagnostics ====================

    /**
     * Get device audit trail.
     */
    suspend fun getDeviceAuditLog(deviceId: String, limit: Int = 100): List<HardwareAuditLog>

    /**
     * Get error logs.
     */
    suspend fun getErrorLogs(workspaceId: String, limit: Int = 50): List<HardwareAuditLog>

    /**
     * Get device health summary.
     */
    suspend fun getDeviceHealth(deviceId: String): Map<String, Any>
}
