/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.planner

import com.zeroclaw.android.model.content.ContentBlock
import com.zeroclaw.android.ui.renderer.BlockInteraction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase5UiPlannerTest {

    @Test
    fun testDeclarativeUiSchemaLowering() {
        val containerNode = DeclarativeUiNode.ContainerNode(
            id = "c1",
            layoutType = "column",
            title = "Test Column",
            children = listOf(
                DeclarativeUiNode.ContentNode(
                    id = "b1",
                    block = ContentBlock.Text(blockId = "b1", sequenceIndex = 0, text = "Hello"),
                )
            ),
        )

        val loweredBlock = containerNode.toContentBlock(0)
        assertTrue(loweredBlock is ContentBlock.Container)
        val container = loweredBlock as ContentBlock.Container
        assertEquals("c1", container.blockId)
        assertEquals("column", container.layoutType)
        assertEquals(2, container.children.size) // Title + 1 child
    }

    @Test
    fun testAdaptiveLayoutEngineInference() {
        val images = listOf(
            ContentBlock.Image(blockId = "i1", sequenceIndex = 0, url = "url1"),
            ContentBlock.Image(blockId = "i2", sequenceIndex = 1, url = "url2"),
        )
        val pattern = AdaptiveLayoutEngine.inferLayoutPattern(images)
        assertEquals("gallery", pattern)

        val keys = setOf("results", "total_count")
        val semanticPattern = AdaptiveLayoutEngine.inferLayoutFromSemanticKeys(keys)
        assertEquals("card_grid", semanticPattern)
    }

    @Test
    fun testToolSemanticUiBridgeTransformation() {
        val jsonToolOutput = """
            {
                "query": "Zero-Assist AI",
                "status": "success",
                "count": "42"
            }
        """.trimIndent()

        val uiNode = ToolSemanticUiBridge.transformToolOutputToUiNode("web_search", jsonToolOutput)
        val block = uiNode.toContentBlock(0)
        assertTrue(block is ContentBlock.Container)

        val container = block as ContentBlock.Container
        assertTrue(container.children.size >= 3)
    }

    @Test
    fun testInteractionPlannerInference() {
        val block = ContentBlock.Text(blockId = "t1", sequenceIndex = 0, text = "Copyable text")
        val interactions = InteractionPlanner.inferAvailableInteractions(block)

        assertTrue(interactions.any { it is BlockInteraction.CopyText })
    }

    @Test
    fun testRichUiPlannerAndInspector() {
        val blocks = listOf(
            ContentBlock.Markdown(blockId = "md1", sequenceIndex = 0, markdown = "Section 1"),
            ContentBlock.Markdown(blockId = "md2", sequenceIndex = 1, markdown = "Section 2"),
        )

        val plannedBlock = RichUiPlanner.planUi("Dashboard Title", blocks)
        assertTrue(plannedBlock is ContentBlock.Container)

        val diagnosticTree = UiPlannerInspector.inspectBlockTree(plannedBlock)
        assertTrue(diagnosticTree.contains("Dashboard Title"))
        assertTrue(diagnosticTree.contains("Container"))
    }
}
