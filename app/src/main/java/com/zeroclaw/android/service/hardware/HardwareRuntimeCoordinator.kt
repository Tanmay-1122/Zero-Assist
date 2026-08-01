/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.hardware

import android.Manifest
import android.bluetooth.BluetoothSocket
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.SystemClock
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.zeroclaw.android.model.HardwareCommand
import com.zeroclaw.android.model.HardwareCommandResult
import com.zeroclaw.android.model.HardwareDevice
import com.zeroclaw.android.network.CleartextHttpPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.UUID

/**
 * Executes hardware commands against the Android-visible transport for a device.
 */
class HardwareRuntimeCoordinator(
    private val context: Context,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(DEFAULT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .readTimeout(DEFAULT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .writeTimeout(DEFAULT_TIMEOUT_MS.toLong(), TimeUnit.MILLISECONDS)
        .build(),
) : HardwareCommandExecutor {
    override suspend fun execute(
        device: HardwareDevice,
        command: HardwareCommand,
    ): HardwareCommandResult = withContext(Dispatchers.IO) {
        val config = runCatching { JSONObject(device.configJson) }.getOrNull() ?: JSONObject()
        val transportType = config.optString("transportType", "").ifBlank {
            if (!device.ipAddress.isNullOrBlank()) "network_http" else ""
        }
        when (transportType) {
            "usb_serial" -> executeUsbSerial(config, command)
            "network_http", "http" -> executeHttp(device, config, command, transportType)
            "bluetooth_serial" -> executeBluetoothSerial(config, command)
            else -> HardwareCommandResult(
                ok = false,
                transportType = transportType.ifBlank { "unknown" },
                error = "No executable hardware transport is configured for ${device.name}",
            )
        }
    }

    private fun executeUsbSerial(
        config: JSONObject,
        command: HardwareCommand,
    ): HardwareCommandResult {
        val address = config.optString("address", "")
        val baudRate = config.optInt("baudRate", DEFAULT_BAUD_RATE)
        val timeoutMs = config.optInt("timeoutMs", DEFAULT_TIMEOUT_MS)
        val usbManager = context.getSystemService(UsbManager::class.java)
            ?: return failed("usb_serial", "USB manager unavailable")
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
        val driver = drivers.firstOrNull { it.device.deviceName == address }
            ?: return failed("usb_serial", "USB serial driver not found for $address")

        if (!usbManager.hasPermission(driver.device)) {
            return failed("usb_serial", "USB permission is not granted for $address")
        }

        val connection = usbManager.openDevice(driver.device)
            ?: return failed("usb_serial", "Failed to open USB device $address")
        val port = driver.ports.firstOrNull()
        if (port == null) {
            runCatching { connection.close() }
            return failed("usb_serial", "No serial ports exposed by $address")
        }

        return try {
            port.open(connection)
            port.setParameters(
                baudRate,
                DATA_BITS,
                UsbSerialPort.STOPBITS_1,
                UsbSerialPort.PARITY_NONE,
            )
            port.write(command.toJsonLine().toByteArray(Charsets.UTF_8), timeoutMs)
            parseResponse(
                transportType = "usb_serial",
                rawResponse = readLineFromPort(port, timeoutMs),
            )
        } catch (e: Exception) {
            failed("usb_serial", e.message ?: "USB serial command failed")
        } finally {
            runCatching { port.close() }
            runCatching { connection.close() }
        }
    }

    private fun executeHttp(
        device: HardwareDevice,
        config: JSONObject,
        command: HardwareCommand,
        transportType: String,
    ): HardwareCommandResult {
        val endpoint = config.optString("commandEndpoint", "").ifBlank {
            device.ipAddress?.let { "http://$it/hardware/command" }.orEmpty()
        }
        if (endpoint.isBlank()) {
            return failed(transportType, "HTTP command endpoint is not configured")
        }

        return try {
            CleartextHttpPolicy.requireAllowed(endpoint, "Hardware runtime")
            val body = command.toJsonLine().trim()
                .toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(endpoint)
                .post(body)
                .build()
            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (!response.isSuccessful) {
                    return failed(transportType, "HTTP ${response.code}: $responseBody")
                }
                if (responseBody.isBlank()) {
                    HardwareCommandResult(ok = true, transportType = transportType)
                } else {
                    parseResponse(transportType, responseBody)
                }
            }
        } catch (e: Exception) {
            failed(transportType, e.message ?: "HTTP hardware command failed")
        }
    }

    private fun executeBluetoothSerial(
        config: JSONObject,
        command: HardwareCommand,
    ): HardwareCommandResult {
        if (!hasBluetoothConnectPermission()) {
            return failed("bluetooth_serial", "Bluetooth connect permission is not granted")
        }
        val address = config.optString("address", "")
        if (address.isBlank()) {
            return failed("bluetooth_serial", "Bluetooth address is not configured")
        }
        val timeoutMs = config.optInt("timeoutMs", DEFAULT_TIMEOUT_MS)
        val serviceUuid = bluetoothServiceUuid(config)
        val bluetoothManager = context.getSystemService(BluetoothManager::class.java)
            ?: return failed("bluetooth_serial", "Bluetooth manager unavailable")
        val adapter = bluetoothManager.adapter
            ?: return failed("bluetooth_serial", "Bluetooth adapter unavailable")

        return try {
            val device = adapter.bondedDevices.firstOrNull { it.address == address }
                ?: return failed("bluetooth_serial", "Bluetooth device is not paired")
            adapter.cancelDiscovery()
            val socket = device.createRfcommSocketToServiceRecord(serviceUuid)
            try {
                socket.connect()
                socket.outputStream.write(command.toJsonLine().toByteArray(Charsets.UTF_8))
                socket.outputStream.flush()
                parseResponse(
                    transportType = "bluetooth_serial",
                    rawResponse = readLineFromStream(socket, timeoutMs),
                )
            } finally {
                runCatching { socket.close() }
            }
        } catch (e: SecurityException) {
            failed("bluetooth_serial", "Bluetooth connect permission is not granted")
        } catch (e: Exception) {
            failed("bluetooth_serial", e.message ?: "Bluetooth serial command failed")
        }
    }

    private fun readLineFromPort(port: UsbSerialPort, timeoutMs: Int): String {
        val buffer = ByteArray(READ_BUFFER_SIZE)
        val builder = StringBuilder()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs

        while (SystemClock.elapsedRealtime() < deadline) {
            val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L).toInt()
            val read = port.read(buffer, remaining)
            if (read > 0) {
                builder.append(String(buffer, 0, read, Charsets.UTF_8))
                if (builder.contains("\n")) {
                    return builder.toString().substringBefore("\n").trim()
                }
            }
        }

        return builder.toString().trim()
    }

    private fun readLineFromStream(socket: BluetoothSocket, timeoutMs: Int): String {
        val stream = socket.inputStream
        val builder = StringBuilder()
        val deadline = SystemClock.elapsedRealtime() + timeoutMs

        while (SystemClock.elapsedRealtime() < deadline) {
            val next = readByteIfAvailable(stream)
            if (next == null) {
                Thread.sleep(READ_POLL_DELAY_MS)
                continue
            }
            val char = next.toInt().toChar()
            if (char == '\n') {
                return builder.toString().trim()
            }
            builder.append(char)
        }

        return builder.toString().trim()
    }

    private fun readByteIfAvailable(stream: InputStream): Byte? {
        if (stream.available() <= 0) {
            return null
        }
        val value = stream.read()
        return if (value >= 0) value.toByte() else null
    }

    private fun bluetoothServiceUuid(config: JSONObject): UUID {
        val metadata = config.optJSONObject("metadata")
        val configuredUuid = config.optString("serviceUuid")
            .ifBlank { metadata?.optString("serviceUuid").orEmpty() }
            .ifBlank { DEFAULT_SPP_UUID }
        return runCatching {
            UUID.fromString(configuredUuid)
        }.getOrElse {
            UUID.fromString(DEFAULT_SPP_UUID)
        }
    }

    private fun hasBluetoothConnectPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    private fun parseResponse(
        transportType: String,
        rawResponse: String,
    ): HardwareCommandResult {
        return HardwareCommandResponseParser.parse(transportType, rawResponse)
    }

    private fun failed(transportType: String, error: String): HardwareCommandResult {
        return HardwareCommandResult(
            ok = false,
            transportType = transportType,
            error = error,
        )
    }

    private companion object {
        const val DEFAULT_BAUD_RATE = 115_200
        const val DEFAULT_TIMEOUT_MS = 5_000
        const val DATA_BITS = 8
        const val READ_BUFFER_SIZE = 256
        const val READ_POLL_DELAY_MS = 20L
        const val DEFAULT_SPP_UUID = "00001101-0000-1000-8000-00805f9b34fb"
    }
}
