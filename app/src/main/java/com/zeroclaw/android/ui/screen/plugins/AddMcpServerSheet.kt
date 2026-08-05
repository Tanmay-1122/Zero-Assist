/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.plugins

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.data.remote.McpServerProbe
import com.zeroclaw.android.model.McpServerEntry
import com.zeroclaw.android.model.McpTransportType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Bottom sheet for adding or editing an MCP server.
 *
 * @param editingEntry If non-null, we're editing an existing server.
 * @param onDismiss Callback when the sheet is dismissed.
 * @param onSave Callback with the new or updated server entry.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AddMcpServerSheet(
    editingEntry: McpServerEntry? = null,
    onDismiss: () -> Unit,
    onSave: (McpServerEntry) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val isEditing = editingEntry != null

    var name by remember(editingEntry) { mutableStateOf(editingEntry?.name ?: "") }
    var transport by remember(editingEntry) {
        mutableStateOf(editingEntry?.transport ?: McpTransportType.HTTP)
    }
    var command by remember(editingEntry) { mutableStateOf(editingEntry?.command ?: "") }
    var argsText by remember(editingEntry) {
        mutableStateOf(editingEntry?.args?.joinToString(" ") ?: "")
    }
    var url by remember(editingEntry) { mutableStateOf(editingEntry?.url ?: "") }
    var description by remember(editingEntry) { mutableStateOf(editingEntry?.description ?: "") }
    var timeoutText by remember(editingEntry) {
        mutableStateOf(editingEntry?.toolTimeoutSecs?.toString() ?: "")
    }

    // Env and headers as editable key=value list
    val envPairs = remember(editingEntry) {
        mutableStateListOf<Pair<String, String>>().apply {
            editingEntry?.env?.forEach { (k, v) -> add(k to v) }
        }
    }
    val headerPairs = remember(editingEntry) {
        mutableStateListOf<Pair<String, String>>().apply {
            editingEntry?.headers?.forEach { (k, v) -> add(k to v) }
        }
    }

    var envKeyInput by remember { mutableStateOf("") }
    var envValueInput by remember { mutableStateOf("") }
    var headerKeyInput by remember { mutableStateOf("") }
    var headerValueInput by remember { mutableStateOf("") }

    var isValidatingUrl by remember { mutableStateOf(false) }
    var urlValidationResult by remember { mutableStateOf<Result<String>?>(null) }
    val coroutineScope = rememberCoroutineScope()

    fun validateMcpServerUrl(targetUrl: String, headers: List<Pair<String, String>>) {
        if (targetUrl.isBlank()) return
        isValidatingUrl = true
        urlValidationResult = null
        coroutineScope.launch(Dispatchers.IO) {
            val res = McpServerProbe.probe(targetUrl.trim(), transport, headers.toMap())
            withContext(Dispatchers.Main) {
                isValidatingUrl = false
                urlValidationResult = res
            }
        }
    }

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
                text = if (isEditing) "Edit MCP Server" else "Add MCP Server",
                style = MaterialTheme.typography.titleMedium,
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Name
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Server name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                ),
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Transport selector
            Text(
                text = "Transport",
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(modifier = Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                McpTransportType.entries.forEach { type ->
                    FilterChip(
                        selected = transport == type,
                        onClick = { transport = type },
                        label = { Text(type.name) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        ),
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Transport-specific fields
            when (transport) {
                McpTransportType.STDIO -> {
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        label = { Text("Command") },
                        placeholder = { Text("npx") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = argsText,
                        onValueChange = { argsText = it },
                        label = { Text("Arguments (space-separated)") },
                        placeholder = { Text("-y @modelcontextprotocol/server-memory") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        ),
                    )

                    // Env vars for stdio
                    LocalStdioEnvSection(envPairs = envPairs, envKeyInput = envKeyInput, envValueInput = envValueInput,
                        onEnvKeyChange = { envKeyInput = it }, onEnvValueChange = { envValueInput = it },
                        onEnvAdd = { if (envKeyInput.isNotBlank()) { envPairs.add(envKeyInput to envValueInput); envKeyInput = ""; envValueInput = "" } },
                        onEnvRemove = { i -> envPairs.removeAt(i) })
                }

                McpTransportType.LOCALHOST_STDIO -> {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("Shim URL") },
                        placeholder = { Text("http://localhost:9735/") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = command,
                        onValueChange = { command = it },
                        label = { Text("Command") },
                        placeholder = { Text("npx") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        ),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = argsText,
                        onValueChange = { argsText = it },
                        label = { Text("Arguments (space-separated)") },
                        placeholder = { Text("-y @modelcontextprotocol/server-memory") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        ),
                    )

                    LocalStdioEnvSection(envPairs = envPairs, envKeyInput = envKeyInput, envValueInput = envValueInput,
                        onEnvKeyChange = { envKeyInput = it }, onEnvValueChange = { envValueInput = it },
                        onEnvAdd = { if (envKeyInput.isNotBlank()) { envPairs.add(envKeyInput to envValueInput); envKeyInput = ""; envValueInput = "" } },
                        onEnvRemove = { i -> envPairs.removeAt(i) })
                }

                McpTransportType.HTTP, McpTransportType.SSE -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedTextField(
                            value = url,
                            onValueChange = {
                                url = it
                                urlValidationResult = null
                            },
                            label = { Text("URL") },
                            placeholder = { Text("https://api.example.com/mcp") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            ),
                        )
                        Button(
                            onClick = { validateMcpServerUrl(url, headerPairs) },
                            enabled = url.isNotBlank() && !isValidatingUrl,
                            modifier = Modifier.height(56.dp),
                        ) {
                            if (isValidatingUrl) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onPrimary,
                                )
                            } else {
                                Text("Validate")
                            }
                        }
                    }

                    urlValidationResult?.let { res ->
                        Spacer(modifier = Modifier.height(6.dp))
                        res.onSuccess { msg ->
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.6f),
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CheckCircle,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = msg,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    )
                                }
                            }
                        }.onFailure { err ->
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.6f),
                                shape = MaterialTheme.shapes.small,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(16.dp),
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Validation failed: ${err.localizedMessage ?: "Unreachable"}",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onErrorContainer,
                                    )
                                }
                            }
                        }
                    }

                    // Headers for HTTP/SSE
                    if (headerPairs.isNotEmpty() || headerKeyInput.isNotBlank()) {
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = "HTTP Headers",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                    for (i in headerPairs.indices) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "${headerPairs[i].first}: ${headerPairs[i].second}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { headerPairs.removeAt(i) }) {
                                Text("Remove")
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(modifier = Modifier.fillMaxWidth()) {
                        OutlinedTextField(
                            value = headerKeyInput,
                            onValueChange = { headerKeyInput = it },
                            label = { Text("Header name") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            ),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        OutlinedTextField(
                            value = headerValueInput,
                            onValueChange = { headerValueInput = it },
                            label = { Text("Value") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            colors = OutlinedTextFieldDefaults.colors(
                                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                            ),
                        )
                        TextButton(
                            onClick = {
                                if (headerKeyInput.isNotBlank()) {
                                    headerPairs.add(headerKeyInput to headerValueInput)
                                    headerKeyInput = ""
                                    headerValueInput = ""
                                }
                            },
                        ) {
                            Text("Add")
                        }
                    }
                }
            }

            // Description
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = description,
                onValueChange = { description = it },
                label = { Text("Description (optional)") },
                placeholder = { Text("e.g. Levii UI Automation agent") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                ),
            )

            // Timeout
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = timeoutText,
                onValueChange = { timeoutText = it.filter { c -> c.isDigit() } },
                label = { Text("Tool timeout (seconds, optional)") },
                placeholder = { Text("180") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                ),
            )

            Spacer(modifier = Modifier.height(20.dp))

            // Save / Cancel
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
                Spacer(modifier = Modifier.width(8.dp))
                TextButton(
                    onClick = {
                        if (name.isBlank()) return@TextButton
                        val entry = McpServerEntry(
                            id = editingEntry?.id ?: UUID.randomUUID().toString(),
                            name = name.trim(),
                            enabled = editingEntry?.enabled ?: true,
                            transport = transport,
                            command = command.trim(),
                            args = argsText.trim().split("\\s+".toRegex()).filter { it.isNotBlank() },
                            url = url.trim(),
                            env = envPairs.toMap(),
                            headers = headerPairs.toMap(),
                            toolTimeoutSecs = timeoutText.toLongOrNull(),
                            description = description.trim(),
                        )
                        onSave(entry)
                        onDismiss()
                    },
                ) {
                    Text(if (isEditing) "Save" else "Add")
                }
            }
        }
    }
}

