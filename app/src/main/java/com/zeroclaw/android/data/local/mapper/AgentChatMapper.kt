/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.local.mapper

import com.zeroclaw.android.data.local.entity.AgentChatMessageEntity
import com.zeroclaw.android.data.local.entity.AgentFamilyEntity
import com.zeroclaw.android.model.AgentChatMessage
import com.zeroclaw.android.model.AgentMessageType
import com.zeroclaw.android.model.AgentRole
import com.zeroclaw.android.model.ApprovalState
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val contentBlockJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/**
 * Convert AgentChatMessageEntity to AgentChatMessage model.
 */
fun AgentChatMessageEntity.toModel(): AgentChatMessage {
    val parsedBlocks = if (!contentBlocksJson.isNullOrBlank()) {
        try {
            contentBlockJson.decodeFromString<List<com.zeroclaw.android.model.content.ContentBlock>>(contentBlocksJson)
        } catch (e: Exception) {
            emptyList()
        }
    } else {
        emptyList()
    }

    return AgentChatMessage(
        id = id,
        senderId = senderId,
        senderName = senderName,
        senderAvatar = senderAvatar,
        senderColor = senderColor,
        senderRole = AgentRole.valueOf(senderRoleEnum),
        content = content,
        messageType = AgentMessageType.valueOf(messageTypeEnum),
        timestamp = timestamp,
        targetAgentId = targetAgentId,
        requiresApproval = requiresApproval,
        approvalState = ApprovalState.valueOf(approvalStateEnum),
        isStreaming = isStreaming,
        blocks = parsedBlocks,
    )
}

/**
 * Convert AgentChatMessage model to AgentChatMessageEntity.
 */
fun AgentChatMessage.toEntity(familyId: String): AgentChatMessageEntity {
    val serializedBlocks = if (blocks.isNotEmpty()) {
        try {
            contentBlockJson.encodeToString(blocks)
        } catch (e: Exception) {
            null
        }
    } else {
        null
    }

    return AgentChatMessageEntity(
        id = id,
        familyId = familyId,
        senderId = senderId,
        senderName = senderName,
        senderAvatar = senderAvatar,
        senderColor = senderColor,
        senderRoleEnum = senderRole.name,
        content = content,
        messageTypeEnum = messageType.name,
        timestamp = timestamp,
        targetAgentId = targetAgentId,
        requiresApproval = requiresApproval,
        approvalStateEnum = approvalState.name,
        isStreaming = isStreaming,
        contentBlocksJson = serializedBlocks,
    )
}

/**
 * Convert list of AgentChatMessageEntity to AgentChatMessage models.
 */
fun List<AgentChatMessageEntity>.toModels(): List<AgentChatMessage> {
    return map { it.toModel() }
}

/**
 * Convert list of AgentChatMessage to AgentChatMessageEntity.
 */
fun List<AgentChatMessage>.toEntities(familyId: String): List<AgentChatMessageEntity> {
    return map { it.toEntity(familyId) }
}

/**
 * Convert AgentFamilyEntity to a data class (family model).
 * Since we don't have a Family model yet, we return a simple data class.
 */
fun AgentFamilyEntity.toModel(): AgentFamily {
    val agentIds = try {
        Json.decodeFromString(ListSerializer(String.serializer()), agentIdsJson)
    } catch (e: Exception) {
        emptyList()
    }

    return AgentFamily(
        id = id,
        name = name,
        agentIds = agentIds,
        createdAt = createdAt,
        lastMessageTime = lastMessageTime,
    )
}

/**
 * Convert AgentFamily to AgentFamilyEntity.
 */
fun AgentFamily.toEntity(): AgentFamilyEntity {
    return AgentFamilyEntity(
        id = id,
        name = name,
        agentIdsJson = Json.encodeToString(ListSerializer(String.serializer()), agentIds),
        createdAt = createdAt,
        lastMessageTime = lastMessageTime,
    )
}

/**
 * Lightweight data class for family/group info.
 * Part of the chat message system.
 */
data class AgentFamily(
    val id: String,
    val name: String,
    val agentIds: List<String>,
    val createdAt: Long,
    val lastMessageTime: Long,
)
