// Copyright 2026 Zero-Assist Community, MIT License

@file:Suppress("MatchingDeclarationName")

package com.zeroclaw.android.ui.screen.dashboard

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.widget.Toast

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zeroclaw.android.ZeroClawApplication
import com.zeroclaw.android.backup.SyncStatus
import com.zeroclaw.android.model.ActivityEvent
import com.zeroclaw.android.model.CostSummary
import com.zeroclaw.android.model.DaemonStatus
import com.zeroclaw.android.model.KeyRejectionEvent
import com.zeroclaw.android.model.LiveActivityItem
import com.zeroclaw.android.model.MemoryConflict
import com.zeroclaw.android.model.ServiceState
import com.zeroclaw.android.ui.component.AccentEdgeCard
import com.zeroclaw.android.ui.component.DashboardCard
import com.zeroclaw.android.ui.component.LoadingIndicator
import com.zeroclaw.android.ui.component.RestartRequiredBanner
import com.zeroclaw.android.ui.component.SectionHeader
import com.zeroclaw.android.ui.component.ZeroAssistScreen
import com.zeroclaw.android.ui.component.premiumPulse
import com.zeroclaw.android.ui.component.premiumFadeInUp
import com.zeroclaw.android.ui.theme.AppSuccess
import com.zeroclaw.android.ui.theme.AppWarning
import com.zeroclaw.android.ui.theme.ZeroAssistSpacing
import com.zeroclaw.android.ui.theme.JetBrainsMonoFamily
import com.zeroclaw.android.util.BatteryOptimization
import com.zeroclaw.android.viewmodel.DaemonUiState
import com.zeroclaw.android.viewmodel.DaemonViewModel

import kotlinx.coroutines.launch

/**
 * Aggregated state for the dashboard content composable.
 *
 * @property serviceState Current daemon service lifecycle state.
 * @property statusState Daemon status with loading/error variants.
 * @property keyRejection Latest API key rejection event, if any.
 * @property costSummary Accumulated cost summary, if available.
 * @property enabledAgentCount Number of enabled agent connections.
 * @property installedPluginCount Number of installed plugins.
 * @property daemonStatus Latest daemon status snapshot, if available.
 * @property activityEvents Recent activity feed events.
 * @property memoryHealthWarning Warning from failed memory health check, if any.
 * @property restartRequired Whether configuration changes require a daemon restart.
 * @property estopEngaged Whether the emergency stop is currently active.
 * @property syncStatus Current Google Drive backup sync state.
 * @property isSyncAvailable Whether an authenticated Drive account can receive syncs.
 * @property showWelcome Whether the daily welcome should be shown.
 * @property welcomeText Greeting text for the welcome message.
 */
@Immutable
data class DashboardState(
    val serviceState: ServiceState,
    val statusState: DaemonUiState<DaemonStatus>,
    val keyRejection: KeyRejectionEvent?,
    val costSummary: CostSummary?,
    val enabledAgentCount: Int,
    val installedPluginCount: Int,
    val daemonStatus: DaemonStatus?,
    val activityEvents: List<ActivityEvent>,
    val liveActivities: List<LiveActivityItem>,
    val memoryHealthWarning: String? = null,
    val restartRequired: Boolean = false,
    val estopEngaged: Boolean = false,
    val syncStatus: SyncStatus = SyncStatus.IDLE,
    val isSyncAvailable: Boolean = false,
    val showWelcome: Boolean = false,
    val welcomeText: String = "",
    val webEnabled: Boolean = false,
    val webToggling: Boolean = false,
    val localIpAddress: String? = null,
    val gatewayPort: Int = 42617,
)

