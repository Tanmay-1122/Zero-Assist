/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.uiagent

/** Pluggable single-step planner for the UI agent loop. */
interface UiAgentPlanner {
    suspend fun decide(
        prompt: UiPrompt,
        context: UiAgentSessionContext,
    ): UiAgentDecision
}

data class UiAgentSessionContext(
    val sessionId: String,
    val goal: UiAgentGoal,
    val stepIndex: Int,
    val maxSteps: Int,
    val startedAtEpochMs: Long,
    val deadlineAtEpochMs: Long,
    val history: List<UiAgentStepRecord> = emptyList(),
) {
    val remainingStepBudget: Int
        get() = (maxSteps - stepIndex).coerceAtLeast(0)
}
