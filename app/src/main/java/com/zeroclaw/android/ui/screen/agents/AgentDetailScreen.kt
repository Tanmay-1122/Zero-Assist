/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zeroclaw.android.data.ProviderRegistry
import com.zeroclaw.android.data.remote.ModelFetcher
import com.zeroclaw.android.model.Agent
import com.zeroclaw.android.model.AgentLiveState
import com.zeroclaw.android.model.AgentStatus
import com.zeroclaw.android.model.AgentTemplates
import com.zeroclaw.android.model.ModelListFormat
import com.zeroclaw.android.model.ProviderAuthType
import com.zeroclaw.android.model.ThinkingLevel
import com.zeroclaw.android.ui.component.CollapsibleSection
import com.zeroclaw.android.ui.component.ConnectionPickerSection
import com.zeroclaw.android.ui.component.ON_DEVICE_KEY_ID
import com.zeroclaw.android.ui.component.ModelSuggestionField
import com.zeroclaw.android.ui.component.RestartRequiredBanner

/** Spacing between form fields. */
private const val FIELD_SPACING_DP = 12

/** Standard section spacing. */
private const val SECTION_SPACING_DP = 16

/** Spacing after heading. */
private const val HEADING_SPACING_DP = 16

/** Bottom section spacing. */
private const val BOTTOM_SPACING_DP = 24

/** Small vertical spacing. */
private const val SMALL_SPACING_DP = 8

/** Channel item spacing. */
private const val CHANNEL_SPACING_DP = 4

/** Maximum slider value for per-agent temperature. */
private const val DETAIL_TEMPERATURE_MAX = 2.0f

/** Number of slider steps for temperature. */
private const val DETAIL_TEMPERATURE_STEPS = 20

/** Default temperature value when no per-agent temperature is set. */
private const val DEFAULT_DETAIL_TEMPERATURE = 0.7f

