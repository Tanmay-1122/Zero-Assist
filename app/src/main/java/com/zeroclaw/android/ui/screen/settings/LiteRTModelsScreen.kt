/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.ui.screen.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zeroclaw.android.service.DownloadError
import com.zeroclaw.android.service.DownloadedModel
import com.zeroclaw.android.service.EngineState
import com.zeroclaw.android.service.LocalModel
import com.zeroclaw.android.service.estimateGpuMemoryMb
import com.zeroclaw.android.service.calculateDevicePerformance
import com.zeroclaw.android.service.DevicePerformance
import com.zeroclaw.android.ui.component.ErrorCard
import com.zeroclaw.android.ui.component.SectionHeader
import com.zeroclaw.android.viewmodel.LiteRTUiState
import com.zeroclaw.android.viewmodel.LiteRTViewModel
import kotlin.math.roundToInt

/**
 * Settings screen for managing on-device LiteRT LM models.
 *
 * Shows the full model catalog with download status, download/delete controls,
 * engine lifecycle controls (load / unload), and a free-space indicator. The
 * screen is purely state-driven — all user actions are forwarded to
 * [LiteRTViewModel].
 */
@Composable
fun LiteRTModelsScreen(
    edgeMargin: Dp,
    viewModel: LiteRTViewModel = viewModel(),
    modifier: Modifier = Modifier,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = edgeMargin)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        SectionHeader(title = "On-Device AI Models")

        Text(
            text = "Download Gemma 4 or Qwen3 models to run inference entirely on-device " +
                "without any cloud connectivity. Models are stored in the app's private storage.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // Error banner
        uiState.errorMessage?.let { msg ->
            ErrorCard(message = msg, onRetry = viewModel::clearError)
        }

        // Download error banner
        uiState.downloadError?.let { error ->
            ErrorCard(
                message = when (error) {
                    DownloadError.NOT_ENOUGH_DISK_SPACE ->
                        "Not enough storage space. Free up some space and try again."
                    DownloadError.NETWORK_ERROR ->
                        "Download failed. Check your internet connection and try again."
                    DownloadError.DOWNLOAD_INCOMPLETE ->
                        "Download was incomplete. Please retry."
                },
                onRetry = viewModel::clearError,
            )
        }

        // Free space indicator
        FreeSpaceRow(freeSpaceBytes = uiState.freeSpaceBytes)

        // Engine status card
        EngineStatusCard(
            state = uiState.engineState,
            loadedModelId = uiState.loadedModelId,
            onUnload = viewModel::unloadModel,
        )

        SectionHeader(title = "Available Models")

        // Model cards
        uiState.catalog.forEach { model ->
            val downloaded = uiState.downloadedModels.find { it.id == model.id }
            ModelCard(
                model = model,
                downloaded = downloaded,
                isLoaded = uiState.loadedModelId == model.id,
                isDownloading = uiState.downloadingId == model.id,
                downloadProgress = if (uiState.downloadingId == model.id) uiState.downloadProgress else null,
                totalMemoryBytes = uiState.totalMemoryBytes,
                onDownload = { viewModel.downloadModel(model) },
                onCancelDownload = viewModel::cancelDownload,
                onDelete = { viewModel.deleteModel(model.id) },
                onLoad = { downloaded?.let { viewModel.loadModel(it) } },
                onUnload = viewModel::unloadModel,
            )
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

// ---------------------------------------------------------------------------
// Sub-composables
// ---------------------------------------------------------------------------

@Composable
private fun FreeSpaceRow(freeSpaceBytes: Long) {
    val freeGb = freeSpaceBytes / (1024.0 * 1024 * 1024)
    Text(
        text = "Free storage: ${"%.1f".format(freeGb)} GB",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun EngineStatusCard(
    state: EngineState,
    loadedModelId: String?,
    onUnload: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = when (state) {
                EngineState.READY -> MaterialTheme.colorScheme.primaryContainer
                EngineState.ERROR -> MaterialTheme.colorScheme.errorContainer
                else -> MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Engine Status",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = when (state) {
                        EngineState.UNINITIALIZED -> "No model loaded"
                        EngineState.INITIALIZING -> "Loading model…"
                        EngineState.READY -> "Ready — ${loadedModelId ?: "unknown"}"
                        EngineState.ERROR -> "Engine error"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (state == EngineState.INITIALIZING) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            }
            if (state == EngineState.READY) {
                OutlinedButton(onClick = onUnload) {
                    Icon(Icons.Outlined.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                    Text(" Unload", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun ModelCard(
    model: LocalModel,
    downloaded: DownloadedModel?,
    isLoaded: Boolean,
    isDownloading: Boolean,
    downloadProgress: Float?,
    totalMemoryBytes: Long,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onDelete: () -> Unit,
    onLoad: () -> Unit,
    onUnload: () -> Unit,
) {
    val sizeGb = model.sizeBytes / (1024.0 * 1024 * 1024)
    val downloadedSizeGb = downloaded?.sizeBytes?.let { it / (1024.0 * 1024 * 1024) }
    val estimatedGpuMb = estimateGpuMemoryMb(model, model.defaultContextTokens)
    val performance = calculateDevicePerformance(totalMemoryBytes, estimatedGpuMb)

    val perfText = when (performance) {
        DevicePerformance.GOOD -> "Fast execution"
        DevicePerformance.OK -> "Moderate speed"
        DevicePerformance.POOR -> "May run slow / low RAM"
    }
    val perfColor = when (performance) {
        DevicePerformance.GOOD -> Color(0xFF4CAF50)
        DevicePerformance.OK -> Color(0xFFFF9800)
        DevicePerformance.POOR -> MaterialTheme.colorScheme.error
    }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (isLoaded) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Title row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = model.displayName,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (model.isRecommended) {
                            Text(
                                text = "Recommended",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
                if (downloaded != null) {
                    Icon(
                        imageVector = Icons.Outlined.CheckCircle,
                        contentDescription = "Downloaded",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }

            // Metadata chips
            Text(
                text = "Size: ${"%.1f".format(sizeGb)} GB  •  " +
                    "Context: ${model.defaultContextTokens / 1024}K tokens  •  " +
                    "GPU est.: $estimatedGpuMb MB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // Performance suggestion badge
            Text(
                text = "Estimated Performance: $perfText",
                style = MaterialTheme.typography.bodySmall,
                color = perfColor,
                fontWeight = FontWeight.Medium,
            )

            if (downloadedSizeGb != null) {
                Text(
                    text = "On device: ${"%.1f".format(downloadedSizeGb)} GB",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // Download progress bar
            AnimatedVisibility(visible = isDownloading) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LinearProgressIndicator(
                        progress = { downloadProgress ?: 0f },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = if (downloadProgress != null) {
                            "${(downloadProgress * 100).roundToInt()}% downloaded"
                        } else {
                            "Starting download…"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            // Action buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                when {
                    isDownloading -> {
                        OutlinedButton(
                            onClick = onCancelDownload,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Cancel")
                        }
                    }
                    downloaded == null -> {
                        Button(
                            onClick = onDownload,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                Icons.Outlined.CloudDownload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(
                                " Download (${"%.1f".format(sizeGb)} GB)",
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }
                    isLoaded -> {
                        FilledTonalButton(
                            onClick = onUnload,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                Icons.Outlined.Stop,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(" Unload", style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedButton(
                            onClick = onDelete,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                    else -> {
                        Button(
                            onClick = onLoad,
                            modifier = Modifier.weight(1f),
                        ) {
                            Icon(
                                Icons.Outlined.PlayArrow,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Text(" Load", style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedButton(
                            onClick = onDelete,
                            colors = ButtonDefaults.outlinedButtonColors(
                                contentColor = MaterialTheme.colorScheme.error,
                            ),
                        ) {
                            Icon(
                                Icons.Outlined.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
