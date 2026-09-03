/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.quickstart

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zeroclaw.android.ZeroClawApplication
import com.zeroclaw.android.model.Agent
import com.zeroclaw.android.model.ConversationEntry
import com.zeroclaw.android.model.TerminalEntry
import com.zeroclaw.android.ui.screen.terminal.CommandRegistry
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private const val COMMAND_LIMIT = 4
private const val AGENT_LIMIT = 3
private const val STOP_TIMEOUT_MS = 5_000L
private const val TERMINAL_AGENT_NAME = "Zero-Assist"

/**
 * Supplies quick-start content for command and chat entry surfaces.
 */
class QuickStartViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val app = application as ZeroClawApplication

    val topSlashCommands: StateFlow<List<String>> =
        app.terminalEntryRepository.entries
            .map { entries -> buildTopSlashCommands(entries) }
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
                initialValue = fallbackCommands(),
            )

    val recentAgents: StateFlow<List<Agent>> =
        combine(
            app.conversationHistoryRepository.entries,
            app.agentRepository.agents,
        ) { historyEntries, agents ->
            buildRecentAgents(historyEntries, agents)
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = emptyList(),
        )

    private fun buildTopSlashCommands(entries: List<TerminalEntry>): List<String> {
        val counts =
            entries
                .asSequence()
                .filter { entry -> entry.entryType == "input" }
                .mapNotNull { entry -> CommandRegistry.identifyCommand(entry.content)?.name }
                .groupingBy { it }
                .eachCount()

        if (counts.isEmpty()) {
            return fallbackCommands()
        }

        val commandOrder =
            CommandRegistry.commands
                .mapIndexed { index, command -> command.name to index }
                .toMap()

        return counts
            .entries
            .sortedWith(
                compareByDescending<Map.Entry<String, Int>> { it.value }
                    .thenBy { commandOrder[it.key] ?: Int.MAX_VALUE },
            ).take(COMMAND_LIMIT)
            .map { "/${it.key}" }
    }

    private fun buildRecentAgents(
        historyEntries: List<ConversationEntry>,
        agents: List<Agent>,
    ): List<Agent> {
        val agentByName = agents.associateBy { it.name }

        return historyEntries
            .asSequence()
            .filter { entry -> entry.agentName != TERMINAL_AGENT_NAME }
            .sortedByDescending { it.timestamp }
            .mapNotNull { entry -> agentByName[entry.agentName] }
            .distinctBy { agent -> agent.id }
            .take(AGENT_LIMIT)
            .toList()
    }

    private fun fallbackCommands(): List<String> =
        CommandRegistry.commands
            .take(COMMAND_LIMIT)
            .map { command -> "/${command.name}" }
}
