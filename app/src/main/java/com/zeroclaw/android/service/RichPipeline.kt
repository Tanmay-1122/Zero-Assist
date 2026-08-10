/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.capability.CapabilityRegistry
import com.zeroclaw.android.model.content.AssistantEvent
import com.zeroclaw.android.model.content.BlockState
import com.zeroclaw.android.model.content.ContentBlock
import com.zeroclaw.android.planner.RichUiPlanner
import com.zeroclaw.android.runtime.BlockRuntime
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * State manager for incremental block reconciliation.
 *
 * Reconciles incoming [AssistantEvent]s into an ordered list of [ContentBlock]s
 * without replacing unaffected block instances. Preserves stable block IDs and order.
 */
class BlockReconciler {
    private val blockMap = mutableMapOf<String, ContentBlock>()
    private val blockOrder = mutableListOf<String>()

    /**
     * Resets reconciler state for a new message stream.
     */
    fun reset() {
        blockMap.clear()
        blockOrder.clear()
    }

    /**
     * Initializes reconciler state with pre-existing blocks (e.g. from Room DB).
     */
    fun initialize(blocks: List<ContentBlock>) {
        reset()
        blocks.forEach { block ->
            blockMap[block.blockId] = block
            blockOrder.add(block.blockId)
        }
    }

    /**
     * Processes an [AssistantEvent] and returns the updated ordered block list.
     */
    fun processEvent(event: AssistantEvent): List<ContentBlock> {
        when (event) {
            is AssistantEvent.BlockStarted -> {
                val block = event.block
                if (!blockMap.containsKey(block.blockId)) {
                    blockOrder.add(block.blockId)
                }
                blockMap[block.blockId] = block
            }

            is AssistantEvent.BlockDelta -> {
                val existing = blockMap[event.blockId]
                if (existing != null) {
                    blockMap[event.blockId] = applyDelta(existing, event.delta)
                }
            }

            is AssistantEvent.ThinkingChunk -> {
                val existing = blockMap[event.blockId]
                if (existing != null) {
                    blockMap[event.blockId] = applyDelta(existing, event.delta)
                } else {
                    val newBlock = ContentBlock.Reasoning(
                        version = event.version,
                        blockId = event.blockId,
                        sequenceIndex = blockOrder.size,
                        reasoningText = event.delta,
                        isComplete = false,
                        state = BlockState.Streaming,
                    )
                    blockOrder.add(event.blockId)
                    blockMap[event.blockId] = newBlock
                }
            }

            is AssistantEvent.TextChunk -> {
                val existing = blockMap[event.blockId]
                if (existing != null) {
                    blockMap[event.blockId] = applyDelta(existing, event.delta)
                } else {
                    val newBlock = ContentBlock.Markdown(
                        version = event.version,
                        blockId = event.blockId,
                        sequenceIndex = blockOrder.size,
                        markdown = event.delta,
                        state = BlockState.Streaming,
                    )
                    blockOrder.add(event.blockId)
                    blockMap[event.blockId] = newBlock
                }
            }

            is AssistantEvent.BlockUpdated -> {
                val block = event.block
                if (!blockMap.containsKey(block.blockId)) {
                    blockOrder.add(block.blockId)
                }
                blockMap[block.blockId] = block
            }

            is AssistantEvent.ReasoningFinished, is AssistantEvent.BlockFinished -> {
                val blockId = when (event) {
                    is AssistantEvent.ReasoningFinished -> event.blockId
                    is AssistantEvent.BlockFinished -> event.blockId
                    else -> ""
                }
                val existing = blockMap[blockId]
                if (existing != null) {
                    blockMap[blockId] = setBlockState(existing, BlockState.Ready)
                }
            }

            is AssistantEvent.BlockError -> {
                val existing = blockMap[event.blockId]
                if (existing != null) {
                    blockMap[event.blockId] = setBlockState(
                        existing,
                        BlockState.Error(event.errorCode, event.errorMessage),
                    )
                }
            }

            is AssistantEvent.StreamFinished -> {
                blockMap.keys.toList().forEach { id ->
                    val block = blockMap[id]
                    if (block != null && (block.state is BlockState.Streaming || block.state is BlockState.Loading)) {
                        blockMap[id] = setBlockState(block, BlockState.Ready)
                    }
                }
            }

            is AssistantEvent.StreamError -> {
                blockMap.keys.toList().forEach { id ->
                    val block = blockMap[id]
                    if (block != null && (block.state is BlockState.Streaming || block.state is BlockState.Loading)) {
                        blockMap[id] = setBlockState(
                            block,
                            BlockState.Error(event.errorCode, event.errorMessage),
                        )
                    }
                }
            }

            is AssistantEvent.StreamCancelled -> {
                blockMap.keys.toList().forEach { id ->
                    val block = blockMap[id]
                    if (block != null && (block.state is BlockState.Streaming || block.state is BlockState.Loading)) {
                        blockMap[id] = setBlockState(block, BlockState.Cancelled)
                    }
                }
            }

            is AssistantEvent.StreamStarted -> {
                // Initial stream started signal
            }
        }

        return blockOrder.mapNotNull { blockMap[it] }
    }

