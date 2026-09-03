/*
 * Copyright 2026 @Natfii
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.terminal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.ui.screen.terminal.orb.OrbSize
import com.zeroclaw.android.ui.screen.terminal.orb.OrbState
import com.zeroclaw.android.ui.screen.terminal.orb.OrbTheme
import com.zeroclaw.android.ui.screen.terminal.orb.ThinkingOrb
import com.zeroclaw.android.ui.theme.TerminalTypography
import com.zeroclaw.android.util.LocalPowerSaveMode

/** Maximum height for the thinking/details section in dp. */
private const val MAX_CARD_HEIGHT_DP = 220

/** Padding inside the thinking card in dp. */
private const val CARD_PADDING_DP = 16

/** Spacing between header row and body text in dp. */
private const val BODY_SPACING_DP = 10

/** Minimum touch height for the card header. */
private const val HEADER_MIN_HEIGHT_DP = 48

/** Spacing between header actions. */
private const val HEADER_ACTION_SPACING_DP = 8

private val THINKING_CARD_SHAPE = androidx.compose.foundation.shape.RoundedCornerShape(12.dp)

/**
 * Card displaying live thinking/reasoning tokens from the model.
 *
 * The header is always visible and shows a phase-derived spinner label,
 * a chevron collapse/expand indicator, and a Cancel button.
 *
 * Tapping the card toggles an expanded section that shows the LLM's
 * thinking tokens (if any) and the tool activity footer.
 *
 * When [LocalPowerSaveMode] is active, entry/exit animations are disabled
 * to conserve battery.
 *
 * @param thinkingText  Accumulated thinking tokens to display.
 * @param visible       Whether the card is currently visible.
 * @param onCancel      Callback when the user taps the cancel button.
 * @param activeTools   Tools currently executing during the turn.
 * @param toolResults   Completed tool execution results for the current turn.
 * @param phase         Current streaming phase driving the header label.
 * @param providerRound 1-based LLM call round (round 2+ shown in header).
 * @param toolCallCount Number of tool calls from the last LLM response.
 * @param llmDurationSecs Wall-clock seconds the LLM took before tool dispatch.
 * @param modifier      Modifier applied to the outer container.
 */
