/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.hardware

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.zeroclaw.android.model.HardwareDevice
import com.zeroclaw.android.model.HardwareTransportCandidate
import com.zeroclaw.android.viewmodel.HardwareViewModel
import com.zeroclaw.android.navigation.GpioPinControlRoute
import com.zeroclaw.android.navigation.SensorMonitorRoute
import com.zeroclaw.android.navigation.ActuatorControlRoute
import com.zeroclaw.android.ui.component.premiumFadeInUp
import kotlinx.coroutines.launch

/**
 * Hardware devices management screen featuring device list, registration,
 * and status monitoring with Material 3 design consistency.
 *
 * Displays registered hardware devices (Raspberry Pi, Pico, etc.) with:
 * - Live connection status indicators
 * - Device type and firmware information
 * - Quick access to GPIO, sensor, and actuator controls
 * - Device registration form
 *
 * @param modifier Modifier applied to the root container.
 * @param edgeMargin Horizontal padding for window size class responsiveness.
 * @param navController Navigation controller for routing to device controls.
 * @param viewModel Hardware ViewModel for device management.
 */
@Composable
fun HardwareDevicesScreen(
    modifier: Modifier = Modifier,
    edgeMargin: Dp = 16.dp,
    navController: NavController? = null,
    viewModel: HardwareViewModel = viewModel(),
) {
    val devices by viewModel.devices.collectAsStateWithLifecycle()
    val transportCandidates by viewModel.transportCandidates.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    var showRegisterDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val bluetoothPermissionLauncher =
        rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions(),
        ) {
            viewModel.discoverHardwareTransports()
        }
    val scanHardware: () -> Unit = {
        if (hasBluetoothScanPermissions(context)) {
            viewModel.discoverHardwareTransports()
        } else {
            bluetoothPermissionLauncher.launch(
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                ),
            )
        }
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (intent.action == usbPermissionAction(context)) {
                    viewModel.discoverHardwareTransports()
                }
            }
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(usbPermissionAction(context)),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(edgeMargin)
                .padding(top = 16.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Hardware Devices",
                    style = MaterialTheme.typography.headlineLarge,
                    color = MaterialTheme.colorScheme.onBackground,
                )
                Text(
                    text = hardwareDeviceConnectionSummary(devices),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = scanHardware,
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                    enabled = !isLoading,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Search,
                        contentDescription = "Scan Hardware",
                    )
                }

                Button(
                    onClick = { showRegisterDialog = true },
                    modifier = Modifier.size(48.dp),
                    shape = CircleShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                    ),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Add,
                        contentDescription = "Register Device",
                        tint = MaterialTheme.colorScheme.onPrimary,
                    )
                }
            }
        }

        // Error message display
        AnimatedVisibility(visible = !errorMessage.isNullOrEmpty()) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(edgeMargin),
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.errorContainer,
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = errorMessage ?: "",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { viewModel.clearError() }, modifier = Modifier.size(24.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Dismiss error",
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Device list
        if (devices.isEmpty() && transportCandidates.isEmpty() && !isLoading) {
            EmptyDevicesPlaceholder(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(edgeMargin),
                onRegisterClick = { showRegisterDialog = true },
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(edgeMargin),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
            ) {
                if (transportCandidates.isNotEmpty()) {
                    item {
                        Text(
                            text = "Discovered Hardware",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    items(transportCandidates) { candidate ->
                        HardwareTransportCandidateCard(
                            candidate = candidate,
                            isLoading = isLoading,
                            onRegister = {
                                scope.launch {
                                    viewModel.registerTransportCandidate(candidate)
                                }
                            },
                            onRequestPermission = {
                                requestUsbHardwarePermission(context, candidate.address)
                            },
                        )
                    }
                }

                if (devices.isNotEmpty() && transportCandidates.isNotEmpty()) {
                    item {
                        Text(
                            text = "Registered Devices",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }

                itemsIndexed(devices) { index, device ->
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.premiumFadeInUp(
                            delayMillis = index * 50,
                        ),
                    ) {
                        HardwareDeviceCard(
                            device = device,
                            isLoading = isLoading,
                            onGpioClick = { navController?.navigate(GpioPinControlRoute(device.id)) },
                            onSensorClick = { navController?.navigate(SensorMonitorRoute(device.id)) },
                            onActuatorClick = { navController?.navigate(ActuatorControlRoute(device.id)) },
                            onProbeClick = {
                                scope.launch {
                                    viewModel.probeDeviceConnection(device.id)
                                }
                            },
                            onStatusUpdate = { newStatus ->
                                scope.launch {
                                    viewModel.updateDeviceStatus(device.id, newStatus)
                                }
                            },
                        )
                    }
                }
            }
        }
    }

    // Register device dialog
    if (showRegisterDialog) {
        RegisterDeviceDialog(
            onDismiss = { showRegisterDialog = false },
            onRegister = { name, type ->
                scope.launch {
                    viewModel.registerDevice(name, type)
                    showRegisterDialog = false
                }
            },
        )
    }
}

/**
 * Discovered Android transport candidate card.
 */
@Composable
private fun HardwareTransportCandidateCard(
    candidate: HardwareTransportCandidate,
    isLoading: Boolean,
    onRegister: () -> Unit,
    onRequestPermission: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = candidate.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = candidate.transportType.replace('_', ' ').replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                ConnectionStatusBadge(
                    status = if (candidate.isPermissionGranted) "discovered" else "permission_pending",
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            DetailRow("Address", candidate.address)
            DetailRow("Device Type", candidate.suggestedDeviceType.replaceFirstChar { it.uppercase() })

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (candidate.transportType == "usb_serial" && !candidate.isPermissionGranted) {
                    OutlinedButton(
                        onClick = onRequestPermission,
                        enabled = !isLoading,
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                    ) {
                        Text("Grant Access", style = MaterialTheme.typography.labelSmall)
                    }
                }

                Button(
                    onClick = onRegister,
                    enabled = !isLoading,
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp),
                ) {
                    Text("Register", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/**
 * Hardware device card displaying device info and quick action buttons.
 */
@Composable
private fun HardwareDeviceCard(
    device: HardwareDevice,
    isLoading: Boolean,
    onGpioClick: () -> Unit,
    onSensorClick: () -> Unit,
    onActuatorClick: () -> Unit,
    onProbeClick: () -> Unit,
    onStatusUpdate: (String) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            // Device header with status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = device.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = device.type.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ConnectionStatusBadge(status = device.connectionStatus)
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Device details
            if (device.ipAddress != null) {
                DetailRow("IP Address", device.ipAddress!!)
            }
            DetailRow("Firmware", device.firmwareVersion)

            Spacer(modifier = Modifier.height(12.dp))

            if (device.connectionStatus != "connected") {
                OutlinedButton(
                    onClick = onProbeClick,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp),
                    enabled = !isLoading,
                ) {
                    Text("Connect", style = MaterialTheme.typography.labelSmall)
                }

                Spacer(modifier = Modifier.height(8.dp))
            }

            // Quick action buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onGpioClick,
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp),
                    enabled = !isLoading && device.connectionStatus == "connected",
                ) {
                    Text("GPIO", style = MaterialTheme.typography.labelSmall)
                }

                OutlinedButton(
                    onClick = onSensorClick,
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp),
                    enabled = !isLoading && device.connectionStatus == "connected",
                ) {
                    Text("Sensors", style = MaterialTheme.typography.labelSmall)
                }

                OutlinedButton(
                    onClick = onActuatorClick,
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp),
                    enabled = !isLoading && device.connectionStatus == "connected",
                ) {
                    Text("Actuators", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

/**
 * Connection status badge with color-coded indicator.
 */
@Composable
private fun ConnectionStatusBadge(status: String) {
    val (color, label) = when (status) {
        "connected" -> Pair(
            MaterialTheme.colorScheme.primary,
            "Connected"
        )
        "disconnected" -> Pair(
            MaterialTheme.colorScheme.outline,
            "Offline"
        )
        "error" -> Pair(
            MaterialTheme.colorScheme.error,
            "Error"
        )
        "configured" -> Pair(
            MaterialTheme.colorScheme.secondary,
            "Configured"
        )
        "configuring" -> Pair(
            MaterialTheme.colorScheme.tertiary,
            "Configuring"
        )
        "unavailable" -> Pair(
            MaterialTheme.colorScheme.outline,
            "Unavailable"
        )
        "discovered" -> Pair(
            MaterialTheme.colorScheme.secondary,
            "Discovered"
        )
        "permission_pending" -> Pair(
            MaterialTheme.colorScheme.tertiary,
            "Permission"
        )
        else -> Pair(
            MaterialTheme.colorScheme.surfaceVariant,
            "Unknown"
        )
    }

    Surface(
        modifier = Modifier
            .background(color.copy(alpha = 0.1f), shape = RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        color = Color.Transparent,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color, shape = CircleShape),
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = color,
            )
        }
    }
}

/**
 * Detail row for device information display.
 */
@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

private fun hasBluetoothScanPermissions(context: Context): Boolean {
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        (
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_SCAN,
            ) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.BLUETOOTH_CONNECT,
                ) == PackageManager.PERMISSION_GRANTED
            )
}

