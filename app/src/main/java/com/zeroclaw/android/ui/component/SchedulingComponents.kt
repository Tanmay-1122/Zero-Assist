/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.model.CronJobIntent
import com.zeroclaw.android.viewmodel.ScheduleTState

/**
 * Dialog for handling intelligent task scheduling results and clarifications.
 */
@Composable
fun SchedulingResultDialog(
    state: ScheduleTState,
    onDismiss: () -> Unit,
    onClarificationSubmit: (Map<String, String>) -> Unit
) {
    when (state) {
        is ScheduleTState.Success -> {
            SuccessDialog(state, onDismiss)
        }

        is ScheduleTState.NeedsClarification -> {
            ClarificationDialog(
                intent = state.intent,
                questions = state.questions,
                onSubmit = onClarificationSubmit,
                onDismiss = onDismiss
            )
        }

        is ScheduleTState.Error -> {
            ErrorDialog(state.message, onDismiss)
        }

        else -> {}
    }
}

/**
 * Success dialog showing job created.
 */
@Composable
fun SuccessDialog(
    state: ScheduleTState.Success,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.CheckCircle,
                contentDescription = "Success",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        },
        title = { Text("Task Scheduled") },
        text = { Text(state.message) },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Done")
            }
        }
    )
}

/**
 * Dialog for asking clarification questions.
 */
@Composable
fun ClarificationDialog(
    intent: CronJobIntent,
    questions: List<String>,
    onSubmit: (Map<String, String>) -> Unit,
    onDismiss: () -> Unit
) {
    val answers = remember { mutableStateMapOf<String, String>() }
    val scrollState = rememberScrollState()

    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Info,
                contentDescription = "Clarification",
                tint = MaterialTheme.colorScheme.secondary
            )
        },
        title = { Text("Need Clarification") },
        text = {
            Column(
                modifier = Modifier
                    .verticalScroll(scrollState)
                    .fillMaxWidth()
            ) {
                Text(
                    text = "I understand you want to execute this task:",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.paddingBottom(8.dp)
                )

                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(8.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = intent.toSummary(),
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "But I need a few clarifications:",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.paddingBottom(8.dp)
                )

                questions.forEachIndexed { index, question ->
                    OutlinedTextField(
                        value = answers[question] ?: "",
                        onValueChange = { answers[question] = it },
                        label = { Text(question) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        maxLines = 2
                    )
                }
            }
        },
        confirmButton = {
            val allAnswered = questions.all { answers[it]?.isNotEmpty() == true }
            Button(
                onClick = { onSubmit(answers.toMap()) },
                enabled = allAnswered
            ) {
                Text("Continue")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Error dialog.
 */
@Composable
fun ErrorDialog(
    message: String,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Warning,
                contentDescription = "Error",
                tint = MaterialTheme.colorScheme.error
            )
        },
        title = { Text("Scheduling Error") },
        text = { Text(message) },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("OK")
            }
        }
    )
}

/**
 * Status indicator shown inline while processing.
 */
@Composable
fun SchedulingStatusIndicator(
    state: ScheduleTState,
    modifier: Modifier = Modifier
) {
    when (state) {
        ScheduleTState.Processing -> {
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Processing your request...")
            }
        }

        is ScheduleTState.Success -> {
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .background(
                        MaterialTheme.colorScheme.primaryContainer,
                        MaterialTheme.shapes.small
                    )
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.CheckCircle,
                    contentDescription = "Success",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Job created: ${state.jobId}",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        is ScheduleTState.Error -> {
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .padding(12.dp)
                    .background(
                        MaterialTheme.colorScheme.errorContainer,
                        MaterialTheme.shapes.small
                    )
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Warning,
                    contentDescription = "Error",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = state.message,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        else -> {}
    }
}

// Helper extension function for Dp.paddingBottom
fun Modifier.paddingBottom(value: Dp) = this.padding(bottom = value)
