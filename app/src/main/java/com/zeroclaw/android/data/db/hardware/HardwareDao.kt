/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.db.hardware

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.zeroclaw.android.model.HardwareDevice
import com.zeroclaw.android.model.GpioPin
import com.zeroclaw.android.model.SensorReading
import com.zeroclaw.android.model.SensorAlert
import com.zeroclaw.android.model.ActuatorCommand
import com.zeroclaw.android.model.HardwareAuditLog
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for HardwareDevice operations.
 */
@Dao
interface HardwareDeviceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(device: HardwareDevice): Long

    @Update
    suspend fun update(device: HardwareDevice)

    @Delete
    suspend fun delete(device: HardwareDevice)

    @Query("SELECT * FROM hardware_devices WHERE id = :deviceId LIMIT 1")
    suspend fun getById(deviceId: String): HardwareDevice?

    @Query("""
        SELECT * FROM hardware_devices 
        WHERE workspaceId = :workspaceId
        ORDER BY createdAt DESC
    """)
    fun observeByWorkspace(workspaceId: String): Flow<List<HardwareDevice>>

    @Query("""
        SELECT * FROM hardware_devices 
        WHERE workspaceId = :workspaceId AND connectionStatus = 'connected'
        ORDER BY name
    """)
    suspend fun getConnectedDevices(workspaceId: String): List<HardwareDevice>

    @Query("""
        SELECT * FROM hardware_devices 
        WHERE workspaceId = :workspaceId AND type = :type
        ORDER BY name
    """)
    suspend fun getByType(workspaceId: String, type: String): List<HardwareDevice>

    @Query("""
        UPDATE hardware_devices 
        SET connectionStatus = :status, lastSeen = :timestamp
        WHERE id = :deviceId
    """)
    suspend fun updateConnectionStatus(deviceId: String, status: String, timestamp: String)

    @Query("SELECT COUNT(*) FROM hardware_devices WHERE workspaceId = :workspaceId")
    suspend fun getDeviceCount(workspaceId: String): Int

    @Query("SELECT COUNT(*) FROM hardware_devices WHERE workspaceId = :workspaceId AND connectionStatus = 'connected'")
    suspend fun getConnectedDeviceCount(workspaceId: String): Int
}

/**
 * Room DAO for GPIO pin operations.
 */
@Dao
interface GpioPinDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(pin: GpioPin): Long

    @Update
    suspend fun update(pin: GpioPin)

    @Delete
    suspend fun delete(pin: GpioPin)

    @Query("SELECT * FROM gpio_pins WHERE id = :pinId LIMIT 1")
    suspend fun getById(pinId: String): GpioPin?

    @Query("""
        SELECT * FROM gpio_pins 
        WHERE deviceId = :deviceId
        ORDER BY pinNumber ASC
    """)
    fun observeByDevice(deviceId: String): Flow<List<GpioPin>>

    @Query("""
        SELECT * FROM gpio_pins 
        WHERE deviceId = :deviceId AND workspaceId = :workspaceId AND isActive = 1
        ORDER BY pinNumber
    """)
    suspend fun getActivePins(deviceId: String, workspaceId: String): List<GpioPin>

    @Query("""
        SELECT * FROM gpio_pins 
        WHERE deviceId = :deviceId AND mode = :mode
        ORDER BY pinNumber
    """)
    suspend fun getByMode(deviceId: String, mode: String): List<GpioPin>

    @Query("""
        SELECT * FROM gpio_pins 
        WHERE deviceId = :deviceId AND sensorType IS NOT NULL
        ORDER BY label
    """)
    suspend fun getSensorPins(deviceId: String): List<GpioPin>

    @Query("""
        UPDATE gpio_pins 
        SET state = :state, lastUpdated = :timestamp
        WHERE id = :pinId
    """)
    suspend fun updatePinState(pinId: String, state: Int, timestamp: String)

    @Query("""
        UPDATE gpio_pins
        SET mode = :mode, lastUpdated = :timestamp
        WHERE id = :pinId
    """)
    suspend fun updatePinMode(pinId: String, mode: String, timestamp: String)

    @Query("""
        UPDATE gpio_pins
        SET state = :value, frequency = :frequency, lastUpdated = :timestamp
        WHERE id = :pinId
    """)
    suspend fun updatePwmPin(pinId: String, value: Int, frequency: Int?, timestamp: String)

    @Query("SELECT COUNT(*) FROM gpio_pins WHERE deviceId = :deviceId AND mode = 'output'")
    suspend fun getOutputPinCount(deviceId: String): Int

    @Query("SELECT COUNT(*) FROM gpio_pins WHERE deviceId = :deviceId AND mode = 'input'")
    suspend fun getInputPinCount(deviceId: String): Int
}

