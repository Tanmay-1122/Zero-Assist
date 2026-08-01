/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.model

import java.util.Collections

/**
 * A request lifecycle tracked on the dashboard live activity feed.
 *
 * Created when an inbound [channel_message] event arrives, then enriched
 * with subsequent [ProcessingStep]s as the daemon processes the request
 * through LLM calls, tool invocations, and response delivery.
 *
 * @property id Monotonically increasing event ID from the daemon.
 * @property channel Channel name (e.g. "telegram", "discord").
 * @property direction "inbound" or "outbound".
 * @property sender Sender identifier, if available.
 * @property contentPreview Truncated message content preview.
 * @property timestampMs Epoch milliseconds when the item was created.
 * @property steps Ordered list of processing steps for this request.
 *   Mutable internally for the grouper; treat as read-only outside.
 * @property status Current lifecycle status.
 */
data class LiveActivityItem(
    val id: Long,
    val channel: String,
    val direction: String,
    val sender: String?,
    val contentPreview: String?,
    val timestampMs: Long,
    val steps: MutableList<ProcessingStep> = Collections.synchronizedList(mutableListOf()),
    val status: ActivityStatus = ActivityStatus.ACTIVE,
)

/**
 * A single processing step within a [LiveActivityItem] lifecycle.
 *
 * @property kind Step type identifier (e.g. "llm_request", "tool_call").
 * @property detail Human-readable description (e.g. "GPT-4o", "web_search").
 * @property timestampMs Epoch milliseconds when the step was recorded.
 * @property durationMs Duration in milliseconds, set when the step completes.
 * @property success Whether the step succeeded, set when it completes.
 */
data class ProcessingStep(
    val kind: StepKind,
    val detail: String,
    val timestampMs: Long,
    val durationMs: Long? = null,
    val success: Boolean? = null,
)

/**
 * Categorised processing step types for the live activity feed.
 */
enum class StepKind {
    THINKING,
    TOOL_CALL,
    TOOL_RESULT,
    RESPONSE,
}

/**
 * Lifecycle status of a [LiveActivityItem].
 */
enum class ActivityStatus {
    /** Request is actively being processed. */
    ACTIVE,
    /** Request completed successfully. */
    COMPLETED,
    /** Request failed with an error. */
    ERROR,
}
