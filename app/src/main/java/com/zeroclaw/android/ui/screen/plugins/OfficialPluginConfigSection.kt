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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zeroclaw.android.ZeroClawApplication
import com.zeroclaw.android.model.AppSettings
import com.zeroclaw.android.model.ComposioReadiness
import com.zeroclaw.android.model.OfficialPlugins
import com.zeroclaw.android.service.sandbox.SandboxState
import com.zeroclaw.android.ui.component.SettingsToggleRow
import com.zeroclaw.android.ui.screen.settings.SettingsViewModel
import com.zeroclaw.android.ui.screen.settings.googleworkspace.ALL_GWS_SERVICES
import com.zeroclaw.android.ui.screen.settings.googleworkspace.GWS_SERVICE_LABELS
import com.zeroclaw.android.ui.theme.zeroAssistOutlinedTextFieldColors
import com.zeroclaw.android.ui.theme.zeroAssistSecondaryActionButtonColors
/** Available web search engine options. */
private val WEB_SEARCH_ENGINES = listOf("duckduckgo", "brave")

/**
 * Renders a purpose-built configuration form for an official plugin.
 *
 * Dispatches to a per-plugin section composable based on [officialPluginId].
 * Each section reads from [settings] and writes changes via [viewModel],
 * mirroring the fields previously found in `WebAccessScreen` and
 * `ToolManagementScreen`.
 *
 * @param officialPluginId One of the [OfficialPlugins] constant IDs.
 * @param settings Current application settings.
 * @param viewModel The [SettingsViewModel] for persisting changes.
 * @param onSelectSharedFolder Callback to launch folder picker for shared folder plugin.
 * @param onSelectWorkflowFolder Callback to launch folder picker for workflow folder plugin.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
fun OfficialPluginConfigSection(
    officialPluginId: String,
    settings: AppSettings,
    viewModel: SettingsViewModel,
    onSelectSharedFolder: (() -> Unit)? = null,
    onSelectWorkflowFolder: (() -> Unit)? = null,
    onGwsSignIn: (() -> Unit)? = null,
    onGwsSignOut: (() -> Unit)? = null,
    gwsIsSignedIn: Boolean = false,
    gwsAccountEmail: String? = null,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        when (officialPluginId) {
            OfficialPlugins.WEB_SEARCH -> WebSearchConfig(settings, viewModel)
            OfficialPlugins.WEB_FETCH -> WebFetchConfig(settings, viewModel)
            OfficialPlugins.HTTP_REQUEST -> HttpRequestConfig(settings, viewModel)
            OfficialPlugins.BROWSER -> BrowserConfig(settings, viewModel)
            OfficialPlugins.COMPOSIO -> ComposioConfig(settings, viewModel)

            OfficialPlugins.SHARED_FOLDER -> SharedFolderConfig(settings, viewModel, onSelectSharedFolder)
            OfficialPlugins.WORKFLOW_FOLDER -> WorkflowFolderConfig(settings, viewModel, onSelectWorkflowFolder)
            OfficialPlugins.LINUX_SANDBOX -> LinuxSandboxConfig()
            OfficialPlugins.TERMUX -> TermuxPluginConfigSection(settings = settings, viewModel = viewModel)
            OfficialPlugins.GOOGLE_WORKSPACE -> GoogleWorkspaceConfig(
                settings = settings,
                viewModel = viewModel,
                isSignedIn = gwsIsSignedIn,
                accountEmail = gwsAccountEmail,
                onSignIn = onGwsSignIn,
                onSignOut = onGwsSignOut,
            )
        }
    }
}

/**
 * Web search plugin configuration.
 *
 * Controls the search engine provider, Brave API key, max results, and
 * timeout. Maps to upstream `[tools.web_search]` TOML section.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WebSearchConfig(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) {
    var engineExpanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = engineExpanded,
        onExpandedChange = { engineExpanded = it },
    ) {
        OutlinedTextField(
        colors = zeroAssistOutlinedTextFieldColors(),
            value = settings.webSearchProvider,
            onValueChange = {},
            readOnly = true,
            label = { Text("Search engine") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(engineExpanded) },
            enabled = settings.webSearchEnabled,
            modifier =
                Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = engineExpanded,
            onDismissRequest = { engineExpanded = false },
        ) {
            for (engine in WEB_SEARCH_ENGINES) {
                DropdownMenuItem(
                    text = { Text(engine) },
                    onClick = {
                        viewModel.updateWebSearchProvider(engine)
                        engineExpanded = false
                    },
                )
            }
        }
    }

    if (settings.webSearchProvider == "brave") {
        OutlinedTextField(
        colors = zeroAssistOutlinedTextFieldColors(),
            value = settings.webSearchBraveApiKey,
            onValueChange = { viewModel.updateWebSearchBraveApiKey(it) },
            label = { Text("Brave Search API key") },
            supportingText = { Text("Required for Brave search engine") },
            singleLine = true,
            enabled = settings.webSearchEnabled,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth(),
        )
        if (settings.webSearchEnabled && settings.webSearchBraveApiKey.isBlank()) {
            Text(
                text = "Brave Search requires an API key",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }

    OutlinedTextField(
        colors = zeroAssistOutlinedTextFieldColors(),
        value = settings.webSearchMaxResults.toString(),
        onValueChange = { v ->
            v.toIntOrNull()?.let { viewModel.updateWebSearchMaxResults(it) }
        },
        label = { Text("Max results") },
        supportingText = { Text("Number of search results (1\u201310)") },
        singleLine = true,
        enabled = settings.webSearchEnabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        colors = zeroAssistOutlinedTextFieldColors(),
        value = settings.webSearchTimeoutSecs.toString(),
        onValueChange = { v ->
            v.toIntOrNull()?.let { viewModel.updateWebSearchTimeoutSecs(it) }
        },
        label = { Text("Timeout (seconds)") },
        singleLine = true,
        enabled = settings.webSearchEnabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Web fetch plugin configuration.
 *
 * Controls domain allowlists, blocklists, response size limits, and
 * timeouts. Maps to upstream `[tools.web_fetch]` TOML section.
 */
