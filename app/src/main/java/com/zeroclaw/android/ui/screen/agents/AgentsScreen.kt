/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

@file:Suppress("MatchingDeclarationName")

package com.zeroclaw.android.ui.screen.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.SmartToy
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zeroclaw.android.model.Agent
import com.zeroclaw.android.model.AgentLiveState
import com.zeroclaw.android.model.AgentStatus
import com.zeroclaw.android.ui.component.EmptyState
import com.zeroclaw.android.ui.component.premiumFadeInUp
import com.zeroclaw.android.ui.component.ProviderIcon
import com.zeroclaw.android.ui.theme.zeroAssistOutlinedTextFieldColors

/**
 * Aggregated state for the agents content composable.
 *
 * @property agents Filtered list of agents to display.
 * @property searchQuery Current search query text.
 */
data class AgentsState(
    val agents: List<Agent>,
    val searchQuery: String,
    val liveStates: Map<String, AgentLiveState> = emptyMap(),
)

/**
 * Agent list and management screen with search and FAB for adding agents.
 *
 * Thin stateful wrapper that collects ViewModel flows and delegates
 * rendering to [AgentsContent].
 *
 * @param onNavigateToDetail Callback to navigate to agent detail for editing.
 * @param onNavigateToAdd Callback to navigate to the add agent screen.
 * @param edgeMargin Horizontal padding based on window width size class.
 * @param agentsViewModel The [AgentsViewModel] for agent list state.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
fun AgentsScreen(
    onNavigateToDetail: (String) -> Unit,
    onNavigateToAdd: () -> Unit,
    edgeMargin: Dp,
    agentsViewModel: AgentsViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val agents by agentsViewModel.agents.collectAsStateWithLifecycle()
    val searchQuery by agentsViewModel.searchQuery.collectAsStateWithLifecycle()
    val liveStates by agentsViewModel.liveStates.collectAsStateWithLifecycle()

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(
                onClick = onNavigateToAdd,
                modifier =
                    Modifier.semantics {
                        contentDescription = "Add new agent"
                    },
            ) {
                Icon(Icons.Filled.Add, contentDescription = null)
            }
        },
        modifier = modifier,
    ) { innerPadding ->
        AgentsContent(
            state = AgentsState(agents = agents, searchQuery = searchQuery, liveStates = liveStates),
            edgeMargin = edgeMargin,
            onNavigateToDetail = onNavigateToDetail,
            onSearchChange = agentsViewModel::updateSearch,
            onToggleAgent = agentsViewModel::toggleAgent,
            modifier = Modifier.padding(innerPadding),
        )
    }
}

/**
 * Stateless agents content composable for testing.
 *
 * @param state Aggregated agents state snapshot.
 * @param edgeMargin Horizontal padding based on window width size class.
 * @param onNavigateToDetail Callback to navigate to agent detail.
 * @param onNavigateToAdd Callback to add a new agent.
 * @param onSearchChange Callback when search text changes.
 * @param onToggleAgent Callback to toggle an agent by ID.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
fun AgentsContent(
    state: AgentsState,
    edgeMargin: Dp,
    onNavigateToDetail: (String) -> Unit,
    onSearchChange: (String) -> Unit,
    onToggleAgent: (String) -> Unit,
    showSearch: Boolean = true,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = edgeMargin),
    ) {
        if (showSearch) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onSearchChange,
                label = { Text("Search agents") },
                singleLine = true,
                colors = zeroAssistOutlinedTextFieldColors(),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(16.dp))
        }

            if (state.agents.isEmpty()) {
                EmptyState(
                    icon = Icons.Outlined.SmartToy,
                    message =
                        if (state.searchQuery.isBlank()) {
                            "No agents configured yet"
                        } else {
                            "No agents match your search"
                        },
                )
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(
                        items = state.agents,
                        key = { index, it -> it.id },
                        contentType = { index, it -> "agent" },
                    ) { index, agent ->
                        val onToggle =
                            remember(index) {
                                { onToggleAgent(agent.id) }
                            }
                        val onClick =
                            remember(index) {
                                { onNavigateToDetail(agent.id) }
                            }
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier.premiumFadeInUp(
                                delayMillis = index * 50,
                            ),
                        ) {
                            AgentListItem(
                                agent = agent,
                                liveState = state.liveStates[agent.id],
                                onToggle = onToggle,
                                onClick = onClick,
                            )
                        }
                    }
                }
            }
        }
    }


/**
 * Single agent row in the list with provider icon, name, and enable toggle.
 *
 * Tapping the card navigates to the agent detail (edit) screen.
 *
 * @param agent The agent to display.
 * @param onToggle Callback when the enable switch is toggled.
 * @param onClick Callback when the card is tapped (opens detail).
 */
@Composable
private fun AgentListItem(
    agent: Agent,
    liveState: AgentLiveState?,
    onToggle: () -> Unit,
    onClick: () -> Unit,
) {
    val status = liveState?.status ?: AgentStatus.IDLE
    val statusColor = liveState?.let { agentStatusColor(status) } ?: MaterialTheme.colorScheme.onSurfaceVariant
    val currentTask = liveState?.currentTask.orEmpty()

    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(12.dp),
        modifier =
            Modifier
                .fillMaxWidth()
                .defaultMinSize(minHeight = 48.dp),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProviderIcon(
                provider = agent.provider,
                size = 48.dp,
                shape = RoundedCornerShape(12.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = agent.name.ifBlank { "${agent.provider} \u2022 ${agent.modelName}" },
                        style = MaterialTheme.typography.titleSmall,
                    )
                    StatusBadge(
                        status = status,
                        statusColor = statusColor,
                    )
                }

                if (agent.name.isNotBlank()) {
                    Text(
                        text = "${agent.provider} \u2022 ${agent.modelName}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (status != AgentStatus.IDLE && currentTask.isNotBlank()) {
                    Text(
                        text = currentTask,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                } else {
                    AgentLiveStatusLine(
                        liveState = liveState,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            Switch(
                checked = agent.isEnabled,
                onCheckedChange = { onToggle() },
                colors =
                    SwitchDefaults.colors(
                        checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                        checkedBorderColor = MaterialTheme.colorScheme.primary,
                        uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                modifier =
                    Modifier.semantics {
                        contentDescription =
                            "${agent.name} ${if (agent.isEnabled) "enabled" else "disabled"}"
                    },
            )
        }
    }
}

@Composable
private fun StatusBadge(
    status: AgentStatus,
    statusColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = Color.Transparent,
        modifier = modifier.border(
            width = 1.dp,
            color = statusColor.copy(alpha = 0.45f),
            shape = RoundedCornerShape(999.dp),
        ),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Spacer(
                modifier =
                    Modifier
                        .size(8.dp)
                        .background(statusColor, RoundedCornerShape(50)),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = agentStatusLabel(status),
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun AgentLiveStatusLine(
    liveState: AgentLiveState?,
    modifier: Modifier = Modifier,
) {
    val status = liveState?.status ?: AgentStatus.IDLE
    val statusColor = agentStatusColor(status)

    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusBadge(
            status = status,
            statusColor = statusColor,
        )
        if (status == AgentStatus.IDLE) {
            Text(
                text = "Available",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
