/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.model.ActivityStatus
import com.zeroclaw.android.model.DaemonEvent
import com.zeroclaw.android.model.StepKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

class LiveActivityGrouperTest {

    private lateinit var grouper: LiveActivityGrouper

    @BeforeEach
    fun setup() {
        grouper = LiveActivityGrouper()
    }

    @Test
    fun `inbound channel_message creates a new item`() {
        val result = grouper.process(inboundMessage("telegram", "alice", "hello"))

        assertEquals(1, result.size)
        assertEquals("telegram", result[0].channel)
        assertEquals("alice", result[0].sender)
        assertEquals("hello", result[0].contentPreview)
        assertEquals(ActivityStatus.ACTIVE, result[0].status)
    }

    @Test
    fun `outbound channel_message is ignored`() {
        val result = grouper.process(outboundMessage("telegram"))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `llm_request appends THINKING step`() {
        grouper.process(inboundMessage("telegram", "alice", "hello"))
        val result = grouper.process(llmRequest("openai", "gpt-4o"))

        assertEquals(1, result.size)
        assertEquals(1, result[0].steps.size)
        assertEquals(StepKind.THINKING, result[0].steps[0].kind)
        assertEquals("openai/gpt-4o", result[0].steps[0].detail)
    }

    @Test
    fun `tool_call_start appends TOOL_CALL step`() {
        grouper.process(inboundMessage("telegram", "alice", "hello"))
        val result = grouper.process(toolCallStart("web_search"))

        assertEquals(1, result[0].steps.size)
        assertEquals(StepKind.TOOL_CALL, result[0].steps[0].kind)
        assertEquals("web_search", result[0].steps[0].detail)
        assertNull(result[0].steps[0].durationMs)
    }

    @Test
    fun `tool_call updates matching TOOL_CALL step with duration`() {
        grouper.process(inboundMessage("telegram", "alice", "hello"))
        grouper.process(toolCallStart("web_search"))
        val result = grouper.process(toolCall("web_search", 250, true))

        assertEquals(1, result[0].steps.size)
        assertEquals(250L, result[0].steps[0].durationMs)
        assertEquals(true, result[0].steps[0].success)
    }

    @Test
    fun `tool_call without prior start appends TOOL_RESULT`() {
        grouper.process(inboundMessage("telegram", "alice", "hello"))
        val result = grouper.process(toolCall("shell", 100, true))

        assertEquals(1, result[0].steps.size)
        assertEquals(StepKind.TOOL_RESULT, result[0].steps[0].kind)
    }

    @Test
    fun `llm_response appends RESPONSE step`() {
        grouper.process(inboundMessage("telegram", "alice", "hello"))
        val result = grouper.process(llmResponse("openai", "gpt-4o", 500, true))

        assertEquals(1, result[0].steps.size)
        assertEquals(StepKind.RESPONSE, result[0].steps[0].kind)
        assertEquals(500L, result[0].steps[0].durationMs)
        assertEquals(true, result[0].steps[0].success)
    }

    @Test
    fun `turn_complete marks item as completed`() {
        grouper.process(inboundMessage("telegram", "alice", "hello"))
        val result = grouper.process(turnComplete())

        assertEquals(1, result.size)
        assertEquals(ActivityStatus.COMPLETED, result[0].status)
    }

    @Test
    fun `error marks item as ERROR`() {
        grouper.process(inboundMessage("telegram", "alice", "hello"))
        val result = grouper.process(errorEvent("provider", "rate limited"))

        // Item stays visible with the red-X state so the user sees the failure.
        assertEquals(1, result.size)
        assertEquals(ActivityStatus.ERROR, result[0].status)
    }

    @Test
    fun `llm_response with success=false marks item as ERROR`() {
        grouper.process(inboundMessage("telegram", "alice", "hello"))
        val result = grouper.process(llmResponse("openai", "gpt-4o", 500, false))

        assertEquals(1, result.size)
        assertEquals(ActivityStatus.ERROR, result[0].status)
        assertEquals(1, result[0].steps.size)
        assertEquals(false, result[0].steps[0].success)
    }

    @Test
    fun `turn_complete upgrades ERROR item to COMPLETED`() {
        // A failed LLM response is surfaced as ERROR, but a later
        // turn_complete means the daemon recovered the turn — green tick wins.
        grouper.process(inboundMessage("telegram", "alice", "hello"))
        grouper.process(llmResponse("openai", "gpt-4o", 500, false))
        val result = grouper.process(turnComplete())

        assertEquals(ActivityStatus.COMPLETED, result[0].status)
    }

    @Test
    fun `turn_complete completes the newest in-progress item only`() {
        // Older request failed and stayed visible as ERROR.
        grouper.process(inboundMessage("telegram", "alice", "first"))
        grouper.process(llmResponse("openai", "gpt-4o", 500, false))
        // Newer request is still processing.
        grouper.process(inboundMessage("discord", "bob", "second"))
        val result = grouper.process(turnComplete())

        assertEquals(2, result.size)
        assertEquals("discord", result[0].channel)
        assertEquals("bob", result[0].sender)
        assertEquals(ActivityStatus.COMPLETED, result[0].status)
        assertEquals(ActivityStatus.ERROR, result[1].status)
    }

    @Test
    fun `full request lifecycle`() {
        grouper.process(inboundMessage("telegram", "alice", "research mythos"))
        grouper.process(llmRequest("anthropic", "claude-sonnet-4"))
        grouper.process(toolCallStart("web_search"))
        grouper.process(toolCall("web_search", 320, true))
        grouper.process(llmResponse("anthropic", "claude-sonnet-4", 1200, true))
        val result = grouper.process(turnComplete())

        // Item is marked COMPLETED (stays visible for COMPLETED_DISPLAY_MS).
        assertEquals(1, result.size)
        assertEquals(ActivityStatus.COMPLETED, result[0].status)
    }

    @Test
    fun `multiple requests tracked independently`() {
        grouper.process(inboundMessage("telegram", "alice", "first"))
        grouper.process(inboundMessage("discord", "bob", "second"))

        val result = grouper.process(llmRequest("openai", "gpt-4o"))

        assertEquals(2, result.size)
        // Newest item (discord) should get the LLM request step.
        assertEquals("discord", result[0].channel)
        assertEquals(1, result[0].steps.size)
        // Older item (telegram) should have no steps.
        assertEquals("telegram", result[1].channel)
        assertTrue(result[1].steps.isEmpty())
    }

    @Test
    fun `events without active item are silently ignored`() {
        val result = grouper.process(llmRequest("openai", "gpt-4o"))

        assertTrue(result.isEmpty())
    }

    @Test
    fun `items older than expiry are pruned`() {
        // Create an item with a very old timestamp.
        val oldEvent = DaemonEvent(
            id = 1,
            timestampMs = System.currentTimeMillis() - (6 * 60 * 1000), // 6 minutes ago
            kind = "channel_message",
            data = mapOf(
                "channel" to "telegram",
                "direction" to "inbound",
                "sender" to "alice",
                "content" to "old message",
            ),
        )
        grouper.process(oldEvent)

        // Create a new item.
        grouper.process(inboundMessage("telegram", "bob", "new message"))

        val result = grouper.process(turnComplete())

        // Old item was pruned by expiry, new item is COMPLETED (stays visible).
        assertEquals(1, result.size)
        assertEquals(ActivityStatus.COMPLETED, result[0].status)
        assertEquals("bob", result[0].sender)
    }

    @Test
    fun `max visible items respected`() {
        // Create more items than MAX_VISIBLE + buffer.
        for (i in 1..8) {
            grouper.process(inboundMessage("telegram", "user$i", "msg $i"))
        }

        val result = grouper.process(llmRequest("openai", "gpt-4o"))

        // Should have at most MAX_VISIBLE + 4 items (the internal buffer).
        assertTrue(result.size <= LiveActivityGrouper.MAX_VISIBLE + 4)
        // The newest item should have the LLM request step.
        assertEquals("user8", result[0].sender)
    }

    @Test
    fun `stuck active item transitions to completed after threshold`() {
        // Create an item that's been ACTIVE for longer than STUCK_THRESHOLD_MS.
        val stuckEvent = DaemonEvent(
            id = 99,
            timestampMs = System.currentTimeMillis() - (LiveActivityGrouper.STUCK_THRESHOLD_MS + 60_000),
            kind = "channel_message",
            data = mapOf(
                "channel" to "telegram",
                "direction" to "inbound",
                "sender" to "stuck_user",
                "content" to "stuck message",
            ),
        )
        grouper.process(stuckEvent)

        // Process any event to trigger pruneExpired.
        val result = grouper.process(llmRequest("openai", "gpt-4o"))

        // The stuck item should now be COMPLETED, not ACTIVE.
        assertEquals(1, result.size)
        assertEquals(ActivityStatus.COMPLETED, result[0].status)
        assertEquals("stuck_user", result[0].sender)
    }

    // ── Helper factories ──────────────────────────────────────

    private fun inboundMessage(
        channel: String,
        sender: String,
        content: String,
    ) = DaemonEvent(
        id = nextId(),
        timestampMs = System.currentTimeMillis(),
        kind = "channel_message",
        data = mapOf(
            "channel" to channel,
            "direction" to "inbound",
            "sender" to sender,
            "content" to content,
        ),
    )

    private fun outboundMessage(channel: String) = DaemonEvent(
        id = nextId(),
        timestampMs = System.currentTimeMillis(),
        kind = "channel_message",
        data = mapOf(
            "channel" to channel,
            "direction" to "outbound",
        ),
    )

    private fun llmRequest(provider: String, model: String) = DaemonEvent(
        id = nextId(),
        timestampMs = System.currentTimeMillis(),
        kind = "llm_request",
        data = mapOf(
            "provider" to provider,
            "model" to model,
            "messages" to "5",
        ),
    )

    private fun llmResponse(
        provider: String,
        model: String,
        durationMs: Long,
        success: Boolean,
    ) = DaemonEvent(
        id = nextId(),
        timestampMs = System.currentTimeMillis(),
        kind = "llm_response",
        data = mapOf(
            "provider" to provider,
            "model" to model,
            "duration_ms" to durationMs.toString(),
            "success" to success.toString(),
        ),
    )

    private fun toolCallStart(tool: String) = DaemonEvent(
        id = nextId(),
        timestampMs = System.currentTimeMillis(),
        kind = "tool_call_start",
        data = mapOf("tool" to tool),
    )

    private fun toolCall(tool: String, durationMs: Long, success: Boolean) = DaemonEvent(
        id = nextId(),
        timestampMs = System.currentTimeMillis(),
        kind = "tool_call",
        data = mapOf(
            "tool" to tool,
            "duration_ms" to durationMs.toString(),
            "success" to success.toString(),
        ),
    )

    private fun turnComplete() = DaemonEvent(
        id = nextId(),
        timestampMs = System.currentTimeMillis(),
        kind = "turn_complete",
        data = emptyMap(),
    )

    private fun errorEvent(component: String, message: String) = DaemonEvent(
        id = nextId(),
        timestampMs = System.currentTimeMillis(),
        kind = "error",
        data = mapOf(
            "component" to component,
            "message" to message,
        ),
    )

    private var idCounter = 0L
    private fun nextId(): Long = ++idCounter
}
