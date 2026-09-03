/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar

import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.zeroclaw.android.ZeroClawApplication
import com.zeroclaw.android.model.ServiceState
import com.zeroclaw.android.service.VoiceAssistantCommandLauncher
import com.zeroclaw.android.service.SettingsVoiceWakeupPreferences
import com.zeroclaw.android.ui.component.LockGateScreen
import com.zeroclaw.android.ui.component.ZeroAssistBottomBar
import com.zeroclaw.android.ui.screen.voice.VoiceAssistantPopupHost
import com.zeroclaw.android.ui.screen.voice.VoiceAssistantViewModel
import com.zeroclaw.android.ui.theme.ZeroAssistDimens
import com.zeroclaw.android.viewmodel.DaemonViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.zeroclaw.android.backup.BackupViewModel
import com.zeroclaw.android.backup.SyncStatus
import com.zeroclaw.android.ui.backup.SyncStatusIndicator

/** Set of top-level routes where the bottom navigation bar should be visible. */
private val topLevelRoutes = TopLevelDestination.entries.map { it.route::class }

/**
 * Root composable providing the application shell with adaptive navigation
 * and a top app bar.
 *
 * Uses [NavigationSuiteScaffold] to automatically switch between a bottom
 * navigation bar (< 600dp), navigation rail (600-840dp), and navigation
 * drawer (840dp+) based on the current window width.
 *
 * A compact status indicator is visible in the top bar to provide
 * persistent daemon status feedback.
 *
 * @param windowWidthSizeClass Current [WindowWidthSizeClass] for responsive layout.
 * @param viewModel The [DaemonViewModel] for daemon state.
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ZeroAssistAppShell(
    windowWidthSizeClass: WindowWidthSizeClass,
) {
    val context = LocalContext.current
    val app = context.applicationContext as ZeroClawApplication

    // Wait for repositories to be ready before creating ViewModels that depend on them
    val repositoriesReady by app.repositoriesReady.collectAsStateWithLifecycle(initialValue = false)

    if (!repositoriesReady) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            // Show loading indicator while repositories initialize
            androidx.compose.material3.CircularProgressIndicator()
        }
        return
    }

    val viewModel: DaemonViewModel = viewModel()
    val voiceAssistantViewModel = rememberVoiceAssistantViewModel(app)
    val onboardingCompleted by app.onboardingRepository.isCompleted.collectAsStateWithLifecycle(
        initialValue = true,
    )
    val backupViewModel = rememberBackupViewModel(context, onboardingCompleted)
    val isDriveSignedIn by (backupViewModel?.isSignedIn?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(false) })
    val syncStatus by (backupViewModel?.syncStatus?.collectAsStateWithLifecycle()
        ?: remember { mutableStateOf(SyncStatus.IDLE) })

    val startDestination: Any =
        if (onboardingCompleted) DashboardRoute else OnboardingRoute

    val navController = rememberNavController()
    LaunchVoiceCommandCollector(voiceAssistantViewModel, navController, context)

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val serviceState by viewModel.serviceState.collectAsStateWithLifecycle()

    val isLocked by app.sessionLockManager.isLocked.collectAsStateWithLifecycle()
    val settings by app.settingsRepository.settings.collectAsStateWithLifecycle(
        initialValue = com.zeroclaw.android.model.AppSettings(),
    )
    val isOnboarding = currentDestination?.hasRoute(OnboardingRoute::class) == true
    val shouldShowLock =
        isLocked && settings.lockEnabled && settings.pinHash.isNotEmpty() && !isOnboarding
    LaunchVoiceLaunchRequestCollector(app, voiceAssistantViewModel, isOnboarding, shouldShowLock)

    val isTopLevel =
        !isOnboarding &&
            currentDestination?.hierarchy?.any { dest ->
                topLevelRoutes.any { routeClass -> dest.hasRoute(routeClass) }
            } == true

    val edgeMargin =
        if (windowWidthSizeClass == WindowWidthSizeClass.Compact) {
            ZeroAssistDimens.CompactEdgeMargin
        } else {
            ZeroAssistDimens.ExpandedEdgeMargin
        }

    Box(modifier = Modifier.fillMaxSize()) {
        if (isOnboarding) {
            OnboardingScaffold(
                navController = navController,
                startDestination = startDestination,
                edgeMargin = edgeMargin,
                voiceAssistantViewModel = voiceAssistantViewModel,
            )
        } else if (isTopLevel) {
            TopLevelScaffold(
                navController = navController,
                startDestination = startDestination,
                edgeMargin = edgeMargin,
                voiceAssistantViewModel = voiceAssistantViewModel,
                serviceState = serviceState,
                isDriveSignedIn = isDriveSignedIn,
                syncStatus = syncStatus,
                currentDestination = currentDestination,
            )
        } else {
            DetailScaffold(
                navController = navController,
                startDestination = startDestination,
                edgeMargin = edgeMargin,
                voiceAssistantViewModel = voiceAssistantViewModel,
                serviceState = serviceState,
                isDriveSignedIn = isDriveSignedIn,
                syncStatus = syncStatus,
                currentDestination = currentDestination,
            )
        }

        if (shouldShowLock) {
            LockGateScreen(
                pinHash = settings.pinHash,
                onUnlock = { app.sessionLockManager.unlock() },
            )
        } else if (!isOnboarding) {
            VoiceAssistantPopupHost(
                viewModel = voiceAssistantViewModel,
                onOpenVoiceSettings = {
                    voiceAssistantViewModel.closePopup()
                    navController.navigate(VoiceAssistantSettingsRoute)
                },
            )
        }
    }
}

@Composable
private fun rememberVoiceAssistantViewModel(app: ZeroClawApplication): VoiceAssistantViewModel {
    val factory = remember(app) {
        VoiceAssistantViewModel.factory(
            repository = app.localVoiceCatalogRepository,
            localVoiceStorage = app.localVoiceStorage,
            localSpeechRecognizerProvider = { app.localSpeechRecognizer },
            voiceContactLookup = app.voiceContactLookup,
            voiceAssistantConversation = app.voiceAssistantConversation,
            localSpeechSynthesizerProvider = { app.localSpeechSynthesizer },
            voiceDownloadManager = app.voiceDownloadManager,
            voiceOutputPreferences = app.voiceOutputPreferences,
            voiceWakeupPreferences = SettingsVoiceWakeupPreferences(app.settingsRepository),
            voiceWakeupDetectorProvider = { app.voiceWakeupDetector },
            voiceWakeupServiceController = app.voiceWakeupServiceController,
        )
    }
    return viewModel(factory = factory)
}

@Composable
private fun rememberBackupViewModel(
    context: android.content.Context,
    onboardingCompleted: Boolean,
): BackupViewModel? {
    if (!onboardingCompleted) return null
    val factory = remember(context) {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val appInstance = context.applicationContext as ZeroClawApplication
                @Suppress("UNCHECKED_CAST")
                return BackupViewModel(
                    syncRepository = appInstance.syncRepository,
                    settingsRepository = appInstance.settingsRepository,
                ) as T
            }
        }
    }
    return viewModel(factory = factory)
}

@Composable
private fun LaunchVoiceCommandCollector(
    viewModel: VoiceAssistantViewModel,
    navController: androidx.navigation.NavHostController,
    context: android.content.Context,
) {
    LaunchedEffect(viewModel, navController, context) {
        viewModel.voiceCommands.collect { command ->
            viewModel.runCommandLaunch(
                command = command,
                launchCommand = { launchedCommand, _ ->
                    VoiceAssistantCommandLauncher.launch(
                        context = context,
                        command = launchedCommand,
                    )
                },
            )
        }
    }
}

@Composable
private fun LaunchVoiceLaunchRequestCollector(
    app: ZeroClawApplication,
    viewModel: VoiceAssistantViewModel,
    isOnboarding: Boolean,
    shouldShowLock: Boolean,
) {
    LaunchedEffect(app, viewModel, isOnboarding, shouldShowLock) {
        app.voiceAssistantLaunchRequests.requests.collect {
            if (!isOnboarding && !shouldShowLock) {
                viewModel.openPopup()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnboardingScaffold(
    navController: androidx.navigation.NavHostController,
    startDestination: Any,
    edgeMargin: androidx.compose.ui.unit.Dp,
    voiceAssistantViewModel: VoiceAssistantViewModel,
) {
    Scaffold { innerPadding ->
        ZeroAssistNavHost(
            navController = navController,
            startDestination = startDestination,
            edgeMargin = edgeMargin,
            voiceAssistantViewModel = voiceAssistantViewModel,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TopLevelScaffold(
    navController: androidx.navigation.NavHostController,
    startDestination: Any,
    edgeMargin: androidx.compose.ui.unit.Dp,
    voiceAssistantViewModel: VoiceAssistantViewModel,
    serviceState: ServiceState,
    isDriveSignedIn: Boolean,
    syncStatus: com.zeroclaw.android.backup.SyncStatus,
    currentDestination: NavDestination?,
) {
    val selectedDestination =
        TopLevelDestination.entries.firstOrNull { destination ->
            currentDestination?.hierarchy?.any { dest ->
                dest.hasRoute(destination.route::class)
            } == true
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { TopBarTitle(serviceState = serviceState) },
                actions = {
                    com.zeroclaw.android.ui.screen.plugins.TermuxStatusIndicator()
                    Spacer(modifier = Modifier.width(8.dp))
                    if (isDriveSignedIn) {
                        SyncStatusIndicator(status = syncStatus)
                    } else {
                        DriveUnavailableStub()
                    }
                },
            )
        },
        bottomBar = {
            ZeroAssistBottomBar(
                selectedDestination = selectedDestination,
                onNavigate = { destination ->
                    navController.navigate(destination.route) {
                        popUpTo(navController.graph.startDestinationId) { saveState = true }
                        launchSingleTop = true
                        restoreState = true
                    }
                },
            )
        },
    ) { innerPadding ->
        ZeroAssistNavHost(
            navController = navController,
            startDestination = startDestination,
            edgeMargin = edgeMargin,
            voiceAssistantViewModel = voiceAssistantViewModel,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailScaffold(
    navController: androidx.navigation.NavHostController,
    startDestination: Any,
    edgeMargin: androidx.compose.ui.unit.Dp,
    voiceAssistantViewModel: VoiceAssistantViewModel,
    serviceState: ServiceState,
    isDriveSignedIn: Boolean,
    syncStatus: com.zeroclaw.android.backup.SyncStatus,
    currentDestination: NavDestination?,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    TopBarTitle(
                        serviceState = serviceState,
                        title = screenTitleFor(currentDestination) ?: "Zero-Assist",
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Navigate back",
                        )
                    }
                },
                actions = {
                    if (isDriveSignedIn) {
                        SyncStatusIndicator(status = syncStatus)
                    } else {
                        DriveUnavailableStub()
                    }
                },
            )
        },
    ) { innerPadding ->
        ZeroAssistNavHost(
            navController = navController,
            startDestination = startDestination,
            edgeMargin = edgeMargin,
            voiceAssistantViewModel = voiceAssistantViewModel,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

@Composable
private fun TopBarTitle(
    serviceState: ServiceState,
    title: String = "Zero-Assist",
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
        )
        Spacer(modifier = Modifier.width(6.dp))
        Box(
            modifier =
                Modifier
                    .size(6.dp)
                    .background(
                        color = when (serviceState) {
                            ServiceState.RUNNING -> MaterialTheme.colorScheme.primary
                            ServiceState.STARTING, ServiceState.STOPPING -> MaterialTheme.colorScheme.tertiary
                            else -> MaterialTheme.colorScheme.error
                        },
                        shape = CircleShape,
                    ),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = statusLabelFor(serviceState),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
@Composable
private fun DriveUnavailableStub() {
    Icon(
        imageVector = Icons.Outlined.CloudOff,
        contentDescription = "Drive not signed in",
        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
        modifier = Modifier.padding(8.dp),
    )
}

private fun statusLabelFor(serviceState: ServiceState): String =
    when (serviceState) {
        ServiceState.STOPPED -> "Stopped"
        ServiceState.STARTING -> "Starting"
        ServiceState.RUNNING -> "Running"
        ServiceState.STOPPING -> "Stopping"
        ServiceState.ERROR -> "Error"
    }

private fun screenTitleFor(destination: NavDestination?): String? {
    TopLevelDestination.entries
        .find { destination?.hasRoute(it.route::class) == true }
        ?.let { return it.label }
    return when {
        destination?.hasRoute(VoiceAssistantSettingsRoute::class) == true -> "Voice Assistant"
        else -> null
    }
}
