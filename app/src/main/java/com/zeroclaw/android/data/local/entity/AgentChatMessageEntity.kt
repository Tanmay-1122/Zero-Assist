/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.zeroclaw.android.model.AgentMessageType
import com.zeroclaw.android.model.ApprovalState

/**
 * Room entity for persisting agent chat messages in a group chat.
 *
 * Stores all messages from the family/group chat session, including:
 * - Task assignments from master to sub-agents
 * - Status updates as agents work
 * - Summaries of completed work
 * - Approval requests
 * - User messages
 * - System events
 *
 * @property id Unique message identifier (UUID)
 * @property familyId ID of the family/group this message belongs to
 * @property senderId ID of the agent or "user" or "system"
 * @property senderName Display name of sender (cached for display)
 * @property senderAvatar Emoji avatar of sender (cached)
 * @property senderColor Accent color as Long (cached)
 * @property senderRoleEnum Role of sender as string (stored for querying)
 * @property content Message text
 * @property messageTypeEnum Message type as string
 * @property timestamp Milliseconds since epoch
 * @property targetAgentId Optional: agent this message addresses
 * @property requiresApproval Whether approval is needed
 * @property approvalStateEnum Approval state as string
 * @property isStreaming Whether message is streaming/incomplete
 */
@Entity(
    tableName = "agent_chat_messages",
    indices = [
        Index(value = ["family_id"]),
        Index(value = ["family_id", "timestamp_ms"]),
    ],
)
data class AgentChatMessageEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "family_id")
    val familyId: String,

    @ColumnInfo(name = "sender_id")
    val senderId: String,

    @ColumnInfo(name = "sender_name")
    val senderName: String,

    @ColumnInfo(name = "sender_avatar")
    val senderAvatar: String,

    @ColumnInfo(name = "sender_color")
    val senderColor: Long,

    @ColumnInfo(name = "sender_role")
    val senderRoleEnum: String,

    @ColumnInfo(name = "content")
    val content: String,

    @ColumnInfo(name = "message_type")
    val messageTypeEnum: String,

    @ColumnInfo(name = "timestamp_ms")
    val timestamp: Long,

    @ColumnInfo(name = "target_agent_id")
    val targetAgentId: String? = null,

    @ColumnInfo(name = "requires_approval")
    val requiresApproval: Boolean = false,

    @ColumnInfo(name = "approval_state")
    val approvalStateEnum: String = ApprovalState.NONE.name,

    @ColumnInfo(name = "is_streaming")
    val isStreaming: Boolean = false,

    @ColumnInfo(name = "content_blocks_json")
    val contentBlocksJson: String? = null,
)

/**
 * Room entity for tracking family/group chat sessions.
 *
 * @property id Unique identifier for the family group
 * @property name Display name of the family/group
 * @property agentIds JSON array of agent IDs in this family
 * @property createdAt Timestamp when family was created
 * @property lastMessageTime Timestamp of last message in this family
 */
@Entity(
    tableName = "agent_families",
    indices = [
        Index(value = ["last_message_time_ms"]),
    ],
)
data class AgentFamilyEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "name")
    val name: String,

    @ColumnInfo(name = "agent_ids_json")
    val agentIdsJson: String, // JSON array of agent IDs

    @ColumnInfo(name = "created_at_ms")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "last_message_time_ms")
    val lastMessageTime: Long = System.currentTimeMillis(),
)
