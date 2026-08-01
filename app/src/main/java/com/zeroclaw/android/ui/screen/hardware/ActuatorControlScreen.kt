/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.hardware

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.ElectricBolt
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.zeroclaw.android.model.ActuatorCommand
import com.zeroclaw.android.model.GpioPin
import com.zeroclaw.android.viewmodel.HardwareViewModel
import kotlinx.coroutines.launch

/**
 * Actuator command scheduling and control screen for sending commands to devices.
 *
 * Features:
 * - Send immediate actuator commands (on/off/toggle/pulse)
 * - Schedule delayed commands with duration
 * - View command history and execution status
 * - Cancel pending commands
 * - Visual status indicators
 *
 * @param modifier Modifier applied to the root container.
 * @param edgeMargin Horizontal padding for window size class responsiveness.
 * @param navController Navigation controller for back navigation.
 * @param viewModel Hardware ViewModel for actuator operations.
 */
@Composable
fun ActuatorControlScreen(
    deviceId: String? = null,
    modifier: Modifier = Modifier,
    edgeMargin: Dp = 16.dp,
    navController: NavController? = null,
    viewModel: HardwareViewModel = viewModel(),
) {
    val pendingCommands by viewModel.pendingCommands.collectAsStateWithLifecycle()
    val devicePins by viewModel.devicePins.collectAsStateWithLifecycle()
    val selectedDevice by viewModel.selectedDevice.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    var showCommandDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val actuatorPins = devicePins.filter { it.mode == "output" || it.mode == "pwm" }

    LaunchedEffect(deviceId) {
        deviceId?.let { viewModel.selectDeviceById(it) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header with back button
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(edgeMargin)
                .padding(top = 8.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f),
            ) {
                IconButton(onClick = { navController?.popBackStack() }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Back",
                    )
                }
                Column {
                    Text(
                        text = "Actuators",
                        style = MaterialTheme.typography.headlineSmall,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    if (selectedDevice != null) {
                        Text(
                            text = selectedDevice!!.name,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Button(
                onClick = { showCommandDialog = true },
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
                enabled = !isLoading && selectedDevice != null && selectedDevice!!.connectionStatus == "connected",
            ) {
                Icon(
                    imageVector = Icons.Outlined.ElectricBolt,
                    contentDescription = "Send Command",
                )
            }
        }

        // Error message
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
                            contentDescription = "Dismiss",
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Commands list
        if (pendingCommands.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.ElectricBolt,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "No Pending Commands",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Send commands to control actuators",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(edgeMargin),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
            ) {
                items(pendingCommands) { command ->
                    ActuatorCommandCard(
                        command = command,
                    )
                }
            }
        }
    }

    // Send command dialog
    if (showCommandDialog) {
        SendCommandDialog(
            pins = actuatorPins,
            onDismiss = { showCommandDialog = false },
            onSend = { commandType, pinId, duration ->
                scope.launch {
                    viewModel.sendActuatorCommand(commandType, pinId, duration.toLongOrNull() ?: 0L)
                    showCommandDialog = false
                }
            },
        )
    }
}

/**
 * Card displaying an actuator command with status and controls.
 */
@Composable
private fun ActuatorCommandCard(
    command: ActuatorCommand,
) {
    val statusColor = when (command.status) {
        "pending" -> MaterialTheme.colorScheme.primary
        "executing" -> MaterialTheme.colorScheme.secondary
        "completed" -> MaterialTheme.colorScheme.tertiary
        "failed" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

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
                        text = "Pin ${command.pinId ?: "N/A"}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = command.commandType.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Surface(
                    modifier = Modifier
                        .background(statusColor.copy(alpha = 0.1f), shape = RoundedCornerShape(6.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    color = MaterialTheme.colorScheme.background.copy(alpha = 0f),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(statusColor, shape = CircleShape),
                        )
                        Text(
                            text = command.status.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Command details
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Value",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = command.value?.toString() ?: "-",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                if (command.durationMs != null && command.durationMs!! > 0) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Duration",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "${command.durationMs}ms",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }

                if (command.isScheduled) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Scheduled",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Schedule,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                            Text(
                                text = "Yes",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            if (command.result != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                ) {
                    Text(
                        text = command.result!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(8.dp),
                    )
                }
            }

        }
    }
}

/**
 * Dialog for sending a new actuator command.
 */
@Composable
private fun SendCommandDialog(
    pins: List<GpioPin>,
    onDismiss: () -> Unit,
    onSend: (commandType: String, pinId: String, duration: String) -> Unit,
) {
    var selectedCommand by remember { mutableStateOf("on") }
    var selectedPinId by remember { mutableStateOf(pins.firstOrNull()?.id.orEmpty()) }
    var duration by remember { mutableStateOf("1000") }
    var scheduleEnabled by remember { mutableStateOf(false) }

    LaunchedEffect(pins) {
        if (selectedPinId.isBlank()) {
            selectedPinId = pins.firstOrNull()?.id.orEmpty()
        }
    }

    val commands = listOf(
        "on" to "Turn ON",
        "off" to "Turn OFF",
        "toggle" to "Toggle",
        "pulse" to "Pulse",
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Send Actuator Command") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = "Command Type",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                commands.forEach { (cmdValue, cmdLabel) ->
                    OutlinedButton(
                        onClick = { selectedCommand = cmdValue },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp),
                    ) {
                        Text(cmdLabel)
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Target Pin",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                if (pins.isEmpty()) {
                    OutlinedTextField(
                        value = selectedPinId,
                        onValueChange = { selectedPinId = it },
                        label = { Text("Pin ID") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    pins.forEach { pin ->
                        val isSelected = pin.id == selectedPinId
                        val buttonModifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                        if (isSelected) {
                            Button(
                                onClick = { selectedPinId = pin.id },
                                modifier = buttonModifier,
                            ) {
                                Text(pin.label ?: "Pin ${pin.pinNumber}")
                            }
                        } else {
                            OutlinedButton(
                                onClick = { selectedPinId = pin.id },
                                modifier = buttonModifier,
                            ) {
                                Text(pin.label ?: "Pin ${pin.pinNumber}")
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    androidx.compose.material3.Checkbox(
                        checked = scheduleEnabled,
                        onCheckedChange = { scheduleEnabled = it },
                    )
                    Text(
                        text = "Schedule Command",
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }

                if (scheduleEnabled) {
                    OutlinedTextField(
                        value = duration,
                        onValueChange = { duration = it },
                        label = { Text("Duration (ms)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSend(selectedCommand, selectedPinId, duration) },
                enabled = selectedPinId.isNotBlank(),
            ) {
                Text("Send")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
