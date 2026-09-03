/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.agents

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zeroclaw.android.data.ProviderRegistry
import com.zeroclaw.android.data.remote.ModelFetcher
import com.zeroclaw.android.model.Agent
import com.zeroclaw.android.model.AgentRole
import com.zeroclaw.android.model.AgentTemplate
import com.zeroclaw.android.model.AgentTemplates
import com.zeroclaw.android.model.ModelListFormat
import com.zeroclaw.android.model.ProviderAuthType
import com.zeroclaw.android.model.ThinkingLevel
import com.zeroclaw.android.ui.component.CollapsibleSection
import com.zeroclaw.android.ui.component.ConnectionPickerSection
import com.zeroclaw.android.ui.component.ON_DEVICE_KEY_ID
import com.zeroclaw.android.ui.component.ModelSuggestionField
import java.util.UUID

/** Spacing between form fields. */
private const val FIELD_SPACING_DP = 12

/** Standard section spacing. */
private const val SECTION_SPACING_DP = 24

/** Maximum slider value for per-agent temperature. */
private const val AGENT_TEMPERATURE_MAX = 2.0f

/** Number of slider steps for temperature. */
private const val AGENT_TEMPERATURE_STEPS = 20

/** Default temperature value for new agents. */
private const val DEFAULT_AGENT_TEMPERATURE = 0.7f

/** Top padding for the model fetch error text (4dp). */
private val FETCH_ERROR_TOP_PADDING = 4.dp

/** Animation duration for screen transitions. */
private const val ANIMATION_DURATION_MS = 300

