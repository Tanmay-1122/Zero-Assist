/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.data.ProviderRegistry
import com.zeroclaw.android.model.ApiKey
import com.zeroclaw.android.model.KeyStatus
import com.zeroclaw.android.service.EngineState

/** Icon size for provider icons inside connection cards. */
private const val CONNECTION_ICON_SIZE_DP = 40

/** Status dot diameter. */
private const val STATUS_DOT_SIZE_DP = 8

/** Spacing between connection cards. */
private const val CARD_SPACING_DP = 8

/** Internal padding for connection cards. */
private const val CARD_PADDING_DP = 12

/** Spacing between the icon and text content. */
private const val ICON_TEXT_SPACING_DP = 12

/** Spacing between the status dot and its label. */
private const val DOT_LABEL_SPACING_DP = 4

/** Border width for the selected card. */
private const val SELECTED_BORDER_WIDTH_DP = 2

/**
 * Sentinel key ID used when the user selects the on-device AI option.
 *
 * Callers should compare [selectedKeyId] against this constant to detect
 * when on-device inference is the active connection.
 */
const val ON_DEVICE_KEY_ID = "__on_device__"

/**
 * Reusable section displaying stored API keys as selectable connection cards.
 *
 * Each card shows the provider icon, name, masked key, and status. The
 * selected card is highlighted with a primary-colored border and tinted
 * background. An optional on-device AI card always appears first.
 * A trailing button allows adding new connections without leaving the screen.
 *
 * @param keys All stored API keys to display.
 * @param selectedKeyId The ID of the currently selected key, or [ON_DEVICE_KEY_ID] for on-device.
 * @param onKeySelected Callback when the user taps a cloud connection card.
 * @param onAddNewConnection Callback when the user taps the add button.
 * @param showOnDeviceOption Whether to show the on-device AI card at the top.
 * @param onDeviceEngineState Current engine state, used for the on-device status badge.
 * @param onOnDeviceSelected Callback when the user taps the on-device card.
 * @param modifier Modifier applied to the root layout.
 */