@Composable
private fun WebFetchConfig(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) {
    OutlinedTextField(
        colors = zeroAssistOutlinedTextFieldColors(),
        value = settings.webFetchAllowedDomains,
        onValueChange = { viewModel.updateWebFetchAllowedDomains(it) },
        label = { Text("Allowed domains") },
        supportingText = { Text("Comma-separated (empty allows all)") },
        enabled = settings.webFetchEnabled,
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        colors = zeroAssistOutlinedTextFieldColors(),
        value = settings.webFetchBlockedDomains,
        onValueChange = { viewModel.updateWebFetchBlockedDomains(it) },
        label = { Text("Blocked domains") },
        supportingText = { Text("Comma-separated domains to deny") },
        enabled = settings.webFetchEnabled,
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        colors = zeroAssistOutlinedTextFieldColors(),
        value = settings.webFetchMaxResponseSize.toString(),
        onValueChange = { v ->
            v.toIntOrNull()?.let { viewModel.updateWebFetchMaxResponseSize(it) }
        },
        label = { Text("Max response size (bytes)") },
        singleLine = true,
        enabled = settings.webFetchEnabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        colors = zeroAssistOutlinedTextFieldColors(),
        value = settings.webFetchTimeoutSecs.toString(),
        onValueChange = { v ->
            v.toIntOrNull()?.let { viewModel.updateWebFetchTimeoutSecs(it) }
        },
        label = { Text("Timeout (seconds)") },
        singleLine = true,
        enabled = settings.webFetchEnabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * HTTP request plugin configuration.
 *
 * Controls domain allowlists, response size limits, and timeouts.
 * Uses a deny-by-default policy. Maps to upstream `[tools.http_request]`
 * TOML section.
 */
@Composable
private fun HttpRequestConfig(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) {
    OutlinedTextField(
        colors = zeroAssistOutlinedTextFieldColors(),
        value = settings.httpRequestAllowedDomains,
        onValueChange = { viewModel.updateHttpRequestAllowedDomains(it) },
        label = { Text("Allowed domains") },
        supportingText = { Text("Comma-separated (required, deny-by-default)") },
        enabled = settings.httpRequestEnabled,
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        colors = zeroAssistOutlinedTextFieldColors(),
        value = settings.httpRequestMaxResponseSize.toString(),
        onValueChange = { v ->
            v.toIntOrNull()?.let { viewModel.updateHttpRequestMaxResponseSize(it) }
        },
        label = { Text("Max response size (bytes)") },
        singleLine = true,
        enabled = settings.httpRequestEnabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        colors = zeroAssistOutlinedTextFieldColors(),
        value = settings.httpRequestTimeoutSecs.toString(),
        onValueChange = { v ->
            v.toIntOrNull()?.let { viewModel.updateHttpRequestTimeoutSecs(it) }
        },
        label = { Text("Timeout (seconds)") },
        singleLine = true,
        enabled = settings.httpRequestEnabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )

    Text(
        text =
            "HTTP requests use a deny-by-default policy. Only domains listed " +
                "above will be accessible. Leave empty to block all requests.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 4.dp),
    )

    if (settings.httpRequestEnabled && settings.httpRequestAllowedDomains.isBlank()) {
        Text(
            text = "No allowed domains configured \u2014 HTTP requests will be rejected",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}

/**
 * Browser plugin configuration.
 *
 * Controls domain allowlists for web page browsing. Maps to upstream
 * `[browser]` TOML section.
 */
@Composable
private fun BrowserConfig(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) {
    OutlinedTextField(
        colors = zeroAssistOutlinedTextFieldColors(),
        value = settings.browserAllowedDomains,
        onValueChange = { viewModel.updateBrowserAllowedDomains(it) },
        label = { Text("Allowed domains") },
        supportingText = { Text("Comma-separated (empty allows all)") },
        enabled = settings.browserEnabled,
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * Composio integration plugin configuration.
 *
 * Controls the Sessions/MCP key and legacy REST settings for third-party
 * tool integrations via Composio. Maps to upstream `[composio]` TOML section.
 */
@Composable
private fun ComposioConfig(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) {
    val readiness =
        ComposioReadiness.from(
            enabled = settings.composioEnabled,
            apiKey = settings.composioApiKey,
        )

    OutlinedTextField(
        colors = zeroAssistOutlinedTextFieldColors(),
        value = settings.composioApiKey,
        onValueChange = { viewModel.updateComposioApiKey(it) },
        label = { Text("Composio key") },
        supportingText = {
            Text(
                "Use a ck_ Sessions/MCP consumer key for connected account tools. " +
                    "Legacy REST keys use entity ID; uak_ CLI login keys stay inactive.",
            )
        },
        singleLine = true,
        enabled = settings.composioEnabled,
        visualTransformation = PasswordVisualTransformation(),
        modifier = Modifier.fillMaxWidth(),
    )

    if (settings.composioEnabled) {
        Text(
            text = readiness.statusMessage,
            style = MaterialTheme.typography.bodySmall,
            color =
                if (readiness.isActive) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    if (!readiness.usesSessionsKey && !readiness.usesCliUserKey) {
        OutlinedTextField(
            colors = zeroAssistOutlinedTextFieldColors(),
            value = settings.composioEntityId,
            onValueChange = { viewModel.updateComposioEntityId(it) },
            label = { Text("Legacy REST user/entity ID") },
            supportingText = {
                Text("Only used by legacy REST keys. ck_ Sessions keys ignore this value.")
            },
            singleLine = true,
            enabled = settings.composioEnabled,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (
        settings.composioEnabled &&
        readiness.usesLegacyRestKey &&
        settings.composioEntityId.equals("youtube", ignoreCase = true)
    ) {
        Text(
            text =
                "Entity ID should be the Composio user id, usually default. " +
                    "YouTube belongs in the app/tool call.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
}


/**
 * Shared folder plugin configuration.
 *
 * Displays the currently selected shared folder URI/path. The file picker
 * is launched from the plugin detail screen when clicking the plugin.
 *
 * @param settings Current application settings.
 * @param viewModel The [SettingsViewModel] for persisting changes.
 * @param onSelectFolder Callback to launch the folder picker (optional).
 */
@Composable
private fun SharedFolderConfig(
    settings: AppSettings,
    @Suppress("UNUSED_PARAMETER") viewModel: SettingsViewModel,
    onSelectFolder: (() -> Unit)? = null,
) {
    FolderAccessConfig(
        enabled = settings.sharedFolderEnabled,
        folderUri = settings.sharedFolderUri,
        introText =
            "Select a folder on your device to grant the AI agent access to read, write, " +
                "and manage files within it.",
        defaultFolderName = null,
        selectedFolderLabel = "Selected folder",
        selectButtonText = "Select Folder",
        changeButtonText = "Change Folder",
        selectedHelpText = "Tap the button above to change the selected folder",
        emptyEnabledText =
            if (onSelectFolder != null) {
                "No folder selected. Tap the button above to select a folder to share."
            } else {
                "No folder selected. Please click the plugin to select a folder with which to share."
            },
        disabledText = "Enable the plugin to select a folder to share with the AI agent.",
        onSelectFolder = onSelectFolder,
    )
}

@Composable
private fun WorkflowFolderConfig(
    settings: AppSettings,
    viewModel: SettingsViewModel,
    onSelectFolder: (() -> Unit)? = null,
) {
    FolderAccessConfig(
        enabled = settings.workflowFolderEnabled,
        folderUri = settings.workflowFolderUri,
        introText =
            "Workflow Folder is available by default in app storage. Select a custom " +
                "folder only if you want workflow files in a device folder.",
        defaultFolderName = "Default app Workflow Folder",
        selectedFolderLabel = "Workflow folder",
        selectButtonText = "Select Custom Folder",
        changeButtonText = "Change Workflow Folder",
        selectedHelpText = "Tap the button above to use a different workflow folder",
        emptyEnabledText = "Using the app's default Workflow Folder.",
        disabledText = "Enable the plugin to use the default Workflow Folder.",
        onSelectFolder = onSelectFolder,
        onUseDefaultFolder = { viewModel.setWorkflowFolderUri("") },
    )
}

@Composable
private fun FolderAccessConfig(
    enabled: Boolean,
    folderUri: String,
    introText: String,
    defaultFolderName: String?,
    selectedFolderLabel: String,
    selectButtonText: String,
    changeButtonText: String,
    selectedHelpText: String,
    emptyEnabledText: String,
    disabledText: String,
    onSelectFolder: (() -> Unit)? = null,
    onUseDefaultFolder: (() -> Unit)? = null,
) {
    Column {
        Text(
            text = introText,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        if (folderUri.isNotEmpty() || (enabled && defaultFolderName != null)) {
            OutlinedTextField(
                colors = zeroAssistOutlinedTextFieldColors(),
                value =
                    if (folderUri.isNotEmpty()) {
                        try {
                            android.net.Uri.decode(folderUri).substringAfterLast('/')
                        } catch (e: Exception) {
                            folderUri
                        }
                    } else {
                        defaultFolderName.orEmpty()
                    },
                onValueChange = {},
                label = { Text(selectedFolderLabel) },
                readOnly = true,
                singleLine = true,
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(12.dp))

            if (onSelectFolder != null) {
                FilledTonalButton(
                    colors = zeroAssistSecondaryActionButtonColors(),
                    onClick = onSelectFolder,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (folderUri.isNotEmpty()) changeButtonText else selectButtonText)
                }
            }
            if (folderUri.isNotEmpty() && onUseDefaultFolder != null) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = onUseDefaultFolder,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Use Default Folder")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = if (folderUri.isNotEmpty()) selectedHelpText else emptyEnabledText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else if (enabled) {
            if (onSelectFolder != null) {
                FilledTonalButton(
                    colors = zeroAssistSecondaryActionButtonColors(),
                    onClick = onSelectFolder,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(selectButtonText)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = emptyEnabledText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp),
            )
        } else {
            Text(
                text = disabledText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun LinuxSandboxConfig() {
    val context = LocalContext.current
    val app = context.applicationContext as ZeroClawApplication
    val sandboxState by app.linuxSandboxManager.state.collectAsStateWithLifecycle()

    Column {
        when (val state = sandboxState) {
            is SandboxState.NotInstalled -> {
                Text("Linux Sandbox is not installed.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { app.linuxSandboxManager.setup() }) {
                    Text("Install Sandbox")
                }
            }

            is SandboxState.Downloading -> {
                Text("Downloading Alpine Linux rootfs...", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            is SandboxState.Extracting -> {
                Text("Extracting rootfs...", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            is SandboxState.Installing -> {
                Text("Configuring: ${state.detail}", style = MaterialTheme.typography.bodyMedium)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            is SandboxState.Ready -> {
                Text("Sandbox is ready.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(8.dp))
                val mb = remember(state) { app.linuxSandboxManager.getDiskUsageMB() }
                Text("Disk usage: $mb MB", style = MaterialTheme.typography.bodySmall)
                val packagesOk = remember(state) { app.linuxSandboxManager.arePackagesInstalled() }
                Text(
                    if (packagesOk) "Packages: installed" else "Packages: not installed",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                Row {
                    if (!packagesOk) {
                        Button(onClick = { app.linuxSandboxManager.installPackages() }) {
                            Text("Install Packages")
                        }
                        Spacer(Modifier.width(8.dp))
                    }
                    OutlinedButton(
                        onClick = { app.linuxSandboxManager.reset() },
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = MaterialTheme.colorScheme.error,
                        ),
                    ) {
                        Text("Reset Sandbox")
                    }
                }
            }

            is SandboxState.Error -> {
                Text("Error: ${state.message}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { app.linuxSandboxManager.setup() }) {
                    Text("Retry Install")
                }
            }
        }
    }
}

/**
 * Google Workspace plugin configuration.
 *
 * Shows sign-in status, per-service toggles for all 13 GWS services,
 * audit logging toggle, and a test connection button. Reads from
 * [AppSettings] and persists via [SettingsViewModel].
 */
@Composable
private fun GoogleWorkspaceConfig(
    settings: AppSettings,
    viewModel: SettingsViewModel,
    isSignedIn: Boolean,
    accountEmail: String?,
    onSignIn: (() -> Unit)? = null,
    onSignOut: (() -> Unit)? = null,
) {
    val allowedServices = remember(settings.googleWorkspaceAllowedServices) {
        settings.googleWorkspaceAllowedServices
            .split(",")
            .map { it.trim() }
            .filter { it in ALL_GWS_SERVICES }
            .ifEmpty { ALL_GWS_SERVICES }
            .toSet()
    }

    Column {
        // Sign-in status
        if (isSignedIn) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "Connected",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (accountEmail != null) {
                            Text(
                                text = accountEmail,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    if (onSignOut != null) {
                        OutlinedButton(onClick = onSignOut) {
                            Text("Sign Out")
                        }
                    }
                }
            }
        } else {
            if (onSignIn != null) {
                FilledTonalButton(
                    onClick = onSignIn,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Sign in with Google")
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = "Required to access Gmail, Drive, Calendar, and other Workspace services",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Enable toggle
        SettingsToggleRow(
            title = "Enable Google Workspace",
            subtitle = "Allow agents to use Gmail, Drive, Calendar, and more",
            checked = settings.googleWorkspaceEnabled,
            onCheckedChange = { viewModel.updateGoogleWorkspaceEnabled(it) },
            contentDescription = "Enable Google Workspace",
        )

        Spacer(Modifier.height(16.dp))

        // Service toggles
        Text(
            text = "Allowed Services",
            style = MaterialTheme.typography.titleSmall,
        )
        Spacer(Modifier.height(8.dp))

        ALL_GWS_SERVICES.forEach { service ->
            val label = GWS_SERVICE_LABELS[service] ?: service
            val isChecked = service in allowedServices
            SettingsToggleRow(
                title = label,
                subtitle = service,
                checked = isChecked,
                onCheckedChange = {
                    val newServices = if (isChecked) {
                        allowedServices - service
                    } else {
                        allowedServices + service
                    }
                    viewModel.updateGoogleWorkspaceAllowedServices(
                        newServices.sorted().joinToString(","),
                    )
                },
                contentDescription = "Toggle $label",
            )
        }

        Spacer(Modifier.height(16.dp))

        // Audit log
        SettingsToggleRow(
            title = "Audit Logging",
            subtitle = "Log all Google Workspace API calls",
            checked = settings.googleWorkspaceAuditLog,
            onCheckedChange = { viewModel.updateGoogleWorkspaceAuditLog(it) },
            contentDescription = "Toggle audit logging",
        )
    }
}
