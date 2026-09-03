/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.model

import com.zeroclaw.android.service.DeviceAction
import com.zeroclaw.android.service.uiagent.UiAgentGoal

/** Source for a local assistant voice model. */
enum class VoiceModelSource {
    CATALOG,
    IMPORTED,
}

/** Install lifecycle for an offline voice model. */
sealed interface VoiceModelStatus {
    data object AvailableForDownload : VoiceModelStatus

    data class Downloading(
        val bytesDownloaded: Long = 0L,
        val totalBytes: Long? = null,
    ) : VoiceModelStatus

    data object Installed : VoiceModelStatus

    data class Failed(val reason: String) : VoiceModelStatus
}

/** Metadata for a voice that can be used by the local assistant popup. */
data class VoiceModel(
    val id: String,
    val displayName: String,
    val toneLabel: String,
    val localeTag: String,
    val description: String,
    val sizeBytes: Long,
    val source: VoiceModelSource,
    val status: VoiceModelStatus,
    val sampleText: String,
    val modelUri: String? = null,
    val downloadUri: String? = null,
    val packageSha256: String? = null,
) {
    val isEnglish: Boolean
        get() = localeTag.startsWith("en", ignoreCase = true)
}

/** Runtime phase for the assistant popup. */
enum class VoiceAssistantSessionState {
    MissingVoice,
    Idle,
    Listening,
    Processing,
    Speaking,
}

/** App-shell state consumed by the assistant popup UI. */
data class VoiceAssistantUiState(
    val popupVisible: Boolean = false,
    val sessionState: VoiceAssistantSessionState = VoiceAssistantSessionState.MissingVoice,
    val selectedVoice: VoiceModel? = null,
    val wakeupEnabled: Boolean = false,
    val wakeupAvailable: Boolean = false,
    val wakeupStatusMessage: String = "Background wake word is unavailable until a local detector is bundled.",
    val speechRecognitionAvailable: Boolean = true,
    val speechRecognitionStatusMessage: String? = null,
    val partialTranscript: String = "",
    val lastTranscript: String = "",
    val statusMessage: String? = null,
) {
    val canListen: Boolean
        get() = selectedVoice != null && speechRecognitionAvailable
}

/** One-shot voice command captured locally and ready for the main app pipeline. */
data class VoiceAssistantCommand(
    val text: String,
    val phoneDialUri: String? = null,
    val deviceAction: VoiceAssistantDeviceAction? = null,
    val localDeviceAction: DeviceAction? = null,
    val uiAgentGoal: UiAgentGoal? = null,
)

/** Local device action requested by the voice assistant. */
sealed interface VoiceAssistantDeviceAction {
    data class SetAlarm(
        val hour: Int,
        val minute: Int,
        val message: String,
    ) : VoiceAssistantDeviceAction

    data class SetTimer(
        val lengthSeconds: Int,
        val message: String,
    ) : VoiceAssistantDeviceAction

    data class CreateReminder(
        val title: String,
        val triggerAtEpochMillis: Long,
        val durationMinutes: Int = 15,
    ) : VoiceAssistantDeviceAction
}

/** Playback state for voice previews and assistant speech output. */
sealed interface VoicePreviewState {
    data object Idle : VoicePreviewState

    data class Speaking(
        val voiceId: String,
    ) : VoicePreviewState

    data class Completed(
        val voiceId: String,
    ) : VoicePreviewState

    data class Unavailable(
        val message: String,
    ) : VoicePreviewState

    data class Failed(
        val message: String,
    ) : VoicePreviewState
}

/** Import state for copying custom voice files into app-private storage. */
sealed interface VoiceImportState {
    data object Idle : VoiceImportState

    data object Importing : VoiceImportState

    data class Imported(
        val displayName: String,
    ) : VoiceImportState

    data class Failed(
        val message: String,
    ) : VoiceImportState
}
