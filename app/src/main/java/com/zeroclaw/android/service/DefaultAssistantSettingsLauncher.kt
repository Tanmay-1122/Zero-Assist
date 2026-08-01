/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import android.content.Context
import android.content.Intent

/** Opens Android settings screens where the user can choose the default assist app. */
object DefaultAssistantSettingsLauncher {
    const val ACTION_VOICE_INPUT_SETTINGS = "android.settings.VOICE_INPUT_SETTINGS"
    const val ACTION_MANAGE_DEFAULT_APPS_SETTINGS = "android.settings.MANAGE_DEFAULT_APPS_SETTINGS"
    const val ACTION_SETTINGS = "android.settings.SETTINGS"

    val fallbackActions: List<String> =
        listOf(
            ACTION_VOICE_INPUT_SETTINGS,
            ACTION_MANAGE_DEFAULT_APPS_SETTINGS,
            ACTION_SETTINGS,
        )

    fun open(context: Context): Boolean {
        val appContext = context.applicationContext
        return fallbackActions.any { action ->
            runCatching {
                appContext.startActivity(
                    Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }.isSuccess
        }
    }
}
