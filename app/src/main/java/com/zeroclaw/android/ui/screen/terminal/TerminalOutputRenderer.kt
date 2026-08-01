/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

@file:Suppress("TooManyFunctions")

package com.zeroclaw.android.ui.screen.terminal

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.ui.component.MarkdownText
import com.zeroclaw.android.ui.theme.InlineTerminalError
import com.zeroclaw.android.ui.theme.TerminalTypography
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject

/** Horizontal padding inside each terminal block. */
private const val BLOCK_HORIZONTAL_PADDING_DP = 12

/** Vertical padding inside each terminal block. */
private const val BLOCK_VERTICAL_PADDING_DP = 8

/** Corner radius for structured output containers. */
private const val STRUCTURED_CORNER_DP = 8

/** Border width for structured output containers. */
private const val STRUCTURED_BORDER_DP = 1

/** Indentation spaces for pretty-printed JSON. */
private const val JSON_INDENT_SPACES = 2

/** JSON key for daemon running status detection. */
private const val KEY_DAEMON_RUNNING = "daemon_running"

/** JSON key for session cost detection. */
private const val KEY_SESSION_COST = "session_cost_usd"

/**
 * Renders a single [TerminalBlock] in the terminal scrollback.
 *
 * Each block variant has its own visual style: input lines show a
 * prompt prefix, responses use rendered markdown, structured output
 * renders formatted JSON, errors are highlighted in red, and system
 * messages appear dimmed. All blocks support long-press to copy via
 * [onCopy] and expose merged accessibility semantics.
 *
 * @param block The terminal block to render.
 * @param onCopy Callback invoked with the copyable text on long-press.
 * @param dismissedErrorIds Set of error block IDs already dismissed — survives recomposition.
 * @param modifier Modifier applied to the block container.
 */
@Composable
fun TerminalBlockItem(
    block: TerminalBlock,
    onCopy: (String) -> Unit,
    dismissedErrorIds: MutableSet<Long> = mutableSetOf(),
    modifier: Modifier = Modifier,
) {
    when (block) {
        is TerminalBlock.Input -> InputBlock(block, onCopy, modifier)
        is TerminalBlock.Response -> ResponseBlock(block, onCopy, modifier)
        is TerminalBlock.Structured -> StructuredBlock(block, onCopy, modifier)
        is TerminalBlock.Error -> ErrorBlock(block, onCopy, dismissedErrorIds, modifier)
        is TerminalBlock.System -> SystemBlock(block, onCopy, modifier)
    }
}

/**
 * Renders a user input block with a `> ` prompt prefix.
 *
 * @param block The input block to render.
 * @param onCopy Callback invoked with the input text on long-press.
 * @param modifier Modifier applied to the block container.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun InputBlock(
    block: TerminalBlock.Input,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val isCommand = block.text.startsWith("/")
    val description = if (isCommand) "Command: ${block.text}" else "Message: ${block.text}"

    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.End,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 18.dp, bottomEnd = 4.dp),
            shadowElevation = 2.dp,
            modifier =
                Modifier
                    .fillMaxWidth(0.80f)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { onCopy(block.text) },
                    ).semantics(mergeDescendants = true) {
                        contentDescription = description
                    },
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                Text(
                    text = block.text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
                for (imageName in block.imageNames) {
                    Text(
                        text = "📷 Attached: $imageName",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
}

/**
 * Renders an agent response block with markdown formatting.
 *
 * @param block The response block to render.
 * @param onCopy Callback invoked with the response content on long-press.
 * @param modifier Modifier applied to the block container.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ResponseBlock(
    block: TerminalBlock.Response,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f),
            shape = RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomStart = 4.dp, bottomEnd = 18.dp),
            border = androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
            ),
            shadowElevation = 1.dp,
            modifier =
                Modifier
                    .fillMaxWidth(0.88f)
                    .semantics(mergeDescendants = true) {
                        contentDescription = block.content
                    },
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            ) {
                MarkdownText(
                    markdown = block.content,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    onLongClick = { onCopy(block.content) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * Renders a structured JSON output block with pattern-detected formatting.
 *
 * Detects common response patterns (status, cost summary, arrays) and
 * renders them in a human-readable format. Falls back to pretty-printed
 * JSON for unrecognised structures.
 *
 * @param block The structured block to render.
 * @param onCopy Callback invoked with the raw JSON on long-press.
 * @param modifier Modifier applied to the block container.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StructuredBlock(
    block: TerminalBlock.Structured,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val formattedContent = remember(block.json) { formatStructuredJson(block.json) }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier =
                Modifier
                    .fillMaxWidth(0.86f)
                    .border(
                        width = STRUCTURED_BORDER_DP.dp,
                        color = borderColor,
                        shape = RoundedCornerShape(12.dp),
                    ).combinedClickable(
                        onClick = {},
                        onLongClick = { onCopy(block.json) },
                    ).semantics(mergeDescendants = true) {
                        contentDescription = formattedContent
                    },
        ) {
            Text(
                text = formattedContent,
                style = TerminalTypography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier =
                    Modifier.padding(
                        horizontal = BLOCK_HORIZONTAL_PADDING_DP.dp,
                        vertical = BLOCK_VERTICAL_PADDING_DP.dp,
                    ),
            )
        }
    }
}

/** Milliseconds before an error block begins fading out. */
private const val ERROR_AUTO_DISMISS_MS = 7_000L