@Composable
fun ThinkingCard(
    thinkingText: String,
    visible: Boolean,
    onCancel: () -> Unit,
    activeTools: List<ToolProgress> = emptyList(),
    toolResults: List<ToolResultEntry> = emptyList(),
    phase: StreamingPhase = StreamingPhase.THINKING,
    providerRound: Int = 0,
    toolCallCount: Int = 0,
    llmDurationSecs: Long = 0,
    modifier: Modifier = Modifier,
) {
    val isPowerSave = LocalPowerSaveMode.current
    val enterTransition = if (isPowerSave) EnterTransition.None else expandVertically()
    val exitTransition = if (isPowerSave) ExitTransition.None else shrinkVertically()

    var expanded by remember { mutableStateOf(false) }

    val hasDetails =
        thinkingText.isNotEmpty() ||
            activeTools.isNotEmpty() ||
            toolResults.isNotEmpty() ||
            toolCallCount > 0
    val collapsedPreview =
        buildCollapsedPreview(
            thinkingText = thinkingText,
            activeTools = activeTools,
            toolResults = toolResults,
            toolCallCount = toolCallCount,
        )

    AnimatedVisibility(
        visible = visible,
        enter = enterTransition,
        exit = exitTransition,
        modifier = modifier,
    ) {
        ElevatedCard(
            shape = THINKING_CARD_SHAPE,
            colors =
                CardDefaults.elevatedCardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
                ),
            elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(THINKING_CARD_SHAPE)
                    .border(
                        width = 1.dp,
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.55f),
                        shape = THINKING_CARD_SHAPE,
                    )
                    .then(
                        if (hasDetails) {
                            Modifier.clickable { expanded = !expanded }
                        } else {
                            Modifier
                        },
                    )
                    .semantics(mergeDescendants = true) {
                        contentDescription = "Model is thinking"
                    },
        ) {
            Column(
                modifier = Modifier.padding(CARD_PADDING_DP.dp),
            ) {
                // ── Header row ──────────────────────────────────────────────
                val headerLabel =
                    when (phase) {
                        StreamingPhase.SEARCHING_MEMORY -> "Searching memory…"
                        StreamingPhase.CALLING_PROVIDER ->
                            if (providerRound > 1) "Thinking (round $providerRound)…" else "Thinking…"
                        StreamingPhase.THINKING         -> "Processing…"
                        StreamingPhase.TOOL_EXECUTING   -> "Running tools…"
                        StreamingPhase.RESPONDING       -> "Responding…"
                        StreamingPhase.COMPACTING       -> "Compacting memory…"
                        else                            -> "Thinking…"
                    }

                // Every active StreamingPhase triggers a distinct orb animation.
                val orbState = when (phase) {
                    StreamingPhase.SEARCHING_MEMORY -> OrbState.SEARCHING  // globe scan
                    StreamingPhase.CALLING_PROVIDER -> OrbState.SOLVING    // rubik twist
                    StreamingPhase.THINKING         -> OrbState.SHAPING    // morph — thoughts taking shape
                    StreamingPhase.TOOL_EXECUTING   -> OrbState.COMPOSING  // ribbon strands
                    StreamingPhase.RESPONDING       -> OrbState.LISTENING  // rolling wave
                    StreamingPhase.COMPACTING       -> OrbState.WORKING    // orbiting particles (fallback)
                    else                            -> OrbState.WORKING
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = HEADER_MIN_HEIGHT_DP.dp)
                            .semantics {
                                liveRegion = LiveRegionMode.Polite
                            },
                ) {
                    if (isPowerSave) {
                        // Keep the lightweight text spinner in battery-saver mode.
                        BrailleSpinner(label = headerLabel)
                    } else {
                        // Animated dotted orb + phase label side-by-side.
                        ThinkingOrb(
                            state = orbState,
                            orbSize = OrbSize.SMALL,
                            theme = OrbTheme.AUTO,
                            contentDescription = headerLabel,
                            modifier = Modifier.size(34.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = headerLabel,
                            style = TerminalTypography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Chevron – only when there is expandable content
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(HEADER_ACTION_SPACING_DP.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (hasDetails) {
                            Icon(
                                imageVector =
                                    if (expanded) {
                                        Icons.Default.KeyboardArrowUp
                                    } else {
                                        Icons.Default.KeyboardArrowDown
                                    },
                                contentDescription = if (expanded) "Collapse" else "Expand",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                        }

                        TextButton(
                            onClick = onCancel,
                            colors =
                                ButtonDefaults.textButtonColors(
                                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier =
                                Modifier.semantics {
                                    contentDescription = "Cancel request"
                                },
                        ) {
                            Text(
                                text = "Cancel",
                                style = TerminalTypography.labelMedium,
                            )
                        }
                    }
                }

                // ── Expandable details ───────────────────────────────────────
                if (!expanded && !collapsedPreview.isNullOrBlank()) {
                    Text(
                        text = collapsedPreview,
                        style = TerminalTypography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.92f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 6.dp, start = 2.dp),
                    )
                }

                AnimatedVisibility(visible = expanded && hasDetails) {
                    Column {
                        if (thinkingText.isNotEmpty()) {
                            val scrollState = rememberScrollState()
                            LaunchedEffect(thinkingText) {
                                scrollState.scrollTo(scrollState.maxValue)
                            }

                            HorizontalDivider(
                                modifier = Modifier.padding(vertical = BODY_SPACING_DP.dp),
                                color = MaterialTheme.colorScheme.outlineVariant,
                            )

                            Text(
                                text = "Live details",
                                style =
                                    TerminalTypography.labelSmall.copy(
                                        fontStyle = FontStyle.Italic,
                                    ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.95f),
                                modifier = Modifier.padding(bottom = 6.dp),
                            )

                            Text(
                                text = thinkingText,
                                style = TerminalTypography.bodySmall,
                                color =
                                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.84f),
                                modifier =
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = MAX_CARD_HEIGHT_DP.dp)
                                        .verticalScroll(scrollState),
                            )
                        }

                        ToolActivityFooter(
                            activeTools = activeTools,
                            toolResults = toolResults,
                            toolCallCount = toolCallCount,
                            llmDurationSecs = llmDurationSecs,
                        )
                    }
                }
            }
        }
    }
}

