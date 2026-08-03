/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.navigation

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.zeroclaw.android.ZeroClawApplication
import com.zeroclaw.android.model.ServiceState
import com.zeroclaw.android.service.ZeroAssistDaemonService
import com.zeroclaw.android.ui.component.PinEntryMode
import com.zeroclaw.android.ui.component.PinEntrySheet
import com.zeroclaw.android.ui.screen.agents.AddAgentScreen
import com.zeroclaw.android.ui.screen.agents.AgentDetailScreen
import com.zeroclaw.android.ui.screen.agents.AgentGroupChatScreen
import com.zeroclaw.android.ui.screen.agents.AgentsScreen
import com.zeroclaw.android.ui.screen.dashboard.DashboardScreen
import com.zeroclaw.android.ui.screen.onboarding.OnboardingScreen
import com.zeroclaw.android.backup.BackupViewModel
import com.zeroclaw.android.ui.screen.hub.ConnectionsHubScreen
import com.zeroclaw.android.ui.screen.plugins.PluginDetailScreen
import com.zeroclaw.android.ui.screen.plugins.PluginsViewModel
import com.zeroclaw.android.ui.screen.plugins.SkillsViewModel
import com.zeroclaw.android.ui.screen.settings.AboutScreen
import com.zeroclaw.android.ui.screen.settings.AutonomyScreen
import com.zeroclaw.android.ui.screen.settings.BatterySettingsScreen
import com.zeroclaw.android.ui.screen.settings.CostDetailScreen
import com.zeroclaw.android.ui.screen.settings.EmbeddingRoutesScreen
import com.zeroclaw.android.ui.screen.settings.GatewayScreen
import com.zeroclaw.android.ui.screen.settings.IdentityScreen
import com.zeroclaw.android.ui.screen.settings.MemoryAdvancedScreen
import com.zeroclaw.android.ui.screen.settings.ModelRoutesScreen
import com.zeroclaw.android.ui.screen.settings.ObservabilityScreen
import com.zeroclaw.android.ui.screen.settings.PluginRegistryScreen
import com.zeroclaw.android.ui.screen.settings.SecurityAdvancedScreen
import com.zeroclaw.android.ui.screen.settings.SecurityOverviewScreen
import com.zeroclaw.android.ui.screen.settings.ServiceConfigScreen
import com.zeroclaw.android.ui.screen.settings.SettingsScreen
import com.zeroclaw.android.ui.screen.settings.SettingsViewModel
import com.zeroclaw.android.ui.screen.settings.SkillPermissionsScreen
import com.zeroclaw.android.ui.screen.settings.TunnelScreen
import com.zeroclaw.android.ui.screen.settings.UpdatesScreen
import com.zeroclaw.android.ui.screen.settings.LiteRTModelsScreen
import com.zeroclaw.android.ui.screen.settings.apikeys.ApiKeyDetailScreen
import com.zeroclaw.android.ui.screen.settings.apikeys.ApiKeysScreen
import com.zeroclaw.android.ui.screen.settings.apikeys.ApiKeysViewModel
import com.zeroclaw.android.ui.screen.settings.apikeys.AuthProfilesScreen
import com.zeroclaw.android.ui.screen.settings.channels.ChannelDetailScreen
import com.zeroclaw.android.ui.screen.settings.channels.ConnectedChannelsScreen
import com.zeroclaw.android.ui.screen.settings.doctor.DoctorScreen
import com.zeroclaw.android.ui.screen.settings.gateway.QrScannerScreen
import com.zeroclaw.android.ui.screen.settings.logs.LogViewerScreen
import com.zeroclaw.android.ui.screen.settings.memory.MemoryBrowserScreen
import com.zeroclaw.android.ui.screen.settings.spotify.SpotifyAccountScreen
import com.zeroclaw.android.ui.screen.setup.SetupScreen
import com.zeroclaw.android.ui.screen.terminal.TerminalScreen
import com.zeroclaw.android.ui.screen.voice.VoiceAssistantSettingsScreen
import com.zeroclaw.android.ui.screen.voice.VoiceAssistantViewModel
import com.zeroclaw.android.ui.screen.settings.googleworkspace.GoogleWorkspaceSettingsScreen
import com.zeroclaw.android.ui.screen.hardware.HardwareDevicesScreen
import com.zeroclaw.android.ui.screen.hardware.GpioPinControlScreen
import com.zeroclaw.android.ui.screen.hardware.SensorMonitorScreen
import com.zeroclaw.android.ui.screen.hardware.SensorAlertConfigScreen
import com.zeroclaw.android.ui.screen.hardware.ActuatorControlScreen
import com.zeroclaw.android.ui.screen.hardware.HardwareHealthScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Single [NavHost] mapping all route objects to their screen composables.
 *
 * @param navController Navigation controller managing the back stack.
 * @param startDestination Route object for the initial destination.
 * @param edgeMargin Horizontal padding based on window width size class.
 * @param modifier Modifier applied to the [NavHost].
 */
