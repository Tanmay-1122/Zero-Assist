/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.repository

import com.zeroclaw.android.data.local.dao.AgentChatMessageDao
import com.zeroclaw.android.data.local.mapper.toEntities
import com.zeroclaw.android.data.local.mapper.toModel
import com.zeroclaw.android.data.local.mapper.toModels
import com.zeroclaw.android.model.AgentChatMessage
import com.zeroclaw.android.runtime.BlockRuntime
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * End-to-end repository managing rich conversation persistence and runtime restoration.
 */
class RichConversationRepository(
    private val agentChatDao: AgentChatMessageDao,
) {
    /**
     * Observes rich messages for a family/group conversation session.
     */
    fun getMessagesFlow(familyId: String): Flow<List<AgentChatMessage>> {
        return agentChatDao.observeMessagesForFamily(familyId).map { entities ->
            entities.toModels()
        }
    }

    /**
     * Saves a list of rich messages with serialized [ContentBlock] trees to Room.
     */
    suspend fun saveMessages(familyId: String, messages: List<AgentChatMessage>) {
        agentChatDao.insertAll(messages.toEntities(familyId))
    }

    /**
     * Restores state of a [BlockRuntime] from stored message content blocks.
     */
    suspend fun restoreRuntime(runtime: BlockRuntime, messageId: String) {
        val entity = agentChatDao.getMessageById(messageId)
        if (entity != null) {
            runtime.initialize(entity.toModel().effectiveBlocks)
        }
    }
}
