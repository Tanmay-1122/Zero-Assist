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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zeroclaw.android.model.LlmTask
import com.zeroclaw.android.viewmodel.AgentToolsViewModel

/**
 * Screen for monitoring dynamic LLM task execution.
 *
 * Displays pending, running, and completed tasks with token usage tracking.
 */
@Composable
fun LlmTaskMonitorScreen(
    viewModel: AgentToolsViewModel,
    modifier: Modifier = Modifier,
) {
    val pendingTasks by viewModel.pendingLlmTasks.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val toolStats by viewModel.toolStats.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
    ) {
        // Header
        Text(
            "LLM Task Monitor",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp),
        )

        // Stats Overview
        TokenUsageCard(
            pendingTasks = pendingTasks,
            stats = toolStats,
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
        )

        // Task legend
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
        ) {
            TaskStatusBadge("pending", MaterialTheme.colorScheme.outline)
            Spacer(modifier = Modifier.weight(1f))
            TaskStatusBadge("running", MaterialTheme.colorScheme.secondary)
            Spacer(modifier = Modifier.weight(1f))
            TaskStatusBadge("completed", MaterialTheme.colorScheme.primary)
        }

        // Task list
        if (pendingTasks.isEmpty()) {
            Text(
                "No active LLM tasks.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth(),
            ) {
                items(pendingTasks) { task ->
                    LlmTaskCard(
                        task = task,
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
fun TokenUsageCard(
    pendingTasks: List<LlmTask>,
    stats: Map<String, Any>,
    modifier: Modifier = Modifier,
) {
    val totalTokens = (stats["totalTokens"] as? Int) ?: 0
    val estimatedTokens = pendingTasks.sumOf { it.estimatedTokens ?: 5000 }
    val totalEstimated = totalTokens + estimatedTokens

    Card(
        modifier = modifier.background(MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.Default.Speed,
                    contentDescription = "Tokens",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(end = 12.dp),
                )

                Column(
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        "Token Usage",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        fontWeight = FontWeight.Bold,
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        "Used: $totalTokens | Pending: $estimatedTokens | Total: $totalEstimated",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }

            // Progress bar
            Spacer(modifier = Modifier.height(12.dp))

            LinearProgressIndicator(
                progress = { if (totalEstimated > 0) totalTokens.toFloat() / totalEstimated else 0f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp),
                color = MaterialTheme.colorScheme.primary,
                trackColor = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.2f),
            )
        }
    }
}

@Composable
fun TaskStatusBadge(
    status: String,
    color: Color,
) {
    Card(
        modifier = Modifier.background(color),
    ) {
        Text(
            status.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            fontSize = 10.sp,
        )
    }
}

@Composable
fun LlmTaskCard(
    task: LlmTask,
    modifier: Modifier = Modifier,
) {
    val statusColor = when (task.status) {
        "completed" -> MaterialTheme.colorScheme.primary
        "running" -> MaterialTheme.colorScheme.secondary
        "failed" -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }

    val progressPercent = when {
        task.actualTokens > 0 -> minOf(1f, task.actualTokens.toFloat() / (task.estimatedTokens?.toFloat() ?: 5000f))
        task.status == "running" -> 0.3f
        else -> 0f
    }

    Card(
        modifier = modifier.padding(vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Card(
                    modifier = Modifier.background(statusColor),
                ) {
                    Text(
                        task.status.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        fontSize = 10.sp,
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    "Agent: ${task.agentId.take(8)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Task name and model
            Text(
                task.taskName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium,
            )

            Text(
                "Model: ${task.targetModel} | Priority: ${task.priority}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Instructions preview
            if (task.instructions.isNotEmpty()) {
                Text(
                    task.instructions.take(100) + if (task.instructions.length > 100) "..." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                    maxLines = 2,
                )

                Spacer(modifier = Modifier.height(8.dp))
            }

            // Token tracking
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        "Tokens",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Medium,
                    )

                    Text(
                        "${task.actualTokens}/${task.estimatedTokens ?: 5000}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }

                // Progress bar
                LinearProgressIndicator(
                    progress = { progressPercent },
                    modifier = Modifier
                        .weight(2f)
                        .height(6.dp)
                        .padding(horizontal = 8.dp),
                    color = statusColor,
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                )
            }

            // Result display
            if (task.status == "completed" && task.result != null) {
                Spacer(modifier = Modifier.height(12.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                    ) {
                        Text(
                            "Result:",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            task.result?.take(150) ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 3,
                        )
                    }
                }
            }

            // Error display
            if (task.status == "failed" && task.errorMessage != null) {
                Spacer(modifier = Modifier.height(12.dp))

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.errorContainer),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = "Error",
                            tint = MaterialTheme.colorScheme.error,
                            modifier = Modifier
                                .padding(end = 8.dp)
                                .padding(top = 2.dp),
                        )

                        Text(
                            task.errorMessage ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            maxLines = 2,
                        )
                    }
                }
            }
        }
    }
}
