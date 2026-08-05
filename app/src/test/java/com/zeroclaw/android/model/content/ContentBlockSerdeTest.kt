/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.model.content

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentBlockSerdeTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun testMarkdownBlockSerde() {
        val block: ContentBlock = ContentBlock.Markdown(
            version = 1,
            blockId = "b_0",
            sequenceIndex = 0,
            markdown = "# Hello World",
        )

        val encoded = json.encodeToString(block)
        assertTrue(encoded.contains("\"type\":\"markdown\""))
        assertTrue(encoded.contains("\"version\":1"))

        val decoded = json.decodeFromString<ContentBlock>(encoded)
        assertEquals(block, decoded)
    }

    @Test
    fun testToolCardBlockSerde() {
        val block: ContentBlock = ContentBlock.ToolCard(
            version = 1,
            blockId = "b_1",
            sequenceIndex = 1,
            toolCallId = "call_123",
            toolName = "web_search",
            status = ToolStatus.SUCCESS,
            inputJson = "{\"query\":\"Zero-Assist\"}",
            resultBlocks = listOf(
                ContentBlock.Markdown(
                    version = 1,
                    blockId = "b_1_sub",
                    sequenceIndex = 0,
                    markdown = "Search results...",
                )
            ),
        )

        val encoded = json.encodeToString(block)
        assertTrue(encoded.contains("\"type\":\"tool_card\""))

        val decoded = json.decodeFromString<ContentBlock>(encoded)
        assertEquals(block, decoded)
    }

    @Test
    fun testAssistantEventSerde() {
        val event: AssistantEvent = AssistantEvent.TextChunk(
            version = 1,
            messageId = "msg_1",
            conversationId = "conv_1",
            blockId = "b_0",
            delta = "Hello ",
        )

        val encoded = json.encodeToString(event)
        assertTrue(encoded.contains("\"event_type\":\"text_chunk\""))

        val decoded = json.decodeFromString<AssistantEvent>(encoded)
        assertEquals(event, decoded)
    }
}
