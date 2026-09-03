/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface VoiceMicSessionState {
    data object Idle : VoiceMicSessionState

    data object WakeListening : VoiceMicSessionState

    data object PopupListening : VoiceMicSessionState

    data class Recovering(
        val reason: String,
    ) : VoiceMicSessionState
}

data class VoicePopupMicStartResult(
    val wakeupPaused: Boolean,
    val wakeupServiceStarted: Boolean,
    val failureMessage: String? = null,
)

data class VoicePopupMicFinishResult(
    val wakeupServiceStarted: Boolean,
    val failureMessage: String? = null,
)

/**
 * Coordinates foreground dictation and wake listening ownership of RECORD_AUDIO.
 *
 * This intentionally stays Android-light: platform audio focus and app-op checks
 * can sit around it, while the ownership state remains deterministic and easy
 * to unit test.
 */
class VoiceMicSessionCoordinator {
    private val _state = MutableStateFlow<VoiceMicSessionState>(VoiceMicSessionState.Idle)
    private var wakeupPausedForPopup = false

    val state: StateFlow<VoiceMicSessionState> = _state.asStateFlow()

    fun isPopupListening(): Boolean = _state.value == VoiceMicSessionState.PopupListening

    fun deferWakeupUntilPopupEnds() {
        if (isPopupListening()) {
            wakeupPausedForPopup = true
        }
    }

    fun noteWakeupStarted() {
        if (_state.value == VoiceMicSessionState.Idle) {
            _state.value = VoiceMicSessionState.WakeListening
        }
    }

    fun noteWakeupStopped() {
        if (_state.value == VoiceMicSessionState.WakeListening) {
            _state.value = VoiceMicSessionState.Idle
        }
        wakeupPausedForPopup = false
    }

    fun beginPopupDictation(
        wakeupRequested: Boolean,
        wakeupServiceStarted: Boolean,
        stopWakeup: () -> VoiceWakeupServiceCommandResult,
        trace: VoiceTurnTrace? = null,
    ): VoicePopupMicStartResult {
        if (_state.value == VoiceMicSessionState.PopupListening) {
            trace?.mark("mic_owner_popup_reused")
            return VoicePopupMicStartResult(
                wakeupPaused = wakeupPausedForPopup,
                wakeupServiceStarted = wakeupServiceStarted,
            )
        }

        if (!wakeupRequested) {
            _state.value = VoiceMicSessionState.PopupListening
            trace?.mark("wakeup_pause_skipped", "requested=false")
            trace?.mark("mic_owner_popup")
            return VoicePopupMicStartResult(
                wakeupPaused = false,
                wakeupServiceStarted = wakeupServiceStarted,
            )
        }

        trace?.mark("wakeup_pause_requested")
        return when (val result = stopWakeup()) {
            VoiceWakeupServiceCommandResult.Accepted -> {
                wakeupPausedForPopup = true
                _state.value = VoiceMicSessionState.PopupListening
                trace?.mark("wakeup_paused")
                trace?.mark("mic_owner_popup")
                VoicePopupMicStartResult(
                    wakeupPaused = true,
                    wakeupServiceStarted = false,
                )
            }
            is VoiceWakeupServiceCommandResult.Failed -> {
                _state.value = VoiceMicSessionState.Recovering(result.message)
                trace?.mark("wakeup_pause_failed", result.message)
                VoicePopupMicStartResult(
                    wakeupPaused = false,
                    wakeupServiceStarted = wakeupServiceStarted,
                    failureMessage = result.message,
                )
            }
        }
    }

    fun finishPopupDictation(
        resumeWakeup: Boolean,
        wakeupRequested: Boolean,
        startWakeup: () -> VoiceWakeupServiceCommandResult,
        trace: VoiceTurnTrace? = null,
    ): VoicePopupMicFinishResult {
        val shouldResumeWakeup = resumeWakeup && wakeupPausedForPopup && wakeupRequested
        wakeupPausedForPopup = false

        if (!shouldResumeWakeup) {
            _state.value = VoiceMicSessionState.Idle
            return VoicePopupMicFinishResult(wakeupServiceStarted = false)
        }

        trace?.mark("wakeup_resume_requested")
        return when (val result = startWakeup()) {
            VoiceWakeupServiceCommandResult.Accepted -> {
                _state.value = VoiceMicSessionState.WakeListening
                trace?.mark("wakeup_resumed")
                VoicePopupMicFinishResult(wakeupServiceStarted = true)
            }
            is VoiceWakeupServiceCommandResult.Failed -> {
                _state.value = VoiceMicSessionState.Recovering(result.message)
                trace?.mark("wakeup_resume_failed", result.message)
                VoicePopupMicFinishResult(
                    wakeupServiceStarted = false,
                    failureMessage = result.message,
                )
            }
        }
    }
}
