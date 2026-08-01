/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.startup

import android.os.SystemClock
import android.os.Trace
import android.util.Log

internal object AppStartupTrace {
    fun mark(
        name: String,
        detail: String = "",
    ) {
        val suffix = detail.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()
        Log.d(TAG, "startup trace mark: $name$suffix")
    }

    fun <T> section(
        name: String,
        block: () -> T,
    ): T {
        val startedAt = SystemClock.elapsedRealtime()
        Trace.beginSection(name.traceSectionName())
        Log.d(TAG, "startup trace start: $name")
        return try {
            block()
        } finally {
            Trace.endSection()
            Log.d(
                TAG,
                "startup trace complete: $name elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
            )
        }
    }

    suspend fun <T> suspendSection(
        name: String,
        block: suspend () -> T,
    ): T {
        val startedAt = SystemClock.elapsedRealtime()
        Trace.beginSection(name.traceSectionName())
        Log.d(TAG, "startup trace start: $name")
        return try {
            block()
        } finally {
            Trace.endSection()
            Log.d(
                TAG,
                "startup trace complete: $name elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
            )
        }
    }

    private const val TAG = "AppStartupTrace"
}

internal const val COLD_START_CRITICAL_PATH_MS = 60_000L

internal fun isColdStartCriticalWindow(
    processStartElapsedRealtimeMs: Long,
    nowElapsedRealtimeMs: Long,
    windowMs: Long = COLD_START_CRITICAL_PATH_MS,
): Boolean =
    nowElapsedRealtimeMs - processStartElapsedRealtimeMs < windowMs

private fun String.traceSectionName(): String = take(TRACE_SECTION_NAME_LIMIT)

private const val TRACE_SECTION_NAME_LIMIT = 127