private fun requestUsbHardwarePermission(context: Context, deviceName: String) {
    val usbManager = context.getSystemService(UsbManager::class.java) ?: return
    val device = usbManager.deviceList.values.firstOrNull { it.deviceName == deviceName } ?: return
    val intent = Intent(usbPermissionAction(context)).setPackage(context.packageName)
    val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    val pendingIntent = PendingIntent.getBroadcast(
        context,
        deviceName.hashCode(),
        intent,
        flags,
    )
    usbManager.requestPermission(device, pendingIntent)
}

private fun usbPermissionAction(context: Context): String {
    return "${context.packageName}.USB_HARDWARE_PERMISSION"
}

/**
 * Empty state placeholder when no devices are registered.
 */
@Composable
private fun EmptyDevicesPlaceholder(
    modifier: Modifier = Modifier,
    onRegisterClick: () -> Unit,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            imageVector = Icons.Outlined.SportsEsports,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "No Hardware Devices",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "Register your first Raspberry Pi, Pico, or other device to get started",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp),
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(onClick = onRegisterClick) {
            Text("Register Device")
        }
    }
}

/**
 * Dialog for registering a new hardware device.
 */
@Composable
private fun RegisterDeviceDialog(
    onDismiss: () -> Unit,
    onRegister: (name: String, type: String) -> Unit,
) {
    var deviceName by remember { mutableStateOf("") }
    var selectedType by remember { mutableStateOf("raspberry_pi") }
    val types = listOf(
        "raspberry_pi" to "Raspberry Pi",
        "pico" to "Raspberry Pi Pico",
        "arduino" to "Arduino",
        "esp32" to "ESP32",
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Register Hardware Device") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = deviceName,
                    onValueChange = { deviceName = it },
                    label = { Text("Device Name") },
                    placeholder = { Text("e.g., Living Room Pi") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(),
                )

                Text(
                    text = "Device Type",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                types.forEach { (typeValue, typeLabel) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { selectedType = typeValue }
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(20.dp)
                                .background(
                                    color = if (selectedType == typeValue)
                                        MaterialTheme.colorScheme.primary else
                                        MaterialTheme.colorScheme.outline,
                                    shape = CircleShape,
                                ),
                            contentAlignment = Alignment.Center,
                        ) {
                            if (selectedType == typeValue) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(
                                            MaterialTheme.colorScheme.onPrimary,
                                            shape = CircleShape,
                                        ),
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(typeLabel, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onRegister(deviceName.ifEmpty { "New Device" }, selectedType) },
                enabled = deviceName.isNotEmpty(),
            ) {
                Text("Register")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

fun hardwareDeviceConnectionSummary(devices: List<HardwareDevice>): String {
    val connectedCount = devices.count { it.connectionStatus == "connected" }
    val deviceLabel = if (devices.size == 1) "device" else "devices"
    return "$connectedCount of ${devices.size} $deviceLabel connected"
}
