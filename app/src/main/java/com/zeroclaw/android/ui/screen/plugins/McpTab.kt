/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.plugins

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Hardware
import androidx.compose.material.icons.filled.Http
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.model.CuratedMcpServers
import com.zeroclaw.android.model.McpServerEntry
import com.zeroclaw.android.ui.component.EmptyState

/**
 * The MCP management tab content, replacing the old "Not Installed" tab.
 *
 * Shows global MCP toggles, a curated server catalog for quick-add,
 * and the user's configured servers with status indicators.
 *
 * @param servers List of configured MCP servers.
 * @param mcpEnabled Whether MCP is globally enabled.
 * @param deferredLoading Whether deferred tool loading is active.
 * @param connectionStatus Per-server connection status map.
 * @param onToggleMcp Callback to toggle MCP globally.
 * @param onToggleDeferred Callback to toggle deferred loading.
 * @param onToggleServer Callback to toggle a specific server.
 * @param onRemoveServer Callback to remove a server by ID.
 * @param onAddServer Callback when the "Add Custom Server" button is tapped.
 * @param onEditServer Callback when a server card is tapped for editing.
 * @param onAddFromCurated Callback when a curated server card is tapped.
 */
@Composable
fun McpTab(
    servers: List<McpServerEntry>,
    mcpEnabled: Boolean,
    deferredLoading: Boolean,
    connectionStatus: Map<String, ServerStatus>,
    onToggleMcp: () -> Unit,
    onToggleDeferred: () -> Unit,
    onToggleServer: (String) -> Unit,
    onRemoveServer: (String) -> Unit,
    onAddServer: () -> Unit,
    onEditServer: (McpServerEntry) -> Unit,
    onAddFromCurated: (CuratedMcpServers.CuratedServer) -> Unit,
) {
    var showDeleteConfirmation by remember { mutableStateOf<String?>(null) }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Global toggles
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "MCP Tools",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = "Connect external tool servers via Model Context Protocol",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = mcpEnabled,
                        onCheckedChange = { onToggleMcp() },
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Deferred loading",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Text(
                            text = "Load tool schemas on demand via tool_search",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = deferredLoading,
                        onCheckedChange = { onToggleDeferred() },
                        enabled = mcpEnabled,
                        colors = SwitchDefaults.colors(
                            checkedTrackColor = MaterialTheme.colorScheme.primary,
                        ),
                    )
                }
            }
        }

        // Curated servers section
        if (mcpEnabled) {
            item {
                Text(
                    text = "Quick Add",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            item {
                LazyCuratedServerRow(onAddFromCurated = onAddFromCurated)
            }
        }

        // Configured servers section
        if (mcpEnabled && servers.isNotEmpty()) {
            item {
                Text(
                    text = "Your Servers",
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            items(servers, key = { it.id }) { server ->
                McpServerCard(
                    server = server,
                    status = connectionStatus[server.id] ?: ServerStatus.UNKNOWN,
                    onToggle = { onToggleServer(server.id) },
                    onDelete = { showDeleteConfirmation = server.id },
                    onClick = { onEditServer(server) },
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
        }

        // Empty state when enabled but no servers
        if (mcpEnabled && servers.isEmpty()) {
            item {
                EmptyState(
                    icon = Icons.Outlined.Extension,
                    message = "No MCP servers configured.\nAdd one from the catalog above or create a custom server.",
                    modifier = Modifier.padding(vertical = 32.dp),
                )
            }
        }

        // Disabled state
        if (!mcpEnabled) {
            item {
                EmptyState(
                    icon = Icons.Outlined.Extension,
                    message = "MCP is disabled.\nEnable it to connect external tool servers.",
                    modifier = Modifier.padding(vertical = 32.dp),
                )
            }
        }

        // Add custom server button
        if (mcpEnabled) {
            item {
                FilledTonalButton(
                    onClick = onAddServer,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Add Custom Server")
                }
            }
        }

        // Restart notice
        if (mcpEnabled && servers.isNotEmpty()) {
            item {
                Text(
                    text = "Restart your chat session for server changes to take effect.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
        }

        // Spacer at bottom
        item { Spacer(modifier = Modifier.height(16.dp)) }
    }

    // Delete confirmation
    showDeleteConfirmation?.let { serverId ->
        val server = servers.find { it.id == serverId }
        if (server != null) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showDeleteConfirmation = null },
                title = { Text("Remove ${server.name}?") },
                text = { Text("This MCP server will be removed from your configuration.") },
                confirmButton = {
                    TextButton(onClick = {
                        onRemoveServer(serverId)
                        showDeleteConfirmation = null
                    }) {
                        Text("Remove")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDeleteConfirmation = null }) {
                        Text("Cancel")
                    }
                },
            )
        }
    }
}

/**
 * Horizontally scrollable row of curated server cards.
 */
@Composable
private fun LazyCuratedServerRow(
    onAddFromCurated: (CuratedMcpServers.CuratedServer) -> Unit,
) {
    androidx.compose.foundation.lazy.LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(CuratedMcpServers.servers.size) { index ->
            val server = CuratedMcpServers.servers[index]
            CuratedServerCard(
                server = server,
                onClick = { onAddFromCurated(server) },
            )
        }
    }
}

/**
 * Compact card for a curated MCP server in the quick-add row.
 */
@Composable
private fun CuratedServerCard(
    server: CuratedMcpServers.CuratedServer,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = Modifier
            .width(140.dp)
            .height(100.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = server.icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = server.name,
                style = MaterialTheme.typography.labelMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = server.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
