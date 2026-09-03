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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavController
import com.zeroclaw.android.model.SensorReading
import com.zeroclaw.android.viewmodel.HardwareViewModel

/**
 * Sensor readings monitoring screen with real-time data display and statistics.
 *
 * Features:
 * - Live sensor data visualization (temperature, humidity, distance, etc.)
 * - Real-time updates with timestamp
 * - Min/max/average statistics
 * - Sensor type filtering with tabs
 * - Alert indicators for out-of-range values
 *
 * @param modifier Modifier applied to the root container.
 * @param edgeMargin Horizontal padding for window size class responsiveness.
 * @param navController Navigation controller for back navigation.
 * @param viewModel Hardware ViewModel for sensor operations.
 */
@Composable
fun SensorMonitorScreen(
    deviceId: String? = null,
    modifier: Modifier = Modifier,
    edgeMargin: Dp = 16.dp,
    navController: NavController? = null,
    viewModel: HardwareViewModel = viewModel(),
) {
    val sensorReadings by viewModel.sensorReadings.collectAsStateWithLifecycle()
    val selectedDevice by viewModel.selectedDevice.collectAsStateWithLifecycle()
    val devicePins by viewModel.devicePins.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    var tabIndex by remember { mutableIntStateOf(0) }

    LaunchedEffect(deviceId) {
        deviceId?.let { viewModel.selectDeviceById(it) }
    }

    val sensorTypes = sensorReadings.map { it.sensorType }.distinct()
    val hasSensorPins = devicePins.any { !it.sensorType.isNullOrBlank() }
    val selectedType = if (tabIndex < sensorTypes.size) sensorTypes[tabIndex] else null
    val filteredReadings = if (selectedType != null) {
        sensorReadings.filter { it.sensorType == selectedType }
    } else {
        sensorReadings
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
                        text = "Sensor Monitor",
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
            IconButton(
                onClick = { viewModel.pollSelectedDeviceSensors() },
                enabled = selectedDevice != null && !isLoading,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Refresh,
                    contentDescription = "Refresh sensors",
                )
            }
        }

        // Error message
        AnimatedVisibility(visible = errorMessage?.isNotEmpty() == true) {
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

        // Sensor type tabs
        if (sensorTypes.isNotEmpty()) {
            TabRow(selectedTabIndex = minOf(tabIndex, sensorTypes.size - 1)) {
                sensorTypes.forEachIndexed { index, sensorType ->
                    Tab(
                        selected = tabIndex == index,
                        onClick = { tabIndex = index },
                        text = {
                            Text(
                                text = sensorType.replaceFirstChar { it.uppercase() },
                                maxLines = 1,
                            )
                        },
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (filteredReadings.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Sensors,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            "No Sensor Data",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { viewModel.pollSelectedDeviceSensors() },
                            enabled = selectedDevice != null && !isLoading,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Refresh")
                        }
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
                    items(filteredReadings) { reading ->
                        SensorReadingCard(
                            reading = reading,
                            isAlert = reading.isAlert,
                        )
                    }
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
                        imageVector = Icons.Outlined.Sensors,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        if (hasSensorPins) "No Sensor Data" else "No Sensors Configured",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    if (hasSensorPins) {
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { viewModel.pollSelectedDeviceSensors() },
                            enabled = selectedDevice != null && !isLoading,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Refresh")
                        }
                    } else {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Configure GPIO pins as sensors in the GPIO Control screen",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/**
 * Card displaying a single sensor reading with value and timestamp.
 */
@Composable
private fun SensorReadingCard(
    reading: SensorReading,
    isAlert: Boolean,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isAlert)
                MaterialTheme.colorScheme.errorContainer else
                MaterialTheme.colorScheme.surfaceContainer,
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
                        text = reading.sensorType.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.titleSmall,
                        color = if (isAlert)
                            MaterialTheme.colorScheme.onErrorContainer else
                            MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = formatTimestamp(reading.timestamp),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isAlert)
                            MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f) else
                            MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (isAlert) {
                    Surface(
                        modifier = Modifier
                            .background(
                                MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(4.dp),
                            )
                            .padding(6.dp),
                        color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                    ) {
                        Text(
                            text = "ALERT",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(4.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Value display with unit
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Value",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isAlert)
                        MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f) else
                        MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = "%.2f".format(reading.value),
                        style = MaterialTheme.typography.headlineSmall,
                        color = if (isAlert)
                            MaterialTheme.colorScheme.onErrorContainer else
                            MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = reading.unit,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isAlert)
                            MaterialTheme.colorScheme.onErrorContainer else
                            MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Status bar
            LinearProgressIndicator(
                progress = { (reading.value / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                color = when {
                    isAlert -> MaterialTheme.colorScheme.error
                    reading.value > 80 -> MaterialTheme.colorScheme.tertiary
                    else -> MaterialTheme.colorScheme.primary
                },
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                strokeCap = StrokeCap.Round,
            )
        }
    }
}

/**
 * Format timestamp to readable format.
 */
private fun formatTimestamp(timestamp: String): String {
    return try {
        // Simple format - just return last portion after last space
        if (timestamp.contains(" ")) {
            timestamp.substringAfterLast(" ")
        } else {
            timestamp
        }
    } catch (e: Exception) {
        timestamp
    }
}