/** Duration of the fade-out animation in milliseconds. */
private const val ERROR_FADE_DURATION_MS = 1_000

/**
 * Renders an error block with red text and an "Error: " prefix.
 *
 * Automatically fades out and collapses after [ERROR_AUTO_DISMISS_MS]
 * so transient API errors don't clutter the terminal permanently.
 *
 * @param block The error block to render.
 * @param onCopy Callback invoked with the error message on long-press.
 * @param dismissedErrorIds Set of error block IDs already dismissed — survives recomposition.
 * @param modifier Modifier applied to the block container.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ErrorBlock(
    block: TerminalBlock.Error,
    onCopy: (String) -> Unit,
    dismissedErrorIds: MutableSet<Long>,
    modifier: Modifier = Modifier,
) {
    if (block.id in dismissedErrorIds) return

    val hasAction = block.actionLabel != null
    var fading by remember { mutableStateOf(false) }

    LaunchedEffect(block.id) {
        if (!hasAction) {
            delay(ERROR_AUTO_DISMISS_MS)
            fading = true
            delay(ERROR_FADE_DURATION_MS.toLong())
            dismissedErrorIds += block.id
        }
    }

    val alpha by animateFloatAsState(
        targetValue = if (fading) 0f else 1f,
        animationSpec = tween(durationMillis = ERROR_FADE_DURATION_MS),
        label = "errorFade",
    )

    val context = LocalContext.current

    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .alpha(alpha)
                .combinedClickable(
                    onClick = {},
                    onLongClick = { onCopy(block.message) },
                ).semantics(mergeDescendants = true) {
                    contentDescription = "Error: ${block.message}"
                }.padding(
                    horizontal = BLOCK_HORIZONTAL_PADDING_DP.dp,
                    vertical = BLOCK_VERTICAL_PADDING_DP.dp,
                ),
    ) {
        Text(
            text = "Error: ${block.message}",
            style = TerminalTypography.bodyMedium,
            color = InlineTerminalError,
            fontWeight = FontWeight.Bold,
        )
        if (hasAction) {
            androidx.compose.material3.Button(
                onClick = {
                    context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS),
                    )
                },
                modifier = Modifier.padding(top = 8.dp),
            ) {
                Text(block.actionLabel!!)
            }
        }
    }
}

/**
 * Renders a system message block in dimmed outline colour.
 *
 * @param block The system block to render.
 * @param onCopy Callback invoked with the system message text on long-press.
 * @param modifier Modifier applied to the block container.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SystemBlock(
    block: TerminalBlock.System,
    onCopy: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth().padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.65f),
            shape = RoundedCornerShape(16.dp),
            border = androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
            ),
            modifier = Modifier
                .combinedClickable(
                    onClick = {},
                    onLongClick = { onCopy(block.text) },
                ).semantics(mergeDescendants = true) {
                    contentDescription = block.text
                },
        ) {
            Text(
                text = block.text,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * Formats a JSON string into a human-readable representation.
 *
 * Detects common response patterns:
 * - Objects with `daemon_running` field: status table with indicators.
 * - Objects with `session_cost_usd` field: cost summary.
 * - JSON arrays of objects: numbered list of entries.
 * - Fallback: pretty-printed JSON.
 *
 * @param json The raw JSON string to format.
 * @return A human-readable text representation.
 */
