/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.model.content

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Event-driven streaming protocol replacing single string chunk streaming.
 */
@Serializable
sealed interface AssistantEvent {
    val version: Int
    val messageId: String
    val conversationId: String

    @Serializable
    @SerialName("stream_started")
    data class StreamStarted(
        override val version: Int = 1,
        override val messageId: String,
        override val conversationId: String,
        val senderId: String,
        val senderName: String,
    ) : AssistantEvent

    @Serializable
    @SerialName("thinking_chunk")
    data class ThinkingChunk(
        override val version: Int = 1,
        override val messageId: String,
        override val conversationId: String,
        val blockId: String,
        val delta: String,
    ) : AssistantEvent

    @Serializable
    @SerialName("reasoning_finished")
    data class ReasoningFinished(
        override val version: Int = 1,
        override val messageId: String,
        override val conversationId: String,
        val blockId: String,
    ) : AssistantEvent

    @Serializable
    @SerialName("text_chunk")
    data class TextChunk(
        override val version: Int = 1,
        override val messageId: String,
        override val conversationId: String,
        val blockId: String,
        val delta: String,
    ) : AssistantEvent

    @Serializable
    @SerialName("block_started")
    data class BlockStarted(
        override val version: Int = 1,
        override val messageId: String,
        override val conversationId: String,
        val block: ContentBlock,
    ) : AssistantEvent

    @Serializable
    @SerialName("block_finished")
    data class BlockFinished(
        override val version: Int = 1,
        override val messageId: String,
        override val conversationId: String,
        val blockId: String,
    ) : AssistantEvent

    @Serializable
    @SerialName("stream_finished")
    data class StreamFinished(
        override val version: Int = 1,
        override val messageId: String,
        override val conversationId: String,
        val totalTokens: Int? = null,
        val durationMs: Long,
    ) : AssistantEvent

    @Serializable
    @SerialName("stream_error")
    data class StreamError(
        override val version: Int = 1,
        override val messageId: String,
        override val conversationId: String,
        val errorCode: String,
        val errorMessage: String,
    ) : AssistantEvent
}