@Composable
private fun LocalStdioEnvSection(
    envPairs: List<Pair<String, String>>,
    envKeyInput: String,
    envValueInput: String,
    onEnvKeyChange: (String) -> Unit,
    onEnvValueChange: (String) -> Unit,
    onEnvAdd: () -> Unit,
    onEnvRemove: (Int) -> Unit,
) {
    if (envPairs.isNotEmpty() || envKeyInput.isNotBlank()) {
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = "Environment variables",
            style = MaterialTheme.typography.labelMedium,
        )
    }
    for (i in envPairs.indices) {
        Spacer(modifier = Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "${envPairs[i].first}=${envPairs[i].second}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { onEnvRemove(i) }) {
                Text("Remove")
            }
        }
    }
    Spacer(modifier = Modifier.height(4.dp))
    Row(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = envKeyInput,
            onValueChange = onEnvKeyChange,
            label = { Text("Key") },
            singleLine = true,
            modifier = Modifier.weight(1f),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            ),
        )
        Spacer(modifier = Modifier.width(8.dp))
        OutlinedTextField(
            value = envValueInput,
            onValueChange = onEnvValueChange,
            label = { Text("Value") },
            singleLine = true,
            modifier = Modifier.weight(1f),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
            ),
        )
        TextButton(onClick = onEnvAdd) {
            Text("Add")
        }
    }
}
