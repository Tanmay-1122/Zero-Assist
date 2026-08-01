/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.history

import android.text.format.DateUtils
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zeroclaw.android.model.ConversationEntry
import com.zeroclaw.android.ui.theme.ZeroAssistTheme
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationHistoryDrawerHost(
    onNewChat: () -> Unit,
    onOpenConversation: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    historyViewModel: HistoryViewModel = viewModel(),
    content: @Composable ((() -> Unit)) -> Unit,
) {
    val drawerState = rememberDrawerState(initialValue = androidx.compose.material3.DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showAgentFilterSheet by rememberSaveable { mutableStateOf(false) }
    var showDateFilterSheet by rememberSaveable { mutableStateOf(false) }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            val groupedHistory by historyViewModel.groupedHistory.collectAsStateWithLifecycle()
            val starredEntries by historyViewModel.starredEntries.collectAsStateWithLifecycle()
            val selectedAgents by historyViewModel.selectedAgents.collectAsStateWithLifecycle()
            val startDateMillis by historyViewModel.startDateMillis.collectAsStateWithLifecycle()
            val endDateMillis by historyViewModel.endDateMillis.collectAsStateWithLifecycle()
            val availableAgents by historyViewModel.availableAgents.collectAsStateWithLifecycle()

            ModalDrawerSheet(
                modifier =
                    Modifier
                        .width(320.dp)
                        .fillMaxHeight(),
                drawerContainerColor = MaterialTheme.colorScheme.surface,
            ) {
                ConversationHistoryDrawerContent(
                    groupedHistory = groupedHistory,
                    starredEntries = starredEntries,
                    selectedAgents = selectedAgents,
                    hasDateFilter = startDateMillis != null || endDateMillis != null,
                    onNewChat = {
                        onNewChat()
                        scope.launch { drawerState.close() }
                    },
                    onOpenAgentFilter = { showAgentFilterSheet = true },
                    onOpenDateFilter = { showDateFilterSheet = true },
                    onEntrySelected = { entry ->
                        onOpenConversation(entry.id)
                        scope.launch { drawerState.close() }
                    },
                    onStarConversation = historyViewModel::starConversation,
                    onUnstarConversation = historyViewModel::unstarConversation,
                    onOpenSettings = {
                        onOpenSettings()
                        scope.launch { drawerState.close() }
                    },
                )
            }

            if (showAgentFilterSheet) {
                AgentFilterBottomSheet(
                    availableAgents = availableAgents,
                    selectedAgents = selectedAgents,
                    onToggleAgent = historyViewModel::toggleAgentFilter,
                    onClear = {
                        historyViewModel.clearAgentFilters()
                        showAgentFilterSheet = false
                    },
                    onDismiss = { showAgentFilterSheet = false },
                )
            }

            if (showDateFilterSheet) {
                DateRangeFilterBottomSheet(
                    initialStartDateMillis = startDateMillis,
                    initialEndDateMillis = endDateMillis,
                    onApply = { start, end ->
                        historyViewModel.setDateRange(start, end)
                        showDateFilterSheet = false
                    },
                    onClear = {
                        historyViewModel.clearDateRange()
                        showDateFilterSheet = false
                    },
                    onDismiss = { showDateFilterSheet = false },
                )
            }
        },
        modifier = modifier,
    ) {
        content {
            scope.launch { drawerState.open() }
        }
    }
}

