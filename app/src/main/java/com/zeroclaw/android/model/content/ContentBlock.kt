/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.model.content

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Universal versioned content block model representing structured multimodal message parts (MVS).
 */
@Serializable
sealed interface ContentBlock {
    val version: Int
    val blockId: String
    val sequenceIndex: Int

    @Serializable
    @SerialName("text")
    data class Text(
        override val version: Int = 1,
        override val blockId: String,
        override val sequenceIndex: Int,
        val text: String,
    ) : ContentBlock

    @Serializable
    @SerialName("markdown")
    data class Markdown(
        override val version: Int = 1,
        override val blockId: String,
        override val sequenceIndex: Int,
        val markdown: String,
    ) : ContentBlock

    @Serializable
    @SerialName("reasoning")
    data class Reasoning(
        override val version: Int = 1,
        override val blockId: String,
        override val sequenceIndex: Int,
        val reasoningText: String,
        val isComplete: Boolean = true,
        val signature: String? = null,
    ) : ContentBlock

    @Serializable
    @SerialName("image")
    data class Image(
        override val version: Int = 1,
        override val blockId: String,
        override val sequenceIndex: Int,
        val url: String? = null,
        val mimeType: String = "image/png",
        val base64Data: String? = null,
        val altText: String? = null,
    ) : ContentBlock

    @Serializable
    @SerialName("file")
    data class File(
        override val version: Int = 1,
        override val blockId: String,
        override val sequenceIndex: Int,
        val fileName: String,
        val mimeType: String,
        val sizeBytes: Long,
        val uri: String? = null,
    ) : ContentBlock

    @Serializable
    @SerialName("tool_card")
    data class ToolCard(
        override val version: Int = 1,
        override val blockId: String,
        override val sequenceIndex: Int,
        val toolCallId: String,
        val toolName: String,
        val status: ToolStatus,
        val inputJson: String,
        val resultBlocks: List<ContentBlock> = emptyList(),
        val executionDurationMs: Long? = null,
    ) : ContentBlock

    @Serializable
    @SerialName("unknown")
    data class Unknown(
        override val version: Int = 1,
        override val blockId: String,
        override val sequenceIndex: Int,
        val typeId: String,
        val rawJson: String,
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
