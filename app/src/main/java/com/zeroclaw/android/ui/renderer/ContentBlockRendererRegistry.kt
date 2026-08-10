/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.renderer

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.model.content.BlockState
import com.zeroclaw.android.model.content.ContentBlock
import com.zeroclaw.android.runtime.BlockCapability
import com.zeroclaw.android.runtime.BlockContext
import coil3.compose.AsyncImage
import com.zeroclaw.android.ui.component.LinkifiedText
import com.zeroclaw.android.ui.component.MarkdownText
import kotlin.reflect.KClass

/**
 * Generic, UI-independent interaction action protocol for rich content blocks.
 * Renderers emit these interactions without containing any business logic.
 */
sealed interface BlockInteraction {
    val blockId: String

    data class Retry(override val blockId: String) : BlockInteraction
    data class Expand(override val blockId: String) : BlockInteraction
    data class Collapse(override val blockId: String) : BlockInteraction
    data class CopyText(override val blockId: String, val text: String) : BlockInteraction
    data class ShareContent(override val blockId: String, val content: String) : BlockInteraction
    data class OpenFile(override val blockId: String, val uri: String, val mimeType: String) : BlockInteraction
    data class ExecuteTool(
        override val blockId: String,
        val toolCallId: String,
        val toolName: String,
        val inputJson: String,
    ) : BlockInteraction
    data class Navigate(override val blockId: String, val destination: String) : BlockInteraction
    data class CustomAction(
        override val blockId: String,
        val actionId: String,
        val payloadJson: String = "{}",
    ) : BlockInteraction
}

/**
 * Context provided to block renderers for styling and interaction callbacks.
 */
data class RenderContext(
    val textColor: Color = Color.Unspecified,
    val isStreaming: Boolean = false,
    val onInteraction: ((BlockInteraction) -> Unit)? = null,
)

/**
 * Interface implemented by content block renderers.
 */
interface BlockRenderer<T : ContentBlock> {
    @Composable
    fun Render(
        block: T,
        context: RenderContext,
        modifier: Modifier,
    )
}

/**
 * Default fallback renderer for unknown/unregistered blocks.
 */
object UnknownBlockRenderer : BlockRenderer<ContentBlock.Unknown> {
    @Composable
    override fun Render(
        block: ContentBlock.Unknown,
        context: RenderContext,
        modifier: Modifier,
    ) {
        val textColor = if (context.textColor != Color.Unspecified) context.textColor else MaterialTheme.colorScheme.onSurface
        Text(
            text = "[Unsupported Block: ${block.typeId}]",
            style = MaterialTheme.typography.bodySmall,
            color = textColor.copy(alpha = 0.5f),
            modifier = modifier,
        )
    }
}

/**
 * Production composable renderer for [ContentBlock.Callout].
 * Visually distinct alerts for Note, Warning, Success, Error, Tip, and Quote.
 */
