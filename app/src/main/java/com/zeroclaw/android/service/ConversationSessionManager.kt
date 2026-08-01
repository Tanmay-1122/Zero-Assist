/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import androidx.room.withTransaction
import com.zeroclaw.android.data.local.ZeroClawDatabase
import com.zeroclaw.android.data.local.entity.AgentChatMessageEntity
import com.zeroclaw.android.data.local.entity.AgentFamilyEntity
import com.zeroclaw.android.data.local.entity.ConversationEntity
import com.zeroclaw.android.data.local.mapper.toModel
import com.zeroclaw.android.data.local.mapper.toModels
import com.zeroclaw.android.data.local.mapper.toEntity
import com.zeroclaw.android.data.repository.ActiveConversationSessionRepository
import com.zeroclaw.android.data.repository.AgentRepository
import com.zeroclaw.android.data.repository.WorkspaceRepository
import com.zeroclaw.android.data.repository.buildConversationPreview
import com.zeroclaw.android.model.Agent
import com.zeroclaw.android.model.AgentChatMessage
import com.zeroclaw.android.model.AgentMessageType
import com.zeroclaw.android.model.AgentRole
import com.zeroclaw.ffi.FfiException
import java.util.Collections
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private const val ACTIVE_FAMILY_SENTINEL = "Active"
private const val DEFAULT_WORKSPACE_NAME = "Default Workspace"
private const val DEFAULT_CONVERSATION_NAME = "Agent Group Chat"
private const val DEFAULT_AGENT_NAME = "Master"
private const val TITLE_MESSAGE_LIMIT = 3
private const val TITLE_SOURCE_MAX_CHARS = 160
private const val TITLE_MAX_CHARS = 48
private const val TITLE_PROMPT =
    "Summarize this conversation in 4 words or less. Reply with only the title text."

/**
 * Owns persisted agent-chat sessions, metadata finalization, and title generation.
 */
