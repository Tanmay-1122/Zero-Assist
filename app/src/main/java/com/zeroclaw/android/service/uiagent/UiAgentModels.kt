/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.uiagent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Sanitized point-in-time view of the current Android UI surface. */
@Serializable
data class UiSnapshot(
    val capturedAtEpochMs: Long,
    val foregroundPackageName: String? = null,
    val foregroundWindowTitle: String? = null,
    val rootNodeIds: List<String> = emptyList(),
    val nodes: List<UiNode> = emptyList(),
) {
    val hasRoot: Boolean
        get() = rootNodeIds.isNotEmpty()
}

/** Flat, prompt-safe UI node model. Parent/child links preserve tree shape without recursion. */
@Serializable
data class UiNode(
    val id: String,
    val parentId: String? = null,
    val packageName: String? = null,
    val className: String? = null,
    val viewIdResourceName: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val boundsInScreen: UiBounds? = null,
    val actions: List<UiNodeAction> = emptyList(),
    val childIds: List<String> = emptyList(),
    val enabled: Boolean = false,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val focused: Boolean = false,
    val selected: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val visibleToUser: Boolean = true,
    val sensitive: Boolean = false,
)

@Serializable
data class UiBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
) {
    val width: Int
        get() = (right - left).coerceAtLeast(0)

    val height: Int
        get() = (bottom - top).coerceAtLeast(0)
}

/** Accessibility primitives the UI agent may ask the executor to perform later. */
@Serializable
enum class UiNodeAction {
    CLICK,
    LONG_CLICK,
    SET_TEXT,
    FOCUS,
    CLEAR_FOCUS,
    SCROLL_FORWARD,
    SCROLL_BACKWARD,
    EXPAND,
    COLLAPSE,
}

@Serializable
enum class UiAgentGlobalAction {
    @SerialName("back")
    BACK,

    @SerialName("home")
    HOME,

    @SerialName("recents")
    RECENTS,

    @SerialName("notifications")
    NOTIFICATIONS,

    @SerialName("quick_settings")
    QUICK_SETTINGS,
}

@Serializable
enum class UiAgentScrollDirection {
    @SerialName("forward")
    FORWARD,

    @SerialName("backward")
    BACKWARD,
}

/** User intent given to the UI agent. */
@Serializable
sealed interface UiAgentGoal {
    @Serializable
    @SerialName("generic")
    data class Generic(
        val instruction: String,
        val targetPackageName: String? = null,
        val targetAppQuery: String? = null,
    ) : UiAgentGoal

    @Serializable
    @SerialName("send_message")
    data class SendMessage(
        val message: String,
        val recipient: String? = null,
        val targetPackageName: String? = null,
        val targetAppQuery: String? = null,
    ) : UiAgentGoal
}

/** Final structured decision returned by an AI UI planner. */
@Serializable
data class UiAgentDecision(
    val action: UiAgentAction,
    val expectedState: UiExpectedState? = null,
    val rationale: String? = null,
    val confidence: Float = 0f,
) {
    fun normalized(): UiAgentDecision =
        copy(
            confidence = confidence.coerceIn(0f, 1f),
            rationale = rationale?.trim()?.takeIf { it.isNotEmpty() },
        )
}

/** Planner action. A later executor can translate these to accessibility bridge commands. */
@Serializable
sealed interface UiAgentAction {
    @Serializable
    @SerialName("tap_node")
    data class TapNode(
        val nodeId: String,
    ) : UiAgentAction

    @Serializable
    @SerialName("set_text")
    data class SetText(
        val nodeId: String,
        val text: String,
    ) : UiAgentAction

    @Serializable
    @SerialName("scroll_node")
    data class ScrollNode(
        val nodeId: String,
        val direction: UiAgentScrollDirection = UiAgentScrollDirection.FORWARD,
    ) : UiAgentAction

    @Serializable
    @SerialName("open_package")
    data class OpenPackage(
        val packageName: String,
    ) : UiAgentAction

    @Serializable
    @SerialName("press_global")
    data class PressGlobal(
        val action: UiAgentGlobalAction,
    ) : UiAgentAction

    @Serializable
    @SerialName("wait")
    data class Wait(
        val expectedState: UiExpectedState? = null,
        val timeoutMs: Long = DEFAULT_WAIT_TIMEOUT_MS,
    ) : UiAgentAction

    @Serializable
    @SerialName("noop")
    data class NoOp(
        val reason: String,
    ) : UiAgentAction

    @Serializable
    @SerialName("abort")
    data class Abort(
        val reason: String,
    ) : UiAgentAction

    @Serializable
    @SerialName("transient_failure")
    data class TransientFailure(
        val reason: String,
    ) : UiAgentAction
}

/** State the planner expects after an action. */
@Serializable
sealed interface UiExpectedState {
    @Serializable
    @SerialName("foreground_package")
    data class ForegroundPackage(
        val packageName: String,
    ) : UiExpectedState

    @Serializable
    @SerialName("text_visible")
    data class TextVisible(
        val text: String,
        val packageName: String? = null,
    ) : UiExpectedState

    @Serializable
    @SerialName("node_available")
    data class NodeAvailable(
        val nodeId: String,
        val packageName: String? = null,
        val viewIdResourceName: String? = null,
        val text: String? = null,
        val contentDescription: String? = null,
    ) : UiExpectedState

    @Serializable
    @SerialName("root_ready")
    data object RootReady : UiExpectedState
}

/** JSON prompt envelope for model calls. */
@Serializable
data class UiPrompt(
    val goal: UiAgentGoal,
    val snapshot: UiSnapshot,
    val previousDecisions: List<UiAgentDecision> = emptyList(),
    val expectedState: UiExpectedState? = null,
    val safetyNotes: List<String> = DEFAULT_SAFETY_NOTES,
)

private const val DEFAULT_WAIT_TIMEOUT_MS = 5_000L

private val DEFAULT_SAFETY_NOTES =
    listOf(
        "Use only visible sanitized UI node data.",
        "Do not infer passwords, one-time codes, or hidden sensitive values.",
        "Prefer reversible actions unless the user goal explicitly requires sending or submitting.",
    )
