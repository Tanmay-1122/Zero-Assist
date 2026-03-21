/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.agents

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.data.DroidRunProviderCatalog
import com.zeroclaw.android.data.ProviderRegistry
import com.zeroclaw.android.data.remote.ModelFetcher
import com.zeroclaw.android.model.ApiKey
import com.zeroclaw.android.model.ModelListFormat
import com.zeroclaw.android.model.ProviderAuthType
import com.zeroclaw.android.ui.component.CollapsibleSection
import com.zeroclaw.android.ui.component.ConnectionPickerSection
import com.zeroclaw.android.ui.component.ModelSuggestionField

private const val DROIDRUN_FIELD_SPACING_DP = 12
private val RECOMMENDATION_CARD_TOP_PADDING = 4.dp

/**
 * DroidRun override editor reused by both add and detail screens.
 *
 * @param enabled Whether the per-agent DroidRun override is enabled.
 * @param onEnabledChange Callback when the enable toggle changes.
 * @param keys All stored API-key connections.
 * @param selectedConnectionId Currently selected DroidRun connection ID.
 * @param onConnectionSelected Callback when a DroidRun connection is selected.
 * @param onAddNewConnection Callback to add a new connection.
 * @param providerId Selected DroidRun provider.
 * @param modelName Selected DroidRun model.
 * @param onModelChanged Callback when the DroidRun model changes.
 * @param modifier Modifier applied to the root section.
 */
@Composable
fun AgentDroidRunSection(
    enabled: Boolean,
    onEnabledChange: (Boolean) -> Unit,
    keys: List<ApiKey>,
    selectedConnectionId: String?,
    onConnectionSelected: (ApiKey) -> Unit,
    onAddNewConnection: () -> Unit,
    providerId: String,
    modelName: String,
    onModelChanged: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val supportedKeys =
        remember(keys) { keys.filter { DroidRunProviderCatalog.isSupported(it.provider) } }
    val selectedKey = supportedKeys.firstOrNull { it.id == selectedConnectionId }
    val resolvedProviderId =
        providerId.ifBlank {
            selectedKey?.let { ProviderRegistry.findById(it.provider)?.id ?: it.provider }.orEmpty()
        }
    val providerInfo = ProviderRegistry.findById(resolvedProviderId)

    var liveModels by remember { mutableStateOf(emptyList<String>()) }
    var isLoadingLive by remember { mutableStateOf(false) }
    var isLiveData by remember { mutableStateOf(false) }
    var modelFetchError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(enabled, resolvedProviderId, selectedConnectionId, supportedKeys) {
        liveModels = emptyList()
        isLiveData = false
        modelFetchError = null
        if (!enabled) return@LaunchedEffect

        val info = ProviderRegistry.findById(resolvedProviderId) ?: return@LaunchedEffect
        if (info.modelListFormat == ModelListFormat.NONE) return@LaunchedEffect

        val key = supportedKeys.firstOrNull { it.id == selectedConnectionId }
        val apiKeyValue = key?.key.orEmpty()
        val baseUrlValue = key?.baseUrl.orEmpty()
        val isLocal =
            info.authType == ProviderAuthType.URL_ONLY ||
                info.authType == ProviderAuthType.URL_AND_OPTIONAL_KEY
        if (!isLocal && apiKeyValue.isBlank()) return@LaunchedEffect

        isLoadingLive = true
        val result = ModelFetcher.fetchModels(info, apiKeyValue, baseUrlValue)
        isLoadingLive = false
        result
            .onSuccess { models ->
                liveModels = models
                isLiveData = true
            }.onFailure { error ->
                modelFetchError = error.message ?: "Failed to fetch models"
            }
    }

    CollapsibleSection(
        title = "DroidRun Phone Tasks",
        modifier = modifier,
        initiallyExpanded = enabled,
    ) {
        Column(
            modifier = Modifier.animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(DROIDRUN_FIELD_SPACING_DP.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Use a dedicated model for phone actions",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Text(
                        text = "This agent can route DroidRun tasks through a separate provider, model, and API key without changing its main chat model.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onEnabledChange,
                )
            }

            AnimatedVisibility(visible = enabled) {
                Column(verticalArrangement = Arrangement.spacedBy(DROIDRUN_FIELD_SPACING_DP.dp)) {
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(
                                text = "Recommended low-cost starters",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            DroidRunProviderCatalog.recommendations.forEach { recommendation ->
                                val displayName =
                                    ProviderRegistry.findById(recommendation.providerId)?.displayName
                                        ?: recommendation.providerId
                                Text(
                                    text = "$displayName: ${recommendation.models}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = "${recommendation.limits} • ${recommendation.reason}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(top = RECOMMENDATION_CARD_TOP_PADDING),
                                )
                            }
                        }
                    }

                    ConnectionPickerSection(
                        keys = supportedKeys,
                        selectedKeyId = selectedConnectionId,
                        onKeySelected = onConnectionSelected,
                        onAddNewConnection = onAddNewConnection,
                        title = "DroidRun Connection",
                        emptyMessage = "Add a supported provider connection such as Gemini, Groq, OpenRouter, Mistral, DeepSeek, or Hugging Face.",
                        addButtonLabel = "Add DroidRun Connection",
                    )

                    ModelSuggestionField(
                        value = modelName,
                        onValueChanged = onModelChanged,
                        suggestions = providerInfo?.suggestedModels.orEmpty(),
                        liveSuggestions = liveModels,
                        isLoadingLive = isLoadingLive,
                        isLiveData = isLiveData,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    modelFetchError?.let { error ->
                        Text(
                            text = "Could not fetch DroidRun models: $error",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                    val connectionLabel =
                        selectedKey?.let { key ->
                            ProviderRegistry.findById(key.provider)?.displayName ?: key.provider
                        } ?: "No connection selected"
                    val providerLabel = providerInfo?.displayName ?: "Choose a provider connection"
                    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Current DroidRun route",
                                    style = MaterialTheme.typography.titleSmall,
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = connectionLabel,
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    text = if (modelName.isBlank()) providerLabel else "$providerLabel • $modelName",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "DroidRun",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        }
    }
}
