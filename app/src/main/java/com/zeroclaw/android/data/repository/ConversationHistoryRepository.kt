/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.repository

import com.zeroclaw.android.data.local.dao.ConversationDao
import com.zeroclaw.android.data.local.entity.ConversationEntity
import com.zeroclaw.android.model.ConversationEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private const val PREVIEW_MAX_CHARS = 40

/**
 * Repository for persisted agent conversation history entries shown in the drawer.
 */
interface ConversationHistoryRepository {
    /** All visible conversations ordered by most recent activity. */
    val entries: Flow<List<ConversationEntry>>

    /**
     * Observe a single conversation metadata entry.
     *
     * @param conversationId Stable conversation/family identifier.
     */
    fun observeEntry(conversationId: String): Flow<ConversationEntry?>
}

/**
 * Room-backed implementation of [ConversationHistoryRepository].
 */
class RoomConversationHistoryRepository(
    private val conversationDao: ConversationDao,
) : ConversationHistoryRepository {
    override val entries: Flow<List<ConversationEntry>> =
        conversationDao.observeVisibleConversations().map { entities ->
            entities.map(ConversationEntity::toModel)
        }

    override fun observeEntry(conversationId: String): Flow<ConversationEntry?> =
        conversationDao.observeConversation(conversationId).map { entity ->
            entity?.toModel()
        }
}

internal fun ConversationEntity.toModel(): ConversationEntry =
    ConversationEntry(
        id = id,
        workspaceName = workspaceName,
        title = title,
        isTitlePending = isTitlePending,
        preview = preview,
        timestamp = lastMessageAt,
        agentName = primaryAgentName,
        isStarred = false,
    )

internal fun buildConversationPreview(text: String): String {
    val collapsed = text.trim().replace(Regex("\\s+"), " ")
    if (collapsed.length <= PREVIEW_MAX_CHARS) {
        return collapsed
    }
    return collapsed.take(PREVIEW_MAX_CHARS).trimEnd() + "..."
}
