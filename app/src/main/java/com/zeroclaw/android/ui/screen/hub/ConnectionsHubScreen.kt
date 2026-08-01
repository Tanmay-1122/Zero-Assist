/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.hub

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zeroclaw.android.model.CuratedMcpServers
import com.zeroclaw.android.ui.screen.agents.AgentsContent
import com.zeroclaw.android.ui.screen.agents.AgentsState
import com.zeroclaw.android.ui.screen.agents.AgentsViewModel
import com.zeroclaw.android.ui.screen.plugins.AddMcpServerSheet
import com.zeroclaw.android.ui.screen.plugins.CuratedAddSheet
import com.zeroclaw.android.model.McpServerEntry
import com.zeroclaw.android.ui.screen.plugins.PluginsContent
import com.zeroclaw.android.ui.screen.plugins.PluginsState
import com.zeroclaw.android.ui.screen.plugins.PluginsViewModel
import com.zeroclaw.android.ui.screen.plugins.SkillsTab
import com.zeroclaw.android.ui.screen.plugins.SkillsViewModel
import com.zeroclaw.android.ui.screen.plugins.McpTab
import com.zeroclaw.android.ui.screen.plugins.McpViewModel

import com.zeroclaw.android.ui.screen.settings.channels.ChannelsViewModel
import com.zeroclaw.android.ui.screen.settings.channels.ConnectedChannelsScreen