/**
 * Dashboard home screen displaying daemon status, component health,
 * cost summary, metrics, and an activity feed.
 *
 * Thin stateful wrapper that collects ViewModel flows and delegates
 * rendering to [DashboardContent].
 *
 * @param edgeMargin Horizontal padding based on window width size class.
 * @param onNavigateToCostDetail Callback to navigate to the cost detail screen.
 * @param restartRequired Whether the daemon needs a restart to apply pending changes.
 * @param onRestartDaemon Callback invoked when the user taps the restart action.
 * @param viewModel The [DaemonViewModel] for daemon state and actions.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
fun DashboardScreen(
    edgeMargin: Dp,
    onNavigateToCostDetail: () -> Unit = {},
    onNavigateToHub: (Int) -> Unit = {},
    restartRequired: Boolean = false,
    onRestartDaemon: () -> Unit = {},
    viewModel: DaemonViewModel = viewModel(),
    welcomeViewModel: WelcomeViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val serviceState by viewModel.serviceState.collectAsStateWithLifecycle()
    val statusState by viewModel.statusState.collectAsStateWithLifecycle()
    val keyRejection by viewModel.keyRejectionEvent.collectAsStateWithLifecycle()
    val costSummary by viewModel.costSummary.collectAsStateWithLifecycle()
    val enabledAgentCount by viewModel.enabledAgentCount.collectAsStateWithLifecycle()
    val installedPluginCount by viewModel.installedPluginCount.collectAsStateWithLifecycle()
    val daemonStatus by viewModel.daemonStatus.collectAsStateWithLifecycle()
    val activityEvents by viewModel.activityEvents.collectAsStateWithLifecycle()
    val liveActivities by viewModel.liveActivities.collectAsStateWithLifecycle()
    val memoryConflict by viewModel.memoryConflict.collectAsStateWithLifecycle()
    val memoryHealthWarning by viewModel.memoryHealthWarning.collectAsStateWithLifecycle()
    val showWelcome by welcomeViewModel.shouldShowWelcome.collectAsStateWithLifecycle()
    val welcomeText by welcomeViewModel.greetingText.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val app = context.applicationContext as ZeroClawApplication
    val estopInitialized by app.estopRepository.isInitialized.collectAsStateWithLifecycle()
    val estopEngaged by app.estopRepository.engaged.collectAsStateWithLifecycle()
    val syncStatus by app.syncRepository.syncStatus.collectAsStateWithLifecycle()
    val webEnabled by viewModel.webEnabled.collectAsStateWithLifecycle()
    val webToggling by viewModel.webToggling.collectAsStateWithLifecycle()
    val localIpAddress by viewModel.localIpAddress.collectAsStateWithLifecycle()
    val gatewayPort by viewModel.gatewayPort.collectAsStateWithLifecycle()
    var isSyncAvailable by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(app, syncStatus) {
        isSyncAvailable = app.syncRepository.reconcileSignedInProfile() != null
    }

    LaunchedEffect(estopInitialized) {
        if (!estopInitialized) {
            app.estopRepository.refreshNow()
        }
    }

    val conflictData = memoryConflict
    if (conflictData is MemoryConflict.StaleData) {
        MemoryConflictDialog(
            conflict = conflictData,
            onDelete = { viewModel.resolveMemoryConflict(shouldDelete = true) },
            onKeep = { viewModel.resolveMemoryConflict(shouldDelete = false) },
        )
    }

    DashboardContent(
        state =
            DashboardState(
                serviceState = serviceState,
                statusState = statusState,
                keyRejection = keyRejection,
                costSummary = costSummary,
                enabledAgentCount = enabledAgentCount,
                installedPluginCount = installedPluginCount,
                daemonStatus = daemonStatus,
                activityEvents = activityEvents,
                liveActivities = liveActivities,
                memoryHealthWarning = memoryHealthWarning,
                restartRequired = restartRequired,
                estopEngaged = estopEngaged,
                syncStatus = syncStatus,
                isSyncAvailable = isSyncAvailable,
                showWelcome = showWelcome,
                welcomeText = welcomeText,
                webEnabled = webEnabled ?: false,
                webToggling = webToggling,
                localIpAddress = localIpAddress,
                gatewayPort = gatewayPort,
            ),
        edgeMargin = edgeMargin,
        onNavigateToCostDetail = onNavigateToCostDetail,
        onNavigateToHub = onNavigateToHub,
        onStartDaemon = viewModel::requestStart,
        onStopDaemon = viewModel::requestStop,
        onRestartDaemon = onRestartDaemon,
        onDismissKeyRejection = viewModel::dismissKeyRejection,
        onDismissMemoryHealthWarning = viewModel::dismissMemoryHealthWarning,
        onEngageEstop = { coroutineScope.launch { app.estopRepository.engage() } },
        onResumeEstop = { coroutineScope.launch { app.estopRepository.resume() } },
        onSyncEverything = {
            coroutineScope.launch {
                app.syncRepository.markPendingSync()
                app.syncRepository.uploadToDrive()
            }
        },
        onToggleWeb = viewModel::toggleWebServer,
        onOpenExternal = {
            val port = gatewayPort
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("http://127.0.0.1:$port/"))
            context.startActivity(intent)
        },
        modifier = modifier,
    )
}

/**
 * Stateless dashboard content composable for testing.
 *
 * Receives all state and callbacks as parameters, rendering the full
 * dashboard layout without any ViewModel dependency.
 *
 * @param state Aggregated dashboard state snapshot.
 * @param edgeMargin Horizontal padding based on window width size class.
 * @param onNavigateToCostDetail Callback to navigate to cost detail.
 * @param onStartDaemon Callback to start the daemon.
 * @param onStopDaemon Callback to stop the daemon.
 * @param onRestartDaemon Callback to restart the daemon after settings changes.
 * @param onDismissKeyRejection Callback to dismiss the key rejection banner.
 * @param onDismissMemoryHealthWarning Callback to dismiss the memory health warning.
 * @param onEngageEstop Callback to engage the emergency stop.
 * @param onResumeEstop Callback to resume from the emergency stop.
 * @param onSyncEverything Callback to upload all backupable state to Drive.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
internal fun DashboardContent(
    state: DashboardState,
    edgeMargin: Dp,
    onNavigateToCostDetail: () -> Unit,
    onNavigateToHub: (Int) -> Unit = {},
    onStartDaemon: () -> Unit,
    onStopDaemon: () -> Unit,
    onRestartDaemon: () -> Unit = {},
    onDismissKeyRejection: () -> Unit,
    onDismissMemoryHealthWarning: () -> Unit = {},
    onEngageEstop: () -> Unit = {},
    onResumeEstop: () -> Unit = {},
    onSyncEverything: () -> Unit = {},
    onToggleWeb: () -> Unit = {},
    onOpenExternal: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val oemType = remember { BatteryOptimization.detectAggressiveOem() }
    val isExempt = remember { BatteryOptimization.isExempt(context) }
    var bannerDismissed by remember { mutableStateOf(false) }

    ZeroAssistScreen(
        edgeMargin = edgeMargin,
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(ZeroAssistSpacing.Large),
    ) {
        if (oemType != null && !isExempt && !bannerDismissed) {
            item(key = "battery-optimization") {
                BatteryOptimizationBanner(
                    oemType = oemType,
                    onDismiss = { bannerDismissed = true },
                    onLearnMore = {
                        val url = BatteryOptimization.getOemInstructionsUrl(oemType)
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse(url)),
                        )
                    },
                )
            }
        }

        if (state.keyRejection != null) {
            item(key = "key-rejection") {
                KeyRejectionBanner(onDismiss = onDismissKeyRejection)
            }
        }

        if (state.memoryHealthWarning != null) {
            item(key = "memory-health-warning") {
                MemoryHealthWarningBanner(
                    warning = state.memoryHealthWarning,
                    onDismiss = onDismissMemoryHealthWarning,
                )
            }
        }

        if (state.restartRequired) {
            item(key = "restart-required") {
                RestartRequiredBanner(
                    edgeMargin = 0.dp,
                    onRestartDaemon = onRestartDaemon,
                )
            }
        }

        if (state.showWelcome) {
            item(key = "welcome") {
                DashboardWelcomeMessage(
                    visible = true,
                    greetingText = state.welcomeText,
                )
            }
        }

        item(key = "status-hero") {
            DaemonStatusCard(
                serviceState = state.serviceState,
                errorMessage = (state.statusState as? DaemonUiState.Error)?.detail,
                daemonStatus = state.daemonStatus,
                userName = extractUserName(state.welcomeText),
                onStart = onStartDaemon,
                onStop = onStopDaemon,
            )
        }

        if (state.serviceState == ServiceState.RUNNING) {
            item(key = "web-dashboard") {
                WebDashboardCard(
                    webEnabled = state.webEnabled,
                    webToggling = state.webToggling,
                    localIpAddress = state.localIpAddress,
                    gatewayPort = state.gatewayPort,
                    onToggle = onToggleWeb,
                    onOpenExternal = onOpenExternal,
                )
            }
        }

        item(key = "sync-everything") {
            SyncEverythingButton(
                syncStatus = state.syncStatus,
                isAvailable = state.isSyncAvailable,
                onClick = onSyncEverything,
            )
        }

        if (state.serviceState == ServiceState.RUNNING) {
            item(key = "live-activity") {
                LiveActivitySection(items = state.liveActivities)
            }
        }

        item(key = "metrics-header") {
            SectionHeader(title = "At a Glance")
        }
        item(key = "metric-cards") {
            KeyMetricsStrip(
                enabledAgentCount = state.enabledAgentCount,
                installedPluginCount = state.installedPluginCount,
                daemonStatus = state.daemonStatus,
                serviceState = state.serviceState,
                onNavigateToHub = onNavigateToHub,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Shared dashboard design primitives — now in ui/component/DashboardCard.kt
// ---------------------------------------------------------------------------

/** Corner radius shared by every card on the dashboard. */
private val DashboardCardShape = RoundedCornerShape(10.dp)