@Composable
fun ZeroAssistNavHost(
    navController: NavHostController,
    startDestination: Any,
    edgeMargin: Dp,
    voiceAssistantViewModel: VoiceAssistantViewModel,
    modifier: Modifier = Modifier,
) {
    val pluginsViewModel: PluginsViewModel = viewModel()
    val scannedTokenHolder: ScannedTokenHolder = viewModel()
    val context = LocalContext.current
    val app = remember(context) { context.applicationContext as ZeroClawApplication }
    val restartRequired by app.daemonBridge.restartRequired
        .collectAsStateWithLifecycle()
    val restartScope = rememberCoroutineScope()
    val onRestartDaemon: () -> Unit =
        remember(app, navController, restartScope) {
            {
                val stopIntent =
                    Intent(context, ZeroAssistDaemonService::class.java).apply {
                        action = ZeroAssistDaemonService.ACTION_STOP
                    }
                context.startService(stopIntent)
                app.terminalEntryRepository.clear()
                navController.navigate(DashboardRoute) {
                    popUpTo(navController.graph.startDestinationId) { inclusive = false }
                    launchSingleTop = true
                }
                restartScope.launch {
                    app.daemonBridge.serviceState.first {
                        it == ServiceState.STOPPED || it == ServiceState.ERROR
                    }
                    val startIntent =
                        Intent(context, ZeroAssistDaemonService::class.java).apply {
                            action = ZeroAssistDaemonService.ACTION_START
                        }
                    context.startForegroundService(startIntent)
                }
            }
        }

    NavHost(
        navController = navController,
        startDestination = startDestination,
        modifier = modifier,
        enterTransition = {
            fadeIn(animationSpec = tween(300)) + androidx.compose.animation.slideInHorizontally(
                initialOffsetX = { it / 20 },
                animationSpec = tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            )
        },
        exitTransition = {
            fadeOut(animationSpec = tween(200))
        },
        popEnterTransition = {
            fadeIn(animationSpec = tween(300)) + androidx.compose.animation.slideInHorizontally(
                initialOffsetX = { -it / 20 },
                animationSpec = tween(300, easing = androidx.compose.animation.core.FastOutSlowInEasing),
            )
        },
        popExitTransition = {
            fadeOut(animationSpec = tween(200))
        },
    ) {
        composable<DashboardRoute> {
            DashboardScreen(
                edgeMargin = edgeMargin,
                onNavigateToCostDetail = { navController.navigate(CostDetailRoute) },
                onNavigateToHub = { index -> navController.navigate(ConnectionsHubRoute(tabIndex = index)) },
                restartRequired = restartRequired,
                onRestartDaemon = onRestartDaemon,
            )
        }

        composable<WebDashboardRoute> {
            com.zeroclaw.android.ui.screen.webdashboard.WebDashboardScreen(
                onBack = { navController.popBackStack() },
            )
        }

        composable<ConnectionsHubRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<ConnectionsHubRoute>()
            val skillsViewModel: SkillsViewModel = viewModel()

            ConnectionsHubScreen(
                initialTabIndex = route.tabIndex,
                onNavigateToAgentDetail = { agentId ->
                    navController.navigate(AgentDetailRoute(agentId = agentId))
                },
                onNavigateToAddAgent = { navController.navigate(AddAgentRoute) },
                onNavigateToGroupChat = { navController.navigate(AgentGroupChatRoute()) },
                onNavigateToPluginDetail = { pluginId ->
                    navController.navigate(PluginDetailRoute(pluginId = pluginId))
                },
                onNavigateToChannelDetail = { channelId, channelType ->
                    navController.navigate(
                        ChannelDetailRoute(
                            channelId = channelId,
                            channelType = channelType,
                        ),
                    )
                },
                edgeMargin = edgeMargin,
                pluginsViewModel = pluginsViewModel,
                skillsViewModel = skillsViewModel,
            )
        }

        composable<AgentDetailRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<AgentDetailRoute>()
            AgentDetailScreen(
                agentId = route.agentId,
                onSaved = { navController.popBackStack() },
                onDeleted = { navController.popBackStack() },
                onOpenInGroupChat = { agentId ->
                    navController.navigate(AgentGroupChatRoute(highlightedAgentId = agentId))
                },
                onNavigateToAddConnection = {
                    navController.navigate(ApiKeyDetailRoute(keyId = null))
                },
                edgeMargin = edgeMargin,
                restartRequired = restartRequired,
                onRestartDaemon = onRestartDaemon,
            )
        }

        composable<AddAgentRoute> {
            AddAgentScreen(
                onSaved = { navController.popBackStack() },
                onNavigateToAddConnection = {
                    navController.navigate(ApiKeyDetailRoute(keyId = null))
                },
                edgeMargin = edgeMargin,
            )
        }

        composable<AgentGroupChatRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<AgentGroupChatRoute>()
            AgentGroupChatScreen(
                familyId = route.familyId,
                highlightedAgentId = route.highlightedAgentId,
                initialInput = route.initialInput,
                onNavigateBack = { navController.popBackStack() },
                onOpenConversation = { conversationId ->
                    navController.navigate(AgentGroupChatRoute(familyId = conversationId))
                },
                onNavigateToSettings = { navController.navigate(SettingsRoute) },
            )
        }


        composable<PluginDetailRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<PluginDetailRoute>()
            PluginDetailScreen(
                pluginId = route.pluginId,
                onBack = { navController.popBackStack() },
                edgeMargin = edgeMargin,
            )
        }

        composable<TerminalRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<TerminalRoute>()
            TerminalScreen(
                edgeMargin = edgeMargin,
                initialInput = route.initialInput,
                onOpenConversation = { conversationId ->
                    navController.navigate(AgentGroupChatRoute(familyId = conversationId))
                },
                onOpenSettings = { navController.navigate(SettingsRoute) },
            )
        }

        // ====== Hardware Routes ======
        composable<HardwareDevicesRoute> {
            HardwareDevicesScreen(
                edgeMargin = edgeMargin,
                navController = navController,
            )
        }

        composable<GpioPinControlRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<GpioPinControlRoute>()
            GpioPinControlScreen(
                deviceId = route.deviceId,
                edgeMargin = edgeMargin,
                navController = navController,
            )
        }

        composable<SensorMonitorRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<SensorMonitorRoute>()
            SensorMonitorScreen(
                deviceId = route.deviceId,
                edgeMargin = edgeMargin,
                navController = navController,
            )
        }

        composable<SensorAlertConfigRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<SensorAlertConfigRoute>()
            SensorAlertConfigScreen(
                deviceId = route.deviceId,
                edgeMargin = edgeMargin,
                navController = navController,
            )
        }

        composable<ActuatorControlRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<ActuatorControlRoute>()
            ActuatorControlScreen(
                deviceId = route.deviceId,
                edgeMargin = edgeMargin,
                navController = navController,
            )
        }

        composable<HardwareHealthRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<HardwareHealthRoute>()
            HardwareHealthScreen(
                deviceId = route.deviceId,
                edgeMargin = edgeMargin,
                navController = navController,
            )
        }

        composable<SettingsRoute> {
            SettingsRouteContent(
                app = app,
                navController = navController,
                edgeMargin = edgeMargin,
                restartRequired = restartRequired,
                onRestartDaemon = onRestartDaemon,
            )
        }

        composable<ServiceConfigRoute> {
            ServiceConfigScreen(edgeMargin = edgeMargin)
        }

        composable<IdentityRoute> {
            IdentityScreen(edgeMargin = edgeMargin)
        }

        composable<BatterySettingsRoute> {
            BatterySettingsScreen(edgeMargin = edgeMargin)
        }

        composable<ApiKeysRoute> {
            ApiKeysRouteContent(
                app = app,
                navController = navController,
                edgeMargin = edgeMargin,
                restartScope = restartScope,
            )
        }

        composable<ApiKeyDetailRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<ApiKeyDetailRoute>()
            val scannedKey by scannedTokenHolder.token
                .collectAsStateWithLifecycle()

            ApiKeyDetailScreen(
                keyId = route.keyId,
                onSaved = { navController.popBackStack() },
                onNavigateToQrScanner = { navController.navigate(QrScannerRoute) },
                edgeMargin = edgeMargin,
                scannedApiKey = scannedKey,
                onScannedApiKeyConsumed = { scannedTokenHolder.consume() },
            )
        }

        composable<ConnectedChannelsRoute> {
            ConnectedChannelsScreen(
                onNavigateToDetail = { channelId, channelType ->
                    navController.navigate(
                        ChannelDetailRoute(
                            channelId = channelId,
                            channelType = channelType,
                        ),
                    )
                },
                edgeMargin = edgeMargin,
            )
        }

        composable<ChannelDetailRoute> { backStackEntry ->
            val route = backStackEntry.toRoute<ChannelDetailRoute>()
            ChannelDetailScreen(
                channelId = route.channelId,
                channelTypeName = route.channelType,
                onSaved = { navController.popBackStack() },
                onBack = { navController.popBackStack() },
                edgeMargin = edgeMargin,
            )
        }

        composable<LogViewerRoute> {
            LogViewerScreen(edgeMargin = edgeMargin)
        }

        composable<DoctorRoute> {
            DoctorScreen(
                edgeMargin = edgeMargin,
                onNavigateToRoute = { route ->
                    when {
                        route == "agents" -> navController.navigate(ConnectionsHubRoute)
                        route == "api-keys" -> navController.navigate(ApiKeysRoute)
                        route == "battery-settings" -> navController.navigate(BatterySettingsRoute)
                        route.startsWith("agent-detail/") -> {
                            val agentId = route.removePrefix("agent-detail/")
                            navController.navigate(AgentDetailRoute(agentId = agentId))
                        }
                        route.startsWith("api-key-detail/") -> {
                            val keyId = route.removePrefix("api-key-detail/")
                            navController.navigate(ApiKeyDetailRoute(keyId = keyId))
                        }
                    }
                },
            )
        }

        composable<AboutRoute> {
            AboutScreen(edgeMargin = edgeMargin)
        }

        composable<UpdatesRoute> {
            UpdatesScreen(edgeMargin = edgeMargin)
        }

        composable<AutonomyRoute> {
            AutonomyScreen(edgeMargin = edgeMargin)
        }

        composable<SecurityOverviewRoute> {
            SecurityOverviewScreen(edgeMargin = edgeMargin)
        }

        composable<TunnelRoute> {
            TunnelScreen(edgeMargin = edgeMargin)
        }

        composable<GatewayRoute> {
            GatewayRouteContent(
                navController = navController,
                edgeMargin = edgeMargin,
                scannedTokenHolder = scannedTokenHolder,
            )
        }

        composable<ModelRoutesRoute> {
            ModelRoutesScreen(edgeMargin = edgeMargin)
        }

        composable<MemoryAdvancedRoute> {
            MemoryAdvancedScreen(edgeMargin = edgeMargin)
        }

        composable<ObservabilityRoute> {
            ObservabilityScreen(edgeMargin = edgeMargin)
        }

        composable<VoiceAssistantSettingsRoute> {
            VoiceAssistantSettingsScreen(
                edgeMargin = edgeMargin,
                viewModel = voiceAssistantViewModel,
            )
        }

        composable<PluginRegistryRoute> {
            PluginRegistryScreen(
                edgeMargin = edgeMargin,
                onSyncNow = { pluginsViewModel.syncNow() },
            )
        }

        composable<QrScannerRoute> {
            QrScannerScreen(
                onTokenScanned = { token ->
                    scannedTokenHolder.set(token)
                    navController.popBackStack()
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable<SecurityAdvancedRoute> {
            SecurityAdvancedScreen(edgeMargin = edgeMargin)
        }

        composable<EmbeddingRoutesRoute> {
            EmbeddingRoutesScreen(edgeMargin = edgeMargin)
        }

        composable<MemoryBrowserRoute> {
            MemoryBrowserScreen(edgeMargin = edgeMargin)
        }

        composable<OnboardingRoute> {
            OnboardingRouteContent(
                app = app,
                navController = navController,
            )
        }

        composable<SetupRoute> {
            SetupScreen(
                onComplete = {
                    navController.navigate(DashboardRoute) {
                        popUpTo(SetupRoute) { inclusive = true }
                    }
                },
            )
        }

        composable<CostDetailRoute> {
            CostDetailScreen(edgeMargin = edgeMargin)
        }

        composable<AuthProfilesRoute> {
            AuthProfilesScreen(edgeMargin = edgeMargin)
        }

        composable<SpotifyAccountRoute> {
            SpotifyAccountScreen(edgeMargin = edgeMargin)
        }

        composable<SkillPermissionsRoute> {
            SkillPermissionsScreen(edgeMargin = edgeMargin)
        }

        composable<LiteRTModelsRoute> {
            LiteRTModelsScreen(edgeMargin = edgeMargin)
        }

        composable<GoogleWorkspaceSettingsRoute> {
            GoogleWorkspaceSettingsScreen(
                edgeMargin = edgeMargin,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

@Composable
private fun SettingsRouteContent(
    app: ZeroClawApplication,
    navController: NavHostController,
    edgeMargin: Dp,
    restartRequired: Boolean,
    onRestartDaemon: () -> Unit,
) {
    val settingsViewModel: SettingsViewModel = viewModel()
    val backupViewModel: BackupViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return BackupViewModel(
                    syncRepository = app.syncRepository,
                    settingsRepository = app.settingsRepository,
                ) as T
            }
        }
    )
    SettingsScreen(
        backupViewModel = backupViewModel,
        onNavigate = { action -> navController.navigate(settingsNavRouteFor(action)) },
        onRerunWizard = {
            settingsViewModel.resetOnboarding()
            navController.navigate(OnboardingRoute) {
                popUpTo(navController.graph.startDestinationId) { inclusive = true }
            }
        },
        restartRequired = restartRequired,
        onRestartDaemon = onRestartDaemon,
        edgeMargin = edgeMargin,
        settingsViewModel = settingsViewModel,
    )
}

private fun settingsNavRouteFor(action: SettingsNavAction): Any = when (action) {
    SettingsNavAction.ServiceConfig -> ServiceConfigRoute
    SettingsNavAction.Battery -> BatterySettingsRoute
    SettingsNavAction.ApiKeys -> ApiKeysRoute
    SettingsNavAction.Channels -> ConnectedChannelsRoute
    SettingsNavAction.LogViewer -> LogViewerRoute
    SettingsNavAction.Doctor -> DoctorRoute
    SettingsNavAction.Identity -> IdentityRoute
    SettingsNavAction.About -> AboutRoute
    SettingsNavAction.Updates -> UpdatesRoute
    SettingsNavAction.Autonomy -> AutonomyRoute
    SettingsNavAction.Tunnel -> TunnelRoute
    SettingsNavAction.Gateway -> GatewayRoute
    SettingsNavAction.ModelRoutes -> ModelRoutesRoute
    SettingsNavAction.MemoryAdvanced -> MemoryAdvancedRoute
    SettingsNavAction.Observability -> ObservabilityRoute
    SettingsNavAction.VoiceAssistant -> VoiceAssistantSettingsRoute
    SettingsNavAction.SecurityOverview -> SecurityOverviewRoute
    SettingsNavAction.PluginRegistry -> PluginRegistryRoute
    SettingsNavAction.MemoryBrowser -> MemoryBrowserRoute
    SettingsNavAction.SecurityAdvanced -> SecurityAdvancedRoute
    SettingsNavAction.EmbeddingRoutes -> EmbeddingRoutesRoute
    SettingsNavAction.AuthProfiles -> AuthProfilesRoute
    SettingsNavAction.SpotifyAccount -> SpotifyAccountRoute
    SettingsNavAction.SkillPermissions -> SkillPermissionsRoute
    SettingsNavAction.LiteRTModels -> LiteRTModelsRoute
}

@Composable
private fun ApiKeysRouteContent(
    app: ZeroClawApplication,
    navController: NavHostController,
    edgeMargin: Dp,
    restartScope: kotlinx.coroutines.CoroutineScope,
) {
    val context = LocalContext.current
    val apiKeysViewModel: ApiKeysViewModel = viewModel()
    val settings by app.settingsRepository.settings.collectAsStateWithLifecycle(
        initialValue = com.zeroclaw.android.model.AppSettings(),
    )
    var pendingRevealKeyId by remember { mutableStateOf<String?>(null) }
    var showPinSetupForReveal by remember { mutableStateOf(false) }
    val credentialsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { apiKeysViewModel.importCredentialsFile(context, it) } }
    val onDeviceSelectedScope = rememberCoroutineScope()
    ApiKeysScreen(
        onNavigateToDetail = { keyId -> navController.navigate(ApiKeyDetailRoute(keyId = keyId)) },
        onRequestBiometric = { keyId ->
            pendingRevealKeyId = keyId
            if (settings.pinHash.isEmpty()) showPinSetupForReveal = true
        },
        onExportResult = { payload ->
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, payload)
                putExtra(Intent.EXTRA_SUBJECT, "Zero-Assist API Keys (encrypted)")
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share encrypted keys"))
        },
        onImportCredentials = { credentialsLauncher.launch(arrayOf("application/json", "*/*")) },
        onNavigateToOnDeviceModels = { navController.navigate(LiteRTModelsRoute) },
        onOnDeviceSelected = {
            onDeviceSelectedScope.launch {
                app.settingsRepository.setDefaultProvider("on-device")
                val agents = app.agentRepository.agents.first()
                val primary = agents.firstOrNull { it.isEnabled && it.provider.isNotBlank() && it.modelName.isNotBlank() }
                if (primary != null && primary.provider != "on-device") {
                    app.agentRepository.save(primary.copy(provider = "on-device", modelName = "gemini-nano"))
                }
            }
        },
        edgeMargin = edgeMargin,
        apiKeysViewModel = apiKeysViewModel,
    )
    if (showPinSetupForReveal && pendingRevealKeyId != null) {
        PinEntrySheet(
            mode = PinEntryMode.SETUP,
            currentPinHash = "",
            onPinSet = { newHash ->
                restartScope.launch { app.settingsRepository.setPinHash(newHash) }
                pendingRevealKeyId?.let { apiKeysViewModel.revealKey(it) }
                pendingRevealKeyId = null; showPinSetupForReveal = false
            },
            onDismiss = { pendingRevealKeyId = null; showPinSetupForReveal = false },
        )
    } else if (pendingRevealKeyId != null && settings.pinHash.isNotEmpty()) {
        PinEntrySheet(
            mode = PinEntryMode.VERIFY,
            currentPinHash = settings.pinHash,
            onPinSet = { pendingRevealKeyId?.let { apiKeysViewModel.revealKey(it) }; pendingRevealKeyId = null },
            onDismiss = { pendingRevealKeyId = null },
        )
    }
}

