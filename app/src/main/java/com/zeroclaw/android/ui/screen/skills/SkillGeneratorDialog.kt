/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

@file:Suppress("UndocumentedPublicFunction", "UndocumentedPublicClass")

package com.zeroclaw.android.ui.screen.skills

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.ZeroClawApplication
import kotlinx.coroutines.launch

@Composable
fun SkillGeneratorDialog(
    onDismiss: () -> Unit,
    onSkillGenerated: () -> Unit,
) {
    var description by remember { mutableStateOf("") }
    var author by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var successMessage by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val app = LocalContext.current.applicationContext as ZeroClawApplication
    val skillsBridge = app.skillsBridge

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Header
        Text(
            "Generate Skill",
            style = MaterialTheme.typography.headlineSmall,
        )

        Text(
            "Describe what you want your skill to do, and we'll generate it for you.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Description input
        OutlinedTextField(
            value = description,
            onValueChange = {
                description = it
                errorMessage = ""
            },
            label = { Text("Skill Description") },
            placeholder = { Text("e.g., Convert CSV files to JSON format") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 4,
            maxLines = 8,
            enabled = !isLoading,
        )

        // Author input
        OutlinedTextField(
            value = author,
            onValueChange = { author = it },
            label = { Text("Author (Optional)") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading,
            singleLine = true,
        )

        // Helper text
        Text(
            "💡 Tip: Be specific about what tools and functionality the skill should have.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
        )

        // Generate button
        if (isLoading) {
            CircularProgressIndicator(modifier = Modifier.padding(16.dp))
            Text(
                "Generating skill...",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Button(
                onClick = {
                    if (description.isBlank()) {
                        errorMessage = "Please provide a skill description"
                        return@Button
                    }

                    isLoading = true
                    scope.launch {
                        try {
                            skillsBridge.generateAndInstallSkill(
                                description,
                                author.ifBlank { null },
                            )
                            successMessage = "Skill generated and installed successfully!"
                            onSkillGenerated()
                            onDismiss()
                        } catch (e: Exception) {
                            errorMessage = "Failed to generate skill: ${e.message}"
                            isLoading = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = description.isNotBlank(),
            ) {
                Icon(
                    Icons.Default.Add,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text("Generate Skill")
            }
        }

        // Cancel button
        OutlinedButton(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading,
        ) {
            Text("Cancel")
        }

        // Error message
        if (errorMessage.isNotEmpty()) {
            Snackbar(
                modifier = Modifier.padding(16.dp),
            ) {
                Column {
                    Text(
                        "Error",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Text(errorMessage)
                }
            }
        }

        // Success message
        if (successMessage.isNotEmpty()) {
            Snackbar(
                modifier = Modifier.padding(16.dp),
            ) {
                Text(successMessage)
            }
        }
    }
}

@Composable
fun SkillGeneratorButton(
    onSkillGenerated: () -> Unit,
) {
    var showDialog by remember { mutableStateOf(false) }

    Button(
        onClick = { showDialog = true },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(
            Icons.Default.Add,
            contentDescription = null,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text("Generate Skill")
    }

    if (showDialog) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { showDialog = false },
        ) {
            SkillGeneratorDialog(
                onDismiss = { showDialog = false },
                onSkillGenerated = onSkillGenerated,
            )
        }
    }
}
