package com.zeroclaw.android.service.devicecontrol

import android.graphics.Rect

data class UiNodeSnapshot(
    val index: Int,
    val text: String,
    val contentDescription: String,
    val className: String,
    val viewId: String,
    val clickable: Boolean,
    val editable: Boolean,
    val scrollable: Boolean,
    val checkable: Boolean,
    val checked: Boolean,
    val focused: Boolean,
    val focusable: Boolean,
    val enabled: Boolean,
    val bounds: Rect,
    val depth: Int,
    val supportedActions: Set<Int> = emptySet(),
) {
    val label: String get() = text.ifBlank { contentDescription }
    val centerX: Int get() = bounds.centerX()
    val centerY: Int get() = bounds.centerY()

    /** Compact single-line representation for the prompt. */
    fun toCompactString(highlight: Boolean = false): String = buildString {
        append("[${index}]")
        if (highlight) append("*")
        append(" $className")
        if (label.isNotBlank()) append(" \"$label\"")
        val tags = buildList {
            if (clickable) add("click")
            if (editable) add("edit")
            if (scrollable) add("scroll")
            if (checkable) add(if (checked) "checked" else "unchecked")
            if (focused) add("focused")
            if (!enabled) add("disabled")
        }
        if (tags.isNotEmpty()) append(" [${tags.joinToString(",")}]")
        append(" (${centerX},${centerY})")
        if (viewId.isNotBlank()) append(" id:${viewId.substringAfterLast('/')}")
        if (supportedActions.isNotEmpty()) {
            val actionNames = supportedActions.mapNotNull { ACCESSIBILITY_ACTION_NAMES[it] }
            if (actionNames.isNotEmpty()) append(" acts:${actionNames.joinToString(",")}")
        }
    }

    companion object {
        private val ACCESSIBILITY_ACTION_NAMES = mapOf(
            16 to "click",
            32 to "longclick",
            64 to "focus",
            128 to "select",
            256 to "clear",
            2048 to "cut",
            4096 to "copy",
            8192 to "paste",
            16384 to "scrollf",
            32768 to "scrollb",
            2097152 to "settext",
            1048576 to "imeenter",
        )
    }
}

sealed interface DeviceAction {
    data class ClickText(val text: String) : DeviceAction
    data class ClickIndex(val index: Int) : DeviceAction
    data class ClickAt(val x: Float, val y: Float) : DeviceAction
    data class TypeText(val text: String, val fieldHint: String? = null) : DeviceAction
    data object PressEnter : DeviceAction
    data class Scroll(val direction: Direction) : DeviceAction
    data class Swipe(
        val startX: Float, val startY: Float,
        val endX: Float, val endY: Float,
        val durationMs: Long = 350
    ) : DeviceAction
    data object Back : DeviceAction
    data object Home : DeviceAction
    data object Recents : DeviceAction
    data object Notifications : DeviceAction
    data class OpenApp(val appName: String, val packageName: String? = null) : DeviceAction
    data class Wait(val millis: Long = 1_000) : DeviceAction
    data class ShareFile(val uri: String, val mimeType: String? = null, val targetPackage: String? = null) : DeviceAction
    data class Done(val message: String = "Done") : DeviceAction

    enum class Direction { UP, DOWN }
}

data class PlannerRequest(
    val requestId: String,
    val goal: String,
    val step: Int,
    val maxSteps: Int,
    val currentPackage: String?,
    val screen: String,
    val previousAction: String?,
    val previousResult: String?,
    val failureCount: Int,
    val actionHistory: List<String> = emptyList(),
    val taskContext: TaskContext? = null,
)

data class PlannerDecision(
    val action: DeviceAction,
    val reasoning: String = "",
    val isComplete: Boolean = false,
    val followUpActions: List<DeviceAction> = emptyList(),
)

fun interface DeviceControlPlanner {
    suspend fun nextAction(request: PlannerRequest): PlannerDecision
}

sealed interface DeviceControlResult {
    data class Success(val message: String, val steps: Int) : DeviceControlResult
    data class Failure(
        val message: String,
        val steps: Int,
        val cause: Throwable? = null,
        val errorCode: ErrorCode? = null,
        val retryable: Boolean = false,
    ) : DeviceControlResult
    data class Cancelled(val steps: Int) : DeviceControlResult

    enum class ErrorCode {
        ACCESSIBILITY_DISABLED,
        ACCESSIBILITY_NOT_CONNECTED,
        NO_ACTIVE_WINDOW,
        APP_NOT_FOUND,
        APP_NOT_LAUNCHABLE,
        APP_LAUNCH_FAILED,
        APP_LAUNCH_FOREGROUND_MISMATCH,
        APP_RESOLUTION_FAILED,
        PLANNER_FAILED,
        ACTION_FAILED,
        LOOP_DETECTED,
        STUCK,
        MAX_STEPS_REACHED,
        CANCELLED,
        INTERNAL_ERROR,
    }
}
