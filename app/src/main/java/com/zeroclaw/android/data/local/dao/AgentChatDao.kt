/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.zeroclaw.android.data.local.entity.AgentChatMessageEntity
import com.zeroclaw.android.data.local.entity.AgentFamilyEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO for accessing agent chat messages.
 */
@Dao
interface AgentChatMessageDao {
    /**
     * Insert a new chat message.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(message: AgentChatMessageEntity)

    /**
     * Insert or update (upsert) a chat message.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(messages: List<AgentChatMessageEntity>)

    /**
     * Get a specific message by its stable identifier.
     */
    @Query("SELECT * FROM agent_chat_messages WHERE id = :messageId LIMIT 1")
    suspend fun getMessageById(messageId: String): AgentChatMessageEntity?

    /**
     * Update an existing chat message.
     */
    @Update
    suspend fun update(message: AgentChatMessageEntity)

    /**
     * Delete a chat message.
     */
    @Delete
    suspend fun delete(message: AgentChatMessageEntity)

    /**
     * Get all messages for a specific family, ordered by timestamp ascending.
     */
    @Query(
        """
        SELECT * FROM agent_chat_messages
        WHERE family_id = :familyId
        ORDER BY timestamp_ms ASC
        """
    )
    fun observeMessagesForFamily(familyId: String): Flow<List<AgentChatMessageEntity>>

    /**
     * Get all messages for a specific family as a one-shot query.
     */
    @Query(
        """
        SELECT * FROM agent_chat_messages
        WHERE family_id = :familyId
        ORDER BY timestamp_ms ASC
        """
    )
    suspend fun getMessagesForFamily(familyId: String): List<AgentChatMessageEntity>

    /**
     * Get pending approval messages for a family.
     */
    @Query(
        """
        SELECT * FROM agent_chat_messages
        WHERE family_id = :familyId 
        AND requires_approval = 1 
        AND approval_state = 'PENDING'
        ORDER BY timestamp_ms DESC
        """
    )
    fun observePendingApprovalsForFamily(familyId: String): Flow<List<AgentChatMessageEntity>>

    /**
     * Get messages from a specific sender.
     */
    @Query(
        """
        SELECT * FROM agent_chat_messages
        WHERE family_id = :familyId AND sender_id = :senderId
        ORDER BY timestamp_ms DESC
        """
    )
    fun observeMessagesFromSender(
        familyId: String,
        senderId: String,
    ): Flow<List<AgentChatMessageEntity>>

    /**
     * Get messages of a specific type.
     */
    @Query(
        """
        SELECT * FROM agent_chat_messages
        WHERE family_id = :familyId AND message_type = :messageType
        ORDER BY timestamp_ms DESC
        """
    )
    fun observeMessagesByType(
        familyId: String,
        messageType: String,
    ): Flow<List<AgentChatMessageEntity>>

    /**
     * Get messages addressing a specific target agent.
     */
    @Query(
        """
        SELECT * FROM agent_chat_messages
        WHERE family_id = :familyId AND target_agent_id = :targetAgentId
        ORDER BY timestamp_ms DESC
        """
    )
    fun observeMessagesForTarget(
        familyId: String,
        targetAgentId: String,
    ): Flow<List<AgentChatMessageEntity>>

    /**
     * Get streaming messages that haven't completed.
     */
    @Query(
        """
        SELECT * FROM agent_chat_messages
        WHERE family_id = :familyId AND is_streaming = 1
        ORDER BY timestamp_ms DESC
        """
    )
    fun observeStreamingMessages(familyId: String): Flow<List<AgentChatMessageEntity>>

    /**
     * Delete all messages for a family.
     */
    @Query("DELETE FROM agent_chat_messages WHERE family_id = :familyId")
    suspend fun deleteAllForFamily(familyId: String)

    /**
     * Update approval state of a message.
     */
    @Query(
        """
        UPDATE agent_chat_messages 
        SET approval_state = :approvalState 
        WHERE id = :messageId
        """
    )
    suspend fun updateApprovalState(messageId: String, approvalState: String)

    /**
     * Complete a streaming message (mark as not streaming).
     */
    @Query(
        """
        UPDATE agent_chat_messages 
        SET is_streaming = 0 
        WHERE id = :messageId
        """
    )
    suspend fun completeStreamingMessage(messageId: String)
}

/**
 * DAO for accessing family/group chat sessions.
 */
@Dao
interface AgentFamilyDao {
    /**
     * Insert a new family.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(family: AgentFamilyEntity)

    /**
     * Update an existing family.
     */
    @Update
    suspend fun update(family: AgentFamilyEntity)

    /**
     * Delete a family.
     */
    @Delete
    suspend fun delete(family: AgentFamilyEntity)

    /**
     * Get a specific family by ID.
     */
    @Query("SELECT * FROM agent_families WHERE id = :familyId")
    suspend fun getFamilyById(familyId: String): AgentFamilyEntity?

    /**
     * Observe a specific family by ID.
     */
    @Query("SELECT * FROM agent_families WHERE id = :familyId")
    fun observeFamilyById(familyId: String): Flow<AgentFamilyEntity?>

    /**
     * Get all families.
     */
    @Query("SELECT * FROM agent_families ORDER BY last_message_time_ms DESC")
    fun observeAllFamilies(): Flow<List<AgentFamilyEntity>>

    /**
     * Update last message time for a family (called when new message arrives).
     */
    @Query(
        """
        UPDATE agent_families 
        SET last_message_time_ms = :timestamp 
        WHERE id = :familyId
        """
    )
    suspend fun updateLastMessageTime(familyId: String, timestamp: Long)
}
