/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.uiagent

/** Executes one normalized planner action through whatever bridge is installed by the app. */
interface UiAgentActionExecutor {
    suspend fun execute(command: UiAgentExecutionCommand): UiAgentExecutionResult
}

data class UiAgentExecutionCommand(
    val goal: UiAgentGoal,
    val action: UiAgentAction,
    val snapshot: UiSnapshot,
    val stepIndex: Int,
    val expectedState: UiExpectedState? = null,
)

sealed interface UiAgentExecutionResult {
    val reason: String?

    data object Succeeded : UiAgentExecutionResult {
        override val reason: String? = null
    }

    data class Failed(
        override val reason: String,
        val retryable: Boolean = false,
    ) : UiAgentExecutionResult
}