/**
 * Room DAO for sensor reading operations.
 */
@Dao
interface SensorReadingDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(reading: SensorReading): Long

    @Update
    suspend fun update(reading: SensorReading)

    @Query("SELECT * FROM sensor_readings WHERE id = :readingId LIMIT 1")
    suspend fun getById(readingId: String): SensorReading?

    @Query("""
        SELECT * FROM sensor_readings 
        WHERE deviceId = :deviceId
        ORDER BY timestamp DESC
        LIMIT :limit
    """)
    suspend fun getLatestReadings(deviceId: String, limit: Int = 100): List<SensorReading>

    @Query("""
        SELECT * FROM sensor_readings 
        WHERE pinId = :pinId AND workspaceId = :workspaceId
        ORDER BY timestamp DESC
        LIMIT :limit
    """)
    suspend fun getReadingsByPin(pinId: String, workspaceId: String, limit: Int = 50): List<SensorReading>

    @Query("""
        SELECT * FROM sensor_readings 
        WHERE sensorType = :sensorType AND workspaceId = :workspaceId
        AND timestamp > :sinceTimestamp
        ORDER BY timestamp DESC
    """)
    suspend fun getReadingsSince(
        sensorType: String,
        workspaceId: String,
        sinceTimestamp: String,
    ): List<SensorReading>

    @Query("""
        SELECT * FROM sensor_readings 
        WHERE isAlert = 1 AND workspaceId = :workspaceId
        ORDER BY timestamp DESC
        LIMIT :limit
    """)
    suspend fun getAlertReadings(workspaceId: String, limit: Int = 50): List<SensorReading>

    @Query("SELECT AVG(value) FROM sensor_readings WHERE pinId = :pinId")
    suspend fun getAverageSensorValue(pinId: String): Float

    @Query("SELECT MAX(value) FROM sensor_readings WHERE pinId = :pinId")
    suspend fun getMaxSensorValue(pinId: String): Float

    @Query("SELECT MIN(value) FROM sensor_readings WHERE pinId = :pinId")
    suspend fun getMinSensorValue(pinId: String): Float
}

/**
 * Room DAO for sensor alert operations.
 */
@Dao
interface SensorAlertDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(alert: SensorAlert): Long

    @Update
    suspend fun update(alert: SensorAlert)

    @Delete
    suspend fun delete(alert: SensorAlert)

    @Query("SELECT * FROM sensor_alerts WHERE id = :alertId LIMIT 1")
    suspend fun getById(alertId: String): SensorAlert?

    @Query("""
        SELECT * FROM sensor_alerts 
        WHERE deviceId = :deviceId AND workspaceId = :workspaceId AND isActive = 1
        ORDER BY alertName
    """)
    fun observeActiveAlerts(deviceId: String, workspaceId: String): Flow<List<SensorAlert>>

    @Query("""
        SELECT * FROM sensor_alerts 
        WHERE sensorType = :sensorType AND workspaceId = :workspaceId AND isActive = 1
    """)
    suspend fun getAlertsByType(sensorType: String, workspaceId: String): List<SensorAlert>

    @Query("""
        UPDATE sensor_alerts 
        SET lastTriggered = :timestamp
        WHERE id = :alertId
    """)
    suspend fun recordTrigger(alertId: String, timestamp: String)

    @Query("SELECT COUNT(*) FROM sensor_alerts WHERE workspaceId = :workspaceId AND isActive = 1")
    suspend fun getActiveAlertCount(workspaceId: String): Int
}

