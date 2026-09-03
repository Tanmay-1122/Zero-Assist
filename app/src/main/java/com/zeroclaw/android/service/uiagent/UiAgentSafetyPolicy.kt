/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.uiagent

/** Conservative guardrail for planner output before it reaches an Android bridge. */
class UiAgentSafetyPolicy(
    private val messageSendVerifier: UiMessageSendVerifier = UiMessageSendVerifier(),
) {
    fun evaluate(
        action: UiAgentAction,
        snapshot: UiSnapshot,
        goal: UiAgentGoal,
    ): UiAgentSafetyResult {
        val targetNode = action.targetNodeId()?.let { nodeId -> snapshot.nodeById(nodeId) }
        if (
            targetNode?.sensitive == true ||
            targetNode.hasSensitiveToken() ||
            action.targetNodeId().containsSensitiveToken()
        ) {
            return UiAgentSafetyResult.Blocked("Action targets a sensitive UI node.")
        }
        val draftWriteVerification =
            verifyMessageDraftWrite(
                action = action,
                targetNode = targetNode,
                snapshot = snapshot,
                goal = goal,
            )
        if (draftWriteVerification is UiMessageSendVerification.Blocked) {
            return UiAgentSafetyResult.Blocked(draftWriteVerification.reason)
        }
        if (requiresConfirmation(action = action, snapshot = snapshot, goal = goal)) {
            return UiAgentSafetyResult.Blocked(
                "Action requires user confirmation, but confirmation is not available yet.",
            )
        }

        return UiAgentSafetyResult.Allowed
    }

    @Suppress("UNUSED_PARAMETER")
    fun requiresConfirmation(
        action: UiAgentAction,
        snapshot: UiSnapshot,
        goal: UiAgentGoal,
    ): Boolean {
        val targetNode =
            action.targetNodeId()
                ?.let { nodeId -> snapshot.nodeById(nodeId) }
                ?: return false

        if (action !is UiAgentAction.TapNode || !targetNode.hasRiskyActivationLabel()) {
            return false
        }

        return !targetNode.isAllowedExplicitSendAction(snapshot = snapshot, goal = goal)
    }

    private fun UiNode.hasRiskyActivationLabel(): Boolean =
        labels()
            .any { value ->
                RISKY_CONFIRMATION_TOKENS.any { token ->
                    value.contains(token, ignoreCase = true)
                }
            }

    private fun UiNode.isAllowedExplicitSendAction(
        snapshot: UiSnapshot,
        goal: UiAgentGoal,
    ): Boolean {
        val sendGoal = goal as? UiAgentGoal.SendMessage ?: return false
        if (!hasSendActivationLabel() || hasNonSendRiskyActivationLabel()) {
            return false
        }
        val verification = messageSendVerifier.verify(snapshot = snapshot, goal = sendGoal)
        return verification is UiMessageSendVerification.Allowed
    }

    private fun verifyMessageDraftWrite(
        action: UiAgentAction,
        targetNode: UiNode?,
        snapshot: UiSnapshot,
        goal: UiAgentGoal,
    ): UiMessageSendVerification {
        val sendGoal = goal as? UiAgentGoal.SendMessage ?: return UiMessageSendVerification.Allowed
        val setText = action as? UiAgentAction.SetText ?: return UiMessageSendVerification.Allowed
        return messageSendVerifier.verifyDraftTarget(
            snapshot = snapshot,
            targetNode = targetNode,
            goal = sendGoal,
            text = setText.text,
        )
    }

    private fun UiNode.hasSendActivationLabel(): Boolean =
        labels().any { value -> value.contains("send", ignoreCase = true) }

    private fun UiNode.hasNonSendRiskyActivationLabel(): Boolean =
        labels()
            .any { value ->
                RISKY_CONFIRMATION_TOKENS
                    .filterNot { token -> token == "send" }
                    .any { token -> value.contains(token, ignoreCase = true) }
            }

    private fun UiNode.labels(): List<String> =
        listOfNotNull(text, contentDescription, viewIdResourceName)

    private fun UiNode?.hasSensitiveToken(): Boolean =
        this
            ?.labels()
            ?.any { label -> label.containsSensitiveToken() }
            ?: false

    private fun String?.containsSensitiveToken(): Boolean {
        val normalized = this?.lowercase().orEmpty()
        return SENSITIVE_TOKENS.any { token -> normalized.contains(token) }
    }

    private fun UiSnapshot.nodeById(nodeId: String): UiNode? =
        nodes.firstOrNull { node -> node.id == nodeId }

    private fun UiAgentAction.targetNodeId(): String? =
        when (this) {
            is UiAgentAction.TapNode -> nodeId
            is UiAgentAction.SetText -> nodeId
            is UiAgentAction.ScrollNode -> nodeId
            is UiAgentAction.Abort,
            is UiAgentAction.NoOp,
            is UiAgentAction.TransientFailure,
            is UiAgentAction.OpenPackage,
            is UiAgentAction.PressGlobal,
            is UiAgentAction.Wait,
            -> null
        }

    private companion object {
        val SENSITIVE_TOKENS =
            listOf(
                "password",
                "passcode",
                "pin",
                "otp",
                "one_time",
                "secret",
                "token",
                "payment",
                "card",
                "cvv",
            )

        val RISKY_CONFIRMATION_TOKENS =
            listOf(
                "send",
                "submit",
                "post",
                "share",
                "confirm",
                "delete",
                "pay",
                "purchase",
                "buy",
            )
    }
}

sealed interface UiAgentSafetyResult {
    data object Allowed : UiAgentSafetyResult

    data class Blocked(
        val reason: String,
    ) : UiAgentSafetyResult
}