/**
 * Screen for adding a new agent with a two-stage flow:
 * 1. Template Picker - Full screen grid of template cards
 * 2. Agent Config Form - Pre-filled from selected template
 *
 * @param onSaved Callback invoked after the agent is created.
 * @param onNavigateToAddConnection Callback to navigate to the API key add screen.
 * @param edgeMargin Horizontal padding based on window width size class.
 * @param detailViewModel The [AgentDetailViewModel] for persisting the agent.
 * @param modifier Modifier applied to the root layout.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalAnimationApi::class)
@Composable
fun AddAgentScreen(
    onSaved: () -> Unit,
    onNavigateToAddConnection: () -> Unit,
    edgeMargin: Dp,
    detailViewModel: AgentDetailViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    var selectedTemplate by remember { mutableStateOf<AgentTemplate?>(null) }
    var isCustomAgent by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (selectedTemplate != null || isCustomAgent) "Configure Agent" else "Choose Template") },
                navigationIcon = {
                    if (selectedTemplate != null || isCustomAgent) {
                        IconButton(onClick = { 
                            selectedTemplate = null
                            isCustomAgent = false
                        }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back to templates")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onSurface,
                ),
            )
        },
        modifier = modifier,
    ) { paddingValues ->
        AnimatedContent(
            targetState = selectedTemplate != null || isCustomAgent,
            transitionSpec = {
                (
                    fadeIn(animationSpec = tween(ANIMATION_DURATION_MS)) +
                        slideInHorizontally(
                            animationSpec = tween(ANIMATION_DURATION_MS),
                            initialOffsetX = { fullWidth -> fullWidth / 3 },
                        )
                    ).togetherWith(
                    fadeOut(animationSpec = tween(ANIMATION_DURATION_MS)) +
                        slideOutHorizontally(
                            animationSpec = tween(ANIMATION_DURATION_MS),
                            targetOffsetX = { fullWidth -> -fullWidth / 6 },
                        )
                    ).using(
                    SizeTransform(clip = false)
                )
            },
            label = "Screen transition",
        ) { showConfigForm ->
            if (!showConfigForm) {
                // STAGE 1: Template Picker
                TemplatePickerScreen(
                    onTemplateSelected = { template ->
                        selectedTemplate = template
                    },
                    onCustomAgent = {
                        isCustomAgent = true
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(horizontal = edgeMargin)
                )
            } else {
                // STAGE 2: Agent Config Form
                AgentConfigForm(
                    template = selectedTemplate,
                    isCustom = isCustomAgent,
                    onSaved = onSaved,
                    onNavigateToAddConnection = onNavigateToAddConnection,
                    edgeMargin = edgeMargin,
                    detailViewModel = detailViewModel,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(horizontal = edgeMargin)
                )
            }
        }
    }
}

/**
 * Template Picker Screen - Full screen grid of agent template cards.
 *
 * @param onTemplateSelected Callback when a template is selected.
 * @param onCustomAgent Callback when user wants to create a custom agent.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
private fun TemplatePickerScreen(
    onTemplateSelected: (AgentTemplate) -> Unit,
    onCustomAgent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(vertical = 16.dp),
    ) {
        Text(
            text = "Choose an Agent Template",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp),
        )
        Text(
            text = "Select a template to get started with pre-configured settings, or create a custom agent from scratch.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 24.dp),
        )

        // Template Grid
        AgentTemplates.ALL.chunked(2).forEach { rowTemplates ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                rowTemplates.forEach { template ->
                    TemplateCard(
                        template = template,
                        onClick = { onTemplateSelected(template) },
                        modifier = Modifier.weight(1f),
                    )
                }
                // If row has only 1 item, add a spacer
                if (rowTemplates.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        // Custom Agent Button
        CustomAgentButton(
            onClick = onCustomAgent,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * Template Card - Displays a single agent template with attractive styling.
 *
 * @param template The agent template to display.
 * @param onClick Callback when the card is clicked.
 * @param modifier Modifier applied to the card.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TemplateCard(
    template: AgentTemplate,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = tween(durationMillis = 100),
        label = "Card scale",
    )

    Box(
        modifier = modifier,
    ) {
        Surface(
            onClick = {
                isPressed = true
                onClick()
            },
            modifier = Modifier
                .scale(scale)
                .border(
                    width = if (isPressed) 3.dp else 2.dp,
                    color = Color(template.accentColor).copy(alpha = if (isPressed) 1f else 0.5f),
                    shape = RoundedCornerShape(16.dp),
                ),
            shape = RoundedCornerShape(16.dp),
            tonalElevation = 4.dp,
            shadowElevation = if (isPressed) 2.dp else 4.dp,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            ) {
                // Avatar and Name Row
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 12.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(Color(template.accentColor).copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = template.avatar,
                            style = MaterialTheme.typography.headlineMedium,
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = template.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = template.role.displayName,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(template.accentColor),
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                // Description
                Text(
                    text = template.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    modifier = Modifier.padding(bottom = 12.dp),
                )

                // Tags
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    template.tags.forEach { tag ->
                        TagChip(
                            text = tag,
                            accentColor = template.accentColor,
                        )
                    }
                }

                // Quick Stats
                Row(
                    modifier = Modifier
                        .padding(top = 12.dp)
                        .fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    QuickStat(
                        label = "Temp",
                        value = "${"%.1f".format(template.defaultTemperature)}",
                    )
                    QuickStat(
                        label = "Max Depth",
                        value = template.defaultMaxDepth.toString(),
                    )
                }
            }
        }

        // Checkmark indicator (appears when pressed)
        if (isPressed) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(Color(template.accentColor)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Selected",
                    tint = Color.White,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/**
 * Custom Agent Button - Allows user to create an agent from scratch.
 *
 * @param onClick Callback when the button is clicked.
 * @param modifier Modifier applied to the button.
 */
@Composable
private fun CustomAgentButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.98f else 1f,
        animationSpec = tween(durationMillis = 100),
        label = "Button scale",
    )

    Surface(
        onClick = {
            isPressed = true
            onClick()
        },
        modifier = modifier
            .scale(scale)
            .height(80.dp)
            .border(
                width = 2.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f),
                shape = RoundedCornerShape(16.dp),
            ),
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = "Custom Agent",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "Start from scratch with default settings",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Tag Chip - Displays a single tag with the template's accent color.
 *
 * @param text The tag text to display.
 * @param accentColor The accent color for the tag.
 */
@Composable
private fun TagChip(
    text: String,
    accentColor: Long,
) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = Color(accentColor).copy(alpha = 0.15f),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = Color(accentColor),
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/**
 * Quick Stat - Displays a single statistic about the template.
 *
 * @param label The label for the statistic.
 * @param value The value of the statistic.
 */
