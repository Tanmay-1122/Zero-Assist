/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.media

import com.zeroclaw.android.model.content.ContentBlock
import com.zeroclaw.android.planner.DeclarativeUiNode
import com.zeroclaw.android.planner.ToolSemanticUiBridge
import com.zeroclaw.android.service.RichPipelineFeatureFlags
import com.zeroclaw.android.service.RichPromptEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RichMediaCapabilityTest {

    @Test
    fun testIntentClassificationForImageRequest() {
        val query = "Give me the image of Mr Beast"
        val intent = MediaIntentClassifier.classifyIntent(query)
        assertEquals(MediaIntent.IMAGE_SEARCH, intent)
        assertEquals(MediaCategory.IMAGE, intent.category)
    }

    @Test
    fun testMediaSearchToolStructuredOutput() {
        val jsonOutput = MediaSearchTool.searchMedia("Mr Beast", MediaCategory.IMAGE)
        assertTrue(jsonOutput.contains("\"type\": \"image\""))
        assertTrue(jsonOutput.contains("\"url\": \"https://example.com/media/mr_beast.png\""))
    }

    @Test
    fun testToolSemanticUiBridgeImageBlockLowering() {
        val jsonOutput = MediaSearchTool.searchMedia("Mr Beast", MediaCategory.IMAGE)
        val uiNode = ToolSemanticUiBridge.transformToolOutputToUiNode("media_search", jsonOutput)
        val block = uiNode.toContentBlock(0)

        assertNotNull(block)
        assertTrue(block is ContentBlock.Image)
        val imageBlock = block as ContentBlock.Image
        assertTrue(imageBlock.url!!.contains("mr_beast.png"))
    }

    @Test
    fun testSystemPromptMediaRenderingInstruction() {
        RichPipelineFeatureFlags.setMode(com.zeroclaw.android.service.PipelineMode.RICH)
        val systemPrompt = RichPromptEngine.buildSystemPrompt("Base prompt")
        assertTrue(systemPrompt.contains("NEVER state \"I cannot display images\""))
        assertTrue(systemPrompt.contains("ImageBlock"))
    }

    @Test
    fun testEndToEndImageQueryProducesImageBlock() {
        // Given user query "Give me the image of Mr Beast"
        val query = "Give me the image of Mr Beast"
        val intent = MediaIntentClassifier.classifyIntent(query)

        // Route to MediaSearchTool
        val jsonText = MediaSearchTool.searchMedia("Mr Beast", intent.category)

        // Lower via ToolSemanticUiBridge
        val node = ToolSemanticUiBridge.transformToolOutputToUiNode("image_search", jsonText)
        val contentBlock = node.toContentBlock(0)

        // Verify output is ContentBlock.Image instead of text markdown
        assertTrue(contentBlock is ContentBlock.Image)
        assertFalse(contentBlock is ContentBlock.Text)
        assertFalse(contentBlock is ContentBlock.Markdown)
    }
}
