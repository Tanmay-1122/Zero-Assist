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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import com.zeroclaw.android.model.AgentEscalation
import com.zeroclaw.android.viewmodel.AgentToolsViewModel

/**
 * Screen for managing agent escalations to human operators.
 *
 * Displays pending escalations with priority, allows resolution.
 */
@Composable
fun EscalationManagementScreen(
    viewModel: AgentToolsViewModel,
    modifier: Modifier = Modifier,
) {
    val pendingEscalations by viewModel.pendingEscalations.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val errorMessage by viewModel.errorMessage.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(16.dp),
    ) {
        // Header
        Text(
            "Agent Escalations",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp),
        )

        Text(
            "${pendingEscalations.size} pending escalations",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            modifier = Modifier.padding(bottom = 16.dp),
        )

        // Error message
        if (errorMessage != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp)
                    .background(MaterialTheme.colorScheme.errorContainer),
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = "Error",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(end = 8.dp),
                    )
                    Text(
                        errorMessage ?: "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        // Escalations list
        if (pendingEscalations.isEmpty()) {
            Text(
                "No pending escalations. System running smoothly!",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
            ) {
                items(pendingEscalations) { escalation ->
                    EscalationCard(
                        escalation = escalation,
                        onResolve = { status, resolution ->
                            viewModel.resolveEscalation(escalation.id, status, resolution)
                        },
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
fun EscalationCard(
    escalation: AgentEscalation,
    onResolve: (String, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showResolutionForm by remember { mutableStateOf(false) }
    var resolutionText by remember { mutableStateOf("") }

    val priorityColor = when (escalation.priority) {
        "critical" -> Color(0xFFD32F2F) // Deep red
        "high" -> Color(0xFFF57C00) // Deep orange
        "normal" -> Color(0xFF1976D2) // Blue
        else -> Color(0xFF388E3C) // Green
    }

    Card(
        modifier = modifier.padding(vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            // Header row
            Row(
                modifier = Modifier
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Priority badge
                Card(
                    modifier = Modifier
                        .background(priorityColor)
                        .padding(4.dp),
                ) {
                    Text(
                        escalation.priority.uppercase(),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // Type badge
                Card(
                    modifier = Modifier.background(MaterialTheme.colorScheme.secondaryContainer),
                ) {
                    Text(
                        escalation.escalationType,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Description
            Text(
                "Issue:",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )

            Text(
                escalation.description,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Agent info
            Row(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "From Agent: ${escalation.agentId.take(8)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                if (escalation.targetRole != null) {
                    Spacer(modifier = Modifier.weight(1f))
                    Text(
                        "Role: ${escalation.targetRole}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Status display
            if (escalation.status == "resolved" && escalation.resolution != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                    ) {
                        Text(
                            "✓ RESOLVED",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            escalation.resolution ?: "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            } else if (!showResolutionForm) {
                // Action buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = { showResolutionForm = true },
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    ) {
                        Text("Resolve")
                    }

                    Button(
                        onClick = {
                            onResolve("rejected", "User rejected escalation")
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                        ),
                    ) {
                        Text("Reject")
                    }
                }
            } else {
                // Resolution form
                OutlinedTextField(
                    value = resolutionText,
                    onValueChange = { resolutionText = it },
                    label = { Text("Resolution") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(100.dp),
                    maxLines = 4,
                )

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                ) {
                    Button(
                        onClick = {
                            if (resolutionText.isNotBlank()) {
                                onResolve("resolved", resolutionText)
                                showResolutionForm = false
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                        enabled = resolutionText.isNotBlank(),
                    ) {
                        Icon(Icons.Default.Check, contentDescription = "Submit", modifier = Modifier.padding(end = 4.dp))
                        Text("Submit")
                    }

                    Button(
                        onClick = { showResolutionForm = false },
                        modifier = Modifier
                            .weight(1f)
                            .height(40.dp),
                        colors = ButtonDefaults.outlinedButtonColors(),
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "Cancel", modifier = Modifier.padding(end = 4.dp))
                        Text("Cancel")
                    }
                }
            }
        }
    }
}
