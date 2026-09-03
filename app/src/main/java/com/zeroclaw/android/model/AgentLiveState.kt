/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.model

/**
 * High-level real-time status for an agent while orchestration is running.
 */
enum class AgentStatus {
    /** Agent is available and not currently working on anything. */
    IDLE,

    /** Agent is processing or waiting on an LLM response. */
    THINKING,

    /** Agent is running a tool or external action. */
    EXECUTING,

    /** Agent is handing off work to another agent. */
    DELEGATING,

    /** Agent is blocked on approval or another agent's output. */
    WAITING,

    /** Agent hit an error while working. */
    ERROR,

    /** Agent just completed a task. */
    DONE,
}

/**
 * Snapshot of one agent's visible live activity.
 *
 * @property agentId Agent identifier this state belongs to.
 * @property status Current high-level activity state.
 * @property currentTask Short human-readable description of what the agent is doing.
 * @property lastUpdated Epoch time in milliseconds when this state last changed.
 */
data class AgentLiveState(
    val agentId: String,
    val status: AgentStatus,
    val currentTask: String = "",
    val lastUpdated: Long = System.currentTimeMillis(),
)