@Suppress("OutdatedDocumentation", "LongParameterList")
@Composable
fun ConnectionPickerSection(
    keys: List<ApiKey>,
    selectedKeyId: String?,
    onKeySelected: (ApiKey) -> Unit,
    onAddNewConnection: () -> Unit,
    title: String = "Connection",
    emptyMessage: String = "No API keys configured yet.",
    addButtonLabel: String = "New Connection",
    showOnDeviceOption: Boolean = true,
    onDeviceEngineState: EngineState = EngineState.UNINITIALIZED,
    onOnDeviceSelected: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        SectionHeader(title = title)
        Spacer(modifier = Modifier.height(CARD_SPACING_DP.dp))

        Column(verticalArrangement = Arrangement.spacedBy(CARD_SPACING_DP.dp)) {
            // On-Device AI card is always rendered first when enabled.
            if (showOnDeviceOption) {
                OnDeviceCard(
                    engineState = onDeviceEngineState,
                    isSelected = selectedKeyId == ON_DEVICE_KEY_ID,
                    onClick = onOnDeviceSelected,
                )
            }

            if (keys.isEmpty() && !showOnDeviceOption) {
                Text(
                    text = emptyMessage,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                keys.forEach { key ->
                    val onClick = remember(key.id) { { onKeySelected(key) } }
                    ConnectionCard(
                        apiKey = key,
                        isSelected = key.id == selectedKeyId,
                        onClick = onClick,
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(CARD_SPACING_DP.dp))

        OutlinedButton(
            onClick = onAddNewConnection,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "Add new API key connection" },
        ) {
            Icon(
                imageVector = Icons.Filled.Add,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(CARD_SPACING_DP.dp))
            Text(addButtonLabel)
        }
    }
}

/**
 * Selectable card for the on-device AI connection option.
 *
 * Shows a Memory chip icon, "On-Device AI" title, and a colour-coded status
 * badge derived from [engineState]. The card receives the same selection
 * highlight treatment as cloud [ConnectionCard]s.
 *
 * @param engineState Current lifecycle state of the local inference engine.
 * @param isSelected Whether this card is currently the active connection.
 * @param onClick Callback when the card is tapped.
 */
@Composable
private fun OnDeviceCard(
    engineState: EngineState,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val statusColor = when (engineState) {
        EngineState.READY -> MaterialTheme.colorScheme.tertiary
        EngineState.INITIALIZING -> MaterialTheme.colorScheme.primary
        EngineState.ERROR -> MaterialTheme.colorScheme.error
        EngineState.UNINITIALIZED -> MaterialTheme.colorScheme.outline
    }
    val statusLabel = when (engineState) {
        EngineState.READY -> "Ready"
        EngineState.INITIALIZING -> "Loading…"
        EngineState.ERROR -> "Error"
        EngineState.UNINITIALIZED -> "No model loaded"
    }

    OutlinedCard(
        onClick = onClick,
        border =
            if (isSelected) {
                BorderStroke(SELECTED_BORDER_WIDTH_DP.dp, MaterialTheme.colorScheme.primary)
            } else {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            },
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    contentDescription = "On-Device AI connection, $statusLabel"
                },
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (isSelected) {
                            Modifier.background(MaterialTheme.colorScheme.secondaryContainer)
                        } else {
                            Modifier
                        },
                    ).padding(CARD_PADDING_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Chip icon
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(CONNECTION_ICON_SIZE_DP.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Outlined.Memory,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
            Spacer(modifier = Modifier.width(ICON_TEXT_SPACING_DP.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "On-Device AI",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = "Gemini Nano · LiteRT · No internet required",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(modifier = Modifier.width(ICON_TEXT_SPACING_DP.dp))
            // Status badge
            Surface(
                shape = RoundedCornerShape(50),
                color = statusColor.copy(alpha = 0.15f),
            ) {
                Text(
                    text = statusLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Medium,
                    color = statusColor,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
    }
}

/**
 * A single selectable connection card showing provider info and key status.
 *
 * @param apiKey The API key to display.
 * @param isSelected Whether this card is currently selected.
 * @param onClick Callback when the card is tapped.
 */
@Composable
private fun ConnectionCard(
    apiKey: ApiKey,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val displayName =
        ProviderRegistry.findById(apiKey.provider)?.displayName
            ?: apiKey.provider
    val selectionLabel = if (isSelected) "Selected" else "Not selected"

    OutlinedCard(
        onClick = onClick,
        border =
            if (isSelected) {
                BorderStroke(
                    SELECTED_BORDER_WIDTH_DP.dp,
                    MaterialTheme.colorScheme.primary,
                )
            } else {
                BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
            },
        modifier =
            Modifier
                .fillMaxWidth()
                .semantics(mergeDescendants = true) {
                    contentDescription =
                        "$displayName connection, $selectionLabel"
                },
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .then(
                        if (isSelected) {
                            Modifier.background(
                                MaterialTheme.colorScheme.secondaryContainer,
                            )
                        } else {
                            Modifier
                        },
                    ).padding(CARD_PADDING_DP.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ProviderIcon(
                provider = apiKey.provider,
                modifier = Modifier.size(CONNECTION_ICON_SIZE_DP.dp),
            )
            Spacer(modifier = Modifier.width(ICON_TEXT_SPACING_DP.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleSmall,
                )
                MaskedText(
                    text = apiKey.key,
                    revealed = false,
                )
            }
            Spacer(modifier = Modifier.width(ICON_TEXT_SPACING_DP.dp))
            KeyStatusIndicator(status = apiKey.status)
        }
    }
}

/**
 * Small status dot with label for an API key's [KeyStatus].
 *
 * @param status The key's current validation status.
 */
@Composable
private fun KeyStatusIndicator(status: KeyStatus) {
    val (color, label) =
        when (status) {
            KeyStatus.ACTIVE ->
                MaterialTheme.colorScheme.primary to "Active"
            KeyStatus.INVALID ->
                MaterialTheme.colorScheme.error to "Invalid"
            KeyStatus.UNKNOWN ->
                MaterialTheme.colorScheme.outline to "Unknown"
        }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier =
                Modifier
                    .size(STATUS_DOT_SIZE_DP.dp)
                    .clip(CircleShape)
                    .background(color),
        )
        Spacer(modifier = Modifier.width(DOT_LABEL_SPACING_DP.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
        )
    }
}
