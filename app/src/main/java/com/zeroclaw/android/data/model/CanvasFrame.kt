/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents a single frame rendered on a Canvas session.
 *
 * Canvas frames are streamed in real-time from the agent, supporting
 * HTML, SVG, Markdown, and plain text content types for visualizations.
 *
 * @param frameId Unique identifier (UUID-v4) for this frame.
 * @param contentType "html", "svg", "markdown", or "text".
 * @param content The rendered content (max 256 KB per frame).
 * @param timestamp ISO-8601 timestamp of when the frame was created.
 */
@Serializable
data class CanvasFrame(
    @SerialName("frame_id")
    val frameId: String,

    @SerialName("content_type")
    val contentType: String,

    val content: String,

    val timestamp: String,
)

/**
 * Represents a Canvas session.
 *
 * Tracks the canvas ID, current frame, history, and connection state.
 *
 * @param canvasId Unique identifier for this canvas.
 * @param currentFrame The most recent frame rendered.
 * @param history Sequence of rendered frames (max 50).
 * @param isConnected Whether the WebSocket connection is active.
 * @param frameCount Total number of frames received.
 */
data class CanvasSession(
    val canvasId: String,
    val currentFrame: CanvasFrame? = null,
    val history: List<CanvasFrame> = emptyList(),
    val isConnected: Boolean = false,
    val frameCount: Int = 0,
)
