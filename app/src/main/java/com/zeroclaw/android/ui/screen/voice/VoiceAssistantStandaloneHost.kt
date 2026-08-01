/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.voice

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zeroclaw.android.ZeroClawApplication
import com.zeroclaw.android.model.AppSettings
import com.zeroclaw.android.model.ThemeMode
import com.zeroclaw.android.model.VoiceAssistantCommand
import com.zeroclaw.android.service.VoiceAssistantCommandLaunchResult
import com.zeroclaw.android.service.SettingsVoiceWakeupPreferences
import com.zeroclaw.android.ui.theme.ZeroAssistTheme

/**
 * Popup-only assistant host shared by the Assist activity and Android's
 * VoiceInteractionSession surface.
 */
@Composable
fun VoiceAssistantStandaloneHost(
    app: ZeroClawApplication,
    onCloseSurface: () -> Unit,
    onOpenVoiceSettings: () -> Unit,
    launchCommand: suspend (
        VoiceAssistantCommand,
        suspend (String) -> Unit,
    ) -> VoiceAssistantCommandLaunchResult,
    modifier: Modifier = Modifier,
) {
    val settings by app.settingsRepository.settings
        .collectAsStateWithLifecycle(
            initialValue = AppSettings(),
        )
    val darkTheme =
        when (settings.theme) {
            ThemeMode.SYSTEM -> isSystemInDarkTheme()
            ThemeMode.LIGHT -> false
            ThemeMode.DARK -> true
        }
    val viewModelFactory = remember(app) {
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
    val voiceAssistantViewModel: VoiceAssistantViewModel =
        viewModel(factory = viewModelFactory)
    val uiState by voiceAssistantViewModel.uiState.collectAsStateWithLifecycle()
    var observedPopup by remember { mutableStateOf(false) }

    LaunchedEffect(voiceAssistantViewModel) {
        voiceAssistantViewModel.openPopup()
    }
    LaunchedEffect(app, voiceAssistantViewModel) {
        app.voiceAssistantLaunchRequests.requests.collect {
            voiceAssistantViewModel.openPopup()
        }
    }
    LaunchedEffect(voiceAssistantViewModel, launchCommand) {
        voiceAssistantViewModel.voiceCommands.collect { command ->
            voiceAssistantViewModel.runCommandLaunch(
                command = command,
                launchCommand = launchCommand,
                onSuccessWithoutFinalMessage = onCloseSurface,
            )
        }
    }
    LaunchedEffect(uiState.popupVisible) {
        if (uiState.popupVisible) {
            observedPopup = true
        } else if (observedPopup) {
            onCloseSurface()
        }
    }

    ZeroAssistTheme(darkTheme = darkTheme) {
        Box(modifier = modifier.fillMaxSize()) {
            VoiceAssistantPopupHost(
                viewModel = voiceAssistantViewModel,
                onOpenVoiceSettings = onOpenVoiceSettings,
                showLauncher = false,
            )
        }
    }
}
