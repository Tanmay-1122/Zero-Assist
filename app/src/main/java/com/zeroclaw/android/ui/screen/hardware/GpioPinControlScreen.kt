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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Widgets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.zeroclaw.android.model.GpioPin
import com.zeroclaw.android.viewmodel.HardwareViewModel
import kotlinx.coroutines.launch

/**
 * GPIO pin configuration and control screen with Material 3 design consistency.
 *
 * Features:
 * - Display GPIO pins for selected device
 * - Configure pin modes (input, output, PWM, analog)
 * - Set digital pin states (HIGH/LOW)
 * - PWM frequency and duty cycle control
 * - Sensor type assignment
 * - Live pin state updates
 *
 * @param modifier Modifier applied to the root container.
 * @param edgeMargin Horizontal padding for window size class responsiveness.
 * @param navController Navigation controller for back navigation.
 * @param viewModel Hardware ViewModel for GPIO operations.
 */
@Composable
fun GpioPinControlScreen(
    deviceId: String? = null,
    modifier: Modifier = Modifier,
    edgeMargin: Dp = 16.dp,
    navController: NavController? = null,
    viewModel: HardwareViewModel = viewModel(),
) {
    val devicePins by viewModel.devicePins.collectAsStateWithLifecycle()
    val selectedDevice by viewModel.selectedDevice.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    var tabIndex by remember { mutableIntStateOf(0) }
    var showAddPinDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

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
                        text = "GPIO Control",
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
                onClick = { showAddPinDialog = true },
                modifier = Modifier.size(48.dp),
                enabled = selectedDevice != null && !isLoading,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Add Pin",
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

        // Tabs for filter view
        if (devicePins.isNotEmpty()) {
            TabRow(selectedTabIndex = tabIndex) {
                Tab(
                    selected = tabIndex == 0,
                    onClick = { tabIndex = 0 },
                    text = { Text("All (${devicePins.size})") },
                )
                Tab(
                    selected = tabIndex == 1,
                    onClick = { tabIndex = 1 },
                    text = { Text("Output (${devicePins.count { it.mode == "output" }})") },
                )
                Tab(
                    selected = tabIndex == 2,
                    onClick = { tabIndex = 2 },
                    text = { Text("Sensors (${devicePins.count { it.sensorType != null }})") },
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            val filteredPins = when (tabIndex) {
                0 -> devicePins
                1 -> devicePins.filter { it.mode == "output" }
                2 -> devicePins.filter { it.sensorType != null }
                else -> devicePins
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(edgeMargin),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 16.dp),
            ) {
                items(filteredPins) { pin ->
                    GpioPinCard(
                        pin = pin,
                        isLoading = isLoading,
                        onModeChange = { newMode ->
                            scope.launch { viewModel.configurePin(pin.id, newMode) }
                        },
                        onStateChange = { newState ->
                            scope.launch { viewModel.setDigitalPin(pin.id, newState) }
                        },
                        onPwmChange = { value ->
                            scope.launch { viewModel.setPwmPin(pin.id, value) }
                        },
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Widgets,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "No GPIO Pins",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = { showAddPinDialog = true },
                        enabled = selectedDevice != null && !isLoading,
                    ) {
                        Text("Add Pin")
                    }
                }
            }
        }
    }

    if (showAddPinDialog) {
        AddPinDialog(
            onDismiss = { showAddPinDialog = false },
            onAdd = { pinNumber, mode, label, sensorType ->
                scope.launch {
                    viewModel.createPin(pinNumber, mode, label, sensorType)
                    showAddPinDialog = false
                }
            },
        )
    }
}

/**
 * GPIO pin configuration card with mode and state controls.
 */
@Composable
private fun GpioPinCard(
    pin: GpioPin,
    isLoading: Boolean,
    onModeChange: (String) -> Unit,
    onStateChange: (Int) -> Unit,
    onPwmChange: (Int) -> Unit,
) {
    var showModeMenu by remember { mutableStateOf(false) }
    val modes = listOf("input", "output", "pwm", "analog")

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // Pin header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = "Pin ${pin.pinNumber}",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (pin.label != null) {
                        Text(
                            text = pin.label!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Box {
                    OutlinedButton(
                        onClick = { showModeMenu = true },
                        enabled = !isLoading,
                    ) {
                        Text(
                            text = pin.mode.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }

                    DropdownMenu(
                        expanded = showModeMenu,
                        onDismissRequest = { showModeMenu = false },
                    ) {
                        modes.forEach { mode ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = mode.uppercase(),
                                        style = MaterialTheme.typography.labelSmall,
                                    )
                                },
                                onClick = {
                                    showModeMenu = false
                                    onModeChange(mode)
                                },
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Mode-specific controls
            when (pin.mode) {
                "output", "input" -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Digital State",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Switch(
                            checked = pin.state == 1,
                            onCheckedChange = { onStateChange(if (it) 1 else 0) },
                            enabled = !isLoading && pin.mode == "output",
                        )
                    }
                }

                "pwm" -> {
                    Column {
                        Text(
                            text = "PWM Value",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        Slider(
                            value = pin.state.coerceIn(0, 4095).toFloat(),
                            onValueChange = { onPwmChange(it.toInt()) },
                            valueRange = 0f..4095f,
                            enabled = !isLoading,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            text = pin.state.coerceIn(0, 4095).toString(),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                "analog" -> {
                    if (pin.sensorType != null) {
                        Text(
                            text = "Sensor Type: ${pin.sensorType}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            // Sensor assignment
            if (pin.sensorType != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.1f),
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Widgets,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = "Sensor: ${pin.sensorType}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AddPinDialog(
    onDismiss: () -> Unit,
    onAdd: (pinNumber: Int, mode: String, label: String?, sensorType: String?) -> Unit,
) {
    var pinNumberText by remember { mutableStateOf("") }
    var selectedMode by remember { mutableStateOf("output") }
    var label by remember { mutableStateOf("") }
    var sensorType by remember { mutableStateOf("") }
    var showModeMenu by remember { mutableStateOf(false) }
    val modes = listOf("input", "output", "pwm", "analog")
    val parsedPin = pinNumberText.toIntOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add GPIO Pin") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = pinNumberText,
                    onValueChange = { value ->
                        pinNumberText = value.filter { it.isDigit() }
                    },
                    label = { Text("Pin Number") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                Box {
                    OutlinedButton(
                        onClick = { showModeMenu = true },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(selectedMode.uppercase())
                    }

                    DropdownMenu(
                        expanded = showModeMenu,
                        onDismissRequest = { showModeMenu = false },
                    ) {
                        modes.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.uppercase()) },
                                onClick = {
                                    selectedMode = mode
                                    showModeMenu = false
                                },
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    label = { Text("Label") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )

                OutlinedTextField(
                    value = sensorType,
                    onValueChange = { sensorType = it },
                    label = { Text("Sensor Type") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    parsedPin?.let { pinNumber ->
                        onAdd(pinNumber, selectedMode, label, sensorType)
                    }
                },
                enabled = parsedPin != null,
            ) {
                Text("Add")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