    private fun applyDelta(block: ContentBlock, delta: String): ContentBlock {
        return when (block) {
            is ContentBlock.Text -> block.copy(
                text = block.text + delta,
                state = BlockState.Streaming,
            )

            is ContentBlock.Markdown -> block.copy(
                markdown = block.markdown + delta,
                state = BlockState.Streaming,
            )

            is ContentBlock.Reasoning -> block.copy(
                reasoningText = block.reasoningText + delta,
                state = BlockState.Streaming,
            )

            is ContentBlock.ToolCard -> block.copy(
                inputJson = block.inputJson + delta,
                state = BlockState.Streaming,
            )

            else -> block
        }
    }

    private fun setBlockState(block: ContentBlock, newState: BlockState): ContentBlock {
        return when (block) {
            is ContentBlock.Text -> block.copy(state = newState)
            is ContentBlock.Markdown -> block.copy(state = newState)
            is ContentBlock.Reasoning -> block.copy(state = newState, isComplete = newState is BlockState.Ready)
            is ContentBlock.Image -> block.copy(state = newState)
            is ContentBlock.File -> block.copy(state = newState)
            is ContentBlock.ToolCard -> block.copy(state = newState)
            is ContentBlock.Code -> block.copy(state = newState)
            is ContentBlock.Callout -> block.copy(state = newState)
            is ContentBlock.Container -> block.copy(state = newState)
            is ContentBlock.Unknown -> block.copy(state = newState)
        }
    }
}

/**
 * Execution pipeline mode configuration.
 */
enum class PipelineMode {
    LEGACY, // Phase 1 pure text streaming
    HYBRID, // Legacy text with rich tool cards
    RICH,   // Complete Phase 6 rich content & UI planning pipeline
}

/**
 * Feature flag configuration manager for controlling rich content rollout.
 */
object RichPipelineFeatureFlags {
    private val _mode = MutableStateFlow(PipelineMode.RICH)
    val mode: StateFlow<PipelineMode> = _mode.asStateFlow()

    fun setMode(newMode: PipelineMode) {
        _mode.value = newMode
    }

    fun isRichEnabled(): Boolean = _mode.value == PipelineMode.RICH
    fun isHybridEnabled(): Boolean = _mode.value == PipelineMode.HYBRID || _mode.value == PipelineMode.RICH
}

/**
 * Security audit sanitizer for cleaning untrusted inputs, Markdown script tags, and unsafe URIs.
 */
object RichSecuritySanitizer {

    private val scriptTagRegex = Regex("(?i)<script.*?>.*?</script>", RegexOption.DOT_MATCHES_ALL)
    private val unsafeUriPrefixes = listOf("javascript:", "data:text/html", "file:///system")

    /**
     * Sanitizes raw text content removing embedded script tags or unsafe URI patterns.
     */
    fun sanitizeText(input: String): String {
        return scriptTagRegex.replace(input, "[sanitized script]")
    }

    /**
     * Sanitizes a URI string. Returns null if unsafe.
     */
    fun sanitizeUri(uri: String?): String? {
        if (uri.isNullOrBlank()) return null
        val lower = uri.lowercase()
        return if (unsafeUriPrefixes.any { lower.startsWith(it) }) {
            null
        } else {
            uri
        }
    }

    /**
     * Sanitizes a [ContentBlock] instance recursively.
     */
    fun sanitizeBlock(block: ContentBlock): ContentBlock {
        return when (block) {
            is ContentBlock.Text -> block.copy(text = sanitizeText(block.text))
            is ContentBlock.Markdown -> block.copy(markdown = sanitizeText(block.markdown))
            is ContentBlock.Image -> block.copy(
                url = sanitizeUri(block.url),
                altText = block.altText?.let { sanitizeText(it) },
            )
            is ContentBlock.File -> block.copy(
                uri = sanitizeUri(block.uri),
                fileName = sanitizeText(block.fileName),
            )
            is ContentBlock.Code -> block.copy(
                code = sanitizeText(block.code),
                fileName = block.fileName?.let { sanitizeText(it) },
            )
            is ContentBlock.Callout -> block.copy(
                title = block.title?.let { sanitizeText(it) },
                content = sanitizeText(block.content),
            )
            is ContentBlock.Container -> block.copy(
                children = block.children.map { sanitizeBlock(it) },
            )
            else -> block
        }
    }
}

