/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.plugins

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.ui.theme.zeroAssistOutlinedTextFieldColors
import com.zeroclaw.android.ui.theme.zeroAssistSecondaryActionButtonColors

@Composable
fun TermuxPreApprovalSettings(
    patterns: List<String>,
    onAddPattern: (String) -> Unit,
    onRemovePattern: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var newPattern by remember { mutableStateOf("") }

    Column(modifier = modifier) {
        Text(
            text = "Pre-approved command patterns",
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "Commands matching these patterns will be auto-approved in Medium tier.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(modifier = Modifier.height(8.dp))

        for (pattern in patterns) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = pattern,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.TextButton(
                    onClick = { onRemovePattern(pattern) },
                ) {
                    Text("Remove")
                }
            }
        }

        if (patterns.isEmpty()) {
            Text(
                text = "No patterns configured.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                colors = zeroAssistOutlinedTextFieldColors(),
                value = newPattern,
                onValueChange = { newPattern = it },
                label = { Text("New pattern") },
                supportingText = { Text("e.g. \"git *\" or \"npm install\"") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            FilledTonalButton(
                colors = zeroAssistSecondaryActionButtonColors(),
                onClick = {
                    if (newPattern.isNotBlank()) {
                        onAddPattern(newPattern.trim())
                        newPattern = ""
                    }
                },
            ) {
                Text("Add")
            }
        }
    }
}
