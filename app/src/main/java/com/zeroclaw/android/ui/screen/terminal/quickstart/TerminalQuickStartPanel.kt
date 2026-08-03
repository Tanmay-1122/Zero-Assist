/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.terminal.quickstart

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.model.Agent
import com.zeroclaw.android.model.AgentRole
import com.zeroclaw.android.ui.theme.ZeroAssistSpacing
import com.zeroclaw.android.ui.theme.ZeroClawTheme

internal val QuickStartActionChipLabels =
    listOf("Write", "Learn", "Code", "Automate")

@Composable
internal fun TerminalQuickStartPanel(
    slashCommands: List<String>,
    recentAgents: List<Agent>,
    onActionSelected: (String) -> Unit,
    onCommandSelected: (String) -> Unit,
    onRecentAgentSelected: (Agent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.68f),
        shape = MaterialTheme.shapes.extraLarge,
        border =
            BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            ),
    ) {
        Column(
            modifier = Modifier.padding(ZeroAssistSpacing.Small),
            verticalArrangement = Arrangement.spacedBy(ZeroAssistSpacing.Small),
        ) {
            QuickStartChipRow(
                title = "Actions",
                items = QuickStartActionChipLabels,
                onItemSelected = onActionSelected,
            )

            QuickStartChipRow(
                title = "Commands",
                items = slashCommands,
                onItemSelected = onCommandSelected,
            )

            if (recentAgents.isNotEmpty()) {
                RecentAgentChipRow(
                    agents = recentAgents,
                    onAgentSelected = onRecentAgentSelected,
                )
            }
        }
    }
}

@Composable
private fun QuickStartChipRow(
    title: String,
    items: List<String>,
    onItemSelected: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ZeroAssistSpacing.XSmall)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(ZeroAssistSpacing.Small)) {
            itemsIndexed(items, key = { _, item -> item }) { index, item ->
                AnimatedQuickStartChip(delayIndex = index) {
                    FilterChip(
                        selected = false,
                        onClick = { onItemSelected(item) },
                        modifier =
                            Modifier.semantics {
                                contentDescription = "$title option $item"
                            },
                        label = {
                            Text(
                                text = item,
                                color = MaterialTheme.colorScheme.onSurface,
                                style = MaterialTheme.typography.labelMedium,
                            )
                        },
                        border =
                            BorderStroke(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.75f),
                            ),
                        colors =
                            FilterChipDefaults.filterChipColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                                labelColor = MaterialTheme.colorScheme.onSurface,
                            ),
                        shape = MaterialTheme.shapes.large,
                    )
                }
            }
        }
    }
}

@Composable
private fun RecentAgentChipRow(
    agents: List<Agent>,
    onAgentSelected: (Agent) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(ZeroAssistSpacing.XSmall)) {
        Text(
            text = "Agents",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyRow(horizontalArrangement = Arrangement.spacedBy(ZeroAssistSpacing.Small)) {
            itemsIndexed(agents, key = { _, agent -> agent.id }) { index, agent ->
                AnimatedQuickStartChip(delayIndex = index) {
                    Surface(
                        onClick = { onAgentSelected(agent) },
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
                        border =
                            BorderStroke(
                                width = 1.dp,
                                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.65f),
                            ),
                        modifier =
                            Modifier.semantics {
                                contentDescription = "Agent ${agent.name}"
                            },
                    ) {
                        Row(
                            modifier =
                                Modifier.padding(
                                    horizontal = ZeroAssistSpacing.Medium,
                                    vertical = ZeroAssistSpacing.Small,
                                ),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(ZeroAssistSpacing.Small),
                        ) {
                            Box(
                                modifier =
                                    Modifier
                                        .size(24.dp)
                                        .background(
                                            color = MaterialTheme.colorScheme.primary,
                                            shape = MaterialTheme.shapes.medium,
                                        ),
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    text = agent.avatar.ifBlank { agent.role.icon },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            }
                            Text(
                                text = agent.name,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AnimatedQuickStartChip(
    delayIndex: Int,
    content: @Composable () -> Unit,
) {
    var visible by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        visible = true
    }

    AnimatedVisibility(
        visible = visible,
        enter =
            fadeIn(animationSpec = tween(durationMillis = 220, delayMillis = delayIndex * 50)) +
                slideInVertically(
                    initialOffsetY = { it / 3 },
                    animationSpec = tween(durationMillis = 220, delayMillis = delayIndex * 50),
                ),
    ) {
        content()
    }
}

@Preview(
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun TerminalQuickStartPanelPreview() {
    val sampleAgents =
        listOf(
            Agent(
                id = "master",
                name = "Master",
                provider = "openai",
                modelName = "gpt-4o",
                role = AgentRole.MASTER,
                avatar = "\uD83D\uDC51",
                isMaster = true,
                accentColor = 0xFF00C8B4,
            ),
            Agent(
                id = "coder",
                name = "Coder",
                provider = "openai",
                modelName = "gpt-4o",
                role = AgentRole.CODER,
                avatar = "\uD83D\uDCBB",
                accentColor = 0xFF00C8B4,
            ),
            Agent(
                id = "researcher",
                name = "Researcher",
                provider = "openai",
                modelName = "gpt-4o",
                role = AgentRole.RESEARCHER,
                avatar = "\uD83D\uDD0D",
                accentColor = 0xFF00C8B4,
            ),
        )

    ZeroClawTheme {
        TerminalQuickStartPanel(
            slashCommands = listOf("/status", "/health", "/cost", "/doctor"),
            recentAgents = sampleAgents,
            onActionSelected = {},
            onCommandSelected = {},
            onRecentAgentSelected = {},
            modifier = Modifier.padding(16.dp),
        )
    }
}
