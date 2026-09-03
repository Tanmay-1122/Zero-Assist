/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zeroclaw.android.model.AskUserRequest
import com.zeroclaw.android.model.AgentEscalation
import com.zeroclaw.android.model.AgentSwarm
import com.zeroclaw.android.model.LlmTask
import com.zeroclaw.android.model.ProjectIntelligence
import com.zeroclaw.android.model.AgentToolTrace
import com.zeroclaw.android.repository.AgentToolsRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * ViewModel for managing agent tool UI state.
 *
 * Exposes StateFlows for ask_user, escalate, swarm, llm_task, and project_intel.
 */
class AgentToolsViewModel(
    private val agentToolsRepository: AgentToolsRepository,
) : ViewModel() {

    // ==================== State Flows ====================

    private val _pendingUserAsks = MutableStateFlow<List<AskUserRequest>>(emptyList())
    val pendingUserAsks: StateFlow<List<AskUserRequest>> = _pendingUserAsks.asStateFlow()

    private val _pendingEscalations = MutableStateFlow<List<AgentEscalation>>(emptyList())
    val pendingEscalations: StateFlow<List<AgentEscalation>> = _pendingEscalations.asStateFlow()

    private val _activeSwarms = MutableStateFlow<List<AgentSwarm>>(emptyList())
    val activeSwarms: StateFlow<List<AgentSwarm>> = _activeSwarms.asStateFlow()

    private val _pendingLlmTasks = MutableStateFlow<List<LlmTask>>(emptyList())
    val pendingLlmTasks: StateFlow<List<LlmTask>> = _pendingLlmTasks.asStateFlow()

    private val _accessibleIntelligence = MutableStateFlow<List<ProjectIntelligence>>(emptyList())
    val accessibleIntelligence: StateFlow<List<ProjectIntelligence>> = _accessibleIntelligence.asStateFlow()

    private val _toolStats = MutableStateFlow<Map<String, Any>>(emptyMap())
    val toolStats: StateFlow<Map<String, Any>> = _toolStats.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    // ==================== Initialization ====================

    init {
        // Load initial data
        loadAllAgentTools("default")
    }

    // ==================== Loading & Refresh ====================

    fun loadAllAgentTools(workspaceId: String) {
        viewModelScope.launch {
            _isLoading.update { true }
            try {
                // Observe pending asks
                agentToolsRepository.observePendingUserAsks(workspaceId).collect { asks ->
                    _pendingUserAsks.update { asks }
                }
            } catch (e: Exception) {
                _errorMessage.update { e.message }
            } finally {
                _isLoading.update { false }
            }
        }

        viewModelScope.launch {
            _isLoading.update { true }
            try {
                // Observe pending escalations
                agentToolsRepository.observePendingEscalations(workspaceId).collect { escalations ->
                    _pendingEscalations.update { escalations }
                }
            } catch (e: Exception) {
                _errorMessage.update { e.message }
            }
        }

        viewModelScope.launch {
            _isLoading.update { true }
            try {
                // Observe active swarms
                agentToolsRepository.observeActiveSwarms(workspaceId).collect { swarms ->
                    _activeSwarms.update { swarms }
                }
            } catch (e: Exception) {
                _errorMessage.update { e.message }
            }
        }

        viewModelScope.launch {
            _isLoading.update { true }
            try {
                // Observe pending tasks
                agentToolsRepository.observePendingTasks(workspaceId).collect { tasks ->
                    _pendingLlmTasks.update { tasks }
                }
            } catch (e: Exception) {
                _errorMessage.update { e.message }
            }
        }

        viewModelScope.launch {
            _isLoading.update { true }
            try {
                // Observe accessible intelligence
                agentToolsRepository.observeAccessibleIntelligence(workspaceId).collect { intel ->
                    _accessibleIntelligence.update { intel }
                }
            } catch (e: Exception) {
                _errorMessage.update { e.message }
            }
        }
    }

    fun clearError() {
        _errorMessage.update { null }
    }

    // ==================== ask_user ====================

    fun askUser(
        agentId: String,
        question: String,
        questionType: String = "text",
        choices: List<String> = emptyList(),
        workspaceId: String = "default",
    ) {
        viewModelScope.launch {
            try {
                agentToolsRepository.askUser(agentId, question, questionType, choices, workspaceId)
                _errorMessage.update { null }
            } catch (e: Exception) {
                _errorMessage.update { "Failed to create user ask: ${e.message}" }
            }
        }
    }

    fun respondToAsk(requestId: String, response: String) {
        viewModelScope.launch {
            try {
                agentToolsRepository.respondToAsk(requestId, response)
                _errorMessage.update { null }
            } catch (e: Exception) {
                _errorMessage.update { "Failed to respond to ask: ${e.message}" }
            }
        }
    }

    // ==================== escalate ====================

    fun escalateToHuman(
        agentId: String,
        escalationType: String,
        description: String,
        targetRole: String? = null,
        priority: String = "normal",
        workspaceId: String = "default",
    ) {
        viewModelScope.launch {
            try {
                agentToolsRepository.escalateToHuman(
                    agentId,
                    escalationType,
                    description,
                    targetRole,
                    priority,
                    workspaceId
                )
                _errorMessage.update { null }
            } catch (e: Exception) {
                _errorMessage.update { "Failed to escalate: ${e.message}" }
            }
        }
    }

    fun resolveEscalation(escalationId: String, status: String, resolution: String) {
        viewModelScope.launch {
            try {
                agentToolsRepository.resolveEscalation(escalationId, status, resolution)
                _errorMessage.update { null }
            } catch (e: Exception) {
                _errorMessage.update { "Failed to resolve escalation: ${e.message}" }
            }
        }
    }

    // ==================== swarm ====================

    fun createSwarm(
        name: String,
        agentIds: List<String>,
        coordinatorAgentId: String,
        strategy: String = "sequential",
        workspaceId: String = "default",
    ) {
        viewModelScope.launch {
            try {
                agentToolsRepository.createSwarm(name, agentIds, coordinatorAgentId, strategy, workspaceId)
                _errorMessage.update { null }
            } catch (e: Exception) {
                _errorMessage.update { "Failed to create swarm: ${e.message}" }
            }
        }
    }

    fun deleteSwarm(swarmId: String) {
        viewModelScope.launch {
            try {
                agentToolsRepository.deleteSwarm(swarmId)
                _errorMessage.update { null }
            } catch (e: Exception) {
                _errorMessage.update { "Failed to delete swarm: ${e.message}" }
            }
        }
    }

    fun loadSwarmsByCoordinator(agentId: String, workspaceId: String = "default") {
        viewModelScope.launch {
            try {
                val swarms = agentToolsRepository.getSwarmsByCoordinator(agentId, workspaceId)
                _activeSwarms.update { swarms }
                _errorMessage.update { null }
            } catch (e: Exception) {
                _errorMessage.update { "Failed to load swarms: ${e.message}" }
            }
        }
    }

    // ==================== llm_task ====================

    fun createLlmTask(
        agentId: String,
        taskName: String,
        instructions: String,
        context: String? = null,
        targetModel: String = "gpt4",
        workspaceId: String = "default",
    ) {
        viewModelScope.launch {
            try {
                agentToolsRepository.createLlmTask(agentId, taskName, instructions, context, targetModel, workspaceId)
                _errorMessage.update { null }
            } catch (e: Exception) {
                _errorMessage.update { "Failed to create LLM task: ${e.message}" }
            }
        }
    }

    fun completeLlmTask(taskId: String, status: String, result: String, tokensUsed: Int) {
        viewModelScope.launch {
            try {
                agentToolsRepository.completeLlmTask(taskId, status, result, tokensUsed)
                _errorMessage.update { null }
            } catch (e: Exception) {
                _errorMessage.update { "Failed to complete LLM task: ${e.message}" }
            }
        }
    }

    fun loadTokenUsage(workspaceId: String) {
        viewModelScope.launch {
            try {
                val totalTokens = agentToolsRepository.getTotalTokensUsed(workspaceId)
                _toolStats.update { it + ("totalTokens" to totalTokens) }
                _errorMessage.update { null }
            } catch (e: Exception) {
                _errorMessage.update { "Failed to load token usage: ${e.message}" }
            }
        }
    }

    // ==================== project_intel ====================

    fun shareIntelligence(
        sourceWorkspaceId: String,
        targetWorkspaceId: String,
        topicName: String,
        contentSummary: String,
        contentFull: String,
        accessLevel: String = "workspace",
        workspaceId: String = "default",
    ) {
        viewModelScope.launch {
            try {
                agentToolsRepository.shareIntelligence(
                    sourceWorkspaceId,
                    targetWorkspaceId,
                    topicName,
                    contentSummary,
                    contentFull,
                    accessLevel,
                    workspaceId
                )
                _errorMessage.update { null }
            } catch (e: Exception) {
                _errorMessage.update { "Failed to share intelligence: ${e.message}" }
            }
        }
    }

    fun searchIntelligenceByTopic(workspaceId: String, topic: String) {
        viewModelScope.launch {
            try {
                val results = agentToolsRepository.searchIntelligenceByTopic(workspaceId, topic)
                _accessibleIntelligence.update { results }
                _errorMessage.update { null }
            } catch (e: Exception) {
                _errorMessage.update { "Failed to search intelligence: ${e.message}" }
            }
        }
    }

    fun recordIntelligenceAccess(intelId: String) {
        viewModelScope.launch {
            try {
                agentToolsRepository.recordIntelligenceAccess(intelId)
            } catch (e: Exception) {
                _errorMessage.update { "Failed to record access: ${e.message}" }
            }
        }
    }

    // ==================== Statistics ====================

    fun loadToolStats(agentId: String) {
        viewModelScope.launch {
            try {
                val stats = agentToolsRepository.getAgentToolStats(agentId)
                _toolStats.update { stats }
                _errorMessage.update { null }
            } catch (e: Exception) {
                _errorMessage.update { "Failed to load tool stats: ${e.message}" }
            }
        }
    }

    fun loadFailedToolCalls(toolName: String) {
        viewModelScope.launch {
            try {
                val failed = agentToolsRepository.getFailedToolCalls(toolName)
                // Store failed calls in a separate flow if needed
                _errorMessage.update { null }
            } catch (e: Exception) {
                _errorMessage.update { "Failed to load failed calls: ${e.message}" }
            }
        }
    }
}
