/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zeroclaw.android.ZeroClawApplication
import com.zeroclaw.android.model.HardwareDevice
import com.zeroclaw.android.model.GpioPin
import com.zeroclaw.android.model.SensorReading
import com.zeroclaw.android.model.SensorAlert
import com.zeroclaw.android.model.ActuatorCommand
import com.zeroclaw.android.model.HardwareTransportCandidate
import com.zeroclaw.android.repository.HardwareRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for hardware device management.
 *
 * Manages state for Raspberry Pi, Pico, and other hardware devices.
 * Automatically retrieves the HardwareRepository from the application context.
 */
class HardwareViewModel(
    application: Application,
) : AndroidViewModel(application) {

    private val app: ZeroClawApplication = application as ZeroClawApplication
    private val hardwareRepository: HardwareRepository = app.hardwareRepository

    // ==================== State Flows ====================

    private val _devices = MutableStateFlow<List<HardwareDevice>>(emptyList())
    val devices: StateFlow<List<HardwareDevice>> = _devices.asStateFlow()

    private val _connectedDevices = MutableStateFlow<List<HardwareDevice>>(emptyList())
    val connectedDevices: StateFlow<List<HardwareDevice>> = _connectedDevices.asStateFlow()

    private val _transportCandidates = MutableStateFlow<List<HardwareTransportCandidate>>(emptyList())
    val transportCandidates: StateFlow<List<HardwareTransportCandidate>> = _transportCandidates.asStateFlow()

    private val _selectedDevice = MutableStateFlow<HardwareDevice?>(null)
    val selectedDevice: StateFlow<HardwareDevice?> = _selectedDevice.asStateFlow()

    private val _devicePins = MutableStateFlow<List<GpioPin>>(emptyList())
    val devicePins: StateFlow<List<GpioPin>> = _devicePins.asStateFlow()

    private val _sensorReadings = MutableStateFlow<List<SensorReading>>(emptyList())
    val sensorReadings: StateFlow<List<SensorReading>> = _sensorReadings.asStateFlow()

    private val _activeAlerts = MutableStateFlow<List<SensorAlert>>(emptyList())
    val activeAlerts: StateFlow<List<SensorAlert>> = _activeAlerts.asStateFlow()

    private val _pendingCommands = MutableStateFlow<List<ActuatorCommand>>(emptyList())
    val pendingCommands: StateFlow<List<ActuatorCommand>> = _pendingCommands.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // ==================== Initialization ====================

    init {
        loadAllDevices("default")
    }

    // ==================== Device Management ====================

    fun loadAllDevices(workspaceId: String) {
        viewModelScope.launch {
            _isLoading.update { true }
            try {
                hardwareRepository.observeDevices(workspaceId).collect { devices ->
                    _devices.update { devices }
                }
            } catch (e: Exception) {
                _errorMessage.update { e.message }
            } finally {
                _isLoading.update { false }
            }
        }

        // Load connected devices
        viewModelScope.launch {
            try {
                val connected = hardwareRepository.getConnectedDevices(workspaceId)
                _connectedDevices.update { connected }
            } catch (e: Exception) {
                _errorMessage.update { e.message }
            }
        }
    }

    fun registerDevice(
        name: String,
        type: String,
        ipAddress: String? = null,
        workspaceId: String = "default",
    ) {
        viewModelScope.launch {
            try {
                hardwareRepository.registerDevice(name, type, ipAddress, workspaceId = workspaceId)
                _errorMessage.update { null }
            } catch (e: Exception) {
                _errorMessage.update { "Failed to register device: ${e.message}" }
            }
        }
    }

    fun selectDevice(device: HardwareDevice) {
        _selectedDevice.update { device }
        loadDevicePins(device.id)
        loadDeviceReadings(device.id)
        loadDeviceAlerts(device.id, device.workspaceId)
    }

    fun selectDeviceById(deviceId: String) {
        val routeDeviceId = deviceId.trim()
        if (routeDeviceId.isBlank()) {
            clearSelectedDevice("Hardware device route is missing")
            return
        }
        viewModelScope.launch {
            try {
                val device = hardwareRepository.getDevice(routeDeviceId)
                if (device == null) {
                    clearSelectedDevice("Hardware device not found")
                    return@launch
                }
                selectDevice(device)
                loadPendingCommands(device.workspaceId)
                _errorMessage.update { null }
            } catch (e: Exception) {
                _errorMessage.update { "Failed to load device: ${e.message}" }
            }
        }
    }

    fun unregisterDevice(deviceId: String) {
        viewModelScope.launch {
            try {
                hardwareRepository.unregisterDevice(deviceId)
                _selectedDevice.update { null }
                _errorMessage.update { null }
            } catch (e: Exception) {
                _errorMessage.update { "Failed to unregister device: ${e.message}" }
            }
        }
    }

    fun updateDeviceStatus(deviceId: String, status: String) {
        viewModelScope.launch {
            try {
                hardwareRepository.updateDeviceStatus(deviceId, status)
                _errorMessage.update { null }
            } catch (e: Exception) {
                _errorMessage.update { "Failed to update status: ${e.message}" }
            }
        }
    }

    fun probeDeviceConnection(deviceId: String) {
        viewModelScope.launch {
            _isLoading.update { true }
            try {
                val probedDevice = hardwareRepository.probeDeviceConnection(deviceId)
                if (probedDevice == null) {
                    _errorMessage.update { "Hardware device not found" }
                    return@launch
                }
                _selectedDevice.update { current ->
                    if (current?.id == probedDevice.id) probedDevice else current
                }
                _errorMessage.update {
                    if (probedDevice.connectionStatus == "connected") {
                        null
                    } else {
                        "Hardware connect result: ${probedDevice.connectionStatus}"
                    }
                }
            } catch (e: Exception) {
                _errorMessage.update { "Failed to connect hardware: ${e.message}" }
            } finally {
                _isLoading.update { false }
            }
        }
    }

    // ==================== Transport Discovery ====================

    fun discoverHardwareTransports() {
        viewModelScope.launch {
            _isLoading.update { true }
            try {
                val candidates = hardwareRepository.discoverTransportCandidates()
                _transportCandidates.update { candidates }
                _errorMessage.update {
                    if (candidates.isEmpty()) {
                        "No USB or paired Bluetooth hardware found"
                    } else {
                        null
                    }
                }
            } catch (e: Exception) {
                _errorMessage.update { "Failed to scan hardware transports: ${e.message}" }
            } finally {
                _isLoading.update { false }
            }
        }
    }

    fun registerTransportCandidate(
        candidate: HardwareTransportCandidate,
        workspaceId: String = "default",
    ) {
        viewModelScope.launch {
            try {
                hardwareRepository.registerDevice(
                    name = candidate.displayName,
                    type = candidate.suggestedDeviceType,
                    ipAddress = null,
                    configJson = candidate.toConfigJson(),
                    workspaceId = workspaceId,
                )
                _transportCandidates.update { candidates ->
                    candidates.filterNot { it.id == candidate.id }
                }
                _errorMessage.update { null }
            } catch (e: Exception) {
                _errorMessage.update { "Failed to register hardware: ${e.message}" }
            }
        }
    }

    // ==================== GPIO Management ====================

    fun loadDevicePins(deviceId: String) {
        viewModelScope.launch {
            try {
                hardwareRepository.observeDevicePins(deviceId).collect { pins ->
                    _devicePins.update { pins }
                }
            } catch (e: Exception) {
                _errorMessage.update { e.message }
            }
        }
    }

    fun configurePin(
        pinId: String,
        mode: String,
    ) {
        val targetPinId = pinId.trim()
        val targetMode = mode.trim().lowercase()
        if (targetPinId.isBlank()) {
            _errorMessage.update { "Select a pin before configuring pin" }
            return
        }
        if (targetMode.isBlank()) {
            _errorMessage.update { "Select a pin mode before configuring pin" }
            return
        }
        viewModelScope.launch {
            try {
                hardwareRepository.updatePinMode(targetPinId, targetMode)
                _errorMessage.update { null }
            } catch (e: Exception) {
                _errorMessage.update { "Failed to configure pin: ${e.message}" }
            }
        }
    }

    fun createPin(
        pinNumber: Int,
        mode: String,
        label: String? = null,
        sensorType: String? = null,
    ) {
        val device = _selectedDevice.value
        if (device == null) {
            _errorMessage.update { "Select a device before adding a pin" }
            return
        }
        viewModelScope.launch {
            try {
                hardwareRepository.configurePin(
                    deviceId = device.id,
                    pinNumber = pinNumber,
                    mode = mode,
                    label = label?.trim()?.takeIf { it.isNotBlank() },
                    sensorType = sensorType?.trim()?.takeIf { it.isNotBlank() },
                    workspaceId = device.workspaceId,
                )
                _errorMessage.update { null }
            } catch (e: Exception) {
                _errorMessage.update { "Failed to add pin: ${e.message}" }
            }
        }
    }

    fun setDigitalPin(pinId: String, state: Int) {
        viewModelScope.launch {
            try {
                hardwareRepository.setDigitalPin(pinId, state == 1)
                _errorMessage.update { null }
            } catch (e: Exception) {
                _errorMessage.update { "Failed to set pin: ${e.message}" }
            }
        }
    }

    fun setPwmPin(pinId: String, value: Int, frequency: Int? = null) {
        viewModelScope.launch {
            try {
                hardwareRepository.setPwmPin(pinId, value, frequency)
                _errorMessage.update { null }
            } catch (e: Exception) {
                _errorMessage.update { "Failed to set PWM: ${e.message}" }
            }
        }
    }

    // ==================== Sensor Management ====================

    fun loadDeviceReadings(deviceId: String) {
        viewModelScope.launch {
            try {
                val readings = hardwareRepository.getLatestReadings(deviceId, 100)
                _sensorReadings.update { readings }
                _errorMessage.update { null }
            } catch (e: Exception) {
                _errorMessage.update { "Failed to load readings: ${e.message}" }
            }
        }
    }

    fun pollSelectedDeviceSensors() {
        val device = _selectedDevice.value
        if (device == null) {
            _errorMessage.update { "Select a device before polling sensors" }
            return
        }
        viewModelScope.launch {
            _isLoading.update { true }
            try {
                val polledReadings = hardwareRepository.pollDeviceSensors(device.id)
                val latestReadings = hardwareRepository.getLatestReadings(device.id, 100)
                _sensorReadings.update { latestReadings }
                _errorMessage.update {
                    if (polledReadings.isEmpty()) {
                        "No sensor pins configured for ${device.name}"
                    } else {
                        null
                    }
                }
            } catch (e: Exception) {
                _errorMessage.update { "Failed to poll sensors: ${e.message}" }
            } finally {
                _isLoading.update { false }
            }
        }
    }

    fun recordSensorReading(
        deviceId: String,
        pinId: String,
        sensorType: String,
        value: Float,
        unit: String,
        workspaceId: String = "default",
    ) {
        viewModelScope.launch {
            try {
                hardwareRepository.recordSensorReading(deviceId, pinId, sensorType, value, unit, workspaceId)
                _errorMessage.update { null }
            } catch (e: Exception) {
                _errorMessage.update { "Failed to record reading: ${e.message}" }
            }
        }
    }

    // ==================== Alert Management ====================

    fun loadDeviceAlerts(deviceId: String, workspaceId: String) {
        viewModelScope.launch {
            try {
                hardwareRepository.observeActiveAlerts(deviceId, workspaceId).collect { alerts ->
                    _activeAlerts.update { alerts }
                }
                _errorMessage.update { null }
            } catch (e: Exception) {
                _errorMessage.update { e.message }
            }
        }
    }

    private fun clearSelectedDevice(message: String) {
        _selectedDevice.update { null }
        _devicePins.update { emptyList() }
        _sensorReadings.update { emptyList() }
        _activeAlerts.update { emptyList() }
        _errorMessage.update { message }
    }

    fun createAlert(
        alertName: String,
        sensorType: String,
        thresholdValue: Double,
    ) {
        val device = _selectedDevice.value
        if (device != null) {
            viewModelScope.launch {
                try {
                    hardwareRepository.createAlert(
                        device.id,
                        sensorType,
                        alertName,
                        "high",
                        thresholdValue.toFloat(),
                        null,
                        device.workspaceId,
                    )
                    loadDeviceAlerts(device.id, device.workspaceId)
                    _errorMessage.update { null }
                } catch (e: Exception) {
                    _errorMessage.update { "Failed to create alert: ${e.message}" }
                }
            }
        }
    }

    fun disableAlert(alertId: String) {
        viewModelScope.launch {
            try {
                hardwareRepository.disableAlert(alertId)
                _selectedDevice.value?.let { device ->
                    loadDeviceAlerts(device.id, device.workspaceId)
                }
                _errorMessage.update { null }
            } catch (e: Exception) {
                _errorMessage.update { "Failed to disable alert: ${e.message}" }
            }
        }
    }

    // ==================== Actuator Control ====================

    fun loadPendingCommands(workspaceId: String) {
        viewModelScope.launch {
            try {
                hardwareRepository.observePendingCommands(workspaceId).collect { commands ->
                    _pendingCommands.update { commands }
                }
                _errorMessage.update { null }
            } catch (e: Exception) {
                _errorMessage.update { e.message }
            }
        }
    }

    fun sendActuatorCommand(
        commandType: String,
        pinId: String? = null,
        durationMs: Long = 0L,
    ) {
        val device = _selectedDevice.value
        if (device == null) {
            _errorMessage.update { "Select a device before sending an actuator command" }
            return
        }
        val targetPinId = pinId?.trim()
        if (targetPinId.isNullOrEmpty()) {
            _errorMessage.update { "Select a pin before sending an actuator command" }
            return
        }
        viewModelScope.launch {
            try {
                    hardwareRepository.sendActuatorCommand(
                        device.id,
                        targetPinId,
                        commandType,
                        null,
                        durationMs.toInt(),
                        device.workspaceId,
                    )
                loadPendingCommands(device.workspaceId)
                _errorMessage.update { null }
            } catch (e: Exception) {
                _errorMessage.update { "Failed to send command: ${e.message}" }
            }
        }
    }

    fun scheduleCommand(
        commandType: String,
        scheduledTime: String,
        pinId: String? = null,
        durationMs: Long = 0L,
    ) {
        val device = _selectedDevice.value
        if (device == null) {
            _errorMessage.update { "Select a device before scheduling an actuator command" }
            return
        }
        val targetPinId = pinId?.trim()
        if (targetPinId.isNullOrEmpty()) {
            _errorMessage.update { "Select a pin before scheduling an actuator command" }
            return
        }
        viewModelScope.launch {
            try {
                    hardwareRepository.scheduleActuatorCommand(
                        device.id,
                        targetPinId,
                        commandType,
                        scheduledTime,
                        null,
                        durationMs.toInt(),
                        device.workspaceId,
                    )
                loadPendingCommands(device.workspaceId)
                _errorMessage.update { null }
            } catch (e: Exception) {
                _errorMessage.update { "Failed to schedule command: ${e.message}" }
            }
        }
    }

    fun completeCommand(commandId: String, status: String, result: String? = null) {
        viewModelScope.launch {
            try {
                hardwareRepository.completeCommand(commandId, status, result)
                _selectedDevice.value?.let { device ->
                    loadPendingCommands(device.workspaceId)
                }
                _errorMessage.update { null }
            } catch (e: Exception) {
                _errorMessage.update { "Failed to complete command: ${e.message}" }
            }
        }
    }

    // ==================== Utility Methods ====================

    fun clearError() {
        _errorMessage.update { null }
    }
}
