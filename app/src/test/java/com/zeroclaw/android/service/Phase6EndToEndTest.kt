/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.diagnostics.RichRuntimeDiagnostics
import com.zeroclaw.android.model.content.AssistantEvent
import com.zeroclaw.android.model.content.ContentBlock
import com.zeroclaw.android.runtime.BlockRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase6EndToEndTest {

    @Test
    fun testFeatureFlagsModeToggling() {
        RichPipelineFeatureFlags.setMode(PipelineMode.LEGACY)
        assertFalse(RichPipelineFeatureFlags.isRichEnabled())

        RichPipelineFeatureFlags.setMode(PipelineMode.RICH)
        assertTrue(RichPipelineFeatureFlags.isRichEnabled())
        assertTrue(RichPipelineFeatureFlags.isHybridEnabled())
    }

    @Test
    fun testSecuritySanitizerScriptAndUri() {
        val unsafeText = "Hello <script>alert('hack')</script> World"
        val sanitizedText = RichSecuritySanitizer.sanitizeText(unsafeText)
        assertFalse(sanitizedText.contains("<script>"))

        assertNull(RichSecuritySanitizer.sanitizeUri("javascript:alert(1)"))
        assertNotNull(RichSecuritySanitizer.sanitizeUri("https://example.com/file.png"))
    }

    @Test
    fun testPromptEngineEnhancement() {
        RichPipelineFeatureFlags.setMode(PipelineMode.RICH)
        val prompt = RichPromptEngine.buildSystemPrompt("Base prompt")
        assertTrue(prompt.contains("Zero-Assist"))
        assertTrue(prompt.contains("semantic JSON"))
    }

    @Test
    fun testRichToolMigrationAdapters() {
        val webSearchBlock = RichToolMigration.adaptWebSearchOutput("ZeroClaw", "{\"results\":\"found\"}")
        assertTrue(webSearchBlock is ContentBlock.Container)

        val deviceTaskBlock = RichToolMigration.adaptDeviceControlOutput("Screen Capture", "success", "Captured 1080p frame")
        assertTrue(deviceTaskBlock is ContentBlock.Container)

        val diffBlock = RichToolMigration.adaptGitHubDiffOutput("Fix bug", "+ line added\n- line removed")
        assertTrue(diffBlock is ContentBlock.Container)
    }

    @Test
    fun testRichProviderPipelineExecution() {
        RichPipelineFeatureFlags.setMode(PipelineMode.RICH)
        val runtime = BlockRuntime("conv_p6", "msg_p6")
        val pipeline = RichProviderPipeline(runtime)

        val startEvent = AssistantEvent.BlockStarted(
            version = 1,
            messageId = "msg_p6",
            conversationId = "conv_p6",
            block = ContentBlock.Markdown(
                blockId = "b1",
                sequenceIndex = 0,
                markdown = "Unsanitized <script>evil()</script> test",
            ),
        )

        val blocks = pipeline.processEvent(startEvent)
        assertNotNull(blocks)
        assertTrue(blocks.isNotEmpty())

        RichRuntimeDiagnostics.record("PIPELINE", "Processed event successfully", 12L)
        val summary = RichRuntimeDiagnostics.dumpSummary()
        assertTrue(summary.contains("PIPELINE"))

        runtime.destroy()
    }
}
