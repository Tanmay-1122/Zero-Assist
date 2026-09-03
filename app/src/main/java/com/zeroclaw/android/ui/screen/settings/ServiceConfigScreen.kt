package com.zeroclaw.android.ui.screen.settings

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zeroclaw.android.data.ProviderRegistry
import com.zeroclaw.android.model.AppSettings
import com.zeroclaw.android.model.FallbackProviderConfig
import com.zeroclaw.android.ui.component.SectionHeader
import com.zeroclaw.android.ui.component.SettingsToggleRow

private const val TEMPERATURE_MAX = 2.0f
private const val TEMPERATURE_STEPS = 20
private const val PORT_MIN = 1
private const val PORT_MAX = 65535
private const val RETRIES_MAX = 10
private const val WARN_PERCENT_MAX = 100
private const val PROVIDER_DRAG_THRESHOLD_PX = 72f

private val MEMORY_BACKENDS = listOf("sqlite", "none", "markdown", "lucid")

@Composable
fun ServiceConfigScreen(
    edgeMargin: Dp,
    settingsViewModel: SettingsViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val settings by settingsViewModel.settings.collectAsStateWithLifecycle()
    val activeProvider by settingsViewModel.currentActiveProvider.collectAsStateWithLifecycle()
    val providerExhaustionMessage by
        settingsViewModel.providerExhaustionMessage.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(providerExhaustionMessage) {
        val message = providerExhaustionMessage ?: return@LaunchedEffect
        val result =
            snackbarHostState.showSnackbar(
                message = message,
                actionLabel = "OK",
                withDismissAction = true,
            )
        if (result == SnackbarResult.ActionPerformed || result == SnackbarResult.Dismissed) {
            settingsViewModel.acknowledgeProviderExhaustion()
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = edgeMargin)
                    .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            SectionHeader(title = "Network")

            OutlinedTextField(
                value = settings.host,
                onValueChange = { settingsViewModel.updateHost(it) },
                label = { Text("Host") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            val portError = settings.port !in PORT_MIN..PORT_MAX
            OutlinedTextField(
                value = settings.port.toString(),
                onValueChange = { value ->
                    value.toIntOrNull()?.let { settingsViewModel.updatePort(it) }
                },
                label = { Text("Port") },
                singleLine = true,
                isError = portError,
                supportingText =
                    if (portError) {
                        { Text("Port must be between $PORT_MIN and $PORT_MAX") }
                    } else {
                        null
                    },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )

            SectionHeader(title = "Startup")

            SettingsToggleRow(
                title = "Auto-start on boot",
                subtitle = "Start the daemon automatically after device reboot",
                checked = settings.autoStartOnBoot,
                onCheckedChange = { settingsViewModel.updateAutoStartOnBoot(it) },
                contentDescription = "Auto-start on boot",
            )

            DefaultsSection(settings = settings, viewModel = settingsViewModel)
            InferenceSection(settings = settings, viewModel = settingsViewModel)
            MemorySection(settings = settings, viewModel = settingsViewModel)
            ReliabilitySection(
                settings = settings,
                activeProvider = activeProvider,
                viewModel = settingsViewModel,
            )
            CostLimitsSection(settings = settings, viewModel = settingsViewModel)
            ProxySection(settings = settings, viewModel = settingsViewModel)

            Spacer(modifier = Modifier.height(64.dp))
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Composable
private fun DefaultsSection(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) {
    SectionHeader(title = "Defaults")

    OutlinedTextField(
        value = settings.defaultProvider,
        onValueChange = { viewModel.updateDefaultProvider(it) },
        label = { Text("Default Provider") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )

    Spacer(modifier = Modifier.height(8.dp))

    OutlinedTextField(
        value = settings.defaultModel,
        onValueChange = { viewModel.updateDefaultModel(it) },
        label = { Text("Default Model") },
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
    )
}

@Composable
private fun InferenceSection(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) {
    SectionHeader(title = "Inference")

    Text(
        text = "Temperature: ${"%.1f".format(settings.defaultTemperature)}",
        style = MaterialTheme.typography.bodyLarge,
    )
    Slider(
        value = settings.defaultTemperature,
        onValueChange = { viewModel.updateDefaultTemperature(it) },
        valueRange = 0f..TEMPERATURE_MAX,
        steps = TEMPERATURE_STEPS - 1,
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics { contentDescription = "Temperature slider" },
    )

    SettingsToggleRow(
        title = "Compact context",
        subtitle = "Reduce token usage by compressing conversation context",
        checked = settings.compactContext,
        onCheckedChange = { viewModel.updateCompactContext(it) },
        contentDescription = "Compact context",
    )

    SettingsToggleRow(
        title = "Strip thinking tags",
        subtitle = "Client-side only - strips thinking tags from console display without affecting daemon behavior",
        checked = settings.stripThinkingTags,
        onCheckedChange = { viewModel.updateStripThinkingTags(it) },
        contentDescription = "Strip thinking tags from responses",
    )

    IterationLimitRow(
        maxToolIterations = settings.maxToolIterations,
        onValueChange = { viewModel.updateMaxToolIterations(it) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun IterationLimitRow(
    maxToolIterations: Int,
    onValueChange: (Int) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }

    val options = listOf(5, 10, 15, 20, 30, 50) + listOf(AppSettings.MAX_TOOL_ITERATIONS_UNLIMITED)
    val labels = options.map { if (it >= AppSettings.MAX_TOOL_ITERATIONS_UNLIMITED) "No limit" else "$it" }
    val currentLabel =
        if (maxToolIterations >= AppSettings.MAX_TOOL_ITERATIONS_UNLIMITED) "No limit"
        else if (maxToolIterations <= 0) "Default (10)"
        else "$maxToolIterations"

    Text(
        text = "Tool iteration limit: $currentLabel",
        style = MaterialTheme.typography.bodyLarge,
    )
    Text(
        text = "Max tool-call rounds per message. \"No limit\" allows unlimited iterations.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = currentLabel,
            onValueChange = {},
            readOnly = true,
            label = { Text("Iteration limit") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier =
                Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            DropdownMenuItem(
                text = { Text("Default (10)") },
                onClick = {
                    onValueChange(AppSettings.DEFAULT_MAX_TOOL_ITERATIONS)
                    expanded = false
                },
            )
            options.zip(labels).forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onValueChange(value)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MemorySection(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) {
    SectionHeader(title = "Memory")

    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
    ) {
        OutlinedTextField(
            value = settings.memoryBackend,
            onValueChange = {},
            readOnly = true,
            label = { Text("Backend") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier =
                Modifier
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                    .fillMaxWidth(),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            MEMORY_BACKENDS.forEach { backend ->
                DropdownMenuItem(
                    text = { Text(backend) },
                    onClick = {
                        viewModel.updateMemoryBackend(backend)
                        expanded = false
                    },
                )
            }
        }
    }

    SettingsToggleRow(
        title = "Auto-save",
        subtitle = "Automatically save conversation context to memory",
        checked = settings.memoryAutoSave,
        onCheckedChange = { viewModel.updateMemoryAutoSave(it) },
        contentDescription = "Memory auto-save",
    )
}

@Composable
private fun ReliabilitySection(
    settings: AppSettings,
    activeProvider: String,
    viewModel: SettingsViewModel,
) {
    SectionHeader(title = "Reliability")

    val retriesError = settings.providerRetries !in 0..RETRIES_MAX
    OutlinedTextField(
        value = settings.providerRetries.toString(),
        onValueChange = { value ->
            value.toIntOrNull()?.let { viewModel.updateProviderRetries(it) }
        },
        label = { Text("Provider retries") },
        singleLine = true,
        isError = retriesError,
        supportingText =
            if (retriesError) {
                { Text("Must be between 0 and $RETRIES_MAX") }
            } else {
                null
            },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )

    if (activeProvider.isNotBlank()) {
        Text(
            text =
                "Active provider: ${ProviderRegistry.findById(activeProvider)?.displayName ?: activeProvider}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }

    Text(
        text = "Provider fallback disabled",
        style = MaterialTheme.typography.titleSmall,
    )
    Text(
        text =
            "Requests stay on the selected main provider/model. If that path fails, " +
                "Zero-Assist reports the error instead of switching providers.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    OutlinedTextField(
        value = settings.reliabilityBackoffMs.toString(),
        onValueChange = { value ->
            value.toIntOrNull()?.let { viewModel.updateReliabilityBackoffMs(it) }
        },
        label = { Text("Provider backoff (ms)") },
        supportingText = { Text("Used only for retry timing on the selected provider.") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ProviderPriorityCard(
    provider: FallbackProviderConfig,
    index: Int,
    providers: List<FallbackProviderConfig>,
    onMove: (from: Int, to: Int) -> Unit,
    onUpdate: (FallbackProviderConfig) -> Unit,
    onRemove: () -> Unit,
) {
    var dragAccumulator by remember(provider.id, index) { mutableFloatStateOf(0f) }

    Card(
        modifier =
            Modifier
                .fillMaxWidth()
                .pointerInput(provider.id, index, providers) {
                    detectDragGesturesAfterLongPress(
                        onDragEnd = { dragAccumulator = 0f },
                        onDragCancel = { dragAccumulator = 0f },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragAccumulator += dragAmount.y
                            if (dragAccumulator <= -PROVIDER_DRAG_THRESHOLD_PX && index > 0) {
                                onMove(index, index - 1)
                                dragAccumulator = 0f
                            } else if (dragAccumulator >= PROVIDER_DRAG_THRESHOLD_PX &&
                                index < providers.lastIndex
                            ) {
                                onMove(index, index + 1)
                                dragAccumulator = 0f
                            }
                        },
                    )
                },
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(
                        imageVector = Icons.Default.DragHandle,
                        contentDescription = "Drag provider",
                    )
                    Column {
                        Text(
                            text = ProviderRegistry.findById(provider.id)?.displayName ?: provider.id,
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = provider.id,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                IconButton(onClick = onRemove) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Remove provider",
                    )
                }
            }

            OutlinedTextField(
                value = provider.apiKey,
                onValueChange = { onUpdate(provider.copy(apiKey = it)) },
                label = { Text("API key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = provider.dailyLimitUsd?.toString().orEmpty(),
                    onValueChange = { value ->
                        onUpdate(provider.copy(dailyLimitUsd = value.toFloatOrNull()))
                    },
                    label = { Text("Daily cap (USD)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(
                    value = provider.monthlyLimitUsd?.toString().orEmpty(),
                    onValueChange = { value ->
                        onUpdate(provider.copy(monthlyLimitUsd = value.toFloatOrNull()))
                    },
                    label = { Text("Monthly cap (USD)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddProviderRow(
    existingProviders: List<FallbackProviderConfig>,
    onAddProvider: (FallbackProviderConfig) -> Unit,
) {
    val predefinedProviders =
        remember {
            ProviderRegistry.allProviders
                .filterNot { it.internal }
                .map { it.id }
                .distinct()
                .sorted()
        }
    var showForm by remember { mutableStateOf(false) }
    var selectedProvider by remember { mutableStateOf(predefinedProviders.firstOrNull().orEmpty()) }
    var customProviderId by remember { mutableStateOf("") }
    var apiKey by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }

    if (!showForm) {
        Button(
            onClick = { showForm = true },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Icon(imageVector = Icons.Default.Add, contentDescription = null)
            Text("Add Provider")
        }
        return
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ExposedDropdownMenuBox(
                expanded = expanded,
                onExpandedChange = { expanded = it },
            ) {
                OutlinedTextField(
                    value = selectedProvider,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Predefined provider") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
                    modifier =
                        Modifier
                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                            .fillMaxWidth(),
                )
                ExposedDropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false },
                ) {
                    predefinedProviders.forEach { providerId ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    ProviderRegistry.findById(providerId)?.displayName
                                        ?: providerId,
                                )
                            },
                            onClick = {
                                selectedProvider = providerId
                                expanded = false
                            },
                        )
                    }
                }
            }

            OutlinedTextField(
                value = customProviderId,
                onValueChange = { customProviderId = it },
                label = { Text("Custom provider ID (optional)") },
                supportingText = { Text("Overrides the predefined provider when filled in") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                label = { Text("API key") },
                visualTransformation = PasswordVisualTransformation(),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Button(
                    onClick = {
                        val resolvedId = customProviderId.trim().ifBlank { selectedProvider.trim() }
                        if (resolvedId.isBlank()) {
                            return@Button
                        }
                        val alreadyExists =
                            existingProviders.any { it.id.equals(resolvedId, ignoreCase = true) }
                        if (alreadyExists) {
                            return@Button
                        }
                        onAddProvider(
                            FallbackProviderConfig(
                                id = resolvedId,
                                apiKey = apiKey.trim(),
                            ),
                        )
                        apiKey = ""
                        customProviderId = ""
                        showForm = false
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Save Provider")
                }
                Button(
                    onClick = {
                        apiKey = ""
                        customProviderId = ""
                        showForm = false
                    },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
private fun CostLimitsSection(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) {
    SectionHeader(title = "Cost Limits")

    Text(
        text = "Budget tracking and usage warnings",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    SettingsToggleRow(
        title = "Enable cost limits",
        subtitle = "Enforce daily and monthly spending caps",
        checked = settings.costEnabled,
        onCheckedChange = { viewModel.updateCostEnabled(it) },
        contentDescription = "Enable cost limits",
    )

    val dailyError = settings.costEnabled && settings.dailyLimitUsd < 0f
    OutlinedTextField(
        value = settings.dailyLimitUsd.toString(),
        onValueChange = { value ->
            value.toFloatOrNull()?.let { viewModel.updateDailyLimitUsd(it) }
        },
        label = { Text("Daily limit (USD)") },
        singleLine = true,
        isError = dailyError,
        supportingText =
            if (dailyError) {
                { Text("Must be a positive amount") }
            } else {
                null
            },
        enabled = settings.costEnabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )

    val monthlyError = settings.costEnabled && settings.monthlyLimitUsd < 0f
    OutlinedTextField(
        value = settings.monthlyLimitUsd.toString(),
        onValueChange = { value ->
            value.toFloatOrNull()?.let { viewModel.updateMonthlyLimitUsd(it) }
        },
        label = { Text("Monthly limit (USD)") },
        singleLine = true,
        isError = monthlyError,
        supportingText =
            if (monthlyError) {
                { Text("Must be a positive amount") }
            } else {
                null
            },
        enabled = settings.costEnabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = Modifier.fillMaxWidth(),
    )

    val warnError = settings.costEnabled && settings.costWarnAtPercent !in 0..WARN_PERCENT_MAX
    OutlinedTextField(
        value = settings.costWarnAtPercent.toString(),
        onValueChange = { value ->
            value.toIntOrNull()?.let { viewModel.updateCostWarnAtPercent(it) }
        },
        label = { Text("Warn at (%)") },
        singleLine = true,
        isError = warnError,
        supportingText =
            if (warnError) {
                { Text("Must be between 0 and $WARN_PERCENT_MAX") }
            } else {
                null
            },
        enabled = settings.costEnabled,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ProxySection(
    settings: AppSettings,
    viewModel: SettingsViewModel,
) {
    SectionHeader(title = "Proxy")

    SettingsToggleRow(
        title = "Enable proxy",
        subtitle = "Route outbound traffic through a proxy",
        checked = settings.proxyEnabled,
        onCheckedChange = { viewModel.updateProxyEnabled(it) },
        contentDescription = "Enable proxy",
    )

    OutlinedTextField(
        value = settings.proxyHttpProxy,
        onValueChange = { viewModel.updateProxyHttpProxy(it) },
        label = { Text("HTTP proxy") },
        supportingText = { Text("e.g. http://proxy:8080") },
        singleLine = true,
        enabled = settings.proxyEnabled,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = settings.proxyHttpsProxy,
        onValueChange = { viewModel.updateProxyHttpsProxy(it) },
        label = { Text("HTTPS proxy") },
        singleLine = true,
        enabled = settings.proxyEnabled,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = settings.proxyAllProxy,
        onValueChange = { viewModel.updateProxyAllProxy(it) },
        label = { Text("All proxy") },
        supportingText = { Text("Catch-all for all protocols") },
        singleLine = true,
        enabled = settings.proxyEnabled,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = settings.proxyNoProxy,
        onValueChange = { viewModel.updateProxyNoProxy(it) },
        label = { Text("No proxy") },
        supportingText = { Text("Comma-separated bypass domains") },
        enabled = settings.proxyEnabled,
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = settings.proxyScope,
        onValueChange = { viewModel.updateProxyScope(it) },
        label = { Text("Scope") },
        supportingText = { Text("zeroclaw (default) or system") },
        singleLine = true,
        enabled = settings.proxyEnabled,
        modifier = Modifier.fillMaxWidth(),
    )

    OutlinedTextField(
        value = settings.proxyServiceSelectors,
        onValueChange = { viewModel.updateProxyServiceSelectors(it) },
        label = { Text("Service selectors") },
        supportingText = { Text("Comma-separated service names for selective routing") },
        enabled = settings.proxyEnabled,
        minLines = 2,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun moveProvider(
    providers: List<FallbackProviderConfig>,
    from: Int,
    to: Int,
): List<FallbackProviderConfig> {
    if (from == to || from !in providers.indices || to !in providers.indices) {
        return providers
    }
    val mutable = providers.toMutableList()
    val item = mutable.removeAt(from)
    mutable.add(to, item)
    return mutable
}
