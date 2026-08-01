/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow

private const val MISSING_WAKEUP_DETECTOR_MESSAGE =
    "Background wake word is unavailable until a local detector is bundled."

/** Local wake-word detector capability exposed to app UI and future foreground-service plumbing. */
data class VoiceWakeupDetectorStatus(
    val available: Boolean,
    val foregroundServiceReady: Boolean,
    val requiresRecordAudioPermission: Boolean,
    val message: String,
) {
    companion object {
        val Unavailable: VoiceWakeupDetectorStatus =
            VoiceWakeupDetectorStatus(
                available = false,
                foregroundServiceReady = false,
                requiresRecordAudioPermission = true,
                message = MISSING_WAKEUP_DETECTOR_MESSAGE,
            )
    }
}

/** Result for guarded foreground wake-up startup attempts. */
sealed interface VoiceWakeupStartResult {
    data object Started : VoiceWakeupStartResult

    data class Unavailable(
        val message: String,
    ) : VoiceWakeupStartResult

    data class Failed(
        val message: String,
    ) : VoiceWakeupStartResult
}

/**
 * Boundary for a future local foreground wake-word service.
 *
 * Implementations must remain on-device and must not start listening unless [status] reports
 * both local detector availability and foreground-service readiness.
 */
interface VoiceWakeupDetector {
    val status: Flow<VoiceWakeupDetectorStatus>

    val wakeEvents: Flow<Unit>
        get() = emptyFlow()

    suspend fun startForegroundWakeup(): VoiceWakeupStartResult

    suspend fun stopForegroundWakeup()
}

/** Fail-closed detector used until a real local hotword engine is bundled. */
class MissingVoiceWakeupDetector : VoiceWakeupDetector {
    override val status: Flow<VoiceWakeupDetectorStatus> =
        MutableStateFlow(VoiceWakeupDetectorStatus.Unavailable)

    override suspend fun startForegroundWakeup(): VoiceWakeupStartResult =
        VoiceWakeupStartResult.Unavailable(VoiceWakeupDetectorStatus.Unavailable.message)

    override suspend fun stopForegroundWakeup() = Unit
}
