/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.agents

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zeroclaw.android.ZeroClawApplication
import com.zeroclaw.android.model.Agent
import com.zeroclaw.android.model.AgentRole
import com.zeroclaw.android.model.AgentTemplate
import com.zeroclaw.android.model.ThinkingLevel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Data class representing the current draft state of agent being created/edited.
 *
 * @property id Unique identifier (generated on creation).
 * @property name Agent display name.
 * @property avatar Emoji or URI string.
 * @property role Agent role from AgentRole enum.
 * @property provider AI provider ID.
 * @property modelName Selected model name.
 * @property systemPrompt System prompt text.
 * @property useGlobalTemperature Whether to use global default temperature.
 * @property temperature Per-agent temperature (0.0-2.0).
 * @property maxDepth Maximum reasoning depth (1-20).
 * @property thinkingLevel Default thinking level.
 * @property tags List of agent tags.
 * @property isMaster Whether this agent is the master orchestrator.
 * @property priority Agent priority for routing (0-10).
 * @property isEnabled Whether agent is active.
 * @property selectedConnectionId API key/connection ID.
 */
data class AgentDraftState(
    val id: String = "",
    val name: String = "",
    val avatar: String = "",
    val role: AgentRole = AgentRole.GENERAL,
    val provider: String = "",
    val modelName: String = "",
    val systemPrompt: String = "",
    val useGlobalTemperature: Boolean = true,
    val temperature: Float = 0.7f,
    val maxDepth: Int = Agent.DEFAULT_MAX_DEPTH,
    val thinkingLevel: ThinkingLevel = ThinkingLevel.DEFAULT,
    val tags: List<String> = emptyList(),
    val isMaster: Boolean = false,
    val priority: Int = 0,
    val isEnabled: Boolean = true,
    val selectedConnectionId: String? = null,
)

/**
 * Enum representing different fields in the agent draft.
 */
enum class AgentField {
    NAME,
    AVATAR,
    ROLE,
    PROVIDER,
    MODEL_NAME,
    SYSTEM_PROMPT,
    USE_GLOBAL_TEMPERATURE,
    TEMPERATURE,
    MAX_DEPTH,
    THINKING_LEVEL,
    TAGS,
    IS_MASTER,
    PRIORITY,
    IS_ENABLED,
    CONNECTION_ID,
}

/**
 * Result of save operation.
 */
sealed class SaveResult {
    data object Success : SaveResult()
    data class Error(val message: String) : SaveResult()
}

/**
 * ViewModel for the agent creation/editing flow.
 *
 * Manages agent draft state, template selection, and persistence.
 *
 * @param application Application context for accessing repositories.
 */
class AddAgentViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val app = application as ZeroClawApplication
    private val repository = app.agentRepository
    private val apiKeyRepository = app.apiKeyRepository

    private val _selectedTemplate = MutableStateFlow<AgentTemplate?>(null)
    /** The currently selected template, if any. */
    val selectedTemplate: StateFlow<AgentTemplate?> = _selectedTemplate.asStateFlow()

    private val _agentDraft = MutableStateFlow(AgentDraftState())
    /** The current draft state of the agent being created. */
    val agentDraft: StateFlow<AgentDraftState> = _agentDraft.asStateFlow()

    /** All stored API keys, observed reactively. */
    val apiKeys: StateFlow<List<com.zeroclaw.android.model.ApiKey>> =
        apiKeyRepository.keys.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            emptyList(),
        )

    /**
     * Selects a template and pre-fills the agent draft with template defaults.
     *
     * @param template The template to select, or null to clear.
     */
    fun selectTemplate(template: AgentTemplate?) {
        _selectedTemplate.value = template
        if (template != null) {
            _agentDraft.value = _agentDraft.value.copy(
                name = template.name,
                role = template.role,
                avatar = template.avatar,
                systemPrompt = template.defaultSystemPrompt,
                temperature = template.defaultTemperature,
                maxDepth = template.defaultMaxDepth,
                tags = template.tags.toList(),
                useGlobalTemperature = false,
            )
        }
    }

    /**
     * Updates a single field in the agent draft.
     *
     * @param field The field to update.
     * @param value The new value.
     */
    fun updateField(field: AgentField, value: Any) {
        val current = _agentDraft.value
        _agentDraft.value = when (field) {
            AgentField.NAME -> current.copy(name = value as String)
            AgentField.AVATAR -> current.copy(avatar = value as String)
            AgentField.ROLE -> current.copy(role = value as AgentRole)
            AgentField.PROVIDER -> current.copy(provider = value as String)
            AgentField.MODEL_NAME -> current.copy(modelName = value as String)
            AgentField.SYSTEM_PROMPT -> current.copy(systemPrompt = value as String)
            AgentField.USE_GLOBAL_TEMPERATURE -> current.copy(useGlobalTemperature = value as Boolean)
            AgentField.TEMPERATURE -> current.copy(temperature = value as Float)
            AgentField.MAX_DEPTH -> current.copy(maxDepth = value as Int)
            AgentField.THINKING_LEVEL -> current.copy(thinkingLevel = value as ThinkingLevel)
            AgentField.TAGS -> current.copy(tags = (value as List<*>).filterIsInstance<String>())
            AgentField.IS_MASTER -> current.copy(isMaster = value as Boolean)
            AgentField.PRIORITY -> current.copy(priority = value as Int)
            AgentField.IS_ENABLED -> current.copy(isEnabled = value as Boolean)
            AgentField.CONNECTION_ID -> current.copy(selectedConnectionId = value as? String)
        }
    }

    /**
     * Adds a tag to the agent draft.
     *
     * @param tag The tag to add.
     */
    fun addTag(tag: String) {
        if (tag.isBlank()) return
        val current = _agentDraft.value
        if (!current.tags.contains(tag)) {
            _agentDraft.value = current.copy(tags = current.tags + tag)
        }
    }

    /**
     * Removes a tag from the agent draft.
     *
     * @param tag The tag to remove.
     */
    fun removeTag(tag: String) {
        val current = _agentDraft.value
        _agentDraft.value = current.copy(tags = current.tags - tag)
    }

    /**
     * Saves the agent to the database.
     *
     * If the agent is being set as Master, unsets any existing master first.
     *
     * @return Flow emitting the save result.
     */
    fun saveAgent(): Flow<SaveResult> = kotlinx.coroutines.flow.flow {
        try {
            val draft = _agentDraft.value

            // Validate required fields
            if (draft.name.isBlank()) {
                emit(SaveResult.Error("Agent name is required"))
                return@flow
            }
            if (draft.provider.isBlank()) {
                emit(SaveResult.Error("Provider is required"))
                return@flow
            }
            if (draft.modelName.isBlank()) {
                emit(SaveResult.Error("Model name is required"))
                return@flow
            }
            if (draft.maxDepth < 1 || draft.maxDepth > 20) {
                emit(SaveResult.Error("Max depth must be between 1 and 20"))
                return@flow
            }

            // Create the Agent model
            val agent = Agent(
                id = draft.id.ifBlank { java.util.UUID.randomUUID().toString() },
                name = draft.name,
                provider = draft.provider,
                modelName = draft.modelName,
                isEnabled = draft.isEnabled,
                systemPrompt = draft.systemPrompt,
                temperature = if (draft.useGlobalTemperature) null else draft.temperature,
                maxDepth = draft.maxDepth,
                thinkingLevel = draft.thinkingLevel,
                role = draft.role,
                avatar = draft.avatar,
                tags = draft.tags,
                isMaster = draft.isMaster,
                priority = draft.priority,
                templateId = _selectedTemplate.value?.id,
                accentColor = _selectedTemplate.value?.accentColor ?: 0xFF6200EE,
            )

            // Save agent
            repository.save(agent)
            emit(SaveResult.Success)
        } catch (e: Exception) {
            emit(SaveResult.Error(e.message ?: "Unknown error"))
        }
    }

    /**
     * Clears the Master flag if isMaster is currently true.
     * Called before saving to ensure data consistency.
     */
    fun clearMasterFlag() {
        val current = _agentDraft.value
        if (current.isMaster) {
            _agentDraft.value = current.copy(isMaster = false)
        }
    }
}