private fun formatStructuredJson(json: String): String {
    val trimmed = json.trim()
    if (trimmed.startsWith("{")) {
        return formatJsonObject(trimmed)
    }
    if (trimmed.startsWith("[")) {
        return formatJsonArray(trimmed)
    }
    return trimmed
}

/**
 * Formats a JSON object string based on detected field patterns.
 *
 * @param json A JSON object string.
 * @return Formatted text representation.
 */
private fun formatJsonObject(json: String): String {
    val obj =
        runCatching { JSONObject(json) }.getOrNull()
            ?: return json

    if (obj.has(KEY_DAEMON_RUNNING)) {
        return formatStatusObject(obj)
    }
    if (obj.has(KEY_SESSION_COST)) {
        return formatCostObject(obj)
    }
    return formatGenericObject(obj)
}

/**
 * Formats a daemon status JSON object with running indicators.
 *
 * @param obj The parsed JSON object containing status fields.
 * @return A multi-line status summary.
 */
private fun formatStatusObject(obj: JSONObject): String =
    buildString {
        val keys = obj.keys().asSequence().toList()
        for (key in keys) {
            val value = obj.get(key)
            val label = key.replace("_", " ")
            val indicator = if (value == true) "\u25CF" else "\u25CB"
            if (value is Boolean) {
                appendLine("$indicator $label")
            } else {
                appendLine("  $label: $value")
            }
        }
    }.trimEnd()

/**
 * Formats a cost summary JSON object.
 *
 * @param obj The parsed JSON object containing cost fields.
 * @return A multi-line cost summary.
 */
private fun formatCostObject(obj: JSONObject): String =
    buildString {
        val keys = obj.keys().asSequence().toList()
        for (key in keys) {
            val value = obj.get(key)
            val label = key.replace("_", " ")
            appendLine("$label: $value")
        }
    }.trimEnd()

/**
 * Formats a JSON array, rendering each element as a numbered entry.
 *
 * @param json A JSON array string.
 * @return A numbered list of entries, or pretty-printed JSON on parse failure.
 */
private fun formatJsonArray(json: String): String {
    val arr =
        runCatching { JSONArray(json) }.getOrNull()
            ?: return json

    if (arr.length() == 0) {
        return "(empty)"
    }

    return buildString {
        for (i in 0 until arr.length()) {
            val element = arr.get(i)
            if (element is JSONObject) {
                appendLine("${i + 1}. ${summarizeObject(element)}")
            } else {
                appendLine("${i + 1}. $element")
            }
        }
    }.trimEnd()
}

/**
 * Summarises a JSON object as a single line of key-value pairs.
 *
 * @param obj The JSON object to summarise.
 * @return A compact "key=value, key=value" representation.
 */
private fun summarizeObject(obj: JSONObject): String {
    val keys = obj.keys().asSequence().toList()
    return keys.joinToString(", ") { key -> "$key=${obj.get(key)}" }
}

/**
 * Formats a generic JSON object as pretty-printed key-value lines.
 *
 * @param obj The parsed JSON object.
 * @return Multi-line "key: value" text.
 */
private fun formatGenericObject(obj: JSONObject): String = runCatching { obj.toString(JSON_INDENT_SPACES) }.getOrDefault(obj.toString())
