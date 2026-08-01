/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Finalizes active conversation metadata when the app moves to the background.
 *
 * This ensures the current session is closed and archived in history as soon as
 * the user leaves the app, so the next session starts fresh.
 */
class ConversationSessionLifecycleObserver(
    private val sessionManager: ConversationSessionManager,
    private val scope: CoroutineScope,
) : DefaultLifecycleObserver {
    override fun onStop(owner: LifecycleOwner) {
        scope.launch {
            sessionManager.finalizeActiveConversation(
                reason = "process_background",
                clearActiveMarker = true,
            )
        }
    }
}
