/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.model.McpServerEntry
import com.zeroclaw.android.model.McpTransportType

/**
 * Card displaying a single MCP server with status, transport badge, enable toggle,
 * and delete button.
 *
 * @param server The MCP server configuration.
 * @param status Connection status from the daemon.
 * @param onToggle Callback when the enable switch is toggled.
 * @param onDelete Callback when the delete button is pressed.
 * @param onClick Callback when the card is tapped (for editing).
 */
@Composable
fun McpServerCard(
    server: McpServerEntry,
    status: ServerStatus,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                // Status dot
                Icon(
                    imageVector = when (status) {
                        ServerStatus.CONNECTED -> Icons.Default.CheckCircle
                        ServerStatus.ERROR -> Icons.Default.Error
                        ServerStatus.DISCONNECTED, ServerStatus.UNKNOWN -> Icons.AutoMirrored.Filled.HelpOutline
                    },
                    contentDescription = null,
                    tint = when (status) {
                        ServerStatus.CONNECTED -> Color(0xFF4CAF50)
                        ServerStatus.ERROR -> MaterialTheme.colorScheme.error
                        ServerStatus.DISCONNECTED, ServerStatus.UNKNOWN ->
                            MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.size(20.dp),
                )

                Spacer(modifier = Modifier.width(8.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = server.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        TransportChip(transport = server.transport)
                    }

                    if (server.description.isNotBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = server.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    val preview = when (server.transport) {
                        McpTransportType.STDIO -> buildString {
                            append(server.command)
                            if (server.args.isNotEmpty()) {
                                append(" ")
                                append(server.args.joinToString(" ").take(60))
                            }
                        }
                        McpTransportType.LOCALHOST_STDIO -> server.url
                        McpTransportType.HTTP, McpTransportType.SSE -> server.url
                    }
                    if (preview.isNotBlank()) {
                        Text(
                            text = preview,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Spacer(modifier = Modifier.width(4.dp))

                Switch(
                    checked = server.enabled,
                    onCheckedChange = { onToggle() },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = MaterialTheme.colorScheme.primary,
                    ),
                )

                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete server",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun TransportChip(transport: McpTransportType, modifier: Modifier = Modifier) {
    val label = when (transport) {
        McpTransportType.STDIO -> "Stdio"
        McpTransportType.LOCALHOST_STDIO -> "Local"
        McpTransportType.HTTP -> "HTTP"
        McpTransportType.SSE -> "SSE"
    }
    AssistChip(
        onClick = {},
        label = {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
            )
        },
        modifier = modifier,
        colors = AssistChipDefaults.assistChipColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
    )
}

