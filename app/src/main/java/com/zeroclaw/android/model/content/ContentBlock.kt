/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.model.content

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Execution and streaming lifecycle state for individual content blocks.
 */
@Serializable
sealed interface BlockState {
    @Serializable
    @SerialName("loading")
    object Loading : BlockState

    @Serializable
    @SerialName("streaming")
    object Streaming : BlockState

    @Serializable
    @SerialName("ready")
    object Ready : BlockState

    @Serializable
    @SerialName("error")
    data class Error(
        val errorCode: String = "UNKNOWN",
        val errorMessage: String = "An error occurred",
    ) : BlockState

    @Serializable
    @SerialName("cancelled")
    object Cancelled : BlockState
}

/**
 * Universal versioned content block model representing structured multimodal message parts (MVS).
 */
@Serializable
sealed interface ContentBlock {
    val version: Int
    val blockId: String
    val parentBlockId: String?
    val sequenceIndex: Int
    val state: BlockState

    @Serializable
    @SerialName("text")
    data class Text(
        override val version: Int = 1,
        @SerialName("block_id")
        override val blockId: String,
        @SerialName("parent_block_id")
        override val parentBlockId: String? = null,
        @SerialName("sequence_index")
        override val sequenceIndex: Int,
        val text: String,
        override val state: BlockState = BlockState.Ready,
    ) : ContentBlock

    @Serializable
    @SerialName("markdown")
    data class Markdown(
        override val version: Int = 1,
        @SerialName("block_id")
        override val blockId: String,
        @SerialName("parent_block_id")
        override val parentBlockId: String? = null,
        @SerialName("sequence_index")
        override val sequenceIndex: Int,
        val markdown: String,
        override val state: BlockState = BlockState.Ready,
    ) : ContentBlock

    @Serializable
    @SerialName("reasoning")
    data class Reasoning(
        override val version: Int = 1,
        @SerialName("block_id")
        override val blockId: String,
        @SerialName("parent_block_id")
        override val parentBlockId: String? = null,
        @SerialName("sequence_index")
        override val sequenceIndex: Int,
        val reasoningText: String,
        val isComplete: Boolean = true,
        val signature: String? = null,
        override val state: BlockState = BlockState.Ready,
    ) : ContentBlock

    @Serializable
    @SerialName("image")
    data class Image(
        override val version: Int = 1,
        @SerialName("block_id")
        override val blockId: String,
        @SerialName("parent_block_id")
        override val parentBlockId: String? = null,
        @SerialName("sequence_index")
        override val sequenceIndex: Int,
        val url: String? = null,
        val mimeType: String = "image/png",
        val base64Data: String? = null,
        val altText: String? = null,
        override val state: BlockState = BlockState.Ready,
    ) : ContentBlock

    @Serializable
    @SerialName("file")
    data class File(
        override val version: Int = 1,
        @SerialName("block_id")
        override val blockId: String,
        @SerialName("parent_block_id")
        override val parentBlockId: String? = null,
        @SerialName("sequence_index")
        override val sequenceIndex: Int,
        val fileName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val uri: String? = null,
        override val state: BlockState = BlockState.Ready,
    ) : ContentBlock

    @Serializable
    @SerialName("tool_card")
    data class ToolCard(
        override val version: Int = 1,
        @SerialName("block_id")
        override val blockId: String,
        @SerialName("parent_block_id")
        override val parentBlockId: String? = null,
        @SerialName("sequence_index")
        override val sequenceIndex: Int,
        val toolCallId: String,
        val toolName: String,
        val status: ToolStatus,
        val inputJson: String,
        val resultBlocks: List<ContentBlock> = emptyList(),
        val executionDurationMs: Long? = null,
        override val state: BlockState = BlockState.Ready,
    ) : ContentBlock

    @Serializable
    @SerialName("code")
    data class Code(
        override val version: Int = 1,
        @SerialName("block_id")
        override val blockId: String,
        @SerialName("parent_block_id")
        override val parentBlockId: String? = null,
        @SerialName("sequence_index")
        override val sequenceIndex: Int,
        val language: String = "plaintext",
        val code: String,
        val fileName: String? = null,
        val isDiff: Boolean = false,
        val showLineNumbers: Boolean = true,
        override val state: BlockState = BlockState.Ready,
    ) : ContentBlock

    @Serializable
    @SerialName("callout")
    data class Callout(
        override val version: Int = 1,
        @SerialName("block_id")
        override val blockId: String,
        @SerialName("parent_block_id")
        override val parentBlockId: String? = null,
        @SerialName("sequence_index")
        override val sequenceIndex: Int,
        val kind: String = "info",
        val title: String? = null,
        val content: String,
        override val state: BlockState = BlockState.Ready,
    ) : ContentBlock

    @Serializable
    @SerialName("container")
    data class Container(
        override val version: Int = 1,
        @SerialName("block_id")
        override val blockId: String,
        @SerialName("parent_block_id")
        override val parentBlockId: String? = null,
        @SerialName("sequence_index")
        override val sequenceIndex: Int,
        val layoutType: String = "column",
        val children: List<ContentBlock> = emptyList(),
        override val state: BlockState = BlockState.Ready,
    ) : ContentBlock

    @Serializable
    @SerialName("unknown")
    data class Unknown(
        override val version: Int = 1,
        @SerialName("block_id")
        override val blockId: String,
        @SerialName("parent_block_id")
        override val parentBlockId: String? = null,
        @SerialName("sequence_index")
        override val sequenceIndex: Int,
        val typeId: String,
        val rawJson: String,
        override val state: BlockState = BlockState.Ready,
    ) : ContentBlock
}

/**
 * Execution status for tool call blocks.
 */
@Serializable
enum class ToolStatus {
    PENDING,
    EXECUTING,
    SUCCESS,
    ERROR,
    CANCELLED,
}