private fun buildCollapsedPreview(
    thinkingText: String,
    activeTools: List<ToolProgress>,
    toolResults: List<ToolResultEntry>,
    toolCallCount: Int,
): String? {
    val runningHint =
        when {
            activeTools.size > 1 -> "Running ${activeTools.size} tools"
            activeTools.size == 1 -> ToolDisplayFormatter.format(activeTools.first().name, activeTools.first().hint)
            else -> null
        }
    if (!runningHint.isNullOrBlank()) return runningHint

    val latestResult = toolResults.lastOrNull()
    if (latestResult != null) {
        val status = if (latestResult.success) "Completed" else "Failed"
        return "$status: ${ToolDisplayFormatter.format(latestResult.name, latestResult.hint)}"
    }

    if (toolCallCount > 0) {
        return "$toolCallCount tool call${if (toolCallCount == 1) "" else "s"} prepared"
    }

    return thinkingText
        .lineSequence()
        .map { it.trim() }
        .filter { !it.startsWith("Thinking", ignoreCase = true) }
        .lastOrNull { it.isNotBlank() }
}

/**
 * Tool activity footer displaying in-flight and completed tool executions.
 *
 * Renders a [HorizontalDivider] followed by optional progress text derived
 * from tool metrics, active tool rows (hourglass indicator), and completed
 * tool rows (check/cross indicator with duration).
 *
 * @param activeTools     Tools currently executing during the turn.
 * @param toolResults     Completed tool execution results for the current turn.
 * @param toolCallCount   Number of tool calls from the last LLM response.
 * @param llmDurationSecs Wall-clock seconds the LLM took before tool dispatch.
 */
@Composable
private fun ToolActivityFooter(
    activeTools: List<ToolProgress>,
    toolResults: List<ToolResultEntry>,
    toolCallCount: Int,
    llmDurationSecs: Long,
) {
    if (activeTools.isEmpty() && toolResults.isEmpty() && toolCallCount == 0) return

    HorizontalDivider(
        modifier = Modifier.padding(vertical = BODY_SPACING_DP.dp),
        color = MaterialTheme.colorScheme.outlineVariant,
    )

    if (toolCallCount > 0) {
        val progressText =
            buildString {
                append("$toolCallCount tool call")
                if (toolCallCount != 1) append("s")
                if (llmDurationSecs > 0) append(" · LLM responded in ${llmDurationSecs}s")
            }
        Text(
            text = progressText,
            style = TerminalTypography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }

    for (tool in activeTools) {
        ToolActivityRow(
            icon = "⏳",
            name = ToolDisplayFormatter.format(tool.name, tool.hint),
            detail = "",
            tint = MaterialTheme.colorScheme.tertiary,
        )
    }

    for (result in toolResults) {
        if (result.canvasFrame != null) {
            CanvasInlineBlock(
                canvasFrame = result.canvasFrame,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
            )
        } else {
            val icon = if (result.success) "✅" else "❌"
            val detail = if (result.durationSecs > 0) "(${result.durationSecs}s)" else ""
            ToolActivityRow(
                icon = icon,
                name = ToolDisplayFormatter.format(result.name, result.hint),
                detail = detail,
                tint =
                    if (result.success) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
            )
        }
    }
}

/**
 * Single row in the tool activity footer showing tool name with status indicator.
 *
 * @param icon   Emoji indicator for the tool state.
 * @param name   Tool identifier.
 * @param detail Additional detail text (hint or duration).
 * @param tint   Color for the tool name text.
 */
@Composable
private fun ToolActivityRow(
    icon: String,
    name: String,
    detail: String,
    tint: Color,
) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(vertical = 2.dp)
                .semantics(mergeDescendants = true) {
                    contentDescription = "$name tool $detail"
                },
    ) {
        Text(
            text = icon,
            style = TerminalTypography.bodySmall,
            modifier = Modifier.width(24.dp),
        )
        Text(
            text = name,
            style = TerminalTypography.bodySmall,
            color = tint,
        )
        if (detail.isNotEmpty()) {
            Text(
                text = detail,
                style = TerminalTypography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
