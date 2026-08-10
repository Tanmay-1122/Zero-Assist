/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.debug

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.diagnostics.RichRuntimeDiagnostics
import com.zeroclaw.android.goal.memory.GoalDiagnostics
import com.zeroclaw.android.goal.integration.GoalExecutionPanel
import com.zeroclaw.android.goal.integration.GoalExecutionUiState
import com.zeroclaw.android.planner.UiPlannerInspector
import com.zeroclaw.android.runtime.BlockRuntime
import com.zeroclaw.android.service.PipelineMode
import com.zeroclaw.android.service.RichPipelineFeatureFlags

/**
 * Developer Inspector Dashboard for inspecting runtime block trees, event logs, planner traces,
 * feature flags, and goal execution state.
 */
@Composable
fun DeveloperInspectorDashboard(
    runtime: BlockRuntime?,
    modifier: Modifier = Modifier,
    goalState: GoalExecutionUiState? = null,
) {
    val currentMode by RichPipelineFeatureFlags.mode.collectAsState()
    val scrollState = rememberScrollState()

    Surface(
        modifier = modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
                .verticalScroll(scrollState),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = "🛠️ Developer Inspector Dashboard",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )

            // 1. Feature Flag Controls
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Pipeline Execution Mode: ${currentMode.name}",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Button(onClick = { RichPipelineFeatureFlags.setMode(PipelineMode.LEGACY) }) {
                            Text("LEGACY")
                        }
                        Button(onClick = { RichPipelineFeatureFlags.setMode(PipelineMode.HYBRID) }) {
                            Text("HYBRID")
                        }
                        Button(onClick = { RichPipelineFeatureFlags.setMode(PipelineMode.RICH) }) {
                            Text("RICH")
                        }
                    }
                }
            }

            // 2. Active Block Tree Hierarchy
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Active Block Tree",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val blocks = runtime?.blocksState?.collectAsState()?.value ?: emptyList()
                    Text(
                        text = if (blocks.isEmpty()) "No active blocks in runtime" else {
                            blocks.joinToString("\n") { block ->
                                UiPlannerInspector.inspectBlockTree(block)
                            }
                        },
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(8.dp),
                    )
                }
            }

            // 3. Media Capabilities & Intent Status
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Media Intent & Capability Router Status",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(
                        text = "• Intent Classifier: ACTIVE (IMAGE_SEARCH, VIDEO_SEARCH, MAP_SEARCH)\n• Media Tool Abstraction: MediaSearchTool\n• Media Rendering Blocks: ImageBlock, GalleryBlock, MapBlock",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }

            // 4. Capability Registry & Resolver Status
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Capability Registry & Resolver Status",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val registeredCapabilities = com.zeroclaw.android.capability.CapabilityRegistry.getAllCapabilities()
                    Text(
                        text = registeredCapabilities.joinToString("\n") { cap ->
                            val providers = com.zeroclaw.android.capability.CapabilityRegistry.getProviders(cap.id)
                            "• [${cap.id}] (${cap.securityLevel}) -> ${providers.size} provider(s): ${providers.joinToString { "${it.providerId} (prio=${it.priority})" }}"
                        },
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(8.dp),
                    )
                }
            }

            // 5. Runtime Diagnostics & Event Traces
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Diagnostics Trace Log",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val summary = RichRuntimeDiagnostics.dumpSummary()
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(8.dp),
                    )
                }
            }

            // 6. Goal Execution Engine (GOAEE)
            GoalExecutionPanel(state = goalState, modifier = Modifier)

            // 7. Goal Diagnostics Telemetry
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "GOAEE Telemetry",
                        style = MaterialTheme.typography.titleMedium,
                    )
                    val telemetry = GoalDiagnostics.telemetry()
                    Text(
                        text = buildString {
                            appendLine("Goals started:   ${telemetry.goalsStarted}")
                            appendLine("Goals completed: ${telemetry.goalsCompleted}")
                            appendLine("Goals failed:    ${telemetry.goalsFailed}")
                            appendLine("Goals cancelled: ${telemetry.goalsCancelled}")
                            appendLine("Tasks succeeded: ${telemetry.tasksSucceeded}")
                            appendLine("Tasks failed:    ${telemetry.tasksFailed}")
                            appendLine("Recovery events: ${telemetry.recoveryEvents}")
                            appendLine("Active goals:    ${telemetry.activeGoals}")
                            appendLine("Resources: ${telemetry.resourceUsage}")
                        }.trimEnd(),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .padding(top = 8.dp)
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(8.dp),
                    )
                }
            }
        }
    }
}