@Composable
private fun GatewayRouteContent(
    navController: NavHostController,
    edgeMargin: Dp,
    scannedTokenHolder: ScannedTokenHolder,
) {
    val settingsVm: SettingsViewModel = viewModel()
    val scannedToken by scannedTokenHolder.token.collectAsStateWithLifecycle()
    LaunchedEffect(scannedToken) {
        if (scannedToken.isNotBlank()) {
            val currentSettings = settingsVm.settings.value
            val existingTokens = currentSettings.gatewayPairedTokens
            val merged = if (existingTokens.isBlank()) scannedToken else "$existingTokens,$scannedToken"
            settingsVm.updateGatewayPairedTokens(merged)
            scannedTokenHolder.consume()
        }
    }
    GatewayScreen(
        edgeMargin = edgeMargin,
        onNavigateToQrScanner = { navController.navigate(QrScannerRoute) },
        settingsViewModel = settingsVm,
    )
}

@Composable
private fun OnboardingRouteContent(
    app: ZeroClawApplication,
    navController: NavHostController,
) {
    val backupViewModel: BackupViewModel = viewModel(
        factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return BackupViewModel(
                    syncRepository = app.syncRepository,
                    settingsRepository = app.settingsRepository,
                ) as T
            }
        }
    )
    OnboardingScreen(
        backupViewModel = backupViewModel,
        onComplete = { navController.navigate(SetupRoute) { popUpTo(OnboardingRoute) { inclusive = true } } },
    )
}