@Composable
private fun QuickStat(
    label: String,
    value: String,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Agent Config Form - Form for configuring agent settings, pre-filled from template.
 *
 * @param template The selected template (null if custom agent).
 * @param isCustom Whether this is a custom agent.
 * @param onSaved Callback invoked after the agent is created.
 * @param onNavigateToAddConnection Callback to navigate to the API key add screen.
 * @param edgeMargin Horizontal padding based on window width size class.
 * @param detailViewModel The [AgentDetailViewModel] for persisting the agent.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
private fun AgentConfigForm(
    template: AgentTemplate?,
    isCustom: Boolean,
    onSaved: () -> Unit,
    onNavigateToAddConnection: () -> Unit,
    edgeMargin: Dp,
    detailViewModel: AgentDetailViewModel,
    modifier: Modifier = Modifier,
) {
    // Initialize form state from template or defaults
    var name by remember { mutableStateOf("") }
    var providerId by remember { mutableStateOf("") }
    var modelName by remember { mutableStateOf("") }
    var systemPrompt by remember { mutableStateOf("") }
    var useGlobalTemperature by remember { mutableStateOf(true) }
    var temperature by remember { mutableStateOf(DEFAULT_AGENT_TEMPERATURE) }
    var maxDepth by remember { mutableStateOf(Agent.DEFAULT_MAX_DEPTH.toString()) }
    var thinkingLevel by remember { mutableStateOf(ThinkingLevel.DEFAULT) }
    var tags by remember { mutableStateOf(template?.tags ?: emptyList()) }
    var selectedConnectionId by remember { mutableStateOf<String?>(null) }

    // Initialize from template when it changes
    LaunchedEffect(template, isCustom) {
        if (template != null) {
            name = template.name
            systemPrompt = template.defaultSystemPrompt
            temperature = template.defaultTemperature
            maxDepth = template.defaultMaxDepth.toString()
            thinkingLevel = ThinkingLevel.DEFAULT
            tags = template.tags
            useGlobalTemperature = false
        } else if (isCustom) {
            name = ""
            systemPrompt = ""
            temperature = DEFAULT_AGENT_TEMPERATURE
            maxDepth = Agent.DEFAULT_MAX_DEPTH.toString()
            thinkingLevel = ThinkingLevel.DEFAULT
            tags = emptyList()
            useGlobalTemperature = true
        }
    }

    val apiKeys by detailViewModel.apiKeys.collectAsStateWithLifecycle()
    val onDeviceEngineState by detailViewModel.onDeviceEngineState.collectAsStateWithLifecycle()

    val providerInfo = ProviderRegistry.findById(providerId)
    val suggestedModels = if (providerId == "on-device") {
        detailViewModel.downloadedOnDeviceModels
    } else {
        providerInfo?.suggestedModels.orEmpty()
    }

    var liveModels by remember { mutableStateOf(emptyList<String>()) }
    var isLoadingLive by remember { mutableStateOf(false) }
    var isLiveData by remember { mutableStateOf(false) }
    var modelFetchError by remember { mutableStateOf<String?>(null) }

    val selectedKey = apiKeys.firstOrNull { it.id == selectedConnectionId }

    LaunchedEffect(providerId, selectedConnectionId, apiKeys) {
        liveModels = emptyList()
        isLiveData = false
        modelFetchError = null
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
        result
            .onSuccess { models ->
                liveModels = models
                isLiveData = true
            }.onFailure { e ->
                modelFetchError = e.message ?: "Failed to fetch models"
            }
    }

    Column(
        modifier =
            modifier
                .verticalScroll(rememberScrollState()),
    ) {
        Spacer(modifier = Modifier.height(FIELD_SPACING_DP.dp))

        // Show template info if not custom
        if (template != null) {
            TemplateInfoCard(
                template = template,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(modifier = Modifier.height(SECTION_SPACING_DP.dp))
        }

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Agent Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("Give your agent a name") },
        )
        Spacer(modifier = Modifier.height(FIELD_SPACING_DP.dp))

        ConnectionPickerSection(
            keys = apiKeys,
            selectedKeyId = selectedConnectionId,
            onKeySelected = { key ->
                selectedConnectionId = key.id
                val resolved = ProviderRegistry.findById(key.provider)
                providerId = resolved?.id ?: key.provider
                modelName = resolved?.suggestedModels?.firstOrNull().orEmpty()
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
        if (modelFetchError != null) {
            Text(
                text = "Could not fetch models: $modelFetchError",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = FETCH_ERROR_TOP_PADDING),
            )
        }
        Spacer(modifier = Modifier.height(FIELD_SPACING_DP.dp))

        OutlinedTextField(
            value = systemPrompt,
            onValueChange = { systemPrompt = it },
            label = { Text("System Prompt") },
            minLines = 4,
            maxLines = 8,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { 
                if (template == null) {
                    Text("Define your agent's personality and behavior")
                } else {
                    Text("Customize the system prompt for this agent")
                }
            },
        )
        Spacer(modifier = Modifier.height(FIELD_SPACING_DP.dp))

        TagsInput(
            tags = tags,
            onTagsChanged = { tags = it },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(SECTION_SPACING_DP.dp))

        val maxDepthValue = maxDepth.toIntOrNull()
        val maxDepthError = maxDepth.isNotEmpty() && (maxDepthValue == null || maxDepthValue < 1)

        CollapsibleSection(title = "Advanced Settings") {
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
                    text = "Temperature: ${"%.2f".format(temperature)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium,
                )
                Slider(
                    value = temperature,
                    onValueChange = { temperature = it },
                    valueRange = 0f..AGENT_TEMPERATURE_MAX,
                    steps = AGENT_TEMPERATURE_STEPS - 1,
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
                label = { Text("Max Reasoning Depth") },
                singleLine = true,
                isError = maxDepthError,
                supportingText =
                    if (maxDepthError) {
                        { Text("Must be a positive integer (1-20)") }
                    } else {
                        { Text("Controls how deep the agent can reason (1-20)") }
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

        Button(
            onClick = {
                detailViewModel.saveAgent(
                    Agent(
                        id = UUID.randomUUID().toString(),
                        name = name,
                        provider = providerId,
                        modelName = modelName,
                        systemPrompt = systemPrompt,
                        temperature = if (useGlobalTemperature) null else temperature,
                        maxDepth = maxDepth.toIntOrNull() ?: Agent.DEFAULT_MAX_DEPTH,
                        thinkingLevel = thinkingLevel,
                        role = template?.role ?: AgentRole.GENERAL,
                        avatar = template?.avatar ?: AgentRole.GENERAL.icon,
                        tags = tags,
                        isMaster = template?.role == AgentRole.MASTER,
                        templateId = template?.id,
                        accentColor = template?.accentColor ?: 0xFF6200EE,
                    ),
                )
                onSaved()
            },
            enabled =
                name.isNotBlank() &&
                    providerId.isNotBlank() &&
                    modelName.isNotBlank() &&
                    !maxDepthError,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .defaultMinSize(minHeight = 56.dp)
                    .semantics { contentDescription = "Create agent" },
            colors = ButtonDefaults.buttonColors(
                containerColor = if (template != null) {
                    Color(template.accentColor)
                } else {
                    MaterialTheme.colorScheme.primary
                }
            ),
        ) {
            Text(
                "Create ${if (template != null) template.name else "Custom"} Agent",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
        }
        Spacer(modifier = Modifier.height(SECTION_SPACING_DP.dp))
    }
}

/**
 * Template Info Card - Displays information about the selected template.
 *
 * @param template The selected template.
 * @param modifier Modifier applied to the card.
 */
@Composable
private fun TemplateInfoCard(
    template: AgentTemplate,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        tonalElevation = 2.dp,
        color = Color(template.accentColor).copy(alpha = 0.1f),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Color(template.accentColor).copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = template.avatar,
                    style = MaterialTheme.typography.headlineLarge,
                )
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Creating from: ${template.name} Template",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color(template.accentColor),
                )
                Text(
                    text = "System prompt and settings are pre-configured",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
