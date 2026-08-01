/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.hardware

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsActive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import com.zeroclaw.android.model.SensorAlert
import com.zeroclaw.android.viewmodel.HardwareViewModel
import kotlinx.coroutines.launch

/**
 * Sensor alert configuration screen for setting thresholds and monitoring triggers.
 *
 * Features:
 * - Define high/low/range thresholds for sensors
 * - Name and describe alerts
 * - View triggered alerts with timestamp
 * - Enable/disable individual alerts
 * - Visual indicator for active alerts
 *
 * @param modifier Modifier applied to the root container.
 * @param edgeMargin Horizontal padding for window size class responsiveness.
 * @param navController Navigation controller for back navigation.
 * @param viewModel Hardware ViewModel for alert operations.
 */
@Composable
fun SensorAlertConfigScreen(
    deviceId: String? = null,
    modifier: Modifier = Modifier,
    edgeMargin: Dp = 16.dp,
    navController: NavController? = null,
    viewModel: HardwareViewModel = viewModel(),
) {
    val activeAlerts by viewModel.activeAlerts.collectAsStateWithLifecycle()
    val selectedDevice by viewModel.selectedDevice.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    var showCreateDialog by remember { mutableStateOf(false) }
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
                        text = "Sensor Alerts",
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
                onClick = { showCreateDialog = true },
                modifier = Modifier.size(48.dp),
                shape = CircleShape,
            ) {
                Icon(
                    imageVector = Icons.Filled.Add,
                    contentDescription = "Create Alert",
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
                        text = errorMessage.orEmpty(),
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

        // Alerts list
        if (activeAlerts.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Notifications,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Text(
                        "No Alerts",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Create an alert to monitor sensor values",
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
                items(activeAlerts) { alert ->
                    SensorAlertCard(
                        alert = alert,
                        isLoading = isLoading,
                        onToggleActive = { newState ->
                            scope.launch {
                                if (!newState) {
                                    viewModel.disableAlert(alert.id)
                                }
                            }
                        },
                    )
                }
            }
        }
    }

    // Create alert dialog
    if (showCreateDialog) {
        CreateAlertDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { alertName, sensorType, thresholdValue ->
                scope.launch {
                    viewModel.createAlert(alertName, sensorType, thresholdValue.toDouble())
                    showCreateDialog = false
                }
            },
        )
    }
}

/**
 * Card displaying a sensor alert with threshold and active status.
 */
@Composable
private fun SensorAlertCard(
    alert: SensorAlert,
    isLoading: Boolean,
    onToggleActive: (Boolean) -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (alert.isActive)
                MaterialTheme.colorScheme.primaryContainer else
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
                        text = alert.alertName,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = alert.sensorType.replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Switch(
                    checked = alert.isActive,
                    onCheckedChange = onToggleActive,
                    enabled = !isLoading,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Threshold details
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Type",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = alert.thresholdType.uppercase(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Threshold",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "%.2f".format(alert.thresholdValue),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                if (alert.lastTriggered != null && alert.isActive) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Last Triggered",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = "Recently",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }

            if (alert.isActive) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                    shape = RoundedCornerShape(6.dp),
                    color = MaterialTheme.colorScheme.error.copy(alpha = 0.1f),
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.NotificationsActive,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            text = "Alert Active",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(start = 6.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Dialog for creating a new sensor alert.
 */
@Composable
private fun CreateAlertDialog(
    onDismiss: () -> Unit,
    onCreate: (alertName: String, sensorType: String, threshold: String) -> Unit,
) {
    var alertName by remember { mutableStateOf("") }
    var sensorType by remember { mutableStateOf("temperature") }
    var thresholdValue by remember { mutableStateOf("") }

    val sensorTypes = listOf(
        "temperature",
        "humidity",
        "pressure",
        "motion",
        "distance",
        "light",
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create Alert") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = alertName,
                    onValueChange = { alertName = it },
                    label = { Text("Alert Name") },
                    placeholder = { Text("e.g., High Temperature") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(),
                )

                Text(
                    text = "Sensor Type",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(4.dp),
                ) {
                    sensorTypes.forEach { type ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { sensorType = type }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(20.dp)
                                    .background(
                                        color = if (sensorType == type)
                                            MaterialTheme.colorScheme.primary else
                                            MaterialTheme.colorScheme.outline,
                                        shape = CircleShape,
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (sensorType == type) {
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
                            Text(type.replaceFirstChar { it.uppercase() })
                        }
                    }
                }

                OutlinedTextField(
                    value = thresholdValue,
                    onValueChange = { thresholdValue = it },
                    label = { Text("Threshold Value") },
                    placeholder = { Text("e.g., 35") },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(),
                )
            }
        },
        confirmButton = {
            Button(
                onClick = { onCreate(alertName.ifEmpty { "Alert" }, sensorType, thresholdValue.ifEmpty { "0" }) },
                enabled = alertName.isNotEmpty() && thresholdValue.isNotEmpty(),
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}