@Composable
private fun ConversationHistoryDrawerContent(
    groupedHistory: Map<String, List<ConversationEntry>>,
    starredEntries: List<ConversationEntry>,
    selectedAgents: Set<String>,
    hasDateFilter: Boolean,
    onNewChat: () -> Unit,
    onOpenAgentFilter: () -> Unit,
    onOpenDateFilter: () -> Unit,
    onEntrySelected: (ConversationEntry) -> Unit,
    onStarConversation: (String) -> Unit,
    onUnstarConversation: (String) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val expandedGroups = remember { mutableStateMapOf<String, Boolean>() }

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 16.dp, vertical = 20.dp),
    ) {
        DrawerHeader()

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = selectedAgents.isNotEmpty(),
                onClick = onOpenAgentFilter,
                label = { Text("By Agent") },
            )
            FilterChip(
                selected = hasDateFilter,
                onClick = onOpenDateFilter,
                label = { Text("By Date") },
            )
        }

        Spacer(modifier = Modifier.size(16.dp))

        Button(
            onClick = onNewChat,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
        ) {
                Text("New Chat")
        }

        Spacer(modifier = Modifier.size(16.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                DrawerSectionTitle(title = "Starred")
            }

            if (starredEntries.isEmpty()) {
                item {
                    EmptyHistoryText("No starred conversations yet")
                }
            } else {
                itemsIndexed(
                    items = starredEntries,
                    key = { index, entry -> starredConversationRenderKey(entry, index) },
                ) { _, entry ->
                    ConversationHistoryRow(
                        entry = entry,
                        showStarBadge = true,
                        onClick = { onEntrySelected(entry) },
                        onStarToggle = {
                            if (entry.isStarred) onUnstarConversation(entry.id) else onStarConversation(entry.id)
                        },
                    )
                }
            }

            item {
                DrawerSectionTitle(title = "Recent")
            }

            groupedHistory.forEach { (workspaceName, entries) ->
                item(key = "workspace-$workspaceName") {
                    val expanded = expandedGroups[workspaceName] ?: true
                    WorkspaceGroupHeader(
                        workspaceName = workspaceName,
                        expanded = expanded,
                        onToggle = {
                            expandedGroups[workspaceName] = !expanded
                        },
                    )
                }

                if ((expandedGroups[workspaceName] ?: true) && entries.isNotEmpty()) {
                    itemsIndexed(
                        items = entries,
                        key = { index, entry -> recentConversationRenderKey(workspaceName, entry, index) },
                    ) { _, entry ->
                        ConversationHistoryRow(
                            entry = entry,
                            showStarBadge = entry.isStarred,
                            onClick = { onEntrySelected(entry) },
                            onStarToggle = {
                                if (entry.isStarred) onUnstarConversation(entry.id) else onStarConversation(entry.id)
                            },
                        )
                    }
                }

                if ((expandedGroups[workspaceName] ?: true) && entries.isEmpty()) {
                    item(key = "empty-$workspaceName") {
                        EmptyHistoryText("No recent conversations")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.size(12.dp))
        DrawerShortcutRow(onOpenSettings = onOpenSettings)
    }
}

@Composable
private fun DrawerHeader() {
    Box(
        modifier =
            Modifier
                .fillMaxWidth()
                .background(
                    brush =
                        androidx.compose.ui.graphics.Brush.horizontalGradient(
                            colors =
                                listOf(
                                    MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f),
                                    MaterialTheme.colorScheme.surface,
                                ),
                        ),
                    shape = RoundedCornerShape(12.dp),
                )
                .padding(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.size(40.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = "Z",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onPrimary,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            Column {
                Text(
                    text = "Zero-Assist",
                    style = MaterialTheme.typography.titleLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Conversation History",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun DrawerSectionTitle(title: String) {
    Column {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.size(4.dp))
        Box(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(end = 64.dp)
                    .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    .size(height = 1.dp, width = 0.dp),
        )
    }
}

@Composable
private fun WorkspaceGroupHeader(
    workspaceName: String,
    expanded: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = workspaceName,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        Icon(
            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationHistoryRow(
    entry: ConversationEntry,
    showStarBadge: Boolean,
    onClick: () -> Unit,
    onStarToggle: () -> Unit,
) {
    var showContextMenu by rememberSaveable(entry.id) { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = { showContextMenu = true },
                    ),
        ) {
            Row {
                if (showStarBadge) {
                    Box(
                        modifier =
                            Modifier
                                .width(3.dp)
                                .background(
                                    color = MaterialTheme.colorScheme.primary,
                                    shape = RoundedCornerShape(topStart = 12.dp, bottomStart = 12.dp),
                                )
                                .align(Alignment.CenterVertically)
                                .padding(vertical = 10.dp),
                    )
                }
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = entry.agentName,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        if (showStarBadge) {
                            Icon(
                                imageVector = Icons.Default.Star,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                        Spacer(modifier = Modifier.weight(1f))
                        Text(
                            text = formatHistoryTimestamp(entry.timestamp),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    if (entry.isTitlePending) {
                        PendingTitlePlaceholder()
                    } else if (!entry.title.isNullOrBlank()) {
                        Text(
                            text = entry.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    if (entry.preview.isNotBlank()) {
                        Text(
                            text = entry.preview,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }

        DropdownMenu(
            expanded = showContextMenu,
            onDismissRequest = { showContextMenu = false },
        ) {
            DropdownMenuItem(
                text = { Text(if (entry.isStarred) "Unstar" else "Star") },
                onClick = {
                    onStarToggle()
                    showContextMenu = false
                },
            )
            DropdownMenuItem(
                text = { Text("Export") },
                onClick = { showContextMenu = false },
                enabled = false,
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                onClick = { showContextMenu = false },
                enabled = false,
            )
        }
    }
}

@Composable
private fun DrawerShortcutRow(onOpenSettings: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
        ) {
            Row(
                modifier =
                    Modifier
                        .clickable(onClick = onOpenSettings)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Settings",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

@Composable
private fun EmptyHistoryText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = 4.dp),
    )
}

@Composable
private fun PendingTitlePlaceholder() {
    val transition = rememberInfiniteTransition(label = "history-title-placeholder")
    val alpha by transition.animateFloat(
        initialValue = 0.45f,
        targetValue = 0.9f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "history-title-placeholder-alpha",
    )

    Box(
        modifier =
            Modifier
                .fillMaxWidth(0.72f)
                .border(
                    width = 0.dp,
                    color = Color.Transparent,
                    shape = RoundedCornerShape(8.dp),
                ).background(
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = alpha * 0.35f),
                    shape = RoundedCornerShape(8.dp),
                ).padding(vertical = 8.dp),
    )
}

internal fun starredConversationRenderKey(
    entry: ConversationEntry,
    index: Int,
): String = "starred:${entry.id}:${entry.timestamp}:$index"

internal fun recentConversationRenderKey(
    workspaceName: String,
    entry: ConversationEntry,
    index: Int,
): String = "recent:$workspaceName:${entry.id}:${entry.timestamp}:$index"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AgentFilterBottomSheet(
    availableAgents: List<String>,
    selectedAgents: Set<String>,
    onToggleAgent: (String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Filter by agent",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(availableAgents, key = { it }) { agentName ->
                    Row(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .clickable { onToggleAgent(agentName) }
                                .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = agentName in selectedAgents,
                            onCheckedChange = { onToggleAgent(agentName) },
                        )
                        Text(
                            text = agentName,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onClear) {
                    Text("Clear")
                }
                TextButton(onClick = onDismiss) {
                    Text("Done")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateRangeFilterBottomSheet(
    initialStartDateMillis: Long?,
    initialEndDateMillis: Long?,
    onApply: (Long?, Long?) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val dateRangeState =
        rememberDateRangePickerState(
            initialSelectedStartDateMillis = initialStartDateMillis,
            initialSelectedEndDateMillis = initialEndDateMillis,
        )

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            DateRangePicker(
                state = dateRangeState,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onClear) {
                    Text("Clear")
                }
                TextButton(
                    onClick = {
                        onApply(
                            dateRangeState.selectedStartDateMillis,
                            dateRangeState.selectedEndDateMillis,
                        )
                    },
                ) {
                    Text("Apply")
                }
            }
        }
    }
}

private fun formatHistoryTimestamp(timestamp: Long): String =
    DateUtils.getRelativeTimeSpanString(
        timestamp,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()

@Preview(showBackground = true, uiMode = android.content.res.Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun ConversationHistoryDrawerContentPreview() {
    ZeroAssistTheme {
        Surface(color = MaterialTheme.colorScheme.surface) {
            ConversationHistoryDrawerContent(
                groupedHistory =
                    mapOf(
                        "Default Workspace" to
                            listOf(
                                ConversationEntry(
                                    id = "1",
                                    workspaceName = "Default Workspace",
                                    preview = "Plan a rollout for the Android sidebar history drawer",
                                    timestamp = System.currentTimeMillis() - 15 * DateUtils.MINUTE_IN_MILLIS,
                                    agentName = "Zero-Assist",
                                    isStarred = false,
                                ),
                                ConversationEntry(
                                    id = "2",
                                    workspaceName = "Default Workspace",
                                    preview = "Review the daemon status logs and summarize failures",
                                    timestamp = System.currentTimeMillis() - 2 * DateUtils.HOUR_IN_MILLIS,
                                    agentName = "Master",
                                    isStarred = false,
                                ),
                            ),
                        "Research Workspace" to
                            listOf(
                                ConversationEntry(
                                    id = "3",
                                    workspaceName = "Research Workspace",
                                    preview = "Summarize the connection registry setup steps",
                                    timestamp = System.currentTimeMillis() - DateUtils.DAY_IN_MILLIS,
                                    agentName = "Planner",
                                    isStarred = false,
                                ),
                            ),
                    ),
                starredEntries =
                    listOf(
                        ConversationEntry(
                            id = "4",
                            workspaceName = "Default Workspace",
                            preview = "Pinned debugging thread for the terminal provider error",
                            timestamp = System.currentTimeMillis() - 45 * DateUtils.MINUTE_IN_MILLIS,
                            agentName = "Zero-Assist",
                            isStarred = true,
                        ),
                    ),
                selectedAgents = setOf("Zero-Assist"),
                hasDateFilter = true,
                onNewChat = {},
                onOpenAgentFilter = {},
                onOpenDateFilter = {},
                onEntrySelected = {},
                onStarConversation = {},
                onUnstarConversation = {},
                onOpenSettings = {},
            )
        }
    }
}
