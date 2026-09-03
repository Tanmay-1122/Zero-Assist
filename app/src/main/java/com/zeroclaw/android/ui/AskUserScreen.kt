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
import androidx.compose.material.icons.filled.Info
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
import com.zeroclaw.android.model.AskUserRequest
import com.zeroclaw.android.viewmodel.AgentToolsViewModel

/**
 * Composite screen for managing agent user input requests.
 *
 * Displays pending asks, allows user to respond.
 */
@Composable
fun AskUserScreen(
    viewModel: AgentToolsViewModel,
    modifier: Modifier = Modifier,
) {
    val pendingAsks by viewModel.pendingUserAsks.collectAsState()
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
            "Agent User Input Requests",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
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
                        Icons.Default.Info,
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

        // Pending asks list
        if (pendingAsks.isEmpty()) {
            Text(
                "No pending user input requests.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(32.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
            ) {
                items(pendingAsks) { ask ->
                    AskUserCard(
                        ask = ask,
                        onRespond = { response ->
                            viewModel.respondToAsk(ask.id, response)
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
fun AskUserCard(
    ask: AskUserRequest,
    onRespond: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var userResponse by remember { mutableStateOf("") }
    var responded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.padding(vertical = 8.dp),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            // Question
            Text(
                "Question from Agent",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                ask.question,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Agent info
            Row(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    "Agent ID: ${ask.agentId.take(8)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    "Type: ${ask.questionType}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Response input
            if (ask.userResponse == null && !responded) {
                when (ask.questionType) {
                    "text" -> {
                        OutlinedTextField(
                            value = userResponse,
                            onValueChange = { userResponse = it },
                            label = { Text("Your response") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            maxLines = 5,
                        )
                    }

                    "boolean" -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
                        ) {
                            Button(
                                onClick = {
                                    userResponse = "true"
                                    responded = true
                                    onRespond("true")
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                                ),
                            ) {
                                Icon(Icons.Default.Check, contentDescription = "Yes", modifier = Modifier.padding(end = 4.dp))
                                Text("Yes")
                            }
                            Button(
                                onClick = {
                                    userResponse = "false"
                                    responded = true
                                    onRespond("false")
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer,
                                ),
                            ) {
                                Icon(Icons.Default.Close, contentDescription = "No", modifier = Modifier.padding(end = 4.dp))
                                Text("No")
                            }
                        }
                    }

                    "choice" -> {
                        // Parse choices
                        val choices = try {
                            org.json.JSONObject(ask.choicesJson ?: "{}").optJSONArray("choices")
                                ?.let { (0 until it.length()).map { i -> it.getString(i) } }
                                ?: emptyList()
                        } catch (e: Exception) {
                            emptyList()
                        }

                        Column(
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            choices.forEach { choice ->
                                Button(
                                    onClick = {
                                        userResponse = choice
                                        responded = true
                                        onRespond(choice)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(40.dp)
                                        .padding(bottom = 4.dp),
                                    colors = ButtonDefaults.outlinedButtonColors(),
                                ) {
                                    Text(choice)
                                }
                            }
                        }
                    }

                    else -> {
                        OutlinedTextField(
                            value = userResponse,
                            onValueChange = { userResponse = it },
                            label = { Text("Your response") },
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Submit button
                if (!responded && ask.questionType !in listOf("boolean", "choice")) {
                    Button(
                        onClick = {
                            if (userResponse.isNotBlank()) {
                                responded = true
                                onRespond(userResponse)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp),
                        enabled = userResponse.isNotBlank(),
                    ) {
                        Text("Submit Response")
                    }
                }
            } else if (ask.userResponse != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                    ) {
                        Text(
                            "Your Response (Submitted)",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Medium,
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            ask.userResponse ?: "",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}
