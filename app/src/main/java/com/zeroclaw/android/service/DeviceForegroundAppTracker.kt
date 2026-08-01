/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Waits for observed foreground package changes before further work continues. */
fun interface DeviceForegroundAppTracker {
    suspend fun waitForPackage(
        packageName: String,
        timeoutMs: Long,
    ): Boolean

    companion object {
        val NoOp: DeviceForegroundAppTracker =
            DeviceForegroundAppTracker { _, _ -> true }
    }
}
