/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.model.content

import com.zeroclaw.android.data.local.mapper.toEntity
import com.zeroclaw.android.data.local.mapper.toModel
import com.zeroclaw.android.model.AgentChatMessage
import com.zeroclaw.android.model.AgentMessageType
import com.zeroclaw.android.model.AgentRole
import com.zeroclaw.android.service.BlockReconciler
import com.zeroclaw.android.ui.renderer.ContentBlockRendererRegistry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase2RichContentTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun testBlockStateSerde() {
        val states: List<BlockState> = listOf(
            BlockState.Loading,
            BlockState.Streaming,
            BlockState.Ready,
            BlockState.Error(errorCode = "ERR_404", errorMessage = "Not found"),
            BlockState.Cancelled,
        )

        states.forEach { state ->
            val encoded = json.encodeToString(state)
            val decoded = json.decodeFromString<BlockState>(encoded)
            assertEquals(state, decoded)
        }
    }

    @Test
    fun testContentBlockParentAndStateDefaults() {
        val legacyJson = """
            {
                "type": "text",
                "version": 1,
                "block_id": "b_legacy",
                "sequence_index": 0,
                "text": "Legacy block text"
            }
        """.trimIndent()

        val block = json.decodeFromString<ContentBlock>(legacyJson)
        assertTrue(block is ContentBlock.Text)
        assertEquals("b_legacy", block.blockId)
        assertNull(block.parentBlockId)
        assertEquals(BlockState.Ready, block.state)
    }

    @Test
    fun testPhase2AssistantEventsSerde() {
        val events: List<AssistantEvent> = listOf(
            AssistantEvent.BlockStarted(
                version = 1,
                messageId = "msg_1",
                conversationId = "conv_1",
                block = ContentBlock.Markdown(
                    version = 1,
                    blockId = "b_1",
                    parentBlockId = "parent_0",
                    sequenceIndex = 0,
                    markdown = "Header",
                    state = BlockState.Streaming,
                ),
            ),
            AssistantEvent.BlockDelta(
                version = 1,
                messageId = "msg_1",
                conversationId = "conv_1",
                blockId = "b_1",
                delta = " and body content",
            ),
            AssistantEvent.BlockFinished(
                version = 1,
                messageId = "msg_1",
                conversationId = "conv_1",
                blockId = "b_1",
            ),
            AssistantEvent.StreamCancelled(
                version = 1,
                messageId = "msg_1",
                conversationId = "conv_1",
                reason = "User requested cancellation",
            ),
        )

        events.forEach { event ->
            val encoded = json.encodeToString(event)
            val decoded = json.decodeFromString<AssistantEvent>(encoded)
            assertEquals(event, decoded)
        }
    }

    @Test
    fun testBlockReconcilerLifecycle() {
        val reconciler = BlockReconciler()

        // 1. BlockStarted
        val startBlock = ContentBlock.Markdown(
            version = 1,
            blockId = "b_0",
            sequenceIndex = 0,
            markdown = "Hello",
            state = BlockState.Streaming,
        )
        val blocks1 = reconciler.processEvent(
            AssistantEvent.BlockStarted(
                version = 1,
                messageId = "msg_1",
                conversationId = "conv_1",
                block = startBlock,
            )
        )
        assertEquals(1, blocks1.size)
        assertEquals("Hello", (blocks1[0] as ContentBlock.Markdown).markdown)

        // 2. BlockDelta
        val blocks2 = reconciler.processEvent(
            AssistantEvent.BlockDelta(
                version = 1,
                messageId = "msg_1",
                conversationId = "conv_1",
                blockId = "b_0",
                delta = " World!",
            )
        )
        assertEquals(1, blocks2.size)
        assertEquals("Hello World!", (blocks2[0] as ContentBlock.Markdown).markdown)

        // 3. BlockFinished
        val blocks3 = reconciler.processEvent(
            AssistantEvent.BlockFinished(
                version = 1,
                messageId = "msg_1",
                conversationId = "conv_1",
                blockId = "b_0",
            )
        )
        assertEquals(1, blocks3.size)
        assertEquals(BlockState.Ready, blocks3[0].state)
    }

    @Test
    fun testRoomMapperWithPhase2Blocks() {
        val message = AgentChatMessage(
            id = "msg_test_100",
            senderId = "agent_1",
            senderName = "Master Agent",
            senderAvatar = "🤖",
            senderColor = 0xFF0000L,
            senderRole = AgentRole.MASTER,
            content = "Testing message",
            messageType = AgentMessageType.SUMMARY,
            blocks = listOf(
                ContentBlock.Markdown(
                    version = 1,
                    blockId = "msg_test_100_b0",
                    parentBlockId = null,
                    sequenceIndex = 0,
                    markdown = "Content block 1",
                    state = BlockState.Ready,
                ),
            ),
        )

        val entity = message.toEntity("family_1")
        assertNotNull(entity.contentBlocksJson)
        assertTrue(entity.contentBlocksJson!!.contains("msg_test_100_b0"))

        val restored = entity.toModel()
        assertEquals(message.id, restored.id)
        assertEquals(1, restored.blocks.size)
        assertEquals("msg_test_100_b0", restored.blocks[0].blockId)
        assertEquals(BlockState.Ready, restored.blocks[0].state)
    }
}
