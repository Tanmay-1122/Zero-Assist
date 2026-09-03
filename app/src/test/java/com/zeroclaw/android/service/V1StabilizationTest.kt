/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.diagnostics.RichRuntimeDiagnostics
import com.zeroclaw.android.model.content.AssistantEvent
import com.zeroclaw.android.model.content.BlockState
import com.zeroclaw.android.model.content.ContentBlock
import com.zeroclaw.android.planner.RichUiPlanner
import com.zeroclaw.android.planner.ToolSemanticUiBridge
import com.zeroclaw.android.runtime.BlockRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V1StabilizationTest {

    @Test
    fun testCodeFencedJsonDefensiveParsing() {
        val fencedJson = """
            ```json
            {
                "status": "active",
                "count": "42"
            }
            ```
        """.trimIndent()

        val uiNode = ToolSemanticUiBridge.transformToolOutputToUiNode("fenced_tool", fencedJson)
        val block = uiNode.toContentBlock(0)
        assertTrue(block is ContentBlock.Container)
        val container = block as ContentBlock.Container
        assertTrue(container.children.size >= 2)
    }

    @Test
    fun testMalformedJsonFallbackGracefulDegradation() {
        val malformedJson = "{ status: active, count: 42, missing_quotes }"
        val uiNode = ToolSemanticUiBridge.transformToolOutputToUiNode("malformed_tool", malformedJson)
        val block = uiNode.toContentBlock(0)
        assertNotNull(block)
        // Degrades gracefully to Markdown without throwing exception
        assertTrue(block is ContentBlock.Markdown)
    }

    @Test
    fun testV1MassiveRoomAndStreamingStressBenchmark() {
        val runtime = BlockRuntime("conv_v1_stress", "msg_v1_stress")

        // 1,000 blocks in conversation
        val initialBlocks = (0 until 1000).map { i ->
            ContentBlock.Markdown(
                blockId = "v1_msg_b$i",
                sequenceIndex = i,
                markdown = "Initial content block $i",
                state = BlockState.Ready,
            )
        }
        runtime.initialize(initialBlocks)
        assertEquals(1000, runtime.blocksState.value.size)

        // 100 streamed deltas
        (0 until 100).forEach { deltaIdx ->
            runtime.processAssistantEvent(
                AssistantEvent.BlockDelta(
                    messageId = "msg_v1_stress",
                    conversationId = "conv_v1_stress",
                    blockId = "v1_msg_b0",
                    delta = " delta_$deltaIdx",
                )
            )
        }

        val updatedBlock0 = runtime.blocksState.value.first { it.blockId == "v1_msg_b0" } as ContentBlock.Markdown
        assertTrue(updatedBlock0.markdown.contains("delta_99"))

        RichRuntimeDiagnostics.record("STRESS_TEST", "1000 blocks and 100 deltas processed cleanly")
        val summary = RichRuntimeDiagnostics.dumpSummary()
        assertTrue(summary.contains("STRESS_TEST"))

        runtime.destroy()
    }

    @Test
    fun testStreamCancellationRecovery() {
        val runtime = BlockRuntime("conv_cancel", "msg_cancel")
        val block = ContentBlock.Text(blockId = "b_cancel", sequenceIndex = 0, text = "Streaming text", state = BlockState.Streaming)
        runtime.initialize(listOf(block))

        runtime.processAssistantEvent(
            AssistantEvent.StreamCancelled(
                version = 1,
                messageId = "msg_cancel",
                conversationId = "conv_cancel",
                reason = "User aborted",
            )
        )

        val cancelledBlock = runtime.blocksState.value.first { it.blockId == "b_cancel" }
        assertEquals(BlockState.Cancelled, cancelledBlock.state)

        runtime.destroy()
    }
}