class ConversationSessionManager(
    private val database: ZeroClawDatabase,
    private val workspaceRepository: WorkspaceRepository,
    private val agentRepository: AgentRepository,
    private val activeConversationSessionRepository: ActiveConversationSessionRepository,
    private val daemonBridge: DaemonServiceBridge,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    private val messageDao = database.agentChatMessageDao()
    private val familyDao = database.agentFamilyDao()
    private val conversationDao = database.conversationDao()
    private val titleJobs = Collections.synchronizedSet(mutableSetOf<String>())

    /**
     * Resolves the requested family ID into a concrete conversation and marks it active.
     */
    suspend fun resolveRequestedFamilyId(requestedFamilyId: String): String {
        if (requestedFamilyId == ACTIVE_FAMILY_SENTINEL) {
            val activeFamilyId = activeConversationSessionRepository.activeFamilyId.first()
            if (!activeFamilyId.isNullOrBlank()) {
                ensureConversationSession(activeFamilyId)
                return activeFamilyId
            }
            return createNewSession()
        }

        ensureConversationSession(requestedFamilyId)
        activeConversationSessionRepository.setActiveFamilyId(requestedFamilyId)
        return requestedFamilyId
    }

    /**
     * Creates and activates a brand-new persisted session.
     */
    suspend fun createNewSession(): String {
        val familyId = UUID.randomUUID().toString()
        seedConversationSession(familyId)
        activeConversationSessionRepository.setActiveFamilyId(familyId)
        return familyId
    }

    /**
     * Finalizes the current session, then creates and activates a fresh one.
     */
    suspend fun startNewSession(currentFamilyId: String?): String {
        if (!currentFamilyId.isNullOrBlank()) {
            finalizeConversation(
                familyId = currentFamilyId,
                reason = "new_chat",
                clearActiveMarker = true,
            )
        } else {
            activeConversationSessionRepository.clearActiveFamilyId()
        }
        return createNewSession()
    }

    /**
     * Finalizes the currently active conversation without changing the active marker by default.
     */
    suspend fun finalizeActiveConversation(
        reason: String,
        clearActiveMarker: Boolean = false,
    ) {
        val activeFamilyId = activeConversationSessionRepository.activeFamilyId.first() ?: return
        finalizeConversation(
            familyId = activeFamilyId,
            reason = reason,
            clearActiveMarker = clearActiveMarker,
        )
    }

    /**
     * Archives any stale active session that survived a previous process death.
     */
    suspend fun archiveRecoveredSessionIfNeeded() {
        val recoveredFamilyId = activeConversationSessionRepository.activeFamilyId.first() ?: return
        finalizeConversation(
            familyId = recoveredFamilyId,
            reason = "cold_launch_recovery",
            clearActiveMarker = true,
        )
    }

    /**
     * Observe all persisted messages for one family.
     */
    fun observeMessagesForFamily(familyId: String): Flow<List<AgentChatMessage>> =
        messageDao.observeMessagesForFamily(familyId).map { entities -> entities.toModels() }

    /**
     * Get the current message snapshot for one family.
     */
    suspend fun getMessagesForFamily(familyId: String): List<AgentChatMessage> =
        withContext(ioDispatcher) {
            messageDao.getMessagesForFamily(familyId).toModels()
        }

    /**
     * Persist one message and refresh the conversation metadata.
     */
    suspend fun persistMessage(
        familyId: String,
        message: AgentChatMessage,
    ) {
        ensureConversationSession(familyId)
        withContext(ioDispatcher) {
            database.withTransaction {
                messageDao.insert(message.toEntity(familyId))
                syncConversationMetadataLocked(familyId)
            }
        }
    }

    /**
     * Persist multiple messages and refresh the conversation metadata once.
     */
    suspend fun persistMessages(
        familyId: String,
        messages: List<AgentChatMessage>,
    ) {
        if (messages.isEmpty()) return
        ensureConversationSession(familyId)
        withContext(ioDispatcher) {
            database.withTransaction {
                messageDao.insertAll(messages.map { message -> message.toEntity(familyId) })
                syncConversationMetadataLocked(familyId)
            }
        }
    }

    /**
     * Update one message via the persisted copy in Room.
     */
    suspend fun updateMessage(
        familyId: String,
        messageId: String,
        update: (AgentChatMessage) -> AgentChatMessage,
    ) {
        ensureConversationSession(familyId)
        withContext(ioDispatcher) {
            val existingEntity = messageDao.getMessageById(messageId) ?: return@withContext
            database.withTransaction {
                messageDao.insert(update(existingEntity.toModel()).toEntity(familyId))
                syncConversationMetadataLocked(familyId)
            }
        }
    }

    /**
     * Delete all messages for one family.
     */
    suspend fun clearMessages(familyId: String) {
        withContext(ioDispatcher) {
            database.withTransaction {
                messageDao.deleteAllForFamily(familyId)
                syncConversationMetadataLocked(familyId)
            }
        }
    }

    /**
     * Rebuilds the conversation engine's per-agent histories from persisted chat messages.
     *
     * This is an approximation because only visible chat messages are stored.
     */
    fun rebuildConversationEngineHistories(
        conversationEngine: AgentConversationEngine,
        agents: List<Agent>,
        messages: List<AgentChatMessage>,
    ) {
        val historiesByAgent = linkedMapOf<String, MutableList<ConversationMessage>>()
        val agentsById = agents.associateBy { agent -> agent.id }

        agents.forEach { agent ->
            historiesByAgent[agent.id] = mutableListOf()
        }

        for (message in messages) {
            when {
                message.messageType == AgentMessageType.SYSTEM_EVENT ||
                    message.messageType == AgentMessageType.ON_DEVICE_RESULT -> Unit
                message.senderId == USER_ID -> {
                    val targetAgentId = message.targetAgentId ?: continue
                    historiesByAgent.getOrPut(targetAgentId) { mutableListOf() }
                        .add(ConversationMessage(role = "user", content = message.content))
                }

                message.messageType == AgentMessageType.TASK_ASSIGNMENT && message.targetAgentId != null -> {
                    historiesByAgent.getOrPut(message.targetAgentId) { mutableListOf() }
                        .add(ConversationMessage(role = "user", content = message.content))
                }

                message.senderId != SYSTEM_ID -> {
                    historiesByAgent.getOrPut(message.senderId) { mutableListOf() }
                        .add(ConversationMessage(role = "assistant", content = message.content))
                }
            }
        }

        agents.forEach { agent ->
            conversationEngine.replaceHistory(
                agent = agent,
                messages = historiesByAgent[agent.id].orEmpty(),
            )
        }
        historiesByAgent.keys
            .filter { agentId -> agentId !in agentsById }
            .forEach(conversationEngine::clearHistory)
    }

    private suspend fun finalizeConversation(
        familyId: String,
        reason: String,
        clearActiveMarker: Boolean,
    ) {
        ensureConversationSession(familyId)
        val metadata = withContext(ioDispatcher) {
            database.withTransaction {
                syncConversationMetadataLocked(familyId)
            }
            conversationDao.getConversation(familyId)
        } ?: run {
            if (clearActiveMarker) {
                activeConversationSessionRepository.clearActiveFamilyId()
            }
            return
        }

        maybeGenerateTitle(metadata, reason)

        if (clearActiveMarker) {
            val activeFamilyId = activeConversationSessionRepository.activeFamilyId.first()
            if (activeFamilyId == familyId) {
                activeConversationSessionRepository.clearActiveFamilyId()
            }
        }
    }

    private suspend fun ensureConversationSession(familyId: String) {
        val existingConversation = withContext(ioDispatcher) {
            conversationDao.getConversation(familyId)
        }
        if (existingConversation != null) {
            activeConversationSessionRepository.setActiveFamilyId(familyId)
            return
        }
        seedConversationSession(familyId)
        activeConversationSessionRepository.setActiveFamilyId(familyId)
    }

    private suspend fun seedConversationSession(familyId: String) {
        val now = System.currentTimeMillis()
        val workspaceName = workspaceRepository.activeWorkspace.first()?.name ?: DEFAULT_WORKSPACE_NAME
        val activeAgents = agentRepository.activeAgentsByPriority.first()
        val familyEntity =
            AgentFamilyEntity(
                id = familyId,
                name = DEFAULT_CONVERSATION_NAME,
                agentIdsJson = Json.encodeToString(activeAgents.map(Agent::id)),
                createdAt = now,
                lastMessageTime = now,
            )
        val conversationEntity =
            ConversationEntity(
                id = familyId,
                workspaceName = workspaceName,
                primaryAgentName = derivePrimaryAgentName(activeAgents),
                preview = "",
                title = null,
                isTitlePending = true,
                createdAt = now,
                lastMessageAt = now,
            )

        withContext(ioDispatcher) {
            database.withTransaction {
                familyDao.insert(familyEntity)
                conversationDao.upsert(conversationEntity)
            }
        }
    }

    private suspend fun syncConversationMetadataLocked(familyId: String): ConversationEntity {
        val existingConversation = conversationDao.getConversation(familyId)
        val messages = messageDao.getMessagesForFamily(familyId)
        val now = System.currentTimeMillis()
        val resolvedWorkspaceName =
            existingConversation?.workspaceName
                ?: workspaceRepository.activeWorkspace.first()?.name
                ?: DEFAULT_WORKSPACE_NAME
        val preview = derivePreview(existingConversation?.preview.orEmpty(), messages)
        val primaryAgentName = derivePrimaryAgentName(existingConversation, messages)
        val lastMessageAt = messages.lastOrNull()?.timestamp ?: existingConversation?.lastMessageAt ?: now
        val createdAt = existingConversation?.createdAt ?: messages.firstOrNull()?.timestamp ?: now
        val conversationEntity =
            ConversationEntity(
                id = familyId,
                workspaceName = resolvedWorkspaceName,
                primaryAgentName = primaryAgentName,
                preview = preview,
                title = existingConversation?.title,
                isTitlePending = existingConversation?.isTitlePending ?: true,
                createdAt = createdAt,
                lastMessageAt = lastMessageAt,
            )
        conversationDao.upsert(conversationEntity)
        familyDao.insert(
            AgentFamilyEntity(
                id = familyId,
                name = DEFAULT_CONVERSATION_NAME,
                agentIdsJson = existingFamilyAgentIdsJson(messages),
                createdAt = createdAt,
                lastMessageTime = lastMessageAt,
            ),
        )
        return conversationEntity
    }

    private fun derivePreview(
        existingPreview: String,
        messages: List<AgentChatMessageEntity>,
    ): String {
        val firstVisibleMessage =
            messages.firstOrNull { message ->
                message.messageTypeEnum != AgentMessageType.SYSTEM_EVENT.name &&
                    message.content.isNotBlank()
            }?.content

        return when {
            !firstVisibleMessage.isNullOrBlank() -> buildConversationPreview(firstVisibleMessage)
            existingPreview.isNotBlank() -> existingPreview
            else -> ""
        }
    }

    private fun derivePrimaryAgentName(
        existingConversation: ConversationEntity?,
        messages: List<AgentChatMessageEntity>,
    ): String =
        messages.firstOrNull { message ->
            message.senderId != USER_ID &&
                message.senderId != SYSTEM_ID &&
                message.messageTypeEnum != AgentMessageType.ON_DEVICE_RESULT.name
        }?.senderName
            ?: existingConversation?.primaryAgentName
            ?: DEFAULT_AGENT_NAME

    private fun derivePrimaryAgentName(activeAgents: List<Agent>): String =
        activeAgents.firstOrNull { agent -> agent.isMaster || agent.role == AgentRole.MASTER }?.name
            ?: activeAgents.firstOrNull()?.name
            ?: DEFAULT_AGENT_NAME

    private fun existingFamilyAgentIdsJson(messages: List<AgentChatMessageEntity>): String =
        Json.encodeToString(
            messages
                .asSequence()
                .filter { message ->
                    message.senderId != USER_ID &&
                        message.senderId != SYSTEM_ID &&
                        message.messageTypeEnum != AgentMessageType.ON_DEVICE_RESULT.name
                }
                .map(AgentChatMessageEntity::senderId)
                .distinct()
                .toList(),
        )

    private suspend fun maybeGenerateTitle(
        conversation: ConversationEntity,
        reason: String,
    ) {
        if (!conversation.isTitlePending || !conversation.title.isNullOrBlank()) {
            return
        }
        if (!titleJobs.add(conversation.id)) {
            return
        }

        try {
            val sourceMessages =
                withContext(ioDispatcher) {
                    messageDao
                        .getMessagesForFamily(conversation.id)
                        .filter { entity ->
                            entity.messageTypeEnum != AgentMessageType.SYSTEM_EVENT.name &&
                                entity.content.isNotBlank()
                        }.take(TITLE_MESSAGE_LIMIT)
                }
            val fallbackTitle = fallbackTitleFor(conversation.preview)
            val resolvedTitle =
                runCatching {
                    if (sourceMessages.isEmpty()) {
                        fallbackTitle
                    } else {
                        requestGeneratedTitle(sourceMessages)
                    }
                }.getOrElse { error ->
                    if (error !is FfiException) {
                        error
                    }
                    fallbackTitle
                }.ifBlank { fallbackTitle }

            withContext(ioDispatcher) {
                val latestConversation = conversationDao.getConversation(conversation.id) ?: return@withContext
                conversationDao.upsert(
                    latestConversation.copy(
                        title = resolvedTitle,
                        isTitlePending = false,
                    ),
                )
            }
        } finally {
            titleJobs.remove(conversation.id)
        }
    }

    private suspend fun requestGeneratedTitle(messages: List<AgentChatMessageEntity>): String {
        val prompt =
            buildString {
                appendLine(TITLE_PROMPT)
                appendLine()
                messages.forEach { message ->
                    val speaker =
                        when (message.senderId) {
                            USER_ID -> "User"
                            SYSTEM_ID -> "System"
                            else -> message.senderName
                        }
                    appendLine("$speaker: ${message.content.take(TITLE_SOURCE_MAX_CHARS)}")
                }
            }
        return sanitizeTitle(daemonBridge.send(prompt))
    }

    private fun sanitizeTitle(rawTitle: String): String {
        val singleLine = rawTitle.lineSequence().firstOrNull().orEmpty().trim()
        val withoutQuotes = singleLine.trim('"', '\'', '`')
        val collapsed = withoutQuotes.replace(Regex("\\s+"), " ").trim()
        if (collapsed.length <= TITLE_MAX_CHARS) {
            return collapsed
        }
        return collapsed.take(TITLE_MAX_CHARS).trimEnd()
    }

    private fun fallbackTitleFor(preview: String): String {
        val cleanPreview = preview.trim()
        if (cleanPreview.isBlank()) {
            return DEFAULT_CONVERSATION_NAME
        }
        if (cleanPreview.length <= TITLE_MAX_CHARS) {
            return cleanPreview
        }
        return cleanPreview.take(TITLE_MAX_CHARS).trimEnd() + "..."
    }

    private companion object {
        private const val USER_ID = "user"
        private const val SYSTEM_ID = "system"
    }
}
