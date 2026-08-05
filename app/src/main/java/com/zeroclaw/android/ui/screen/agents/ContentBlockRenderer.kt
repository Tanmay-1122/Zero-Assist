/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.agents

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.model.content.ContentBlock
import com.zeroclaw.android.ui.component.LinkifiedText

/**
 * Type-safe composable dispatcher for rendering [ContentBlock] variants.
 */
@Composable
fun RenderContentBlock(
    block: ContentBlock,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    isStreaming: Boolean = false,
    modifier: Modifier = Modifier,
) {
    when (block) {
        is ContentBlock.Text -> {
            LinkifiedText(
                text = if (isStreaming) "${block.text} █" else block.text,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor,
                modifier = modifier,
            )
        }

        is ContentBlock.Markdown -> {
            LinkifiedText(
                text = if (isStreaming) "${block.markdown} █" else block.markdown,
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
                        text = "Thinking",
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
                        RenderContentBlock(
                            block = subBlock,
                            textColor = textColor,
                            isStreaming = false,
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

        is ContentBlock.Unknown -> {
            Text(
                text = "[Unsupported Block: ${block.typeId}]",
                style = MaterialTheme.typography.bodySmall,
                color = textColor.copy(alpha = 0.5f),
                modifier = modifier,
            )
        }
    }
}
