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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.zeroclaw.android.model.AgentSwarm
import com.zeroclaw.android.viewmodel.AgentToolsViewModel
import org.json.JSONObject

/**
 * Screen for coordinating multi-agent swarms.
 *
 * Displays active swarms and enables creation of new swarm configurations.
 */
@Composable
fun SwarmCoordinatorScreen(
    viewModel: AgentToolsViewModel,
    modifier: Modifier = Modifier,
) {
    val activeSwarms by viewModel.activeSwarms.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()
    
    var showCreateForm by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Agent Swarms",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )

            Text(
                "${activeSwarms.size} active",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
            )
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Error message
        if (errorMessage != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .background(MaterialTheme.colorScheme.errorContainer),
            ) {
                Text(
                    errorMessage ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(12.dp),
                )
            }
        }

        // Swarms list
        if (activeSwarms.isEmpty()) {
            Text(
                "No active swarms. Create one to coordinate multiple agents.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                items(activeSwarms) { swarm ->
                    SwarmCard(
                        swarm = swarm,
                        onDelete = { viewModel.deleteSwarm(swarm.id) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 12.dp),
                    )
                }
            }
        }

        // Create form
        if (showCreateForm) {
            CreateSwarmForm(
                onSwarmCreated = { name, agentIds, coordinatorId, strategy ->
                    viewModel.createSwarm(name, agentIds, coordinatorId, strategy)
                    showCreateForm = false
                },
                onCancel = { showCreateForm = false },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // FAB
        if (!showCreateForm) {
            FloatingActionButton(
                onClick = { showCreateForm = true },
                modifier = Modifier
                    .align(Alignment.End)
                    .padding(top = 16.dp),
                containerColor = MaterialTheme.colorScheme.primary,
            ) {
                Icon(Icons.Default.Add, contentDescription = "Create Swarm")
            }
        }
    }
}

@Composable
fun SwarmCard(
    swarm: AgentSwarm,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val agentIds = swarm.agentIds.takeIf { it.isNotEmpty() } ?: emptyList()

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
                Column(
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        swarm.name,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                    )

                    Spacer(modifier = Modifier.height(4.dp))

                    Text(
                        "Strategy: ${swarm.strategy}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }

                IconButton(
                    onClick = onDelete,
                    modifier = Modifier,
                ) {
                    Icon(
                        Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Coordinator info
            Text(
                "Coordinator: ${swarm.coordinatorAgentId?.take(8) ?: "None"}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Agents list
            Text(
                "Agents (${agentIds.size}):",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
            )

            agentIds.forEach { agentId ->
                Text(
                    "  • $agentId",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.padding(vertical = 2.dp),
                )
            }
        }
    }
}

@Composable
fun CreateSwarmForm(
    onSwarmCreated: (String, List<String>, String, String) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var swarmName by remember { mutableStateOf("") }
    var coordinatorId by remember { mutableStateOf("") }
    var agentIdInput by remember { mutableStateOf("") }
    var strategy by remember { mutableStateOf("sequential") }

    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(16.dp),
    ) {
        Text(
            "Create New Swarm",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 16.dp),
        )

        // Swarm name
        OutlinedTextField(
            value = swarmName,
            onValueChange = { swarmName = it },
            label = { Text("Swarm Name") },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Coordinator
        OutlinedTextField(
            value = coordinatorId,
            onValueChange = { coordinatorId = it },
            label = { Text("Coordinator Agent ID") },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Agent IDs (comma-separated)
        OutlinedTextField(
            value = agentIdInput,
            onValueChange = { agentIdInput = it },
            label = { Text("Agent IDs (comma-separated)") },
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp),
            maxLines = 3,
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Strategy selection
        Text(
            "Strategy:",
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp),
        ) {
            listOf("sequential", "parallel", "dag", "voting").forEach { strat ->
                Button(
                    onClick = { strategy = strat },
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .padding(horizontal = 2.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (strategy == strat) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.secondaryContainer
                        },
                    ),
                ) {
                    Text(
                        strat,
                        style = MaterialTheme.typography.labelSmall,
                        fontSize = androidx.compose.material3.LocalTextStyle.current.fontSize * 0.8f,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Actions
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onCancel,
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp),
                colors = ButtonDefaults.outlinedButtonColors(),
            ) {
                Text("Cancel")
            }

            Button(
                onClick = {
                    if (swarmName.isNotBlank() && coordinatorId.isNotBlank() && agentIdInput.isNotBlank()) {
                        val agentIds = agentIdInput.split(",").map { it.trim() }
                        onSwarmCreated(swarmName, agentIds, coordinatorId, strategy)
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .height(40.dp),
                enabled = swarmName.isNotBlank() && coordinatorId.isNotBlank() && agentIdInput.isNotBlank(),
            ) {
                Text("Create")
            }
        }
    }
}
