/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Subject
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Fingerprint
import androidx.compose.material.icons.outlined.Forum
import androidx.compose.material.icons.outlined.GppGood
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Route
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material.icons.outlined.Sensors
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.SystemUpdate
import com.zeroclaw.android.backup.BackupViewModel
import com.zeroclaw.android.ui.backup.BackupSettingsSection
import androidx.compose.material.icons.outlined.TaskAlt
import androidx.compose.material.icons.outlined.VerifiedUser
import androidx.compose.material.icons.outlined.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zeroclaw.android.model.AppSettings
import com.zeroclaw.android.model.ThemeMode
import com.zeroclaw.android.navigation.SettingsNavAction
import com.zeroclaw.android.ui.component.RestartRequiredBanner
import com.zeroclaw.android.ui.component.SectionHeader
import com.zeroclaw.android.ui.component.SettingsListItem

/**
 * Root settings screen displaying a sectioned list of configuration options.
 *
 * Thin stateful wrapper that collects ViewModel flows and delegates
 * rendering to [SettingsContent].
 *
 * @param onNavigate Callback invoked with a [SettingsNavAction] when the user taps a setting.
 * @param onRerunWizard Callback to reset onboarding and navigate to the setup wizard.
 * @param edgeMargin Horizontal padding based on window width size class.
 * @param settingsViewModel ViewModel providing current settings for dynamic subtitles.
 * @param restartRequired Whether the daemon needs a restart to apply settings changes.
 * @param onRestartDaemon Callback invoked when the user taps the restart button.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
fun SettingsScreen(
    backupViewModel: BackupViewModel,
    onNavigate: (SettingsNavAction) -> Unit,
    onRerunWizard: () -> Unit,
    edgeMargin: Dp,
    settingsViewModel: SettingsViewModel = viewModel(),
    restartRequired: Boolean = false,
    onRestartDaemon: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()

    SettingsContent(
        settings = settings,
        restartRequired = restartRequired,
        edgeMargin = edgeMargin,
        onNavigate = onNavigate,
        onRerunWizard = onRerunWizard,
        onRestartDaemon = onRestartDaemon,
        onThemeSelected = settingsViewModel::updateTheme,
        backupViewModel = backupViewModel,
        modifier = modifier,
    )
}

/**
 * Stateless settings content composable for testing.
 *
 * @param settings Current app settings snapshot.
 * @param restartRequired Whether the daemon needs a restart.
 * @param edgeMargin Horizontal padding based on window width size class.
 * @param onNavigate Callback for settings navigation.
 * @param onRerunWizard Callback to reset onboarding.
 * @param onRestartDaemon Callback to restart the daemon.
 * @param onThemeSelected Callback when a theme is chosen.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
internal fun SettingsContent(
    settings: AppSettings,
    restartRequired: Boolean,
    edgeMargin: Dp,
    onNavigate: (SettingsNavAction) -> Unit,
    onRerunWizard: () -> Unit,
    onRestartDaemon: () -> Unit,
    onThemeSelected: (ThemeMode) -> Unit,
    backupViewModel: BackupViewModel? = null,
    modifier: Modifier = Modifier,
) {
    var showRerunDialog by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }

    var daemonExpanded by rememberSaveable { mutableStateOf(true) }
    var securityExpanded by rememberSaveable { mutableStateOf(false) }
    var networkExpanded by rememberSaveable { mutableStateOf(false) }
    var advancedExpanded by rememberSaveable { mutableStateOf(false) }
    var diagnosticsExpanded by rememberSaveable { mutableStateOf(false) }
    var inspectExpanded by rememberSaveable { mutableStateOf(false) }
    var appExpanded by rememberSaveable { mutableStateOf(false) }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = edgeMargin)
                .verticalScroll(rememberScrollState()),
    ) {
        Spacer(modifier = Modifier.height(8.dp))

        if (restartRequired) {
            RestartRequiredBanner(
                edgeMargin = edgeMargin,
                onRestartDaemon = onRestartDaemon,
            )
        }

        backupViewModel?.let { BackupSettingsSection(viewModel = it) }

        SectionHeader(
            title = "Daemon",
            isExpandable = true,
            isExpanded = daemonExpanded,
            onToggleExpand = { daemonExpanded = !daemonExpanded }
        )
        AnimatedVisibility(visible = daemonExpanded) {
            SettingsGroup {
                SettingsListItem(
                    icon = Icons.Outlined.Settings,
                    title = "Service Configuration",
                    subtitle =
                        "${settings.host}:${settings.port}" +
                            if (settings.autoStartOnBoot) " | auto-start" else "",
                    onClick = { onNavigate(SettingsNavAction.ServiceConfig) },
                )
                SettingsListItem(
                    icon = Icons.Outlined.BatteryAlert,
                    title = "Battery Settings",
                    subtitle = "Optimization exemptions",
                    onClick = { onNavigate(SettingsNavAction.Battery) },
                )
                SettingsListItem(
                    icon = Icons.Outlined.Fingerprint,
                    title = "Agent Identity",
                    subtitle = if (settings.identityJson.isNotBlank()) "Configured" else "Not set",
                    onClick = { onNavigate(SettingsNavAction.Identity) },
                )
            }
        }

        SectionHeader(
            title = "Security",
            isExpandable = true,
            isExpanded = securityExpanded,
            onToggleExpand = { securityExpanded = !securityExpanded }
        )
        AnimatedVisibility(visible = securityExpanded) {
            SettingsGroup {
                SettingsListItem(
                    icon = Icons.Outlined.VerifiedUser,
                    title = "Security Overview",
                    subtitle = "View current security posture",
                    onClick = { onNavigate(SettingsNavAction.SecurityOverview) },
                )
                SettingsListItem(
                    icon = Icons.Outlined.GppGood,
                    title = "Security Advanced",
                    subtitle = "Sandbox, OTP, e-stop, resource limits",
                    onClick = { onNavigate(SettingsNavAction.SecurityAdvanced) },
                )
                SettingsListItem(
                    icon = Icons.Outlined.Key,
                    title = "API Keys",
                    subtitle = "Manage provider credentials",
                    onClick = { onNavigate(SettingsNavAction.ApiKeys) },
                )
                SettingsListItem(
                    icon = Icons.Outlined.AccountCircle,
                    title = "Auth Profiles",
                    subtitle = "OAuth tokens and stored credentials",
                    onClick = { onNavigate(SettingsNavAction.AuthProfiles) },
                )
                SettingsListItem(
                    icon = Icons.Outlined.MusicNote,
                    title = "Spotify",
                    subtitle = "Music account linking",
                    onClick = { onNavigate(SettingsNavAction.SpotifyAccount) },
                )
                SettingsListItem(
                    icon = Icons.Outlined.Security,
                    title = "Autonomy Level",
                    subtitle = settings.autonomyLevel,
                    onClick = { onNavigate(SettingsNavAction.Autonomy) },
                )
                SettingsListItem(
                    icon = Icons.Outlined.Forum,
                    title = "Connected Channels",
                    subtitle = "Telegram, Discord, Slack, and more",
                    onClick = { onNavigate(SettingsNavAction.Channels) },
                )
            }
        }

        SectionHeader(
            title = "Network",
            isExpandable = true,
            isExpanded = networkExpanded,
            onToggleExpand = { networkExpanded = !networkExpanded }
        )
        AnimatedVisibility(visible = networkExpanded) {
            SettingsGroup {
                SettingsListItem(
                    icon = Icons.Outlined.Hub,
                    title = "Gateway & Pairing",
                    subtitle =
                        if (settings.gatewayRequirePairing) "Pairing required" else "Open access",
                    onClick = { onNavigate(SettingsNavAction.Gateway) },
                )
                SettingsListItem(
                    icon = Icons.Outlined.VpnKey,
                    title = "Tunnel",
                    subtitle = settings.tunnelProvider,
                    onClick = { onNavigate(SettingsNavAction.Tunnel) },
                )
                SettingsListItem(
                    icon = Icons.Outlined.Sync,
                    title = "Plugin Registry",
                    subtitle =
                        if (settings.pluginSyncEnabled) "Auto-sync enabled" else "Manual only",
                    onClick = { onNavigate(SettingsNavAction.PluginRegistry) },
                )
            }
        }

        SectionHeader(
            title = "Advanced Configuration",
            isExpandable = true,
            isExpanded = advancedExpanded,
            onToggleExpand = { advancedExpanded = !advancedExpanded }
        )
        AnimatedVisibility(visible = advancedExpanded) {
            SettingsGroup {
                SettingsListItem(
                    icon = Icons.Outlined.Route,
                    title = "Model Routes",
                    subtitle = "Hint-based provider routing",
                    onClick = { onNavigate(SettingsNavAction.ModelRoutes) },
                )
                SettingsListItem(
                    icon = Icons.Outlined.Memory,
                    title = "Memory Advanced",
                    subtitle = "Embedding, hygiene, recall weights, Qdrant",
                    onClick = { onNavigate(SettingsNavAction.MemoryAdvanced) },
                )
                SettingsListItem(
                    icon = Icons.Outlined.Layers,
                    title = "Embedding Routes",
                    subtitle = "Hint-based embedding provider routing",
                    onClick = { onNavigate(SettingsNavAction.EmbeddingRoutes) },
                )
                SettingsListItem(
                    icon = Icons.Outlined.Speed,
                    title = "Observability",
                    subtitle = settings.observabilityBackend,
                    onClick = { onNavigate(SettingsNavAction.Observability) },
                )
                SettingsListItem(
                    icon = Icons.Outlined.Mic,
                    title = "Voice Assistant",
                    subtitle = "Offline speech, voice library, wake-up",
                    onClick = { onNavigate(SettingsNavAction.VoiceAssistant) },
                )
                SettingsListItem(
                    icon = Icons.Outlined.Psychology,
                    title = "On-Device AI Models",
                    subtitle = "Manage Gemma 4 & Qwen3 LiteRT models",
                    onClick = { onNavigate(SettingsNavAction.LiteRTModels) },
                )
                SettingsListItem(
                    icon = Icons.Outlined.VpnKey,
                    title = "Skill Permissions",
                    subtitle = "Placeholder for future Rhai capability grants",
                    onClick = { onNavigate(SettingsNavAction.SkillPermissions) },
                )
            }
        }

        SectionHeader(
            title = "Diagnostics",
            isExpandable = true,
            isExpanded = diagnosticsExpanded,
            onToggleExpand = { diagnosticsExpanded = !diagnosticsExpanded }
        )
        AnimatedVisibility(visible = diagnosticsExpanded) {
            SettingsGroup {
                SettingsListItem(
                    icon = Icons.AutoMirrored.Outlined.Subject,
                    title = "Log Viewer",
                    subtitle = "View daemon and service logs",
                    onClick = { onNavigate(SettingsNavAction.LogViewer) },
                )
                SettingsListItem(
                    icon = Icons.Outlined.HealthAndSafety,
                    title = "Zero-Assist Doctor",
                    subtitle = "Validate config, keys, and connectivity",
                    onClick = { onNavigate(SettingsNavAction.Doctor) },
                )
            }
        }

        SectionHeader(
            title = "Inspect & Browse",
            isExpandable = true,
            isExpanded = inspectExpanded,
            onToggleExpand = { inspectExpanded = !inspectExpanded }
        )
        AnimatedVisibility(visible = inspectExpanded) {
            SettingsGroup {
                SettingsListItem(
                    icon = Icons.Outlined.Psychology,
                    title = "Memory Browser",
                    subtitle = "Browse and search memory entries",
                    onClick = { onNavigate(SettingsNavAction.MemoryBrowser) },
                )
            }
        }

        SectionHeader(
            title = "App",
            isExpandable = true,
            isExpanded = appExpanded,
            onToggleExpand = { appExpanded = !appExpanded }
        )
        AnimatedVisibility(visible = appExpanded) {
            SettingsGroup {
                SettingsListItem(
                    icon = Icons.Outlined.DarkMode,
                    title = "Theme",
                    subtitle =
                        when (settings.theme) {
                            ThemeMode.SYSTEM -> "System default"
                            ThemeMode.LIGHT -> "Light"
                            ThemeMode.DARK -> "Dark"
                        },
                    onClick = { showThemeDialog = true },
                )
                SettingsListItem(
                    icon = Icons.Outlined.Refresh,
                    title = "Re-run Setup Wizard",
                    subtitle = "Walk through initial configuration again",
                    onClick = { showRerunDialog = true },
                )
                SettingsListItem(
                    icon = Icons.Outlined.SystemUpdate,
                    title = "Updates",
                    subtitle = "Check for new versions",
                    onClick = { onNavigate(SettingsNavAction.Updates) },
                )
                SettingsListItem(
                    icon = Icons.Outlined.Info,
                    title = "About",
                    subtitle = "Version, licenses, links",
                    onClick = { onNavigate(SettingsNavAction.About) },
                )
            }
        }

        DeveloperCreditFooter()
        Spacer(modifier = Modifier.height(16.dp))
    }

    if (showThemeDialog) {
        ThemePickerDialog(
            currentTheme = settings.theme,
            onThemeSelected = { theme ->
                onThemeSelected(theme)
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false },
        )
    }

    if (showRerunDialog) {
        RerunWizardDialog(
            onConfirm = {
                showRerunDialog = false
                onRerunWizard()
            },
            onDismiss = { showRerunDialog = false },
        )
    }
}

@Composable
private fun SettingsGroup(content: @Composable () -> Unit) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            content()
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
}

@Composable
private fun DeveloperCreditFooter(modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val bodyStyle = MaterialTheme.typography.bodySmall

    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Built with </> by ",
            style = bodyStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Tanmay",
            style = bodyStyle.copy(textDecoration = TextDecoration.Underline),
            color = MaterialTheme.colorScheme.primary,
            modifier =
                Modifier.clickable(role = Role.Button) {
                    uriHandler.openUri(DEVELOPER_GITHUB_URL)
                },
        )
        Text(
            text = " Sonawane",
            style = bodyStyle,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Dialog for picking the app theme from [ThemeMode] options.
 *
 * Displays three radio-button rows: System, Light, and Dark.
 *
 * @param currentTheme The currently active [ThemeMode].
 * @param onThemeSelected Called with the chosen [ThemeMode] when the user taps an option.
 * @param onDismiss Called when the dialog is dismissed without selection.
 */
@Composable
private fun ThemePickerDialog(
    currentTheme: ThemeMode,
    onThemeSelected: (ThemeMode) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose Theme") },
        text = {
            Column(modifier = Modifier.selectableGroup()) {
                ThemeMode.entries.forEach { mode ->
                    val label =
                        when (mode) {
                            ThemeMode.SYSTEM -> "System default"
                            ThemeMode.LIGHT -> "Light"
                            ThemeMode.DARK -> "Dark"
                        }
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = mode == currentTheme,
                                    onClick = { onThemeSelected(mode) },
                                    role = Role.RadioButton,
                                ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(
                            selected = mode == currentTheme,
                            onClick = null,
                        )
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

/**
 * Confirmation dialog shown before re-running the setup wizard.
 *
 * @param onConfirm Called when the user confirms.
 * @param onDismiss Called when the user cancels.
 */
@Composable
private fun RerunWizardDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Re-run Setup Wizard?") },
        text = {
            Text(
                "This will open the initial setup wizard again. " +
                    "Your agent identity (AIEOS) will be cleared so you can " +
                    "generate a fresh one. API keys and other settings are preserved.",
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text("Continue")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

private const val DEVELOPER_GITHUB_URL = "https://github.com/Tanmay-1122"
