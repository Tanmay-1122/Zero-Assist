/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.uiagent

/** Source of prompt-safe observed UI snapshots for the AI-driven phone-control planner. */
interface UiSnapshotProvider {
    fun currentSnapshot(): UiSnapshot?
}

/** Provider used when no UI observation backend is connected. */
object MissingUiSnapshotProvider : UiSnapshotProvider {
    override fun currentSnapshot(): UiSnapshot? = null
}
