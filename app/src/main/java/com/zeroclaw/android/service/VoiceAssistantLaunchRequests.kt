/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow

/** App-wide one-shot requests to open the voice assistant popup from system/app entrypoints. */
class VoiceAssistantLaunchRequests {
    private val channel = Channel<Unit>(Channel.BUFFERED)

    val requests: Flow<Unit> = channel.receiveAsFlow()

    fun requestOpen() {
        channel.trySend(Unit)
    }
}