/**
 * Hub screen that consolidates management of agents, plugins, and channels.
 *
 * @param onNavigateToAgentDetail Callback to navigate to agent editing.
 * @param onNavigateToAddAgent Callback to add a new agent.
 * @param onNavigateToPluginDetail Callback to view plugin details.
 * @param onNavigateToChannelDetail Callback to edit or add a channel.
 * @param edgeMargin Horizontal padding based on window width size class.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
fun ConnectionsHubScreen(
    onNavigateToAgentDetail: (String) -> Unit,
    onNavigateToAddAgent: () -> Unit,
    onNavigateToGroupChat: () -> Unit,
    onNavigateToPluginDetail: (String) -> Unit,
    onNavigateToChannelDetail: (String?, String?) -> Unit,
    edgeMargin: Dp,
    agentsViewModel: AgentsViewModel = viewModel(),
    pluginsViewModel: PluginsViewModel = viewModel(),
    skillsViewModel: SkillsViewModel = viewModel(),
    channelsViewModel: ChannelsViewModel = viewModel(),
    mcpViewModel: McpViewModel = viewModel(),
    initialTabIndex: Int = 1,
    modifier: Modifier = Modifier,
) {
    var selectedTabIndex by rememberSaveable { mutableIntStateOf(initialTabIndex) }
    val tabs = listOf("Active", "Chat", "Plugins", "Channels")
    val snackbarHostState = remember { SnackbarHostState() }
    val liveStates by agentsViewModel.liveStates.collectAsStateWithLifecycle()

    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    Scaffold(
        topBar = {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = edgeMargin, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                tabs.forEachIndexed { index, title ->
                    Surface(
                        color =
                            if (selectedTabIndex == index) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                        shape = MaterialTheme.shapes.medium,
                        modifier =
                            Modifier
                                .weight(1f)
                                .clickable { selectedTabIndex = index },
                    )
                    {
                        Text(
                            text = title,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            style = MaterialTheme.typography.titleMedium,
                            color =
                                if (selectedTabIndex == index) {
                                    MaterialTheme.colorScheme.onPrimary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        )
                    }
                }
            }
        },
        floatingActionButton = {
            if (selectedTabIndex == 1) { // Chat tab
                FloatingActionButton(
                    onClick = onNavigateToAddAgent,
                    modifier = Modifier.semantics {
                        contentDescription = "Add new agent"
                    },
                ) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                }
            }
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        contentWindowInsets = WindowInsets(0.dp),
        modifier = modifier,
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
        ) {
            when (selectedTabIndex) {
                0 -> {
                    // Connected Overview
                    val agents by agentsViewModel.agents.collectAsStateWithLifecycle()
                    AgentsContent(
                        state = AgentsState(
                            agents = agents.filter { it.isEnabled },
                            searchQuery = "",
                            liveStates = liveStates,
                        ),
                        edgeMargin = edgeMargin,
                        onNavigateToDetail = onNavigateToAgentDetail,
                        onSearchChange = {},
                        onToggleAgent = agentsViewModel::toggleAgent,
                        showSearch = false,
                    )
                }
                1 -> {
                    // Connections List
                    AgentsContent(
                        state = AgentsState(
                            agents = agentsViewModel.agents.collectAsStateWithLifecycle().value,
                            searchQuery = agentsViewModel.searchQuery.collectAsStateWithLifecycle().value,
                            liveStates = liveStates,
                        ),
                        edgeMargin = edgeMargin,
                        onNavigateToDetail = onNavigateToAgentDetail,
                        onSearchChange = agentsViewModel::updateSearch,
                        onToggleAgent = agentsViewModel::toggleAgent,
                    )
                }
                        2 -> {
                            // Plugins List
                            val showAddMcpSheet = remember { mutableStateOf(false) }
                            val editingMcpEntry = remember { mutableStateOf<McpServerEntry?>(null) }
                            val showCuratedSheet = remember { mutableStateOf<CuratedMcpServers.CuratedServer?>(null) }

                            PluginsContent(
                                state = PluginsState(
                                    plugins = pluginsViewModel.plugins.collectAsStateWithLifecycle().value,
                                    selectedTab = pluginsViewModel.selectedTab.collectAsStateWithLifecycle().value,
                                    searchQuery = pluginsViewModel.searchQuery.collectAsStateWithLifecycle().value,
                                    syncState = pluginsViewModel.syncState.collectAsStateWithLifecycle().value,
                                ),
                                edgeMargin = edgeMargin,
                                snackbarHostState = snackbarHostState,
                                onNavigateToDetail = onNavigateToPluginDetail,
                                onSelectTab = pluginsViewModel::selectTab,
                                onSyncNow = pluginsViewModel::syncNow,
                                onSearchChange = pluginsViewModel::updateSearch,
                                onToggle = pluginsViewModel::togglePlugin,
                                onInstall = pluginsViewModel::installPlugin,
                                skillsTabContent = { SkillsTab(skillsViewModel = skillsViewModel) },
                                mcpTabContent = {
                                    val mcpServers by mcpViewModel.servers.collectAsStateWithLifecycle()
                                    val mcpEnabled by mcpViewModel.mcpEnabled.collectAsStateWithLifecycle()
                                    val mcpDeferred by mcpViewModel.deferredLoading.collectAsStateWithLifecycle()
                                    val mcpStatus by mcpViewModel.connectionStatus.collectAsStateWithLifecycle()
                                    McpTab(
                                        servers = mcpServers,
                                        mcpEnabled = mcpEnabled,
                                        deferredLoading = mcpDeferred,
                                        connectionStatus = mcpStatus,
                                        onToggleMcp = mcpViewModel::toggleMcpEnabled,
                                        onToggleDeferred = mcpViewModel::toggleDeferredLoading,
                                        onToggleServer = mcpViewModel::toggleServer,
                                        onRemoveServer = mcpViewModel::removeServer,
                                        onAddServer = { showAddMcpSheet.value = true },
                                        onEditServer = { editingMcpEntry.value = it; showAddMcpSheet.value = true },
                                        onAddFromCurated = { showCuratedSheet.value = it },
                                    )
                                },
                                onRestoreDefaults = pluginsViewModel::restoreDefaults,
                            )

                            // MCP Add/Edit sheet
                            if (showAddMcpSheet.value) {
                                AddMcpServerSheet(
                                    editingEntry = editingMcpEntry.value,
                                    onDismiss = { showAddMcpSheet.value = false; editingMcpEntry.value = null },
                                    onSave = { entry ->
                                        if (editingMcpEntry.value != null) {
                                            mcpViewModel.updateServer(entry)
                                        } else {
                                            mcpViewModel.addServer(entry)
                                        }
                                    },
                                )
                            }

                            // Curated server add sheet
                            showCuratedSheet.value?.let { curated ->
                                CuratedAddSheet(
                                    curated = curated,
                                    onDismiss = { showCuratedSheet.value = null },
                                    onAdd = { envValues, pathArg ->
                                        mcpViewModel.addFromCurated(curated, envValues, pathArg)
                                        showCuratedSheet.value = null
                                    },
                                )
                            }
                        }
                3 -> {
                    // Channels Settings
                    ConnectedChannelsScreen(
                        onNavigateToDetail = onNavigateToChannelDetail,
                        edgeMargin = edgeMargin,
                        channelsViewModel = channelsViewModel,
                    )
                }
            }
        }
    }
}
