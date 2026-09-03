/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zeroclaw.android.ZeroClawApplication
import com.zeroclaw.android.model.ConversationEntry
import java.time.Instant
import java.time.ZoneId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Shared ViewModel for the conversation history drawer shown on chat surfaces.
 */
class HistoryViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val app = application as ZeroClawApplication

    private val selectedAgentsState = MutableStateFlow<Set<String>>(emptySet())
    val selectedAgents: StateFlow<Set<String>> = selectedAgentsState.asStateFlow()

    private val startDateMillisState = MutableStateFlow<Long?>(null)
    val startDateMillis: StateFlow<Long?> = startDateMillisState.asStateFlow()

    private val endDateMillisState = MutableStateFlow<Long?>(null)
    val endDateMillis: StateFlow<Long?> = endDateMillisState.asStateFlow()

    private val rawHistoryEntries: Flow<List<ConversationEntry>> =
        combine(
            app.conversationHistoryRepository.entries,
            app.starredConversationRepository.starredConversationIds,
        ) { conversationEntries, starredIds ->
            conversationEntries
                .map { entry -> entry.copy(isStarred = entry.id in starredIds) }
                .sortedByDescending(ConversationEntry::timestamp)
        }

    val groupedHistory: StateFlow<Map<String, List<ConversationEntry>>> =
        combine(
            rawHistoryEntries,
            selectedAgentsState,
            startDateMillisState,
            endDateMillisState,
        ) { entries, selectedAgents, startDateMillis, endDateMillis ->
            entries
                .filter { entry -> matchesAgentFilter(entry, selectedAgents) }
                .filter { entry -> matchesDateFilter(entry, startDateMillis, endDateMillis) }
                .groupBy(ConversationEntry::workspaceName)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            emptyMap(),
        )

    val starredEntries: StateFlow<List<ConversationEntry>> =
        rawHistoryEntries
            .map { entries -> entries.filter(ConversationEntry::isStarred) }
            .stateIn(
                viewModelScope,
                SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                emptyList(),
            )

    val availableAgents: StateFlow<List<String>> =
        combine(app.agentRepository.agents, rawHistoryEntries) { agents, entries ->
            buildSet {
                agents.mapTo(this) { agent -> agent.name }
                entries.mapTo(this) { entry -> entry.agentName }
            }.toList().sorted()
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            emptyList(),
        )

    fun toggleAgentFilter(agentName: String) {
        selectedAgentsState.value =
            selectedAgentsState.value.toMutableSet().apply {
                if (!add(agentName)) {
                    remove(agentName)
                }
            }
    }

    fun clearAgentFilters() {
        selectedAgentsState.value = emptySet()
    }

    fun setDateRange(
        startDateMillis: Long?,
        endDateMillis: Long?,
    ) {
        startDateMillisState.value = startDateMillis
        endDateMillisState.value = endDateMillis
    }

    fun clearDateRange() {
        startDateMillisState.value = null
        endDateMillisState.value = null
    }

    fun starConversation(id: String) {
        viewModelScope.launch {
            app.starredConversationRepository.starConversation(id)
        }
    }

    fun unstarConversation(id: String) {
        viewModelScope.launch {
            app.starredConversationRepository.unstarConversation(id)
        }
    }

    private companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}

private fun matchesAgentFilter(
    entry: ConversationEntry,
    selectedAgents: Set<String>,
): Boolean = selectedAgents.isEmpty() || entry.agentName in selectedAgents

private fun matchesDateFilter(
    entry: ConversationEntry,
    startDateMillis: Long?,
    endDateMillis: Long?,
): Boolean {
    if (startDateMillis == null && endDateMillis == null) return true

    val zoneId = ZoneId.systemDefault()
    val entryDate = Instant.ofEpochMilli(entry.timestamp).atZone(zoneId).toLocalDate()
    val startDate = startDateMillis?.let { Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate() }
    val endDate = endDateMillis?.let { Instant.ofEpochMilli(it).atZone(zoneId).toLocalDate() }

    if (startDate != null && entryDate.isBefore(startDate)) return false
    if (endDate != null && entryDate.isAfter(endDate)) return false
    return true
}
