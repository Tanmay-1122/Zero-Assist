/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.renderer

import com.zeroclaw.android.model.content.BlockState
import com.zeroclaw.android.model.content.ContentBlock
import com.zeroclaw.android.runtime.BlockCapability
import com.zeroclaw.android.runtime.plugin.BlockPluginRegistry
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase4NativeBlocksTest {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun testImageBlockPluginCapabilities() {
        val imageBlock = ContentBlock.Image(
            blockId = "img_1",
            sequenceIndex = 0,
            url = "https://example.com/image.png",
            altText = "Sample image",
        )

        val capabilities = BlockPluginRegistry.getCapabilities(imageBlock)
        assertTrue(capabilities.contains(BlockCapability.DOWNLOADABLE))
        assertTrue(capabilities.contains(BlockCapability.SHAREABLE))
        assertTrue(capabilities.contains(BlockCapability.EXPANDABLE))
    }

    @Test
    fun testCodeBlockVariantAndSerde() {
        val codeBlock = ContentBlock.Code(
            blockId = "code_1",
            sequenceIndex = 0,
            language = "kotlin",
            code = "fun main() { println(\"Hello\") }",
            fileName = "Main.kt",
            isDiff = true,
            showLineNumbers = true,
        )

        val encoded = json.encodeToString<ContentBlock>(codeBlock)
        assertTrue(encoded.contains("\"type\":\"code\""))
        assertTrue(encoded.contains("\"language\":\"kotlin\""))

        val decoded = json.decodeFromString<ContentBlock>(encoded)
        assertEquals(codeBlock, decoded)
    }

    @Test
    fun testCalloutBlockVariantAndSerde() {
        val calloutBlock = ContentBlock.Callout(
            blockId = "callout_1",
            sequenceIndex = 0,
            kind = "warning",
            title = "Warning Title",
            content = "This is a warning callout",
        )

        val encoded = json.encodeToString<ContentBlock>(calloutBlock)
        assertTrue(encoded.contains("\"type\":\"callout\""))
        assertTrue(encoded.contains("\"kind\":\"warning\""))

        val decoded = json.decodeFromString<ContentBlock>(encoded)
        assertEquals(calloutBlock, decoded)
    }

    @Test
    fun testContainerBlockRecursiveComposition() {
        val container = ContentBlock.Container(
            blockId = "card_1",
            sequenceIndex = 0,
            layoutType = "card",
            children = listOf(
                ContentBlock.Markdown(blockId = "card_1_title", sequenceIndex = 0, markdown = "### Title"),
                ContentBlock.Code(blockId = "card_1_code", sequenceIndex = 1, language = "json", code = "{\"ok\":true}"),
            ),
        )

        val encoded = json.encodeToString<ContentBlock>(container)
        assertTrue(encoded.contains("\"type\":\"container\""))
        assertTrue(encoded.contains("\"layout_type\":\"card\""))

        val decoded = json.decodeFromString<ContentBlock>(encoded)
        assertEquals(2, (decoded as ContentBlock.Container).children.size)
    }
}