/**
 * System prompt generator instructing LLMs to emit concise text and structured UI payloads.
 */
object RichPromptEngine {

    private const val SYSTEM_RICH_INSTRUCTIONS = """
You are Zero-Assist, an advanced AI assistant supporting rich interactive media interfaces.
Guidelines:
1. Provide concise, clear responses.
2. For tool executions, search results, comparisons, image galleries, and data tables, return semantic JSON data structures with result types.
3. CRITICAL: You are connected to a rich client runtime capable of rendering images (ImageBlock), galleries (GalleryBlock), maps, code diffs, and file attachments natively.
4. NEVER state "I cannot display images", "I cannot view images", or "I am a text-based model". When asked for images, invoke media tools or emit structured semantic media JSON objects containing asset URLs.
5. Keep code snippets well-formatted with file names and language tags.
"""

    /**
     * Enhances system prompt with rich UI guidance and available abstract capabilities.
     */
    fun buildSystemPrompt(basePrompt: String): String {
        return if (RichPipelineFeatureFlags.isRichEnabled()) {
            val capabilitiesDescription = CapabilityRegistry.getAllCapabilities().joinToString("\n") { cap ->
                "- ${cap.id}: ${cap.description}"
            }
            "$basePrompt\n$SYSTEM_RICH_INSTRUCTIONS\nAvailable Capabilities:\n$capabilitiesDescription"
        } else {
            basePrompt
        }
    }
}

/**
 * High-level provider execution pipeline connecting provider streams to UI planning and BlockRuntime.
 */
class RichProviderPipeline(
    val runtime: BlockRuntime,
) {
    /**
     * Processes an incoming [AssistantEvent] through security sanitization, UI planning, and BlockRuntime.
     */
    fun processEvent(event: AssistantEvent): List<ContentBlock> {
        val sanitizedEvent = when (event) {
            is AssistantEvent.BlockStarted -> event.copy(block = RichSecuritySanitizer.sanitizeBlock(event.block))
            is AssistantEvent.BlockUpdated -> event.copy(block = RichSecuritySanitizer.sanitizeBlock(event.block))
            else -> event
        }

        val updatedBlocks = runtime.processAssistantEvent(sanitizedEvent)

        return if (RichPipelineFeatureFlags.isRichEnabled()) {
            val plannedContainer = RichUiPlanner.planUi("Response", updatedBlocks)
            listOf(plannedContainer)
        } else {
            updatedBlocks
        }
    }
}

/**
 * Migration adapters for adapting core tool outputs to semantic UI block trees.
 */
object RichToolMigration {

    /**
     * Adapts web search results into an interactive search results card tree.
     */
    fun adaptWebSearchOutput(query: String, resultsJson: String): ContentBlock {
        return RichUiPlanner.planToolUi("Web Search: $query", resultsJson)
    }

    /**
     * Adapts device control progress into a structured timeline block.
     */
    fun adaptDeviceControlOutput(taskName: String, status: String, details: String): ContentBlock {
        return ContentBlock.Container(
            blockId = "device_task_${System.currentTimeMillis()}",
            sequenceIndex = 0,
            layoutType = "card",
            children = listOf(
                ContentBlock.Callout(
                    blockId = "device_task_callout",
                    sequenceIndex = 0,
                    kind = if (status.lowercase() == "success") "success" else "info",
                    title = "⚡ Device Control: $taskName",
                    content = "Status: $status\n$details",
                )
            ),
        )
    }

    /**
     * Adapts GitHub PR or issue diff data into a Code block with diff highlighting.
     */
    fun adaptGitHubDiffOutput(prTitle: String, diffText: String): ContentBlock {
        return ContentBlock.Container(
            blockId = "gh_pr_${System.currentTimeMillis()}",
            sequenceIndex = 0,
            layoutType = "card",
            children = listOf(
                ContentBlock.Markdown(blockId = "gh_title", sequenceIndex = 0, markdown = "### 🐙 GitHub PR: $prTitle"),
                ContentBlock.Code(
                    blockId = "gh_diff_code",
                    sequenceIndex = 1,
                    language = "diff",
                    code = diffText,
                    fileName = "changes.patch",
                    isDiff = true,
                )
            ),
        )
    }
}
