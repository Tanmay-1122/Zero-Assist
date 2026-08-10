/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.planner

import com.zeroclaw.android.model.content.ContentBlock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Provider-independent intermediate UI node tree schema.
 * Represents structured layout intent before lowering to target [ContentBlock] instances.
 */
sealed interface DeclarativeUiNode {
    val id: String
    val layoutType: String
    val metadata: Map<String, String>

    /**
     * Lowers this intermediate UI node into concrete [ContentBlock] instances.
     */
    fun toContentBlock(sequenceIndex: Int = 0): ContentBlock

    data class ContainerNode(
        override val id: String,
        override val layoutType: String = "column", // column, row, card, grid, timeline
        val title: String? = null,
        val children: List<DeclarativeUiNode> = emptyList(),
        override val metadata: Map<String, String> = emptyMap(),
    ) : DeclarativeUiNode {
        override fun toContentBlock(sequenceIndex: Int): ContentBlock {
            val childBlocks = children.mapIndexed { index, child -> child.toContentBlock(index) }
            val containerChild = if (!title.isNullOrBlank()) {
                listOf(ContentBlock.Markdown(blockId = "${id}_title", sequenceIndex = 0, markdown = "### $title")) + childBlocks
            } else {
                childBlocks
            }

            return ContentBlock.Container(
                blockId = id,
                sequenceIndex = sequenceIndex,
                layoutType = layoutType,
                children = containerChild,
            )
        }
    }

    data class ContentNode(
        override val id: String,
        override val layoutType: String = "leaf",
        val block: ContentBlock,
        override val metadata: Map<String, String> = emptyMap(),
    ) : DeclarativeUiNode {
        override fun toContentBlock(sequenceIndex: Int): ContentBlock {
            return block
        }
    }
}

/**
 * Reusable layout template interface.
 */
fun interface UiTemplate {
    fun build(title: String, items: List<ContentBlock>): DeclarativeUiNode
}

/**
 * Pre-packaged UI templates for common interaction patterns.
 */
object UiTemplateRegistry {

    val SearchResultsTemplate = UiTemplate { title, items ->
        DeclarativeUiNode.ContainerNode(
            id = "template_search_${System.currentTimeMillis()}",
            layoutType = "card",
            title = "🔍 Search Results: $title",
            children = items.mapIndexed { index, block ->
                DeclarativeUiNode.ContentNode(
                    id = "${block.blockId}_node",
                    block = block,
                )
            },
        )
    }

    val ImageGalleryTemplate = UiTemplate { title, items ->
        DeclarativeUiNode.ContainerNode(
            id = "template_gallery_${System.currentTimeMillis()}",
            layoutType = "row",
            title = "🖼️ $title",
            children = items.mapIndexed { index, block ->
                DeclarativeUiNode.ContentNode(
                    id = "${block.blockId}_node",
                    block = block,
                )
            },
        )
    }

    val DashboardTemplate = UiTemplate { title, items ->
        DeclarativeUiNode.ContainerNode(
            id = "template_dashboard_${System.currentTimeMillis()}",
            layoutType = "column",
            title = "📊 $title",
            children = items.mapIndexed { index, block ->
                DeclarativeUiNode.ContentNode(
                    id = "${block.blockId}_node",
                    block = block,
                )
            },
        )
    }

    val TimelineTemplate = UiTemplate { title, items ->
        DeclarativeUiNode.ContainerNode(
            id = "template_timeline_${System.currentTimeMillis()}",
            layoutType = "column",
            title = "⏱️ $title",
            children = items.mapIndexed { index, block ->
                DeclarativeUiNode.ContentNode(
                    id = "${block.blockId}_node",
                    block = block,
                )
            },
        )
    }
}

/**
 * Bridge transforming raw semantic tool JSON outputs into structured UI node trees.
 */
object ToolSemanticUiBridge {
    private val jsonParser = Json { ignoreUnknownKeys = true }

