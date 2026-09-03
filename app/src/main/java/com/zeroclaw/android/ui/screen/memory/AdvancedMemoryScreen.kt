/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

@file:Suppress("TooManyFunctions")

package com.zeroclaw.android.ui.screen.memory

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zeroclaw.android.model.MemoryFact
import com.zeroclaw.android.model.MemoryHealthStats
import com.zeroclaw.android.model.MemoryRetrievalResult

/**
 * Advanced memory browser and management screen.
 *
 * Displays semantic search results, memory health metrics, fact details,
 * knowledge graph relationships, and consolidation controls.
 *
 * @param advancedMemoryViewModel ViewModel for memory state.
 * @param modifier Modifier applied to root layout.
 */
@Composable
fun AdvancedMemoryScreen(
    advancedMemoryViewModel: AdvancedMemoryViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val searchQuery by advancedMemoryViewModel.searchQuery.collectAsStateWithLifecycle()
    val searchResults by advancedMemoryViewModel.searchResults.collectAsStateWithLifecycle()
    val isSearching by advancedMemoryViewModel.isSearching.collectAsStateWithLifecycle()
    val memoryHealth by advancedMemoryViewModel.memoryHealth.collectAsStateWithLifecycle()
    val selectedFact by advancedMemoryViewModel.selectedFact.collectAsStateWithLifecycle()
    val relatedFacts by advancedMemoryViewModel.relatedFacts.collectAsStateWithLifecycle()
    val consolidationInProgress by advancedMemoryViewModel.consolidationInProgress.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(rememberScrollState()),
    ) {
        // Memory health summary card
        if (memoryHealth != null) {
            MemoryHealthCard(
                health = memoryHealth!!,
                onRefresh = { advancedMemoryViewModel.refreshHealthStats() },
                onConsolidate = { advancedMemoryViewModel.runConsolidation() },
                consolidationInProgress = consolidationInProgress,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Search bar
        SemanticSearchBar(
            query = searchQuery,
            onQueryChange = { advancedMemoryViewModel.performSemanticSearch(it) },
            isSearching = isSearching,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Search results or selected fact details
        if (selectedFact != null) {
            MemoryFactDetailCard(
                fact = selectedFact!!,
                relatedFacts = relatedFacts,
                onClose = { /* Clear selection */ },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
            )
        } else if (searchResults.isNotEmpty()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(searchResults) { result ->
                    MemoryResultCard(
                        result = result,
                        onClick = {
                            advancedMemoryViewModel.selectFact(result.fact)
                            advancedMemoryViewModel.recordFactAccess(result.fact.id)
                        },
                        onDelete = { advancedMemoryViewModel.deleteMemoryFact(result.fact.id) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "Search memory to discover facts",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

/**
 * Memory health summary card showing key metrics.
 */
@Composable
private fun MemoryHealthCard(
    health: MemoryHealthStats,
    onRefresh: () -> Unit,
    onConsolidate: () -> Unit,
    consolidationInProgress: Boolean,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Memory Health",
                    style = MaterialTheme.typography.titleMedium,
                )
                Row {
                    IconButton(onClick = onRefresh, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                // Efficiency metric
                Column(modifier = Modifier.weight(1f)) {
                    Text("Efficiency", style = MaterialTheme.typography.labelSmall)
                    Text(
                        "${health.memoryEfficiency.toInt()}%",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    LinearProgressIndicator(
                        progress = { health.memoryEfficiency / 100f },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                    )
                }

                // Fact count metric
                Column(modifier = Modifier.weight(1f)) {
                    Text("Total Facts", style = MaterialTheme.typography.labelSmall)
                    Text(
                        "${health.totalFacts}",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        "High-value: ${health.highValueFacts}",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                // Density metric
                Column(modifier = Modifier.weight(1f)) {
                    Text("Connectivity", style = MaterialTheme.typography.labelSmall)
                    Text(
                        "%.1f".format(health.graphDensity),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        "avg links/fact",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }
            }

            if (consolidationInProgress) {
                Spacer(modifier = Modifier.height(12.dp))
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Text(
                    "Consolidating memory...",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * Semantic search input bar with query field.
 */
@Composable
private fun SemanticSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    isSearching: Boolean,
    modifier: Modifier = Modifier,
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier,
        placeholder = { Text("Search memory semantically...") },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        singleLine = true,
        enabled = !isSearching,
        shape = RoundedCornerShape(12.dp),
    )
}

/**
 * Individual memory search result card.
 */
@Composable
private fun MemoryResultCard(
    result: MemoryRetrievalResult,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .clickable(onClick = onClick),
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
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                // Relevance score badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "%.0f%%".format(result.relevanceScore * 100),
                        style = MaterialTheme.typography.labelSmall,
                        color = relevanceToColor(result.relevanceScore),
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(
                        result.fact.tags.take(2).joinToString(" "),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        overflow = TextOverflow.Ellipsis,
                        maxLines = 1,
                    )
                }

                // Fact content preview
                Text(
                    text = result.fact.content.take(100),
                    style = MaterialTheme.typography.bodySmall,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 2,
                )

                // Metadata
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    Text(
                        result.fact.source,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Imp: %.1f".format(result.fact.importance),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Acc: ${result.fact.accessCount}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "Delete")
            }
        }
    }
}

/**
 * Detailed view of a selected memory fact with related memories.
 */
@Composable
private fun MemoryFactDetailCard(
    fact: MemoryFact,
    relatedFacts: List<MemoryRetrievalResult>,
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
                Text("Memory Detail", style = MaterialTheme.typography.titleSmall)
                IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Info, contentDescription = "Close")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Main fact content
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Text(
                    text = fact.content,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(12.dp),
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Metadata grid
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Importance", style = MaterialTheme.typography.labelSmall)
                    LinearProgressIndicator(
                        progress = { fact.importance },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(4.dp),
                    )
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text("Access Count", style = MaterialTheme.typography.labelSmall)
                    Text(
                        "${fact.accessCount}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (relatedFacts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Link, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "${relatedFacts.size} related memories",
                        style = MaterialTheme.typography.labelSmall,
                    )
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    relatedFacts.take(3).forEach { result ->
                        Text(
                            text = result.fact.content.take(80),
                            style = MaterialTheme.typography.labelSmall,
                            overflow = TextOverflow.Ellipsis,
                            maxLines = 1,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(4.dp),
                        )
                    }
                }
            }
        }
    }
}

/**
 * Convert relevance score to color.
 */
private fun relevanceToColor(relevance: Float): Color {
    return when {
        relevance >= 0.8f -> Color.Green
        relevance >= 0.5f -> Color.Yellow
        else -> Color.Red
    }
}
