/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.model

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Represents a connected hardware device (Raspberry Pi, Pico, etc).
 *
 * Entity for Room database persistence with workspace isolation.
 */
@Entity(tableName = "hardware_devices")
data class HardwareDevice(
    @PrimaryKey val id: String,
    val workspaceId: String = "default",
    val name: String,
    val type: String, // "raspberry_pi", "pico", "arduino", "esp32"
    val firmwareVersion: String,
    val connectionStatus: String = "disconnected", // connected, disconnected, error, configuring
    val ipAddress: String? = null,
    val macAddress: String? = null,
    val serialNumber: String? = null,
    val lastSeen: String? = null,
    val capabilities: String, // JSON: {gpio: true, uart: true, spi: true, i2c: true, adc: true}
    val configJson: String = "{}", // Device-specific config
    val createdAt: String = "",
)

/**
 * GPIO pin state and configuration.
 */
@Entity(tableName = "gpio_pins")
data class GpioPin(
    @PrimaryKey val id: String,
    val deviceId: String,
    val workspaceId: String = "default",
    val pinNumber: Int,
    val label: String? = null,
    val mode: String, // "input", "output", "pwm", "analog"
    val state: Int = 0, // 0=LOW, 1=HIGH for digital; 0-4095 for PWM
    val frequency: Int? = null, // For PWM
    val pullMode: String? = null, // "pull_up", "pull_down", "floating"
    val isActive: Boolean = true,
    val lastUpdated: String = "",
    val sensorType: String? = null, // "temperature", "humidity", "motion", "distance", etc.
)

/**
 * Sensor data reading.
 */
@Entity(tableName = "sensor_readings")
data class SensorReading(
    @PrimaryKey val id: String,
    val deviceId: String,
    val pinId: String,
    val workspaceId: String = "default",
    val sensorType: String, // "dht22", "bmp280", "motion", "distance", "light"
    val value: Float,
    val unit: String, // "°C", "%", "cm", "lux", etc.
    val timestamp: String = "",
    val isAlert: Boolean = false, // Triggers if value exceeds threshold
)

/**
 * Sensor alert/threshold configuration.
 */
@Entity(tableName = "sensor_alerts")
data class SensorAlert(
    @PrimaryKey val id: String,
    val deviceId: String,
    val workspaceId: String = "default",
    val sensorType: String,
    val alertName: String,
    val thresholdType: String, // "high", "low", "range"
    val thresholdValue: Float,
    val thresholdMax: Float? = null, // For range alerts
    val isActive: Boolean = true,
    val lastTriggered: String? = null,
    val createdAt: String = "",
)

/**
 * Relay or actuator control command.
 */
@Entity(tableName = "actuator_commands")
data class ActuatorCommand(
    @PrimaryKey val id: String,
    val deviceId: String,
    val workspaceId: String = "default",
    val pinId: String,
    val commandType: String, // "on", "off", "toggle", "pulse", "pwm_set"
    val value: Int? = null, // For PWM/pulse commands
    val durationMs: Int? = null, // For time-limited commands
    val status: String = "pending", // pending, executing, completed, failed
    val result: String? = null,
    val isScheduled: Boolean = false,
    val scheduledTime: String? = null, // ISO 8601 timestamp
    val createdAt: String = "",
    val executedAt: String? = null,
)

/**
 * Device audit log.
 */
@Entity(tableName = "hardware_audit_logs")
data class HardwareAuditLog(
    @PrimaryKey val id: String,
    val deviceId: String,
    val workspaceId: String = "default",
    val action: String, // "connect", "disconnect", "pin_set", "reading", "alert", "command"
    val details: String, // JSON with action-specific details
    val status: String = "success",
    val errorMessage: String? = null,
    val timestamp: String = "",
)

// ==================== Model Objects ====================

/**
 * Real-time device statistics.
 */
data class DeviceStats(
    val uptime: Long = 0, // milliseconds
    val cpuUsage: Float = 0f, // percentage
    val memoryUsage: Float = 0f, // percentage
    val temperature: Float = 0f, // °C
    val gpioToggleCount: Int = 0,
    val sensorReadingCount: Int = 0,
    val lastReboot: String? = null,
    val freeMemory: Int = 0, // KB
)