object CalloutBlockRenderer : BlockRenderer<ContentBlock.Callout> {
    @Composable
    override fun Render(
        block: ContentBlock.Callout,
        context: RenderContext,
        modifier: Modifier,
    ) {
        val (containerColor, badgeText) = when (block.kind.lowercase()) {
            "warning" -> Pair(Color(0xFFFFF3E0), "⚠️ WARNING")
            "success" -> Pair(Color(0xE8E8F5E9), "✅ SUCCESS")
            "error" -> Pair(Color(0xFFFFEBEE), "❌ ERROR")
            "tip" -> Pair(Color(0xFFF3E5F5), "💡 TIP")
            "quote" -> Pair(MaterialTheme.colorScheme.surfaceVariant, "💬 QUOTE")
            else -> Pair(Color(0xFFE3F2FD), "ℹ️ NOTE")
        }

        Surface(
            shape = MaterialTheme.shapes.small,
            color = containerColor,
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    text = block.title ?: badgeText,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = block.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.9f),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * Production composable renderer for [ContentBlock.Code].
 * Supports header, language badge, copy action, line numbers, and diff view.
 */
object CodeBlockRenderer : BlockRenderer<ContentBlock.Code> {
    @Composable
    override fun Render(
        block: ContentBlock.Code,
        context: RenderContext,
        modifier: Modifier,
    ) {
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.6f),
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
        ) {
            Column {
                // Header Bar
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = block.fileName ?: block.language.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    IconButton(
                        onClick = {
                            context.onInteraction?.invoke(
                                BlockInteraction.CopyText(block.blockId, block.code)
                            )
                        },
                    ) {
                        Text(
                            text = "📋 Copy",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                // Code Snippet with horizontal scroll
                val scrollState = rememberScrollState()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp)
                        .horizontalScroll(scrollState),
                ) {
                    val lines = block.code.lines()
                    Column {
                        lines.forEachIndexed { index, line ->
                            Row {
                                if (block.showLineNumbers) {
                                    Text(
                                        text = "${index + 1} ".padStart(4, ' '),
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                                    )
                                }
                                val textColor = when {
                                    block.isDiff && line.startsWith("+") -> Color(0xFF2E7D32)
                                    block.isDiff && line.startsWith("-") -> Color(0xFFC62828)
                                    else -> MaterialTheme.colorScheme.onSurface
                                }
                                Text(
                                    text = line,
                                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                    color = textColor,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * Production composable renderer for structural [ContentBlock.Container] blocks.
 * Enables recursive block composition for Column, Row, and Card layouts.
 */
object ContainerBlockRenderer : BlockRenderer<ContentBlock.Container> {
    @Composable
    override fun Render(
        block: ContentBlock.Container,
        context: RenderContext,
        modifier: Modifier,
    ) {
        when (block.layoutType.lowercase()) {
            "row" -> {
                Row(
                    modifier = modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    block.children.forEach { child ->
                        key(child.blockId) {
                            ContentBlockRendererRegistry.Render(
                                block = child,
                                context = context,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(horizontal = 4.dp),
                            )
                        }
                    }
                }
            }

            "card" -> {
                Surface(
                    shape = MaterialTheme.shapes.medium,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        block.children.forEach { child ->
                            key(child.blockId) {
                                ContentBlockRendererRegistry.Render(
                                    block = child,
                                    context = context,
                                    modifier = Modifier.padding(vertical = 2.dp),
                                )
                            }
                        }
                    }
                }
            }

            else -> { // Default "column" layout
                Column(
                    modifier = modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    block.children.forEach { child ->
                        key(child.blockId) {
                            ContentBlockRendererRegistry.Render(
                                block = child,
                                context = context,
                                modifier = Modifier.padding(vertical = 2.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Production composable renderer for [ContentBlock.Image].
 * Exercises BlockContext, BlockCapability queries, interactions, and lifecycle states.
 */
object ImageBlockRenderer : BlockRenderer<ContentBlock.Image> {
    @Composable
    override fun Render(
        block: ContentBlock.Image,
        context: RenderContext,
        modifier: Modifier,
    ) {
        val description = block.altText ?: "Image attachment"
        val imageModel = block.url ?: block.base64Data?.let { "data:${block.mimeType};base64,$it" }

        Surface(
            shape = MaterialTheme.shapes.medium,
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
            modifier = modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp)
                .semantics { contentDescription = description }
                .clickable {
                    context.onInteraction?.invoke(
                        BlockInteraction.OpenFile(
                            blockId = block.blockId,
                            uri = block.url ?: block.base64Data ?: "",
                            mimeType = block.mimeType,
                        )
                    )
                },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 280.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (block.state is BlockState.Loading || block.state is BlockState.Streaming) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                        Text(
                            text = "Loading image...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                } else if (block.state is BlockState.Error) {
                    Text(
                        text = "⚠️ Failed to load image: ${(block.state as BlockState.Error).errorMessage}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                } else if (!imageModel.isNullOrBlank()) {
                    AsyncImage(
                        model = imageModel,
                        contentDescription = description,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp),
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(16.dp),
                    ) {
                        Text(
                            text = "🖼️ [Image: $description]",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (!block.url.isNullOrBlank()) {
                            Text(
                                text = block.url!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Default fallback dispatcher for standard ContentBlock variants.
 */
object DefaultBlockRenderer : BlockRenderer<ContentBlock> {
    @Composable
    override fun Render(
        block: ContentBlock,
        context: RenderContext,
        modifier: Modifier,
    ) {
        val textColor = if (context.textColor != Color.Unspecified) context.textColor else MaterialTheme.colorScheme.onSurface
        val isBlockStreaming = context.isStreaming || block.state is BlockState.Streaming

        when (block) {
            is ContentBlock.Text -> {
                LinkifiedText(
                    text = if (isBlockStreaming) "${block.text} █" else block.text,
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                    modifier = modifier,
                )
            }

            is ContentBlock.Markdown -> {
                if (!isBlockStreaming) {
                    MarkdownText(
                        markdown = block.markdown,
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor,
                        modifier = modifier,
                    )
                } else LinkifiedText(
                    text = if (isBlockStreaming) "${block.markdown} █" else block.markdown,
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor,
                    modifier = modifier,
                )
            }

            is ContentBlock.Reasoning -> {
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = MaterialTheme.shapes.small,
                    modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = if (isBlockStreaming) "Thinking..." else "Thinking",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(
                            text = block.reasoningText,
                            style = MaterialTheme.typography.bodySmall,
                            color = textColor.copy(alpha = 0.8f),
                        )
                    }
                }
            }

            is ContentBlock.ToolCard -> {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.3f),
                    shape = MaterialTheme.shapes.small,
                    modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = "Tool: ${block.toolName} (${block.status})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                        if (block.inputJson.isNotBlank()) {
                            Text(
                                text = block.inputJson,
                                style = MaterialTheme.typography.bodySmall,
                                color = textColor.copy(alpha = 0.7f),
                            )
                        }
                        block.resultBlocks.forEach { subBlock ->
                            ContentBlockRendererRegistry.Render(
                                block = subBlock,
                                context = context.copy(isStreaming = false),
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }

            is ContentBlock.Image -> {
                Text(
                    text = "[Image: ${block.altText ?: block.url ?: "attachment"}]",
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor.copy(alpha = 0.7f),
                    modifier = modifier,
                )
            }

            is ContentBlock.File -> {
                Text(
                    text = "[File: ${block.fileName} (${block.sizeBytes} bytes)]",
                    style = MaterialTheme.typography.bodySmall,
                    color = textColor.copy(alpha = 0.7f),
                    modifier = modifier,
                )
            }

            is ContentBlock.Code -> {
                CodeBlockRenderer.Render(block, context, modifier)
            }

            is ContentBlock.Callout -> {
                CalloutBlockRenderer.Render(block, context, modifier)
            }

            is ContentBlock.Container -> {
                ContainerBlockRenderer.Render(block, context, modifier)
            }

            is ContentBlock.Unknown -> {
                UnknownBlockRenderer.Render(block, context, modifier)
            }
        }
    }
}

/**
 * Registry mapping [ContentBlock] variants to their registered [BlockRenderer]s.
 */
object ContentBlockRendererRegistry {
    private val renderers = mutableMapOf<KClass<out ContentBlock>, BlockRenderer<out ContentBlock>>()
    private var fallbackRenderer: BlockRenderer<ContentBlock.Unknown> = UnknownBlockRenderer

    fun <T : ContentBlock> register(kclass: KClass<T>, renderer: BlockRenderer<T>) {
        renderers[kclass] = renderer
    }

    fun setFallbackRenderer(renderer: BlockRenderer<ContentBlock.Unknown>) {
        fallbackRenderer = renderer
    }

    @Suppress("UNCHECKED_CAST")
    @Composable
    fun Render(
        block: ContentBlock,
        context: RenderContext,
        modifier: Modifier = Modifier,
    ) {
        val registered = renderers[block::class] as? BlockRenderer<ContentBlock>
        if (registered != null) {
            registered.Render(block, context, modifier)
        } else if (block is ContentBlock.Unknown) {
            fallbackRenderer.Render(block, context, modifier)
        } else {
            DefaultBlockRenderer.Render(block, context, modifier)
        }
    }
}
