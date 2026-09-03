/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zeroclaw.android.model.ProjectIntelligence
import com.zeroclaw.android.viewmodel.AgentToolsViewModel

/**
 * Screen for browsing and searching cross-workspace project intelligence.
 *
 * Displays shared knowledge with access level visualization.
 */
@Composable
fun ProjectIntelligenceBrowserScreen(
    viewModel: AgentToolsViewModel,
    modifier: Modifier = Modifier,
) {
    val accessibleIntelligence by viewModel.accessibleIntelligence.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()

    var searchQuery by remember { mutableStateOf("") }
    var showShared by remember { mutableStateOf(true) }
    var showPublic by remember { mutableStateOf(true) }
    var showPrivate by remember { mutableStateOf(false) }

    val filteredIntel = accessibleIntelligence.filter { intel ->
        val matchesSearch = searchQuery.isEmpty() || 
            intel.topicName.contains(searchQuery, ignoreCase = true) ||
            intel.contentSummary.contains(searchQuery, ignoreCase = true)

        val matchesAccess = when (intel.accessLevel) {
            "public" -> showPublic
            "workspace" -> showShared
            "private" -> showPrivate
            else -> true
        }

        matchesSearch && matchesAccess
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
    ) {
        // Header
        Text(
            "Project Intelligence",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        Text(
            "${filteredIntel.size} topics accessible",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(bottom = 16.dp),
        )

        // Search bar
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Search topics...") },
            leadingIcon = {
                Icon(
                    Icons.Default.Search,
                    contentDescription = "Search",
                    tint = MaterialTheme.colorScheme.outline,
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
            singleLine = true,
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Access level filters
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                label = "Public",
                selected = showPublic,
                onToggle = { showPublic = !showPublic },
                modifier = Modifier.weight(1f),
            )

            FilterChip(
                label = "Shared",
                selected = showShared,
                onToggle = { showShared = !showShared },
                modifier = Modifier.weight(1f),
            )

            FilterChip(
                label = "Private",
                selected = showPrivate,
                onToggle = { showPrivate = !showPrivate },
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Intel list
        if (filteredIntel.isEmpty()) {
            Text(
                "No project intelligence matches your search.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(filteredIntel) { intel ->
                    ProjectIntelligenceCard(
                        intel = intel,
                        onAccess = { viewModel.recordIntelligenceAccess(intel.id) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun FilterChip(
    label: String,
    selected: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier
            .height(36.dp)
            .background(
                if (selected) {
                    MaterialTheme.colorScheme.primaryContainer
                } else {
                    MaterialTheme.colorScheme.surfaceContainerHigh
                }
            ),
        onClick = onToggle,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

@Composable
fun ProjectIntelligenceCard(
    intel: ProjectIntelligence,
    onAccess: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accessIcon = when (intel.accessLevel) {
        "public" -> Icons.Default.Public
        else -> Icons.Default.Lock
    }

    val accessColor = when (intel.accessLevel) {
        "public" -> Color(0xFF4CAF50) // Green
        "workspace" -> Color(0xFF2196F3) // Blue
        else -> Color(0xFF757575) // Gray
    }

    Card(
        modifier = modifier.padding(vertical = 8.dp),
        onClick = onAccess,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                ) {
                    // Topic name
                    Text(
                        intel.topicName,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    // Relevance score
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "Relevance: ",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                        )

                        // Relevance bar
                        androidx.compose.material3.LinearProgressIndicator(
                            progress = { intel.relevanceScore },
                            modifier = Modifier
                                .weight(1f)
                                .height(4.dp)
                                .padding(horizontal = 4.dp),
                            color = MaterialTheme.colorScheme.secondary,
                        )

                        Text(
                            "${(intel.relevanceScore * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.secondary,
                        )
                    }
                }

                // Access level badge
                Card(
                    modifier = Modifier.background(accessColor),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            accessIcon,
                            contentDescription = intel.accessLevel,
                            tint = Color.White,
                            modifier = Modifier.padding(end = 4.dp),
                        )

                        Text(
                            intel.accessLevel.uppercase(),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.White,
                            fontSize = androidx.compose.material3.LocalTextStyle.current.fontSize * 0.8f,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Summary
            Text(
                intel.contentSummary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Metadata
            Row(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "From: ${intel.sourceWorkspaceId.take(8)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    "Created: ${formatDate(intel.createdAt)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            // Last accessed
            if (intel.lastAccessedAt != null) {
                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    "Last accessed: ${formatDate(intel.lastAccessedAt!!)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    fontSize = 10.sp,
                )
            }
        }
    }
}

@Composable
private fun formatDate(timestamp: String): String {
    return try {
        val millis = timestamp.toLongOrNull() ?: return "unknown"
        val date = java.util.Date(millis)
        java.text.SimpleDateFormat("MMM d, hh:mm", java.util.Locale.getDefault()).format(date)
    } catch (e: Exception) {
        "unknown"
    }
}