/** Hairline border used on every dashboard card in place of elevation shadows. */
@Composable
private fun dashboardCardBorder(): BorderStroke =
    BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

/** Muted divider color used between rows and columns within a card. */
@Composable
private fun dividerColor(): Color =
    MaterialTheme.colorScheme.outline.copy(alpha = 0.13f)

/** Small solid status indicator dot. */
@Composable
private fun StatusDot(
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = STATUS_DOT_SIZE.dp,
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(color),
    )
}

/** Small uppercase, letter-spaced label used to title a card or a stat. */
@Composable
private fun CardEyebrow(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.1.sp),
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
        fontWeight = FontWeight.SemiBold,
        modifier = modifier,
    )
}

/** One "label: value" line of the terminal-style system readout, in monospace. */
@Composable
private fun TerminalStat(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Medium,
        )
    }
}

/**
 * Daemon status card.
 *
 * Structured as a real status panel rather than a decorative hero: a
 * status row (dot + label + primary action) up top, then a compact
 * "user@zero-assist" system readout below a hairline divider. No glow,
 * no animated rings — status is communicated by a single dot and a word.
 *
 * @param serviceState Current lifecycle state of the daemon.
 * @param errorMessage Optional error detail to display when in [ServiceState.ERROR].
 * @param daemonStatus Latest daemon status snapshot for uptime display.
 * @param userName Extracted user name for the system-readout host line.
 * @param onStart Callback invoked when the user taps Start.
 * @param onStop Callback invoked when the user taps Stop.
 */
