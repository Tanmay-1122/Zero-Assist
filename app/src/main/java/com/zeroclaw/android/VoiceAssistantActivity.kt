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
import com.zeroclaw.android.service.VoiceAssistantCommandLauncher
import com.zeroclaw.android.ui.screen.voice.VoiceAssistantStandaloneHost

/**
 * Lightweight Android Assist entrypoint.
 *
 * This keeps Home/Assist activation on a popup-only surface instead of opening
 * the full app shell.
 */
class VoiceAssistantActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        configureAssistantWindow()

        setContent {
            val app = application as ZeroClawApplication
            VoiceAssistantStandaloneHost(
                app = app,
                onCloseSurface = { finish() },
                onOpenVoiceSettings = {
                    startActivity(
                        Intent(this@VoiceAssistantActivity, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                    )
                    finish()
                },
                launchCommand = { command, _ ->
                    VoiceAssistantCommandLauncher.launch(
                        context = this@VoiceAssistantActivity,
                        command = command,
                    )
                },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        (application as ZeroClawApplication).voiceAssistantLaunchRequests.requestOpen()
    }

    private fun configureAssistantWindow() {
        if (!BuildConfig.DEBUG) {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_SECURE,
                WindowManager.LayoutParams.FLAG_SECURE,
            )
        }
        window.setDimAmount(0.32f)
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
    }
}
