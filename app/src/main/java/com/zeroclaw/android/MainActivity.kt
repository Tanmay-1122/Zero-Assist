/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.withFrameNanos
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zeroclaw.android.model.AppSettings
import com.zeroclaw.android.model.ThemeMode
import com.zeroclaw.android.navigation.ZeroAssistAppShell
import com.zeroclaw.android.startup.AppStartupTrace
import com.zeroclaw.android.service.VoiceAssistantIntentRouter
import com.zeroclaw.android.ui.theme.ZeroAssistTheme

/**
 * Main entry point for the Zero-Assist Android application.
 *
 * Sets up edge-to-edge display and delegates all UI to
 * [ZeroAssistAppShell] which manages navigation, the adaptive
 * navigation bar, and all screens.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleVoiceAssistantIntent(intent)
        enableEdgeToEdge()
        if (!BuildConfig.DEBUG) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        }
        setContent {
            val app = application as ZeroClawApplication
            LaunchedEffect(Unit) {
                withFrameNanos { }
                AppStartupTrace.mark("first_compose_frame")
                app.initializeDeferredWorkManager()
            }
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
            ZeroAssistTheme(darkTheme = darkTheme) {
                @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
                val windowSizeClass = calculateWindowSizeClass(this@MainActivity)
                ZeroAssistAppShell(
                    windowWidthSizeClass = windowSizeClass.widthSizeClass,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleVoiceAssistantIntent(intent)
    }

    private fun handleVoiceAssistantIntent(intent: Intent?) {
        if (VoiceAssistantIntentRouter.opensVoiceAssistant(intent)) {
            if (intent?.action == Intent.ACTION_ASSIST) {
                startActivity(
                    Intent(this, VoiceAssistantActivity::class.java)
                        .setAction(VoiceAssistantIntentRouter.ACTION_OPEN_VOICE_ASSISTANT)
                        .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                )
                finish()
                return
            }
            (application as ZeroClawApplication).voiceAssistantLaunchRequests.requestOpen()
        }
    }
}