@Composable
private fun DaemonStatusCard(
    serviceState: ServiceState,
    errorMessage: String?,
    daemonStatus: DaemonStatus?,
    userName: String,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val isTransitioning =
        serviceState == ServiceState.STARTING || serviceState == ServiceState.STOPPING
    val isRunning = serviceState == ServiceState.RUNNING

    val statusLabel = when (serviceState) {
        ServiceState.RUNNING -> "Healthy"
        ServiceState.STOPPED -> "Stopped"
        ServiceState.STARTING -> "Starting\u2026"
        ServiceState.STOPPING -> "Stopping\u2026"
        ServiceState.ERROR -> "Error"
    }
    val statusColor = when (serviceState) {
        ServiceState.RUNNING -> AppSuccess
        ServiceState.ERROR -> MaterialTheme.colorScheme.error
        ServiceState.STARTING, ServiceState.STOPPING -> MaterialTheme.colorScheme.tertiary
        ServiceState.STOPPED -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val uptimeText = formatUptime(daemonStatus, serviceState)
    val rawName = userName.trim().ifBlank { "daemon" }.lowercase().replace(" ", "")

    DashboardCard(
        modifier = Modifier.semantics {
            liveRegion = LiveRegionMode.Polite
            contentDescription = serviceStateDescription(serviceState)
        },
    ) {
        Column(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        CardEyebrow("Daemon")
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(
                                color = statusColor,
                                size = 10.dp,
                                modifier = if (isRunning) Modifier.premiumPulse() else Modifier,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = statusLabel,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }

                    if (isRunning) {
                        TextButton(
                            onClick = onStop,
                            enabled = !isTransitioning,
                            modifier = Modifier
                                .defaultMinSize(minHeight = 32.dp)
                                .semantics { contentDescription = "Stop daemon" },
                        ) {
                            Text(
                                text = "Stop",
                                color = MaterialTheme.colorScheme.error,
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    } else {
                        OutlinedButton(
                            onClick = onStart,
                            enabled = !isTransitioning,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.primary.copy(
                                    alpha = if (isTransitioning) 0.35f else 0.6f,
                                ),
                            ),
                            modifier = Modifier
                                .defaultMinSize(minHeight = 32.dp)
                                .semantics { contentDescription = "Start daemon" },
                        ) {
                            if (isTransitioning) {
                                LoadingIndicator()
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                text = if (isTransitioning) "Starting" else "Start",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }

                if (serviceState == ServiceState.ERROR && errorMessage != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = errorMessage,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
                    )
                }

                Spacer(Modifier.height(16.dp))
                HorizontalDivider(color = dividerColor(), thickness = 0.5.dp)
                Spacer(Modifier.height(12.dp))

                // Terminal-style system readout inside a subtle tinted box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.05f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Column {
                        Text(
                            text = "$rawName@zero-assist",
                            style = MaterialTheme.typography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                            TerminalStat(
                                label = "host",
                                value = Build.MODEL,
                                modifier = Modifier.weight(1f),
                            )
                            TerminalStat(
                                label = "uptime",
                                value = uptimeText,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Web dashboard toggle card. Shows the web server state and provides
 * a toggle to start/stop it, plus "Open in Browser" and "Open on Local Device"
 * buttons when active.
 */
@Composable
private fun WebDashboardCard(
    webEnabled: Boolean,
    webToggling: Boolean,
    localIpAddress: String?,
    gatewayPort: Int,
    onToggle: () -> Unit,
    onOpenExternal: () -> Unit,
) {
    val context = LocalContext.current
    val statusColor = when {
        webToggling -> MaterialTheme.colorScheme.tertiary
        webEnabled -> AppSuccess
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    val statusLabel = when {
        webToggling -> "Toggling\u2026"
        webEnabled -> "Active"
        else -> "Inactive"
    }

    val lanUrl = localIpAddress?.let { "http://$it:$gatewayPort" }
    var showLocalDialog by remember { mutableStateOf(false) }

    if (showLocalDialog && lanUrl != null) {
        AlertDialog(
            onDismissRequest = { showLocalDialog = false },
            title = { Text("Open on Local Device") },
            text = {
                Column {
                    Text(
                        text = "Other devices on the same WiFi can open:",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = lanUrl,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Make sure both devices are on the same network.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("dashboard-url", lanUrl))
                    Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    showLocalDialog = false
                }) {
                    Text("Copy")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, lanUrl)
                        }
                        context.startActivity(Intent.createChooser(shareIntent, "Share link"))
                        showLocalDialog = false
                    }) {
                        Text("Share")
                    }
                    TextButton(onClick = { showLocalDialog = false }) {
                        Text("Close")
                    }
                }
            },
        )
    }

    DashboardCard {
        Column(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        CardEyebrow("Web Dashboard")
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(
                                color = statusColor,
                                size = 10.dp,
                            )
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = statusLabel,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }

                    if (webEnabled && !webToggling) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = onOpenExternal,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                ),
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 32.dp)
                                    .semantics { contentDescription = "Open web dashboard in browser" },
                            ) {
                                Text(
                                    text = "Browser",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                            OutlinedButton(
                                onClick = { showLocalDialog = true },
                                enabled = localIpAddress != null,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(
                                    width = 1.dp,
                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                                ),
                                modifier = Modifier
                                    .defaultMinSize(minHeight = 32.dp)
                                    .semantics { contentDescription = "Show local device link" },
                            ) {
                                Text(
                                    text = "Local Link",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                            }
                        }
                    } else {
                        OutlinedButton(
                            onClick = onToggle,
                            enabled = !webToggling,
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.primary.copy(
                                    alpha = if (webToggling) 0.35f else 0.6f,
                                ),
                            ),
                            modifier = Modifier
                                .defaultMinSize(minHeight = 32.dp)
                                .semantics { contentDescription = if (webEnabled) "Stop web dashboard" else "Start web dashboard" },
                        ) {
                            if (webToggling) {
                                LoadingIndicator()
                                Spacer(Modifier.width(6.dp))
                            }
                            Text(
                                text = if (webToggling) "Toggling" else if (webEnabled) "Stop" else "Start",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Text(
                    text = "LAN accessible on port $gatewayPort",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Full-width "Sync all" action. Flat, bordered, matches the daemon card's
 * Start button treatment rather than a filled pill.
 */
@Composable
private fun SyncEverythingButton(
    syncStatus: SyncStatus,
    isAvailable: Boolean,
    onClick: () -> Unit,
) {
    val isSyncing = syncStatus == SyncStatus.SYNCING
    val alpha = if (isAvailable) 0.45f else 0.18f

    OutlinedButton(
        onClick = onClick,
        enabled = isAvailable && !isSyncing,
        shape = DashboardCardShape,
        border = BorderStroke(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outline.copy(alpha = alpha),
        ),
        modifier = Modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 44.dp)
            .semantics { contentDescription = "Sync everything" },
    ) {
        Icon(
            imageVector = Icons.Outlined.Sync,
            contentDescription = null,
            modifier = Modifier.size(15.dp),
            tint = if (isAvailable)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = if (isSyncing) "Syncing\u2026" else "Sync all",
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            color = if (isAvailable)
                MaterialTheme.colorScheme.onSurface
            else
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
        )
    }
}

/**
 * Dismissible banner warning about aggressive OEM battery management.
 *
 * @param oemType Detected OEM battery management type.
 * @param onDismiss Callback when the user dismisses the banner.
 * @param onLearnMore Callback when the user taps "Learn more".
 */
@Composable
private fun BatteryOptimizationBanner(
    oemType: BatteryOptimization.OemBatteryType,
    onDismiss: () -> Unit,
    onLearnMore: () -> Unit,
) {
    val oemName =
        when (oemType) {
            BatteryOptimization.OemBatteryType.XIAOMI -> "Xiaomi"
            BatteryOptimization.OemBatteryType.SAMSUNG -> "Samsung"
            BatteryOptimization.OemBatteryType.HUAWEI -> "Huawei"
            BatteryOptimization.OemBatteryType.ONEPLUS -> "OnePlus"
            BatteryOptimization.OemBatteryType.OPPO -> "Oppo"
            BatteryOptimization.OemBatteryType.VIVO -> "Vivo"
        }

    AccentEdgeCard(accentColor = AppWarning) {
        CardEyebrow("Battery optimization")
        Spacer(Modifier.height(6.dp))
        Text(
            text = "$oemName devices may stop the daemon in the background. " +
                "Disable battery optimization for reliable operation.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TextButton(onClick = onLearnMore) {
                Text("Learn more", fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = onDismiss) {
                Text("Dismiss", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/**
 * Dismissible banner shown when an API key rejection has been detected.
 *
 * @param onDismiss Callback when the user dismisses the banner.
 */
@Composable
private fun KeyRejectionBanner(onDismiss: () -> Unit) {
    AccentEdgeCard(accentColor = MaterialTheme.colorScheme.error) {
        CardEyebrow("API key")
        Spacer(Modifier.height(6.dp))
        Text(
            text = "An API key may be invalid or expired. Check Settings \u203A API Keys.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
            Text("Dismiss")
        }
    }
}

/**
 * Persistent warning banner for a failed memory health check.
 *
 * @param warning Human-readable failure reason.
 * @param onDismiss Callback to dismiss the banner.
 */
@Composable
private fun MemoryHealthWarningBanner(
    warning: String,
    onDismiss: () -> Unit,
) {
    AccentEdgeCard(accentColor = MaterialTheme.colorScheme.error) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                CardEyebrow("Memory")
                Spacer(Modifier.height(4.dp))
                Text(
                    text = warning,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    }
}

/** Number of seconds in one minute, used for uptime formatting. */
private const val SECONDS_PER_MINUTE = 60

/** Number of seconds in one hour, used for uptime formatting. */
private const val SECONDS_PER_HOUR = 3600L

/**
 * Formats daemon uptime into a human-readable string.
 *
 * When the daemon is running and a status snapshot is available, formats the
 * [DaemonStatus.uptimeSeconds] as "Xh Ym" (e.g. "2h 15m") or "Xm" for
 * durations under one hour. Returns "Offline" when the daemon is not running
 * or no status has been received.
 *
 * @param status Latest daemon health snapshot, or null if unavailable.
 * @param serviceState Current service lifecycle state.
 * @return Formatted uptime string.
 */
private fun formatUptime(
    status: DaemonStatus?,
    serviceState: ServiceState,
): String {
    if (serviceState != ServiceState.RUNNING || status == null) {
        return "Offline"
    }
    val totalSeconds = status.uptimeSeconds
    val hours = totalSeconds / SECONDS_PER_HOUR
    val minutes = (totalSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    return if (hours > 0) {
        "${hours}h ${minutes}m"
    } else {
        "${minutes}m"
    }
}

private fun serviceStateDescription(state: ServiceState): String =
    when (state) {
        ServiceState.STOPPED -> "The daemon is not running."
        ServiceState.STARTING -> "The daemon is starting up\u2026"
        ServiceState.RUNNING -> "The daemon is running and healthy."
        ServiceState.STOPPING -> "The daemon is shutting down\u2026"
        ServiceState.ERROR -> "The daemon encountered an error."
    }

/** Default diameter, in dp, of a [StatusDot]. */
private const val STATUS_DOT_SIZE = 8

/**
 * Compact key-metrics strip: three columns in a single flat card, separated
 * by hairline dividers instead of four separate floating cards. Connections
 * and Plugins are tappable shortcuts into the Hub (marked with a small
 * chevron); Uptime is read-only.
 *
 * @param enabledAgentCount Number of enabled agent connections.
 * @param installedPluginCount Number of installed plugins.
 * @param daemonStatus Latest daemon status snapshot, or null if unavailable.
 * @param serviceState Current service lifecycle state; used to determine
 *   whether to show uptime or "Offline".
 * @param onNavigateToHub Callback invoked with a hub tab index when a
 *   tappable column is selected.
 */
@Composable
private fun KeyMetricsStrip(
    enabledAgentCount: Int,
    installedPluginCount: Int,
    daemonStatus: DaemonStatus?,
    serviceState: ServiceState,
    onNavigateToHub: (Int) -> Unit,
) {
    val uptimeText = formatUptime(daemonStatus, serviceState)

    DashboardCard {
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                StatColumn(
                    label = "Connections",
                    icon = Icons.Outlined.Hub,
                    value = enabledAgentCount.toString(),
                    caption = "enabled",
                    navigable = true,
                    onClick = { onNavigateToHub(1) },
                    modifier = Modifier.weight(1f),
                )
                StatDivider()
                StatColumn(
                    label = "Plugins",
                    icon = Icons.Outlined.Extension,
                    value = installedPluginCount.toString(),
                    caption = "installed",
                    navigable = true,
                    onClick = { onNavigateToHub(2) },
                    modifier = Modifier.weight(1f),
                )
                StatDivider()
                StatColumn(
                    label = "Uptime",
                    icon = Icons.Outlined.Schedule,
                    value = uptimeText,
                    caption = if (serviceState == ServiceState.RUNNING) "running" else "",
                    navigable = false,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Hairline vertical divider between [StatColumn]s. */
@Composable
private fun StatDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .fillMaxHeight()
            .background(dividerColor()),
    )
}

/**
 * One column of [KeyMetricsStrip]: an eyebrow label (with an optional
 * chevron hinting that it is tappable), a bold monospace value, and an
 * optional caption below.
 *
 * @param label Short heading displayed above the value (e.g. "Plugins").
 * @param icon Leading Material outline icon for the column.
 * @param value The primary metric value displayed prominently (e.g. "13").
 * @param caption Optional secondary text below the value (e.g. "installed").
 * @param navigable Whether this column navigates elsewhere when tapped.
 * @param modifier Modifier applied to the root column.
 * @param onClick Callback invoked when a navigable column is tapped.
 */
@Composable
private fun StatColumn(
    label: String,
    icon: ImageVector,
    value: String,
    caption: String,
    navigable: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    Column(
        modifier =
            modifier
                .let { if (navigable) it.clickable(onClick = onClick) else it }
                .padding(horizontal = 10.dp, vertical = 16.dp),
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CardEyebrow(label)
            if (navigable) {
                Spacer(Modifier.width(2.dp))
                Text(
                    text = "\u203A",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            text = value,
            style =
                MaterialTheme.typography.headlineSmall.copy(
                    fontFamily = JetBrainsMonoFamily,
                    fontWeight = FontWeight.Bold,
                ),
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        if (caption.isNotEmpty()) {
            Spacer(Modifier.height(2.dp))
            Text(
                text = caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
            )
        }
    }
}

/**
 * Blocking dialog shown when stale memory backend data is found at daemon startup.
 *
 * @param conflict The stale data descriptor.
 * @param onDelete Callback when user chooses to delete stale files.
 * @param onKeep Callback when user chooses to keep stale files.
 */
@Composable
private fun MemoryConflictDialog(
    conflict: MemoryConflict.StaleData,
    onDelete: () -> Unit,
    onKeep: () -> Unit,
) {
    val sizeText = formatFileSize(conflict.staleSizeBytes)
    val plural = if (conflict.staleFileCount != 1) "s" else ""
    AlertDialog(
        onDismissRequest = { /* non-dismissable — user must choose */ },
        title = { Text("Memory Backend Changed") },
        text = {
            Text(
                "You switched from ${conflict.staleBackend} to " +
                    "${conflict.currentBackend}. Found " +
                    "${conflict.staleFileCount} stale ${conflict.staleBackend} " +
                    "file$plural ($sizeText).\n\n" +
                    "Delete old data to prevent conflicts?",
            )
        },
        confirmButton = {
            FilledTonalButton(onClick = onDelete) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onKeep) {
                Text("Keep")
            }
        },
    )
}

/**
 * Formats a byte count as a human-readable file size.
 *
 * @param bytes Size in bytes.
 * @return Formatted string (e.g. "2.4 MB", "128 KB").
 */
private fun formatFileSize(bytes: Long): String {
    val kb = BYTES_PER_KB
    val mb = kb * BYTES_PER_KB
    return when {
        bytes >= mb -> "%.1f MB".format(bytes.toFloat() / mb)
        bytes >= kb -> "%.0f KB".format(bytes.toFloat() / kb)
        else -> "$bytes B"
    }
}

/** Number of bytes per kilobyte. */
private const val BYTES_PER_KB = 1024L