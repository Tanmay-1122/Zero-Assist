/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zeroclaw.android.data.local.entity.ConversationEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for persisted conversation metadata used by the history drawer.
 */
@Dao
interface ConversationDao {
    /**
     * Observe all visible conversations ordered by most-recent activity.
     *
     * Empty seeded sessions stay hidden until they receive a preview or title.
     */
    @Query(
        """
        SELECT * FROM conversations
        WHERE preview != '' OR title IS NOT NULL
        ORDER BY last_message_at_ms DESC
        """,
    )
    fun observeVisibleConversations(): Flow<List<ConversationEntity>>

    /**
     * Observe one conversation metadata row.
     */
    @Query("SELECT * FROM conversations WHERE id = :conversationId LIMIT 1")
    fun observeConversation(conversationId: String): Flow<ConversationEntity?>

    /**
     * Get one conversation metadata row.
     */
    @Query("SELECT * FROM conversations WHERE id = :conversationId LIMIT 1")
    suspend fun getConversation(conversationId: String): ConversationEntity?

    /**
     * Insert or replace one metadata row.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(conversation: ConversationEntity)
}
