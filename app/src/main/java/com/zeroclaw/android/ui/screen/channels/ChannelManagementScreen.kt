/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

@file:Suppress("TooManyFunctions")

package com.zeroclaw.android.ui.screen.channels

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zeroclaw.android.model.ChannelConfiguration

/**
 * Channel management screen for configuring integrations.
 *
 * Displays available channel types, current configurations, connection status,
 * and provides UI for adding/editing channel settings.
 *
 * @param modifier Modifier applied to root layout.
 * @param channelViewModel ViewModel for channel state management.
 */
@Composable
fun ChannelManagementScreen(
    modifier: Modifier = Modifier,
    channelViewModel: ChannelViewModel = viewModel(),
) {
    val allChannels by channelViewModel.allChannels.collectAsStateWithLifecycle()
    val selectedChannel by channelViewModel.selectedChannel.collectAsStateWithLifecycle()
    val channelStats by channelViewModel.channelStats.collectAsStateWithLifecycle()
    val isLoading by channelViewModel.isLoading.collectAsStateWithLifecycle()
    val syncInProgress by channelViewModel.syncInProgress.collectAsStateWithLifecycle()
    val selectedChannelType = remember { mutableStateOf<String?>(null) }
    val setupMessage = remember { mutableStateOf<String?>(null) }
    val showChannelTypeSelector = remember { mutableStateOf(false) }
    val showEditDialog = remember { mutableStateOf(false) }

    LaunchedEffect(channelViewModel) {
        channelViewModel.loadChannels()
        channelViewModel.refreshStats()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "Channels",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    text = "Connected: ${(channelStats["connected"] as? Int) ?: 0}/${(channelStats["total"] as? Int) ?: 0}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                selectedChannelType.value?.let { channelType ->
                    Text(
                        text = "Selected setup: $channelType",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            IconButton(
                onClick = { channelViewModel.refreshStats() },
                modifier = Modifier.size(40.dp),
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
            }
        }

        HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

        when {
            selectedChannel != null -> {
                selectedChannel?.let { channel ->
                    ChannelDetailCard(
                        channel = channel,
                        syncInProgress = channel.id in syncInProgress,
                        onSync = { channelViewModel.syncChannel(channel.id) },
                        onEdit = { showEditDialog.value = true },
                        onDelete = { channelViewModel.deleteChannel(channel.id) },
                        onClose = { channelViewModel.clearSelection() },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                    )
                }
            }

            isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    CircularProgressIndicator()
                }
            }

            allChannels.isEmpty() -> {
                EmptyChannelsPlaceholder(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(32.dp),
                )
            }

            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                ) {
                    setupMessage.value?.takeIf { it.isNotBlank() }?.let { message ->
                        ChannelSetupStatusBanner(message = message)
                    }

                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .padding(horizontal = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(allChannels) { channel ->
                            ChannelListItem(
                                channel = channel,
                                isSyncing = channel.id in syncInProgress,
                                onClick = { channelViewModel.selectChannel(channel) },
                                onSync = { channelViewModel.syncChannel(channel.id) },
                                onToggle = { channelViewModel.toggleChannelActive(channel.id) },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
            }
        }

        if (selectedChannel == null) {
            ChannelSetupCard(
                onAddChannel = { showChannelTypeSelector.value = true },
                setupMessage = setupMessage.value,
            )
        }
    }

    if (showChannelTypeSelector.value) {
        AlertDialog(
            onDismissRequest = { showChannelTypeSelector.value = false },
            title = { Text("Choose a channel type") },
            text = {
                Column {
                    Text("Select one of the most common integrations:")
                    Spacer(modifier = Modifier.height(12.dp))
                    listOf("Telegram", "Discord", "Email", "Local discovery").forEach { type ->
                        Button(
                            onClick = {
                                selectedChannelType.value = type
                                setupMessage.value =
                                    "Ready to configure $type. Navigate to the channel setup screen to continue."
                                showChannelTypeSelector.value = false
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                        ) {
                            Text(type)
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showChannelTypeSelector.value = false }) {
                    Text("Close")
                }
            },
        )
    }

    if (showEditDialog.value && selectedChannel != null) {
        AlertDialog(
            onDismissRequest = { showEditDialog.value = false },
            title = { Text("Edit channel") },
            text = {
                Text(
                    "This screen can inspect, sync, enable, and delete a channel. " +
                        "The dedicated edit flow is not wired here yet.",
                )
            },
            confirmButton = {
                Button(onClick = { showEditDialog.value = false }) {
                    Text("Close")
                }
            },
        )
    }
}

/**
 * Channel list item showing basic info and quick actions.
 */
@Composable
private fun ChannelListItem(
    channel: ChannelConfiguration,
    isSyncing: Boolean,
    onClick: () -> Unit,
    onSync: () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "[${channel.type}]",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                Row(
                    modifier = Modifier.padding(top = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val statusColor = when (channel.connectionStatus) {
                        "connected" -> Color.Green
                        "disconnected" -> Color(0xFFD4A017)
                        "error" -> Color.Red
                        else -> Color.Gray
                    }
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .background(statusColor, shape = CircleShape),
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = channel.connectionStatus,
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            IconButton(
                onClick = onSync,
                enabled = !isSyncing,
                modifier = Modifier.size(36.dp),
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    Icon(Icons.Default.Refresh, contentDescription = "Sync")
                }
            }

            Switch(
                checked = channel.isActive,
                onCheckedChange = { onToggle() },
            )
        }
    }
}

/**
 * Detailed view of a selected channel with full settings.
 */
@Composable
private fun ChannelDetailCard(
    channel: ChannelConfiguration,
    syncInProgress: Boolean,
    onSync: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(
                        text = channel.name,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Type: ${channel.type}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
                IconButton(onClick = onClose) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close details",
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider()
            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Status", style = MaterialTheme.typography.labelSmall)
                    Text(
                        text = channel.connectionStatus,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = "Auto-Sync", style = MaterialTheme.typography.labelSmall)
                    Text(
                        text = if (channel.autoSync) "Every ${channel.syncIntervalMinutes}m" else "Off",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            channel.errorMessage?.let { errorMessage ->
                Spacer(modifier = Modifier.height(12.dp))
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(4.dp),
                    color = Color.Red.copy(alpha = 0.1f),
                ) {
                    Row(
                        modifier = Modifier.padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Default.Error,
                            contentDescription = null,
                            tint = Color.Red,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Button(
                    onClick = onSync,
                    enabled = !syncInProgress,
                    modifier = Modifier.weight(1f),
                ) {
                    if (syncInProgress) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 1.dp,
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Sync")
                }

                Button(onClick = onEdit, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Edit")
                }

                Button(onClick = onDelete, modifier = Modifier.weight(1f)) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Delete")
                }
            }
        }
    }
}

/**
 * Lightweight status banner for recent setup actions.
 */
@Composable
private fun ChannelSetupStatusBanner(
    message: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.Link,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}

/**
 * Simple entry card that guides users into adding a new channel.
 */
@Composable
private fun ChannelSetupCard(
    onAddChannel: () -> Unit,
    setupMessage: String?,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(18.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Link,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                )
                Column {
                    Text(
                        text = "Connect a channel",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "Add Telegram, Discord, email, or local discovery integrations.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (!setupMessage.isNullOrBlank()) {
                Text(
                    text = setupMessage,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            Button(onClick = onAddChannel) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add channel")
            }
        }
    }
}

/**
 * Placeholder shown when no channels are configured.
 */
@Composable
private fun EmptyChannelsPlaceholder(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = "No channels configured",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            text = "Add a channel to connect integrations",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
