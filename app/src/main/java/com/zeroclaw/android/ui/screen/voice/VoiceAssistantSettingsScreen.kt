/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.outlined.CloudDownload
import androidx.compose.material.icons.outlined.FileUpload
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zeroclaw.android.ui.theme.ZeroAssistSpacing
import com.zeroclaw.android.model.VoiceImportState
import com.zeroclaw.android.model.VoiceModel
import com.zeroclaw.android.model.VoiceModelStatus
import com.zeroclaw.android.model.VoicePreviewState
import com.zeroclaw.android.service.DefaultAssistantSettingsLauncher
import com.zeroclaw.android.service.VoicePackageImportFile
import com.zeroclaw.android.service.VoicePerformanceMode
import com.zeroclaw.android.service.VoiceTurnBenchmarkStatus
import com.zeroclaw.android.service.VoiceTurnTraceSnapshot
import com.zeroclaw.android.ui.component.SectionHeader

@Composable
fun VoiceAssistantSettingsScreen(
    edgeMargin: Dp,
    viewModel: VoiceAssistantViewModel,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val voices by viewModel.voices.collectAsStateWithLifecycle()
    val selectedVoiceId by viewModel.selectedVoiceId.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val previewState by viewModel.previewState.collectAsStateWithLifecycle()
    val importState by viewModel.importState.collectAsStateWithLifecycle()
    val voiceDiagnostics by viewModel.voiceDiagnostics.collectAsStateWithLifecycle()
    val voicePerformanceMode by viewModel.voicePerformanceMode.collectAsStateWithLifecycle()
    LaunchedEffect(viewModel) {
        viewModel.activateVoiceSettingsStatus()
    }
    val wakeupMicrophonePermissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                viewModel.setWakeupEnabled(true)
            } else {
                viewModel.microphonePermissionDenied()
            }
        }
    val importVoiceLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
            if (uris.isEmpty()) return@rememberLauncherForActivityResult
            val files =
                uris.map { uri ->
                    runCatching {
                        context.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                    }
                    val fileInfo = voiceFileInfo(context, uri)
                    VoicePackageImportFile(
                        displayName = fileInfo.displayName,
                        sourceUri = uri.toString(),
                        declaredSizeBytes = fileInfo.sizeBytes,
                    )
                }
            viewModel.importVoiceFiles(files)
        }

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = edgeMargin)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(ZeroAssistSpacing.Medium),
    ) {
        Spacer(modifier = Modifier.height(8.dp))
        SectionHeader(title = "Voice Assistant")

        VoiceModeCard(
            hasSelectedVoice = uiState.selectedVoice != null,
            wakeupEnabled = uiState.wakeupEnabled,
            wakeupAvailable = uiState.wakeupAvailable,
            wakeupStatusMessage = uiState.wakeupStatusMessage,
            onOpenAssistantSettings = {
                DefaultAssistantSettingsLauncher.open(context)
            },
            onWakeupChanged = { enabled ->
                if (
                    enabled &&
                    context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    wakeupMicrophonePermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                } else {
                    viewModel.setWakeupEnabled(enabled)
                }
            },
        )
        VoicePerformanceCard(
            selectedMode = voicePerformanceMode,
            onModeSelected = viewModel::setVoicePerformanceMode,
        )
        PreviewStateText(previewState = previewState)

        SectionHeader(title = "Voice Diagnostics")
        VoiceDiagnosticsCard(
            turns = voiceDiagnostics,
            onClear = viewModel::clearVoiceDiagnostics,
        )

        SectionHeader(title = "English Voices")
        voices.forEach { voice ->
            VoiceModelCard(
                voice = voice,
                selected = voice.id == selectedVoiceId,
                previewState = previewState,
                onDownload = { viewModel.downloadVoice(voice.id) },
                onSelect = { viewModel.selectVoice(voice.id) },
                onPreview = { viewModel.previewVoice(voice.id) },
                onDelete = { viewModel.deleteVoice(voice.id) },
            )
        }

        ImportVoiceCard(
            importState = importState,
            onImport = {
                importVoiceLauncher.launch(arrayOf("*/*"))
            },
        )
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun VoicePerformanceCard(
    selectedMode: VoicePerformanceMode,
    onModeSelected: (VoicePerformanceMode) -> Unit,
) {
    val modeRows =
        listOf(
            listOf(VoicePerformanceMode.AUTO, VoicePerformanceMode.FAST),
            listOf(VoicePerformanceMode.BALANCED, VoicePerformanceMode.QUALITY),
        )
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(ZeroAssistSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(ZeroAssistSpacing.Medium),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Speech mode",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = selectedMode.summaryText(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            modeRows.forEach { modes ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(ZeroAssistSpacing.Small),
                ) {
                    modes.forEach { mode ->
                        val selected = mode == selectedMode
                        if (selected) {
                            Button(
                                onClick = { onModeSelected(mode) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(text = mode.displayLabel())
                            }
                        } else {
                            OutlinedButton(
                                onClick = { onModeSelected(mode) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(text = mode.displayLabel())
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun VoicePerformanceMode.displayLabel(): String =
    when (this) {
        VoicePerformanceMode.AUTO -> "Auto"
        VoicePerformanceMode.FAST -> "Fast"
        VoicePerformanceMode.BALANCED -> "Balanced"
        VoicePerformanceMode.QUALITY -> "Quality"
    }

private fun VoicePerformanceMode.summaryText(): String =
    when (this) {
        VoicePerformanceMode.AUTO -> "Device-aware routing"
        VoicePerformanceMode.FAST -> "Fastest response"
        VoicePerformanceMode.BALANCED -> "Speed with local voice"
        VoicePerformanceMode.QUALITY -> "Best local voice"
    }

@Composable
private fun VoiceDiagnosticsCard(
    turns: List<VoiceTurnTraceSnapshot>,
    onClear: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(ZeroAssistSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(ZeroAssistSpacing.Medium),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Last voice turns",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${turns.size} recorded",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(
                    onClick = onClear,
                    enabled = turns.isNotEmpty(),
                ) {
                    Text("Clear")
                }
            }

            if (turns.isEmpty()) {
                Text(
                    text = "No voice turns recorded yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                turns.forEach { turn ->
                    VoiceTurnSummary(turn = turn)
                }
            }
        }
    }
}

@Composable
private fun VoiceTurnSummary(turn: VoiceTurnTraceSnapshot) {
    val benchmark = turn.benchmark
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ZeroAssistSpacing.XSmall),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = turn.id.takeLast(12),
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = turn.outcome ?: turn.latestEvent?.event.orEmpty(),
                style = MaterialTheme.typography.labelMedium,
                color =
                    if (turn.outcome == null || turn.outcome == "success") {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text =
                "${turn.source} | ${formatDurationMs(turn.durationMs)} | " +
                    "${turn.events.size} events | ${benchmark.summary}",
            style = MaterialTheme.typography.bodySmall,
            color =
                when (benchmark.status) {
                    VoiceTurnBenchmarkStatus.FAST -> MaterialTheme.colorScheme.primary
                    VoiceTurnBenchmarkStatus.SLOW -> MaterialTheme.colorScheme.tertiary
                    VoiceTurnBenchmarkStatus.FAILED -> MaterialTheme.colorScheme.error
                    VoiceTurnBenchmarkStatus.INCOMPLETE -> MaterialTheme.colorScheme.onSurfaceVariant
                },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        turn.events.takeLast(4).forEach { event ->
            val detail = event.detail?.let { " | $it" }.orEmpty()
            Text(
                text = "${formatDurationMs(event.elapsedMs)}  ${event.event}$detail",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@Composable
private fun VoiceModeCard(
    hasSelectedVoice: Boolean,
    wakeupEnabled: Boolean,
    wakeupAvailable: Boolean,
    wakeupStatusMessage: String,
    onOpenAssistantSettings: () -> Unit,
    onWakeupChanged: (Boolean) -> Unit,
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(ZeroAssistSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(ZeroAssistSpacing.Medium),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .padding(start = ZeroAssistSpacing.Medium),
                ) {
                    Text(
                        text = if (hasSelectedVoice) "Local voice ready" else "No voice selected",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "Voice playback assets stay on this phone.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (wakeupEnabled) "Wake-word requested" else "Wake word off",
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        text = wakeupStatusMessage,
                        style = MaterialTheme.typography.bodySmall,
                        color =
                            if (wakeupAvailable) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.error
                            },
                    )
                }
                Switch(
                    checked = wakeupEnabled,
                    onCheckedChange = onWakeupChanged,
                    enabled = true,
                )
            }
            OutlinedButton(onClick = onOpenAssistantSettings) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(modifier = Modifier.size(8.dp))
                Text("Default assistant")
            }
        }
    }
}

@Composable
private fun VoiceModelCard(
    voice: VoiceModel,
    selected: Boolean,
    previewState: VoicePreviewState,
    onDownload: () -> Unit,
    onSelect: () -> Unit,
    onPreview: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(ZeroAssistSpacing.Large),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector =
                        if (selected) {
                            Icons.Filled.CheckCircle
                        } else {
                            Icons.Filled.RadioButtonUnchecked
                        },
                    contentDescription = null,
                    tint =
                        if (selected) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    modifier = Modifier.size(24.dp),
                )
                Column(
                    modifier =
                        Modifier
                            .weight(1f)
                            .padding(start = ZeroAssistSpacing.Medium),
                ) {
                    Text(
                        text = voice.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = "${voice.toneLabel} | ${voice.localeTag} | ${formatSize(voice.sizeBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Text(
                text = voice.description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = statusText(voice.status),
                style = MaterialTheme.typography.labelMedium,
                color =
                    if (voice.status == VoiceModelStatus.Installed) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
            )

            Row(
                horizontalArrangement = Arrangement.spacedBy(ZeroAssistSpacing.Small),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val isInstalled = voice.status == VoiceModelStatus.Installed
                val isSpeaking =
                    previewState is VoicePreviewState.Speaking &&
                        previewState.voiceId == voice.id
                OutlinedButton(
                    onClick = onPreview,
                    enabled = isInstalled && !isSpeaking,
                ) {
                    Icon(
                        imageVector = Icons.Filled.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(if (isSpeaking) "Playing" else "Preview")
                }

                when (voice.status) {
                    VoiceModelStatus.AvailableForDownload,
                    is VoiceModelStatus.Failed -> {
                        Button(onClick = onDownload) {
                            Icon(
                                imageVector = Icons.Outlined.CloudDownload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text("Download")
                        }
                    }
                    is VoiceModelStatus.Downloading -> {
                        Button(
                            onClick = {},
                            enabled = false,
                        ) {
                            Text("Downloading")
                        }
                    }
                    VoiceModelStatus.Installed -> {
                        TextButton(
                            onClick = onSelect,
                            enabled = !selected,
                        ) {
                            Text(if (selected) "Selected" else "Select")
                        }
                        OutlinedButton(onClick = onDelete) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text("Delete")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PreviewStateText(previewState: VoicePreviewState) {
    val message =
        when (previewState) {
            VoicePreviewState.Idle -> return
            is VoicePreviewState.Speaking -> "Playing local preview"
            is VoicePreviewState.Completed -> "Preview finished"
            is VoicePreviewState.Unavailable -> previewState.message
            is VoicePreviewState.Failed -> previewState.message
        }
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color =
            when (previewState) {
                is VoicePreviewState.Failed,
                is VoicePreviewState.Unavailable -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            },
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun ImportVoiceCard(
    importState: VoiceImportState,
    onImport: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(ZeroAssistSpacing.Large),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Outlined.FileUpload,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp),
            )
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(horizontal = ZeroAssistSpacing.Medium),
            ) {
                Text(
                    text = "Import voice",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    text = importStateText(importState),
                    style = MaterialTheme.typography.bodySmall,
                    color =
                        if (importState is VoiceImportState.Failed) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                )
            }
            OutlinedButton(
                onClick = onImport,
                enabled = importState != VoiceImportState.Importing,
            ) {
                Text(if (importState == VoiceImportState.Importing) "Importing" else "Import")
            }
        }
    }
}

private fun importStateText(importState: VoiceImportState): String =
    when (importState) {
        VoiceImportState.Idle -> "Select a .voicepkg/.zip, or both .onnx and .onnx.json."
        VoiceImportState.Importing -> "Copying voice into app-private storage."
        is VoiceImportState.Imported -> "${importState.displayName} is stored on this phone."
        is VoiceImportState.Failed -> importState.message
    }

private fun statusText(status: VoiceModelStatus): String =
    when (status) {
        VoiceModelStatus.AvailableForDownload -> "Available to download"
        is VoiceModelStatus.Downloading -> "Download queued"
        is VoiceModelStatus.Failed -> "Failed: ${status.reason}"
        VoiceModelStatus.Installed -> "Installed on phone"
    }

private fun formatSize(bytes: Long): String {
    val megabytes = bytes / (1024L * 1024L)
    return "${megabytes}MB"
}

private fun formatDurationMs(milliseconds: Long): String =
    if (milliseconds >= 1_000L) {
        "${milliseconds / 1_000L}.${(milliseconds % 1_000L) / 100L}s"
    } else {
        "${milliseconds}ms"
    }

private data class VoiceFileInfo(
    val displayName: String,
    val sizeBytes: Long,
)

private fun voiceFileInfo(
    context: Context,
    uri: Uri,
): VoiceFileInfo {
    var displayName = uri.lastPathSegment?.substringAfterLast('/') ?: "Imported Voice"
    var sizeBytes = 0L
    context.contentResolver
        .query(
            uri,
            arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (nameIndex >= 0) {
                    displayName = cursor.getString(nameIndex) ?: displayName
                }
                if (sizeIndex >= 0) {
                    sizeBytes = cursor.getLong(sizeIndex).coerceAtLeast(0L)
                }
            }
        }
    return VoiceFileInfo(
        displayName = displayName.ifBlank { "Imported Voice" },
        sizeBytes = sizeBytes,
    )
}
