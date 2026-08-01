/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.model

import java.util.UUID

/**
 * Represents a message in the agent group chat.
 * Visible to the user, shows agent-to-agent communication and user interactions.
 *
 * @property id Unique message identifier.
 * @property senderId ID of the agent or "user" or "system".
 * @property senderName Display name of the sender.
 * @property senderAvatar Emoji avatar of the sender.
 * @property senderColor Accent color for the sender.
 * @property senderRole The role of the sending agent.
 * @property content Visible message text.
 * @property messageType Classification of the message.
 * @property timestamp Creation timestamp in milliseconds.
 * @property targetAgentId Optional agent being addressed by this message.
 * @property requiresApproval Whether this message needs explicit approval.
 * @property approvalState Current approval status.
 * @property isStreaming Whether the message is currently being streamed.
 */
data class AgentChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val senderId: String,
    val senderName: String,
    val senderAvatar: String,
    val senderColor: Long,
    val senderRole: AgentRole,
    val content: String,
    val messageType: AgentMessageType,
    val timestamp: Long = System.currentTimeMillis(),
    val targetAgentId: String? = null,
    val requiresApproval: Boolean = false,
    val approvalState: ApprovalState = ApprovalState.NONE,
    val isStreaming: Boolean = false,
) {
    companion object {
        /**
         * Factory for task assignment messages from the master to a sub-agent.
         */
        fun taskAssignment(
            senderId: String,
            senderName: String,
            senderAvatar: String,
            senderColor: Long,
            senderRole: AgentRole,
            content: String,
            targetAgentId: String,
            requiresApproval: Boolean = false,
        ): AgentChatMessage = AgentChatMessage(
            senderId = senderId,
            senderName = senderName,
            senderAvatar = senderAvatar,
            senderColor = senderColor,
            senderRole = senderRole,
            content = content,
            messageType = AgentMessageType.TASK_ASSIGNMENT,
            targetAgentId = targetAgentId,
            requiresApproval = requiresApproval,
        )

        /**
         * Factory for status update messages.
         */
        fun statusUpdate(
            senderId: String,
            senderName: String,
            senderAvatar: String,
            senderColor: Long,
            senderRole: AgentRole,
            content: String,
        ): AgentChatMessage = AgentChatMessage(
            senderId = senderId,
            senderName = senderName,
            senderAvatar = senderAvatar,
            senderColor = senderColor,
            senderRole = senderRole,
            content = content,
            messageType = AgentMessageType.STATUS_UPDATE,
        )

        /**
         * Factory for summary or result messages.
         */
        fun summary(
            senderId: String,
            senderName: String,
            senderAvatar: String,
            senderColor: Long,
            senderRole: AgentRole,
            content: String,
            targetAgentId: String? = null,
        ): AgentChatMessage = AgentChatMessage(
            senderId = senderId,
            senderName = senderName,
            senderAvatar = senderAvatar,
            senderColor = senderColor,
            senderRole = senderRole,
            content = content,
            messageType = AgentMessageType.SUMMARY,
            targetAgentId = targetAgentId,
        )

        /**
         * Factory for user messages, including routed @mentions.
         */
        fun userMessage(
            content: String,
            targetAgentId: String? = null,
        ): AgentChatMessage = AgentChatMessage(
            senderId = "user",
            senderName = "You",
            senderAvatar = "\uD83D\uDC64",
            senderColor = -1,
            senderRole = AgentRole.GENERAL,
            content = content,
            messageType = AgentMessageType.USER_MESSAGE,
            targetAgentId = targetAgentId,
        )

        /**
         * Factory for centered system event messages.
         */
        fun systemEvent(content: String): AgentChatMessage = AgentChatMessage(
            senderId = "system",
            senderName = "System",
            senderAvatar = "\u2699\uFE0F",
            senderColor = -2,
            senderRole = AgentRole.GENERAL,
            content = content,
            messageType = AgentMessageType.SYSTEM_EVENT,
        )

        /**
         * Factory for explicit local-device and cloud-fallback result bubbles.
         */
        fun onDeviceResult(
            content: String,
            senderName: String = "On-device",
        ): AgentChatMessage = AgentChatMessage(
            senderId = "local-device",
            senderName = senderName,
            senderAvatar = "\u2699\uFE0F",
            senderColor = 0xFF00C8B4L,
            senderRole = AgentRole.GENERAL,
            content = content,
            messageType = AgentMessageType.ON_DEVICE_RESULT,
        )

        /**
         * Factory for approval request messages.
         */
        fun approvalRequest(
            senderId: String,
            senderName: String,
            senderAvatar: String,
            senderColor: Long,
            senderRole: AgentRole,
            content: String,
            targetAgentId: String,
        ): AgentChatMessage = AgentChatMessage(
            senderId = senderId,
            senderName = senderName,
            senderAvatar = senderAvatar,
            senderColor = senderColor,
            senderRole = senderRole,
            content = content,
            messageType = AgentMessageType.APPROVAL_REQUEST,
            targetAgentId = targetAgentId,
            requiresApproval = true,
            approvalState = ApprovalState.PENDING,
        )
    }
}

/**
 * Classifies the type of message in the group chat.
 */
enum class AgentMessageType {
    /** Master to sub-agent task summary. */
    TASK_ASSIGNMENT,

    /** Agent-to-chat status line. */
    STATUS_UPDATE,

    /** Agent-to-chat result summary. */
    SUMMARY,

    /** User-to-agent or user-to-chat message. */
    USER_MESSAGE,

    /** Centered system event. */
    SYSTEM_EVENT,

    /** Explicit local-device or cloud-fallback tool result. */
    ON_DEVICE_RESULT,

    /** Approval gate before delegation. */
    APPROVAL_REQUEST,
}

/**
 * Tracks the approval state of a message that requires confirmation.
 */
enum class ApprovalState {
    /** No approval needed. */
    NONE,

    /** Awaiting confirmation. */
    PENDING,

    /** Approved by the user. */
    APPROVED,

    /** Rejected by the user. */
    REJECTED,
}
