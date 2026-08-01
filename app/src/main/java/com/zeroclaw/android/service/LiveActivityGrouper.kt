/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.model.ActivityStatus
import com.zeroclaw.android.model.DaemonEvent
import com.zeroclaw.android.model.LiveActivityItem
import com.zeroclaw.android.model.ProcessingStep
import com.zeroclaw.android.model.StepKind

/**
 * Groups flat daemon events into request-lifecycle [LiveActivityItem]s.
 *
 * Each inbound `channel_message` creates a new item. Subsequent events
 * (LLM requests, tool calls, responses, turn completion) are appended
 * as [ProcessingStep]s on the most recent active item.
 *
 * Items auto-expire after [EXPIRY_MS] milliseconds of inactivity.
 * At most [MAX_VISIBLE] items are retained; older items are dropped.
 *
 * This class is **not** thread-safe — callers must serialise access
 * (e.g. via a single coroutine on the ViewModel scope).
 */
class LiveActivityGrouper {

    private val items = mutableListOf<LiveActivityItem>()

    /**
     * Process a new daemon event and return the current snapshot of
     * live activity items, newest first.
     *
     * @param event Parsed daemon event from the FFI bridge.
     * @return Current list of tracked items, pruned and expired.
     */
    fun process(event: DaemonEvent): List<LiveActivityItem> {
        when (event.kind) {
            "channel_message" -> handleChannelMessage(event)
            "llm_request" -> handleLlmRequest(event)
            "llm_response" -> handleLlmResponse(event)
            "tool_call_start" -> handleToolCallStart(event)
            "tool_call" -> handleToolCall(event)
            "turn_complete" -> handleTurnComplete(event)
            "error" -> handleError(event)
        }
        pruneExpired()
        return snapshot()
    }

    /** Replace the item at [idx] with an updated copy. */
    private fun updateItem(idx: Int, updated: LiveActivityItem) {
        items[idx] = updated
    }

    private fun handleChannelMessage(event: DaemonEvent) {
        val direction = event.data["direction"] ?: return
        if (direction != "inbound") return

        val item = LiveActivityItem(
            id = event.id,
            channel = event.data["channel"] ?: "unknown",
            direction = direction,
            sender = event.data["sender"],
            contentPreview = event.data["content"],
            timestampMs = event.timestampMs,
        )
        items.add(0, item)
        while (items.size > MAX_VISIBLE + 4) {
            items.removeLast()
        }
    }

    private fun handleLlmRequest(event: DaemonEvent) {
        val active = findActive() ?: return
        val provider = event.data["provider"] ?: ""
        val model = event.data["model"] ?: ""
        val detail = if (model.isNotEmpty()) "$provider/$model" else provider
        active.steps.add(
            ProcessingStep(
                kind = StepKind.THINKING,
                detail = detail,
                timestampMs = event.timestampMs,
            ),
        )
    }

    private fun handleLlmResponse(event: DaemonEvent) {
        val active = findActive() ?: return
        val provider = event.data["provider"] ?: ""
        val model = event.data["model"] ?: ""
        val durationMs = event.data["duration_ms"]?.toLongOrNull()
        val success = event.data["success"]?.toBooleanStrictOrNull()
        val detail = if (model.isNotEmpty()) "$provider/$model" else provider
        active.steps.add(
            ProcessingStep(
                kind = StepKind.RESPONSE,
                detail = detail,
                timestampMs = event.timestampMs,
                durationMs = durationMs,
                success = success,
            ),
        )
    }

    private fun handleToolCallStart(event: DaemonEvent) {
        val active = findActive() ?: return
        val tool = event.data["tool"] ?: "unknown"
        active.steps.add(
            ProcessingStep(
                kind = StepKind.TOOL_CALL,
                detail = tool,
                timestampMs = event.timestampMs,
            ),
        )
    }

    private fun handleToolCall(event: DaemonEvent) {
        val active = findActive() ?: return
        val tool = event.data["tool"] ?: return
        val durationMs = event.data["duration_ms"]?.toLongOrNull()
        val success = event.data["success"]?.toBooleanStrictOrNull()
        // Update the most recent TOOL_CALL step for this tool.
        val existing = active.steps.lastOrNull {
            it.kind == StepKind.TOOL_CALL && it.detail == tool && it.durationMs == null
        }
        if (existing != null) {
            val idx = active.steps.indexOf(existing)
            active.steps[idx] = existing.copy(durationMs = durationMs, success = success)
        } else {
            // Fallback: append as a completed tool result.
            active.steps.add(
                ProcessingStep(
                    kind = StepKind.TOOL_RESULT,
                    detail = tool,
                    timestampMs = event.timestampMs,
                    durationMs = durationMs,
                    success = success,
                ),
            )
        }
    }

    private fun handleTurnComplete(event: DaemonEvent) {
        val active = findActive() ?: return
        val idx = items.indexOf(active)
        if (idx != -1) {
            updateItem(idx, active.copy(status = ActivityStatus.COMPLETED))
        }
    }

    private fun handleError(event: DaemonEvent) {
        val active = findActive() ?: return
        val idx = items.indexOf(active)
        if (idx != -1) {
            updateItem(idx, active.copy(status = ActivityStatus.ERROR))
        }
    }

    /** Find the most recent ACTIVE item. */
    private fun findActive(): LiveActivityItem? =
        items.firstOrNull { it.status == ActivityStatus.ACTIVE }

    /** Drop items older than [EXPIRY_MS], but only if they are no longer ACTIVE. */
    private fun pruneExpired() {
        val now = System.currentTimeMillis()
        val iterator = items.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            val age = now - item.timestampMs

            // Safety net: if an ACTIVE item has been spinning for longer than
            // STUCK_THRESHOLD_MS, the `turn_complete` event was likely lost
            // (SharedFlow drop, crash, etc.). Transition it to COMPLETED so
            // the UI shows the green tick instead of an infinite spinner.
            var effectiveStatus = item.status
            if (item.status == ActivityStatus.ACTIVE && age > STUCK_THRESHOLD_MS) {
                val idx = items.indexOf(item)
                if (idx != -1) {
                    updateItem(idx, item.copy(status = ActivityStatus.COMPLETED))
                    effectiveStatus = ActivityStatus.COMPLETED
                }
            }

            // Completed/error items: keep for COMPLETED_DISPLAY_MS so the user sees the tick,
            // then remove. Active items are only removed after full EXPIRY_MS.
            val expiryLimit = if (effectiveStatus == ActivityStatus.ACTIVE) EXPIRY_MS else COMPLETED_DISPLAY_MS
            if (age > expiryLimit) {
                iterator.remove()
            }
        }
    }

    /** Return a snapshot of items ordered newest-first. */
    private fun snapshot(): List<LiveActivityItem> = items.toList()

    companion object {
        /** Maximum number of items to display on the dashboard. */
        const val MAX_VISIBLE = 3

        /** Active items older than this are pruned (5 minutes). */
        const val EXPIRY_MS = 5 * 60 * 1000L

        /** Completed/error items are kept visible for this long (2 minutes) so the tick is seen. */
        const val COMPLETED_DISPLAY_MS = 2 * 60 * 1000L

        /** If an ACTIVE item has been spinning longer than this, the turn_complete
         *  event was likely lost. Force-transition to COMPLETED so the UI shows
         *  a green tick instead of an infinite spinner. */
        const val STUCK_THRESHOLD_MS = 5 * 60 * 1000L
    }
}