    /**
     * Transforms raw tool input/result JSON into a structured [DeclarativeUiNode].
     */
    fun transformToolOutputToUiNode(toolName: String, rawJsonText: String): DeclarativeUiNode {
        val jsonText = sanitizeJsonText(rawJsonText)
        return try {
            val jsonElement = jsonParser.parseToJsonElement(jsonText)
            if (jsonElement is JsonObject) {
                // Check if this is a semantic media response
                val resultsArray = jsonElement["results"] as? JsonArray
                if (resultsArray != null && resultsArray.isNotEmpty()) {
                    val mediaBlocks = mutableListOf<ContentBlock>()
                    resultsArray.forEachIndexed { idx, elem ->
                        if (elem is JsonObject) {
                            val url = elem["url"]?.jsonPrimitive?.content
                            val title = elem["title"]?.jsonPrimitive?.content ?: elem["altText"]?.jsonPrimitive?.content ?: "Media"
                            val type = elem["type"]?.jsonPrimitive?.content ?: "image"

                            if (url != null && (type == "image" || url.endsWith(".png") || url.endsWith(".jpg") || url.endsWith(".jpeg"))) {
                                mediaBlocks.add(
                                    ContentBlock.Image(
                                        blockId = "img_${toolName}_$idx",
                                        sequenceIndex = idx,
                                        url = url,
                                        altText = title,
                                    )
                                )
                            }
                        }
                    }

                    if (mediaBlocks.isNotEmpty()) {
                        return if (mediaBlocks.size == 1) {
                            DeclarativeUiNode.ContentNode(
                                id = "media_node_single",
                                block = mediaBlocks.first(),
                            )
                        } else {
                            UiTemplateRegistry.ImageGalleryTemplate.build(toolName, mediaBlocks)
                        }
                    }
                }

                val keys = jsonElement.keys
                val pattern = AdaptiveLayoutEngine.inferLayoutFromSemanticKeys(keys)

                val items = mutableListOf<ContentBlock>()
                jsonElement.forEach { (key, value) ->
                    val valueStr = if (value is kotlinx.serialization.json.JsonPrimitive) value.content else value.toString()
                    items.add(
                        ContentBlock.Callout(
                            blockId = "tool_${toolName}_$key",
                            sequenceIndex = items.size,
                            kind = "info",
                            title = key.replace("_", " ").uppercase(),
                            content = valueStr,
                        )
                    )
                }

                when (pattern) {
                    "card_grid" -> UiTemplateRegistry.SearchResultsTemplate.build(toolName, items)
                    "gallery" -> UiTemplateRegistry.ImageGalleryTemplate.build(toolName, items)
                    "timeline" -> UiTemplateRegistry.TimelineTemplate.build(toolName, items)
                    else -> UiTemplateRegistry.DashboardTemplate.build(toolName, items)
                }
            } else {
                // Fallback to plain markdown callout node
                DeclarativeUiNode.ContentNode(
                    id = "tool_${toolName}_fallback",
                    block = ContentBlock.Markdown(
                        blockId = "tool_${toolName}_md",
                        sequenceIndex = 0,
                        markdown = rawJsonText,
                    ),
                )
            }
        } catch (e: Exception) {
            // Defensive fallback for any malformed output
            DeclarativeUiNode.ContentNode(
                id = "tool_${toolName}_err",
                block = ContentBlock.Markdown(
                    blockId = "tool_${toolName}_raw",
                    sequenceIndex = 0,
                    markdown = rawJsonText,
                ),
            )
        }
    }

    private fun sanitizeJsonText(input: String): String {
        val trimmed = input.trim()
        return if (trimmed.startsWith("```")) {
            trimmed.substringAfter("\n").substringBeforeLast("```").trim()
        } else {
            trimmed
        }
    }
}

/**
 * Main UI planning orchestrator transforming semantic inputs, raw tool JSON,
 * or block streams into optimized structured UI trees.
 */
object RichUiPlanner {

    /**
     * Plans a structured UI tree for a set of raw content blocks.
     */
    fun planUi(title: String = "Response", blocks: List<ContentBlock>): ContentBlock {
        if (blocks.isEmpty()) {
            return ContentBlock.Markdown(blockId = "empty_plan", sequenceIndex = 0, markdown = "")
        }

        val pattern = AdaptiveLayoutEngine.inferLayoutPattern(blocks)
        val template = when (pattern) {
            "gallery" -> UiTemplateRegistry.ImageGalleryTemplate
            "card_grid" -> UiTemplateRegistry.SearchResultsTemplate
            else -> UiTemplateRegistry.DashboardTemplate
        }

        val nodeTree = template.build(title, blocks)
        return nodeTree.toContentBlock(0)
    }

    /**
     * Plans UI for tool execution results.
     */
    fun planToolUi(toolName: String, jsonOutput: String): ContentBlock {
        val nodeTree = ToolSemanticUiBridge.transformToolOutputToUiNode(toolName, jsonOutput)
        return nodeTree.toContentBlock(0)
    }
}
