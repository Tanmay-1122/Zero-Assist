/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.model.AgentLiveState
import com.zeroclaw.android.model.AgentStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * In-memory live status store for visible multi-agent activity.
 */
class AgentStatusRepository {
    private val _statuses = MutableStateFlow<Map<String, AgentLiveState>>(emptyMap())
    val statuses: StateFlow<Map<String, AgentLiveState>> = _statuses.asStateFlow()

    fun updateStatus(
        agentId: String,
        status: AgentStatus,
        task: String = "",
    ) {
        if (agentId.isBlank()) return
        _statuses.value =
            _statuses.value.toMutableMap().apply {
                this[agentId] =
                    AgentLiveState(
                        agentId = agentId,
                        status = status,
                        currentTask = task.trim(),
                    )
            }
    }

    fun getStatus(agentId: String): AgentLiveState? = _statuses.value[agentId]

    fun clearStatus(agentId: String) {
        if (agentId.isBlank()) return
        _statuses.value =
            _statuses.value.toMutableMap().apply {
                remove(agentId)
            }
    }
}
