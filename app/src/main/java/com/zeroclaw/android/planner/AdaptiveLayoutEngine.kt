/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.planner

import com.zeroclaw.android.model.content.AssistantEvent
import com.zeroclaw.android.model.content.ContentBlock
import com.zeroclaw.android.runtime.BlockCapability
import com.zeroclaw.android.runtime.plugin.BlockPluginRegistry
import com.zeroclaw.android.ui.renderer.BlockInteraction

/**
 * Adaptive Layout Engine inferring optimal UI layout structure based on content characteristics.
 */
object AdaptiveLayoutEngine {

    /**
     * Determines layout pattern string for a list of content blocks or data nodes.
     */
    fun inferLayoutPattern(blocks: List<ContentBlock>): String {
        if (blocks.isEmpty()) return "column"

        val imageCount = blocks.count { it is ContentBlock.Image }
        val codeCount = blocks.count { it is ContentBlock.Code }
        val fileCount = blocks.count { it is ContentBlock.File }

        return when {
            imageCount >= 2 -> "gallery"
            codeCount >= 2 -> "sections"
            fileCount >= 2 -> "card_grid"
            else -> "column"
        }
    }

    /**
     * Infers layout pattern from semantic JSON keys.
     */
    fun inferLayoutFromSemanticKeys(keys: Set<String>): String {
        return when {
            keys.contains("results") || keys.contains("items") -> "card_grid"
            keys.contains("comparison") || keys.contains("matrix") -> "table"
            keys.contains("steps") || keys.contains("workflow") -> "timeline"
            keys.contains("images") || keys.contains("gallery") -> "gallery"
            else -> "card"
        }
    }
}

/**
 * Interaction Planning engine inferring supported UI actions for blocks based on capabilities.
 */
object InteractionPlanner {

    /**
     * Infers list of available [BlockInteraction] actions supported by a block.
     */
    fun inferAvailableInteractions(block: ContentBlock): List<BlockInteraction> {
        val capabilities = BlockPluginRegistry.getCapabilities(block)
        val actions = mutableListOf<BlockInteraction>()

        if (capabilities.contains(BlockCapability.COPYABLE)) {
            val text = extractCopyableText(block) ?: ""
            if (text.isNotBlank()) {
                actions.add(BlockInteraction.CopyText(block.blockId, text))
            }
        }

        if (capabilities.contains(BlockCapability.SHAREABLE)) {
            actions.add(BlockInteraction.ShareContent(block.blockId, block.blockId))
        }

        if (capabilities.contains(BlockCapability.EXPANDABLE)) {
            actions.add(BlockInteraction.Expand(block.blockId))
        }

        if (capabilities.contains(BlockCapability.DOWNLOADABLE)) {
            val uri = when (block) {
                is ContentBlock.Image -> block.url ?: block.base64Data ?: ""
                is ContentBlock.File -> block.uri ?: ""
                else -> ""
            }
            if (uri.isNotBlank()) {
                actions.add(BlockInteraction.OpenFile(block.blockId, uri, "application/octet-stream"))
            }
        }

        return actions
    }

    private fun extractCopyableText(block: ContentBlock): String? {
        return when (block) {
            is ContentBlock.Text -> block.text
            is ContentBlock.Markdown -> block.markdown
            is ContentBlock.Reasoning -> block.reasoningText
            is ContentBlock.Code -> block.code
            is ContentBlock.Callout -> block.content
            is ContentBlock.ToolCard -> block.inputJson
            else -> null
        }
    }
}

/**
 * Diagnostic explainability inspector for debugging UI planner decisions and block trees.
 */
object UiPlannerInspector {

    /**
     * Dumps human-readable diagnostic tree string for a [DeclarativeUiNode].
     */
    fun inspectNodeTree(node: DeclarativeUiNode, depth: Int = 0): String {
        val indent = "  ".repeat(depth)
        val sb = StringBuilder()
        when (node) {
            is DeclarativeUiNode.ContainerNode -> {
                sb.appendLine("${indent}└─ Container [${node.layoutType}] id=${node.id} title=${node.title ?: "none"}")
                node.children.forEach { child ->
                    sb.append(inspectNodeTree(child, depth + 1))
                }
            }

            is DeclarativeUiNode.ContentNode -> {
                sb.appendLine("${indent}└─ Leaf block [${node.block::class.simpleName}] id=${node.id}")
            }
        }
        return sb.toString()
    }

    /**
     * Dumps diagnostic string for a [ContentBlock] tree.
     */
    fun inspectBlockTree(block: ContentBlock, depth: Int = 0): String {
        val indent = "  ".repeat(depth)
        val sb = StringBuilder()
        if (block is ContentBlock.Container) {
            sb.appendLine("${indent}└─ Container [${block.layoutType}] id=${block.blockId} (${block.children.size} children)")
            block.children.forEach { child ->
                sb.append(inspectBlockTree(child, depth + 1))
            }
        } else {
            val content = when (block) {
                is ContentBlock.Text -> block.text
                is ContentBlock.Markdown -> block.markdown
                is ContentBlock.Reasoning -> block.reasoningText
                is ContentBlock.Code -> block.code
                is ContentBlock.Callout -> block.content
                else -> null
            }
            val contentSuffix = if (content != null) " content=\"$content\"" else ""
            sb.appendLine("${indent}└─ Block [${block::class.simpleName}] id=${block.blockId} state=${block.state}$contentSuffix")
        }
        return sb.toString()
    }
}

/**
 * Dynamic streaming replanner updating intermediate UI subtrees incrementally.
 */
class DynamicReplanningEngine {
    private var currentUiTree: DeclarativeUiNode? = null

    fun reset() {
        currentUiTree = null
    }

    /**
     * Reconciles streaming event with current UI tree and produces updated blocks.
     */
    fun reconcileEvent(event: AssistantEvent, existingBlocks: List<ContentBlock>): List<ContentBlock> {
        return when (event) {
            is AssistantEvent.BlockStarted -> {
                val node = DeclarativeUiNode.ContentNode(id = event.block.blockId, block = event.block)
                val current = currentUiTree
                if (current is DeclarativeUiNode.ContainerNode) {
                    val updated = current.copy(children = current.children + node)
                    currentUiTree = updated
                    listOf(updated.toContentBlock(0))
                } else {
                    currentUiTree = node
                    existingBlocks + event.block
                }
            }
            else -> existingBlocks
        }
    }
}