/**
 * Room DAO for actuator command operations.
 */
@Dao
interface ActuatorCommandDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(command: ActuatorCommand): Long

    @Update
    suspend fun update(command: ActuatorCommand)

    @Delete
    suspend fun delete(command: ActuatorCommand)

    @Query("SELECT * FROM actuator_commands WHERE id = :commandId LIMIT 1")
    suspend fun getById(commandId: String): ActuatorCommand?

    @Query("""
        SELECT * FROM actuator_commands 
        WHERE deviceId = :deviceId AND workspaceId = :workspaceId
        ORDER BY createdAt DESC
        LIMIT :limit
    """)
    suspend fun getCommandHistory(deviceId: String, workspaceId: String, limit: Int = 100): List<ActuatorCommand>

    @Query("""
        SELECT * FROM actuator_commands 
        WHERE status = 'pending' AND workspaceId = :workspaceId
        ORDER BY createdAt ASC
    """)
    fun observePendingCommands(workspaceId: String): Flow<List<ActuatorCommand>>

    @Query("""
        SELECT * FROM actuator_commands 
        WHERE isScheduled = 1 AND workspaceId = :workspaceId AND status = 'pending'
        ORDER BY scheduledTime ASC
    """)
    suspend fun getScheduledCommands(workspaceId: String): List<ActuatorCommand>

    @Query("""
        SELECT * FROM actuator_commands
        WHERE isScheduled = 1 AND status = 'pending'
        ORDER BY workspaceId ASC, scheduledTime ASC
    """)
    suspend fun getPendingScheduledCommands(): List<ActuatorCommand>

    @Query("""
        UPDATE actuator_commands
        SET status = 'executing'
        WHERE id = :commandId AND status = 'pending'
    """)
    suspend fun markExecuting(commandId: String): Int

    @Query("""
        UPDATE actuator_commands 
        SET status = :status, executedAt = :timestamp, result = :result
        WHERE id = :commandId
    """)
    suspend fun complete(commandId: String, status: String, timestamp: String, result: String? = null)

    @Query("SELECT COUNT(*) FROM actuator_commands WHERE deviceId = :deviceId AND status = 'completed'")
    suspend fun getCompletedCommandCount(deviceId: String): Int
}

/**
 * Room DAO for hardware audit operations.
 */
@Dao
interface HardwareAuditLogDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(log: HardwareAuditLog): Long

    @Update
    suspend fun update(log: HardwareAuditLog)

    @Query("SELECT * FROM hardware_audit_logs WHERE id = :logId LIMIT 1")
    suspend fun getById(logId: String): HardwareAuditLog?

    @Query("""
        SELECT * FROM hardware_audit_logs 
        WHERE deviceId = :deviceId
        ORDER BY timestamp DESC
        LIMIT :limit
    """)
    suspend fun getDeviceAuditLog(deviceId: String, limit: Int = 100): List<HardwareAuditLog>

    @Query("""
        SELECT * FROM hardware_audit_logs 
        WHERE workspaceId = :workspaceId AND action = :action
        ORDER BY timestamp DESC
        LIMIT :limit
    """)
    suspend fun getAuditByAction(workspaceId: String, action: String, limit: Int = 50): List<HardwareAuditLog>

    @Query("""
        SELECT * FROM hardware_audit_logs 
        WHERE workspaceId = :workspaceId AND status = 'error'
        ORDER BY timestamp DESC
        LIMIT :limit
    """)
    suspend fun getErrorLogs(workspaceId: String, limit: Int = 50): List<HardwareAuditLog>

    @Query("""
        DELETE FROM hardware_audit_logs 
        WHERE timestamp < :beforeTimestamp AND workspaceId = :workspaceId
    """)
    suspend fun pruneOlderThan(beforeTimestamp: String, workspaceId: String)

    @Query("SELECT COUNT(*) FROM hardware_audit_logs WHERE deviceId = :deviceId AND status = 'error'")
    suspend fun getErrorCount(deviceId: String): Int
}
