/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.plugins

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zeroclaw.android.model.AppSettings
import com.zeroclaw.android.service.termux.TermuxAutoConnector
import com.zeroclaw.android.service.termux.TermuxPermissionTier
import com.zeroclaw.android.ui.screen.settings.SettingsViewModel
import com.zeroclaw.android.ui.theme.zeroAssistOutlinedTextFieldColors
import com.zeroclaw.android.ui.theme.zeroAssistSecondaryActionButtonColors

private val TIER_OPTIONS =
    listOf(
        TermuxPermissionTier.MEDIUM.name to "Medium \u2014 basic auto, medium approval, high blocked",
        TermuxPermissionTier.HIGH.name to "High \u2014 auto-approve low+medium+high, blocked approval",
        TermuxPermissionTier.UNCONSTRAINED.name to "Unconstrained \u2014 everything allowed",
    )

/**
 * Rich config section for the Termux plugin.
 *
 * Shows connection status, auto-connect controls, permission tier selector,
 * terminal log toggle, and pre-approved patterns editor.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermuxPluginConfigSection(
    settings: AppSettings,
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val autoConnector = remember {
        val app = context.applicationContext as com.zeroclaw.android.ZeroClawApplication
        TermuxAutoConnector(
            context = context,
            probe = app.termuxRuntimeProbe,
            supervisor = app.termuxBridgeSupervisor,
            healthClient = app.termuxHealthClient,
        )
    }

    var connectionState by remember { mutableStateOf<TermuxAutoConnector.ConnectionState>(
        TermuxAutoConnector.ConnectionState.Checking
    ) }
    var isAutoConnecting by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        autoConnector.observeConnection().collect { connectionState = it }
    }

    Column(modifier = modifier) {
        TermuxConnectionStatus(state = connectionState)
        Spacer(modifier = Modifier.height(12.dp))

        when (connectionState) {
            is TermuxAutoConnector.ConnectionState.NotInstalled -> {
                FilledTonalButton(
                    colors = zeroAssistSecondaryActionButtonColors(),
                    onClick = { autoConnector.openFroidInstall() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Install Termux from F-Droid")
                }
            }
            is TermuxAutoConnector.ConnectionState.PermissionNeeded -> {
                FilledTonalButton(
                    colors = zeroAssistSecondaryActionButtonColors(),
                    onClick = { autoConnector.openPermissionSettings() },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Grant RUN_COMMAND Permission")
                }
            }
            is TermuxAutoConnector.ConnectionState.Connected -> {
                Text(
                    text = "Termux bridge is connected and ready.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            is TermuxAutoConnector.ConnectionState.Failed -> {
                FilledTonalButton(
                    colors = zeroAssistSecondaryActionButtonColors(),
                    onClick = {
                        isAutoConnecting = true
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Retry Connection")
                }
            }
            else -> {
                FilledTonalButton(
                    colors = zeroAssistSecondaryActionButtonColors(),
                    onClick = { isAutoConnecting = true },
                    enabled = !isAutoConnecting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (isAutoConnecting) "Connecting\u2026" else "Auto-Connect")
                }
            }
        }

        if (isAutoConnecting) {
            LaunchedEffect(isAutoConnecting) {
                autoConnector.startAutoConnect().collect { state ->
                    connectionState = state
                    if (state is TermuxAutoConnector.ConnectionState.Connected ||
                        state is TermuxAutoConnector.ConnectionState.Failed ||
                        state is TermuxAutoConnector.ConnectionState.NotInstalled ||
                        state is TermuxAutoConnector.ConnectionState.PermissionNeeded
                    ) {
                        isAutoConnecting = false
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Permission tier selector
        var tierExpanded by remember { mutableStateOf(false) }
        ExposedDropdownMenuBox(
            expanded = tierExpanded,
            onExpandedChange = { tierExpanded = it },
        ) {
            OutlinedTextField(
                colors = zeroAssistOutlinedTextFieldColors(),
                value = settings.termuxPermissionTier,
                onValueChange = {},
                readOnly = true,
                label = { Text("Permission tier") },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(tierExpanded) },
                modifier =
                    Modifier
                        .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                        .fillMaxWidth(),
            )
            ExposedDropdownMenu(
                expanded = tierExpanded,
                onDismissRequest = { tierExpanded = false },
            ) {
                for ((value, description) in TIER_OPTIONS) {
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(value)
                                Text(
                                    text = description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        onClick = {
                            viewModel.updateTermuxPermissionTier(value)
                            tierExpanded = false
                        },
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Terminal log toggle
        com.zeroclaw.android.ui.component.SettingsToggleRow(
            title = "Show terminal log",
            subtitle = "Display command history in the plugin detail screen",
            checked = settings.termuxShowTerminalLog,
            onCheckedChange = { viewModel.updateTermuxShowTerminalLog(it) },
            contentDescription = "Toggle terminal log visibility",
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Pre-approved patterns
        OutlinedTextField(
            colors = zeroAssistOutlinedTextFieldColors(),
            value = settings.termuxPreApprovedPatterns,
            onValueChange = { viewModel.updateTermuxPreApprovedPatterns(it) },
            label = { Text("Pre-approved patterns") },
            supportingText = {
                Text("Comma-separated patterns (e.g. \"git *,npm install *\")")
            },
            minLines = 2,
            modifier = Modifier.fillMaxWidth(),
        )

        if (settings.termuxShowTerminalLog) {
            Spacer(modifier = Modifier.height(16.dp))
            val app = context.applicationContext as com.zeroclaw.android.ZeroClawApplication
            val recentRecords by app.termuxAuditRepository.observeRecentCommands(50)
                .collectAsStateWithLifecycle(initialValue = emptyList())
            TermuxTerminalView(records = recentRecords)
        }
    }
}
