/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.plugins

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zeroclaw.android.model.OfficialPlugins
import com.zeroclaw.android.ui.component.CategoryBadge
import com.zeroclaw.android.ui.component.CollapsibleSection
import com.zeroclaw.android.ui.component.LoadingIndicator
import com.zeroclaw.android.ui.component.OfficialPluginBadge
import com.zeroclaw.android.ui.screen.settings.SettingsViewModel
import com.zeroclaw.android.ui.theme.zeroAssistOutlinedTextFieldColors
import com.zeroclaw.android.ui.theme.zeroAssistSecondaryActionButtonColors
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
/**
 * Plugin detail screen showing full information, install/enable controls,
 * and configuration fields.
 *
 * Official plugins render [OfficialPluginConfigSection] instead of the
 * generic key-value editor, and the uninstall button is hidden.
 *
 * @param pluginId Unique identifier of the plugin to display.
 * @param onBack Callback to navigate back.
 * @param edgeMargin Horizontal padding based on window width size class.
 * @param detailViewModel The [PluginDetailViewModel] for plugin state.
 * @param settingsViewModel The [SettingsViewModel] for official plugin config.
 * @param modifier Modifier applied to the root layout.
 */
@OptIn(FlowPreview::class)
@Composable
fun PluginDetailScreen(
    pluginId: String,
    onBack: () -> Unit,
    edgeMargin: Dp,
    detailViewModel: PluginDetailViewModel = viewModel(),
    settingsViewModel: SettingsViewModel = viewModel(),
    pluginsViewModel: PluginsViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(pluginId) {
        detailViewModel.loadPlugin(pluginId)
    }

    LaunchedEffect(Unit) {
        detailViewModel.navigateBack.collect { onBack() }
    }

    val plugin by detailViewModel.plugin.collectAsStateWithLifecycle()
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val gwsNeedsSignIn by pluginsViewModel.gwsNeedsSignIn.collectAsStateWithLifecycle()
    val gwsIsSignedIn = plugin?.id == OfficialPlugins.GOOGLE_WORKSPACE && !gwsNeedsSignIn
    val gwsAccountEmail = if (gwsIsSignedIn) pluginsViewModel.gwsAuthManager.getAccountEmail() else null
    val loadedPlugin = plugin
    val isOfficial = loadedPlugin?.isOfficial == true
    val context = LocalContext.current
    var folderPickerTarget by remember { mutableStateOf<FolderPickerTarget?>(null) }

    val folderPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        if (uri != null) {
            // Persist the permission
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Failed to persist URI permission", e)
            }

            // Store the URI path
            val path = uri.toString()
            when (folderPickerTarget) {
                FolderPickerTarget.SHARED -> settingsViewModel.setSharedFolderUri(path)
                FolderPickerTarget.WORKFLOW -> settingsViewModel.setWorkflowFolderUri(path)
                null -> settingsViewModel.setSharedFolderUri(path)
            }
        }
        folderPickerTarget = null
    }

    val gwsSignInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        pluginsViewModel.handleGwsSignInResult(result.data)
    }

    val onGwsSignIn = remember {
        { gwsSignInLauncher.launch(pluginsViewModel.getGwsSignInIntent()) }
    }
    val onGwsSignOut = remember {
        { pluginsViewModel.gwsSignOut() }
    }

    if (loadedPlugin == null) {
        Box(
            modifier = modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            LoadingIndicator()
        }
        return
    }

    val onSelectSharedFolder = remember {
        {
            folderPickerTarget = FolderPickerTarget.SHARED
            folderPickerLauncher.launch(null)
        }
    }
    val onSelectWorkflowFolder = remember {
        {
            folderPickerTarget = FolderPickerTarget.WORKFLOW
            folderPickerLauncher.launch(null)
        }
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = edgeMargin)
                .verticalScroll(rememberScrollState()),
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = loadedPlugin.name,
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryBadge(category = loadedPlugin.category)
            if (isOfficial) {
                Spacer(modifier = Modifier.width(8.dp))
                OfficialPluginBadge()
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "v${loadedPlugin.version} by ${loadedPlugin.author}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = loadedPlugin.description,
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(modifier = Modifier.height(24.dp))

        if (loadedPlugin.isInstalled) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Enabled",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                )
                Switch(
                    checked = loadedPlugin.isEnabled,
                    onCheckedChange = { enabled ->
                        if (isOfficial) {
                            settingsViewModel.updateOfficialPluginEnabled(
                                pluginId,
                                enabled,
                            )
                            detailViewModel.setEnabled(pluginId, enabled)
                        } else if (loadedPlugin.isEnabled != enabled) {
                            detailViewModel.toggleEnabled(pluginId)
                        }
                    },
                    modifier =
                        Modifier.semantics {
                            contentDescription =
                                "${loadedPlugin.name} " +
                                if (loadedPlugin.isEnabled) "enabled" else "disabled"
                        },
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        if ((loadedPlugin.isInstalled && isOfficial) || pluginId == OfficialPlugins.LINUX_SANDBOX || pluginId == OfficialPlugins.TERMUX) {
            CollapsibleSection(
                title = "Configuration",
                initiallyExpanded = true,
            ) {
                OfficialPluginConfigSection(
                    officialPluginId = pluginId,
                    settings = settings,
                    viewModel = settingsViewModel,
                    onSelectSharedFolder = if (pluginId == OfficialPlugins.SHARED_FOLDER) onSelectSharedFolder else null,
                    onSelectWorkflowFolder = if (pluginId == OfficialPlugins.WORKFLOW_FOLDER) onSelectWorkflowFolder else null,
                    onGwsSignIn = if (pluginId == OfficialPlugins.GOOGLE_WORKSPACE) onGwsSignIn else null,
                    onGwsSignOut = if (pluginId == OfficialPlugins.GOOGLE_WORKSPACE) onGwsSignOut else null,
                    gwsIsSignedIn = gwsIsSignedIn,
                    gwsAccountEmail = gwsAccountEmail,
                )
            }
            Spacer(modifier = Modifier.height(16.dp))
        } else if (loadedPlugin.isInstalled && loadedPlugin.configFields.isNotEmpty()) {
            CollapsibleSection(
                title = "Configuration",
                initiallyExpanded = true,
            ) {
                loadedPlugin.configFields.forEach { (key, value) ->
                    var fieldValue by remember(key, value) { mutableStateOf(value) }
                    val currentValue by rememberUpdatedState(fieldValue)

                    LaunchedEffect(key) {
                        snapshotFlow { currentValue }
                            .drop(1)
                            .debounce(CONFIG_DEBOUNCE_MS)
                            .collect { detailViewModel.updateConfig(pluginId, key, it) }
                    }

                    OutlinedTextField(
        colors = zeroAssistOutlinedTextFieldColors(),
                        value = fieldValue,
                        onValueChange = { fieldValue = it },
                        label = { Text(key) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
            Spacer(modifier = Modifier.height(16.dp))
        }

        if (loadedPlugin.isInstalled && isOfficial) {
            Text(
                text = "Official plugin \u2014 cannot be uninstalled",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else if (loadedPlugin.isInstalled) {
            OutlinedButton(
                onClick = { detailViewModel.uninstall(pluginId) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "Uninstall",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        } else {
            FilledTonalButton(
                    colors = zeroAssistSecondaryActionButtonColors(),
                onClick = { detailViewModel.install(pluginId) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Install Plugin")
            }
        }
        Spacer(modifier = Modifier.height(24.dp))
    }
}

/** Delay before writing config changes to the database after typing stops. */
private const val CONFIG_DEBOUNCE_MS = 500L
private const val TAG = "PluginDetailScreen"

private enum class FolderPickerTarget {
    SHARED,
    WORKFLOW,
}

