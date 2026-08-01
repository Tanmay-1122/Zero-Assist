/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import android.content.Intent

/** Maps Android intents that should open the local voice assistant popup. */
object VoiceAssistantIntentRouter {
    const val ACTION_OPEN_VOICE_ASSISTANT =
        "com.zeroclaw.android.action.OPEN_VOICE_ASSISTANT"
    const val EXTRA_OPEN_VOICE_ASSISTANT =
        "com.zeroclaw.android.extra.OPEN_VOICE_ASSISTANT"

    fun opensVoiceAssistant(intent: Intent?): Boolean =
        intent?.let {
            opensVoiceAssistant(
                action = it.action,
                explicitExtra = it.getBooleanExtra(EXTRA_OPEN_VOICE_ASSISTANT, false),
            )
        } ?: false

    fun opensVoiceAssistant(
        action: String?,
        explicitExtra: Boolean,
    ): Boolean =
        action == Intent.ACTION_ASSIST ||
            action == ACTION_OPEN_VOICE_ASSISTANT ||
            explicitExtra
}
