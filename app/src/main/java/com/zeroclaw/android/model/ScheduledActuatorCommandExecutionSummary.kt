/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.model

/**
 * Summary for one pass over pending scheduled actuator commands.
 */
data class ScheduledActuatorCommandExecutionSummary(
    val attempted: Int = 0,
    val completed: Int = 0,
    val failed: Int = 0,
    val skipped: Int = 0,
) {
    val hadWork: Boolean
        get() = attempted > 0 || skipped > 0
}
