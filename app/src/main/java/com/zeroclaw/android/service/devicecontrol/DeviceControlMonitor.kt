/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.devicecontrol

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean

enum class DeviceControlStatus {
    IDLE,
    INITIALIZING,
    PLANNING,
    EXECUTING,
    COMPLETED,
    FAILED,
    CANCELLED,
}

data class DeviceControlStepLog(
    val stepIndex: Int,
    val actionDescription: String,
    val isSuccess: Boolean = true,
    val details: String? = null,
    val timestampMs: Long = System.currentTimeMillis(),
)

data class DeviceControlState(
    val isActive: Boolean = false,
    val goal: String = "",
    val currentStep: Int = 0,
    val maxSteps: Int = 30,
    val currentAction: String = "Initializing screen control...",
    val status: DeviceControlStatus = DeviceControlStatus.IDLE,
    val stepLogs: List<DeviceControlStepLog> = emptyList(),
    val errorMessage: String? = null,
    val completionMessage: String? = null,
)

object DeviceControlMonitor {
    private val _state = MutableStateFlow(DeviceControlState())
    val state: StateFlow<DeviceControlState> = _state.asStateFlow()

    private val cancellationRequested = AtomicBoolean(false)

    fun onControlStarted(goal: String, maxSteps: Int) {
        cancellationRequested.set(false)
        _state.value = DeviceControlState(
            isActive = true,
            goal = goal,
            currentStep = 0,
            maxSteps = maxSteps,
            currentAction = "Initializing screen control...",
            status = DeviceControlStatus.INITIALIZING,
            stepLogs = emptyList(),
            errorMessage = null,
            completionMessage = null,
        )
    }

    fun onStepStarted(stepIndex: Int, actionDescription: String) {
        _state.update { curr ->
            curr.copy(
                currentStep = stepIndex,
                currentAction = actionDescription,
                status = DeviceControlStatus.EXECUTING,
            )
        }
    }

    fun onStepFinished(stepIndex: Int, actionDescription: String, isSuccess: Boolean, details: String? = null) {
        _state.update { curr ->
            val log = DeviceControlStepLog(
                stepIndex = stepIndex,
                actionDescription = actionDescription,
                isSuccess = isSuccess,
                details = details,
            )
            curr.copy(
                stepLogs = curr.stepLogs + log,
            )
        }
    }

    fun onControlCompleted(message: String) {
        _state.update { curr ->
            curr.copy(
                status = DeviceControlStatus.COMPLETED,
                currentAction = message,
                completionMessage = message,
            )
        }
    }

    fun onControlFailed(error: String) {
        _state.update { curr ->
            curr.copy(
                status = DeviceControlStatus.FAILED,
                currentAction = "Failed: $error",
                errorMessage = error,
            )
        }
    }

    fun onControlCancelled() {
        cancellationRequested.set(true)
        _state.update { curr ->
            curr.copy(
                status = DeviceControlStatus.CANCELLED,
                currentAction = "Device control cancelled",
                errorMessage = "Cancelled by user",
            )
        }
    }

    fun requestCancel() {
        cancellationRequested.set(true)
        _state.update { curr ->
            if (curr.isActive) {
                curr.copy(
                    status = DeviceControlStatus.CANCELLED,
                    currentAction = "Cancelling device control...",
                )
            } else curr
        }
    }

    fun isCancellationRequested(): Boolean = cancellationRequested.get()

    fun reset() {
        cancellationRequested.set(false)
        _state.value = DeviceControlState()
    }
}

fun DeviceAction.toHumanDescription(): String = when (this) {
    is DeviceAction.ClickText -> "Clicking text \"$text\""
    is DeviceAction.ClickIndex -> "Clicking element #$index"
    is DeviceAction.ClickAt -> "Tapping screen ($x, $y)"
    is DeviceAction.TypeText -> if (!fieldHint.isNullOrBlank()) "Typing \"$text\" into field" else "Typing \"$text\""
    is DeviceAction.PressEnter -> "Pressing Enter key"
    is DeviceAction.Scroll -> "Scrolling ${direction.name.lowercase()}"
    is DeviceAction.Swipe -> "Swiping on screen"
    is DeviceAction.Back -> "Pressing Back button"
    is DeviceAction.Home -> "Pressing Home button"
    is DeviceAction.Recents -> "Opening Recent Apps"
    is DeviceAction.Notifications -> "Opening Notification Shade"
    is DeviceAction.OpenApp -> "Opening app \"$appName\""
    is DeviceAction.Wait -> "Waiting ${millis}ms"
    is DeviceAction.ShareFile -> "Sharing file $uri"
    is DeviceAction.Done -> "Completed: $message"
}
