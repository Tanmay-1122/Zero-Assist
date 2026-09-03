/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.model.CuratedMcpServers
import com.zeroclaw.android.model.McpTransportType

/**
 * Bottom sheet for adding a curated MCP server.
 *
 * Shows pre-filled info and asks the user to fill in required env keys
 * and optionally a path argument (for stdio servers).
 *
 * @param curated The curated server being added.
 * @param onDismiss Callback when the sheet is dismissed.
 * @param onAdd Callback with the user-provided env values and path argument.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CuratedAddSheet(
    curated: CuratedMcpServers.CuratedServer,
    onDismiss: () -> Unit,
    onAdd: (envValues: Map<String, String>, pathArg: String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val envValues = remember { mutableStateMapOf<String, String>() }
    var pathArg by remember { mutableStateOf("") }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            Text(
                text = "Add ${curated.name}",
                style = MaterialTheme.typography.titleMedium,
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = curated.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Note (e.g. "Requires Node.js")
            curated.note?.let { note ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Env keys
            for (key in curated.envKeys) {
                OutlinedTextField(
                    value = envValues[key] ?: "",
                    onValueChange = { envValues[key] = it },
                    label = { Text(key) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Path arg for stdio servers
            if (curated.transport == McpTransportType.STDIO) {
                OutlinedTextField(
                    value = pathArg,
                    onValueChange = { pathArg = it },
                    label = { Text("Path (optional)") },
                    placeholder = { Text("/path/to/directory") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    ),
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    onClick = { onAdd(envValues.toMap(), pathArg) },
                ) {
                    Text("Add")
                }
            }
        }
    }
}