/**
 * Agent detail screen with editable fields and collapsible sections.
 *
 * Uses [ConnectionPickerSection] for connection selection and
 * [ModelSuggestionField] with live model fetching for model entry.
 *
 * @param agentId Unique identifier of the agent to display.
 * @param onSaved Callback invoked after saving changes.
 * @param onDeleted Callback invoked after deleting the agent.
 * @param onNavigateToAddConnection Callback to navigate to the API key add screen.
 * @param edgeMargin Horizontal padding based on window width size class.
 * @param restartRequired Whether the daemon needs a restart to apply changes.
 * @param onRestartDaemon Callback invoked when the user taps the restart button.
 * @param detailViewModel The [AgentDetailViewModel] for agent state.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
fun AgentDetailScreen(
    agentId: String,
    onSaved: () -> Unit,
    onDeleted: () -> Unit,
    onOpenInGroupChat: (String) -> Unit = {},
    onNavigateToAddConnection: () -> Unit,
    edgeMargin: Dp,
    restartRequired: Boolean = false,
    onRestartDaemon: () -> Unit = {},
    detailViewModel: AgentDetailViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(agentId) {
        detailViewModel.loadAgent(agentId)
    }

    val agent by detailViewModel.agent.collectAsStateWithLifecycle()
    val apiKeys by detailViewModel.apiKeys.collectAsStateWithLifecycle()
    val liveStates by detailViewModel.liveStates.collectAsStateWithLifecycle()
    val onDeviceEngineState by detailViewModel.onDeviceEngineState.collectAsStateWithLifecycle()
    val loadedAgent = agent ?: return
    val liveState = liveStates[loadedAgent.id]
    val template = remember(loadedAgent.templateId) { loadedAgent.templateId?.let(AgentTemplates::findById) }

    var name by remember(loadedAgent) { mutableStateOf(loadedAgent.name) }
    var providerId by remember(loadedAgent) { mutableStateOf(loadedAgent.provider) }
    var modelName by remember(loadedAgent) { mutableStateOf(loadedAgent.modelName) }
    var systemPrompt by remember(loadedAgent) { mutableStateOf(loadedAgent.systemPrompt) }
    var useGlobalTemperature by remember(loadedAgent) {
        mutableStateOf(loadedAgent.temperature == null)
    }
    var temperature by remember(loadedAgent) {
        mutableStateOf(loadedAgent.temperature ?: DEFAULT_DETAIL_TEMPERATURE)
    }
    var maxDepth by remember(loadedAgent) {
        mutableStateOf(loadedAgent.maxDepth.toString())
    }
    var thinkingLevel by remember(loadedAgent) {
        mutableStateOf<ThinkingLevel>(loadedAgent.thinkingLevel)
    }

    val initialConnectionId by remember(loadedAgent, apiKeys) {
        derivedStateOf {
            val agentProvider = ProviderRegistry.findById(loadedAgent.provider)?.id
            apiKeys
                .firstOrNull { key ->
                    val keyProvider = ProviderRegistry.findById(key.provider)?.id
                    keyProvider == agentProvider
                }?.id
        }
    }
    var selectedConnectionId by remember(initialConnectionId) {
        mutableStateOf(initialConnectionId)
    }

    val providerInfo = ProviderRegistry.findById(providerId)
    val suggestedModels = if (providerId == "on-device") {
        detailViewModel.downloadedOnDeviceModels
    } else {
        providerInfo?.suggestedModels.orEmpty()
    }

    var showDeleteConfirmation by remember { mutableStateOf(false) }

    var liveModels by remember { mutableStateOf(emptyList<String>()) }
    var isLoadingLive by remember { mutableStateOf(false) }
    var isLiveData by remember { mutableStateOf(false) }
    val maxDepthValue = maxDepth.toIntOrNull()
    val maxDepthError = maxDepth.isNotEmpty() && (maxDepthValue == null || maxDepthValue < 1)

    val selectedKey = apiKeys.firstOrNull { it.id == selectedConnectionId }

    LaunchedEffect(providerId, selectedConnectionId, apiKeys) {
        liveModels = emptyList()
        isLiveData = false
        val info = ProviderRegistry.findById(providerId) ?: return@LaunchedEffect
        if (info.modelListFormat == ModelListFormat.NONE) return@LaunchedEffect
        val key = selectedKey
        val apiKeyValue = key?.key.orEmpty()
        val baseUrlValue = key?.baseUrl.orEmpty()
        val isLocal =
            info.authType == ProviderAuthType.URL_ONLY ||
                info.authType == ProviderAuthType.URL_AND_OPTIONAL_KEY
        if (!isLocal && apiKeyValue.isBlank()) return@LaunchedEffect
        isLoadingLive = true
        val result = ModelFetcher.fetchModels(info, apiKeyValue, baseUrlValue)
        isLoadingLive = false
        result.onSuccess { models ->
            liveModels = models
            isLiveData = true
        }
    }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = edgeMargin)
                .verticalScroll(rememberScrollState()),
    ) {
        Spacer(modifier = Modifier.height(HEADING_SPACING_DP.dp))

        Text(
            text = "Agent Details",
            style = MaterialTheme.typography.headlineSmall,
        )
        Spacer(modifier = Modifier.height(HEADING_SPACING_DP.dp))

        AgentMetadataCard(
            agent = loadedAgent,
            liveState = liveState,
            templateName = template?.name ?: "Custom",
            onOpenInGroupChat = { onOpenInGroupChat(loadedAgent.id) },
        )
        Spacer(modifier = Modifier.height(FIELD_SPACING_DP.dp))

        if (restartRequired) {
            RestartRequiredBanner(
                edgeMargin = edgeMargin,
                onRestartDaemon = onRestartDaemon,
            )
            Spacer(modifier = Modifier.height(FIELD_SPACING_DP.dp))
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Nickname") },
            supportingText = { Text("Display name only \u2014 does not change the daemon identity") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(FIELD_SPACING_DP.dp))

        ConnectionPickerSection(
            keys = apiKeys,
            selectedKeyId = selectedConnectionId,
            onKeySelected = { key ->
                selectedConnectionId = key.id
                val resolved = ProviderRegistry.findById(key.provider)
                providerId = resolved?.id ?: key.provider
            },
            onAddNewConnection = onNavigateToAddConnection,
            onDeviceEngineState = onDeviceEngineState,
            onOnDeviceSelected = {
                selectedConnectionId = ON_DEVICE_KEY_ID
                providerId = "on-device"
                modelName = ProviderRegistry.findById("on-device")?.suggestedModels?.firstOrNull().orEmpty()
            },
        )
        Spacer(modifier = Modifier.height(FIELD_SPACING_DP.dp))

        ModelSuggestionField(
            value = modelName,
            onValueChanged = { modelName = it },
            suggestions = suggestedModels,
            liveSuggestions = liveModels,
            isLoadingLive = isLoadingLive,
            isLiveData = isLiveData,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(SECTION_SPACING_DP.dp))

        CollapsibleSection(title = "System Prompt") {
            OutlinedTextField(
                value = systemPrompt,
                onValueChange = { systemPrompt = it },
                label = { Text("System prompt") },
                minLines = 3,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(modifier = Modifier.height(SECTION_SPACING_DP.dp))

        CollapsibleSection(title = "Advanced") {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Checkbox(
                    checked = useGlobalTemperature,
                    onCheckedChange = { useGlobalTemperature = it },
                )
                Text(
                    text = "Use global default temperature",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (!useGlobalTemperature) {
                Text(
                    text = "Temperature: ${"%.1f".format(temperature)}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Slider(
                    value = temperature,
                    onValueChange = { temperature = it },
                    valueRange = 0f..DETAIL_TEMPERATURE_MAX,
                    steps = DETAIL_TEMPERATURE_STEPS - 1,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .semantics { contentDescription = "Temperature" },
                )
            }
            Spacer(modifier = Modifier.height(FIELD_SPACING_DP.dp))
            OutlinedTextField(
                value = maxDepth,
                onValueChange = { maxDepth = it },
                label = { Text("Max depth") },
                singleLine = true,
                isError = maxDepthError,
                supportingText =
                    if (maxDepthError) {
                        { Text("Must be a positive integer") }
                    } else {
                        null
                    },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(FIELD_SPACING_DP.dp))
            ThinkingLevelSelector(
                selectedLevel = thinkingLevel,
                onLevelSelected = { thinkingLevel = it },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(modifier = Modifier.height(SECTION_SPACING_DP.dp))

        CollapsibleSection(title = "Channels") {
            if (loadedAgent.channels.isEmpty()) {
                Text(
                    text = "No channels configured.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                loadedAgent.channels.forEach { channel ->
                    Text(
                        text = "${channel.type}: ${channel.endpoint}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(modifier = Modifier.height(CHANNEL_SPACING_DP.dp))
                }
            }
        }
        Spacer(modifier = Modifier.height(BOTTOM_SPACING_DP.dp))

        FilledTonalButton(
            onClick = {
                detailViewModel.saveAgent(
                    loadedAgent.copy(
                        name = name,
                        provider = providerId,
                        modelName = modelName,
                        systemPrompt = systemPrompt,
                        temperature = if (useGlobalTemperature) null else temperature,
                        maxDepth = maxDepth.toIntOrNull() ?: Agent.DEFAULT_MAX_DEPTH,
                        thinkingLevel = thinkingLevel,
                    ),
                )
                onSaved()
            },
            enabled = !maxDepthError,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Save Changes")
        }
        Spacer(modifier = Modifier.height(SMALL_SPACING_DP.dp))
        OutlinedButton(
            onClick = { showDeleteConfirmation = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = "Delete Agent",
                color = MaterialTheme.colorScheme.error,
            )
        }
        Spacer(modifier = Modifier.height(BOTTOM_SPACING_DP.dp))
    }

    if (showDeleteConfirmation) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmation = false },
            title = { Text("Delete Agent") },
            text = { Text("Are you sure you want to delete \"$name\"? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteConfirmation = false
                        detailViewModel.deleteAgent(agentId)
                        onDeleted()
                    },
                ) {
                    Text(
                        text = "Delete",
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmation = false }) {
                    Text("Cancel")
                }
            },
        )
    }
}

@Composable
private fun AgentMetadataCard(
    agent: Agent,
    liveState: AgentLiveState?,
    templateName: String,
    onOpenInGroupChat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accentColor = Color(agent.accentColor)
    val status = liveState?.status ?: AgentStatus.IDLE
    val statusTask =
        liveState?.currentTask
            ?.takeIf { it.isNotBlank() }
            ?: if (status == AgentStatus.IDLE) "Available" else agentStatusLabel(status)

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = accentColor.copy(alpha = 0.08f),
        border = androidx.compose.foundation.BorderStroke(1.dp, accentColor.copy(alpha = 0.22f)),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RoleBadge(agent = agent)
                TemplateBadge(templateName = templateName, accentColor = accentColor)
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AgentStatusDot(status = status)
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = agentStatusLabel(status),
                        style = MaterialTheme.typography.titleSmall,
                        color = agentStatusColor(status),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = statusTask,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedButton(
                onClick = onOpenInGroupChat,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Open in Group Chat")
            }
        }
    }
}

@Composable
private fun RoleBadge(
    agent: Agent,
    modifier: Modifier = Modifier,
) {
    val accentColor = Color(agent.accentColor)
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = accentColor.copy(alpha = 0.14f),
        modifier = modifier.border(
            width = 1.dp,
            color = accentColor.copy(alpha = 0.3f),
            shape = RoundedCornerShape(999.dp),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(text = agent.role.icon)
            Text(
                text = agent.role.displayName,
                color = accentColor,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun TemplateBadge(
    templateName: String,
    accentColor: Color,
    modifier: Modifier = Modifier,
) {
    Surface(
        shape = RoundedCornerShape(999.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
        modifier = modifier.border(
            width = 1.dp,
            color = accentColor.copy(alpha = 0.2f),
            shape = RoundedCornerShape(999.dp),
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Template",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = templateName,
                style = MaterialTheme.typography.labelMedium,
                color = accentColor,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
