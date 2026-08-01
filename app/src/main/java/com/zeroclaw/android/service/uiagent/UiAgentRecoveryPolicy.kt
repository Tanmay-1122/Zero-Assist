/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.uiagent

/**
 * Classifies transient UI-agent failures and suggests reversible recovery only.
 *
 * The policy never executes actions. The loop remains responsible for applying the suggestion,
 * refreshing state, and re-running planner + safety checks before continuing.
 */
class UiAgentRecoveryPolicy {
    fun planRecovery(
        failure: UiAgentRecoverableFailure,
        snapshot: UiSnapshot?,
        decision: UiAgentDecision?,
        goal: UiAgentGoal,
    ): UiAgentRecoveryDecision {
        val reason = failure.reason.orEmpty()
        val normalizedReason = reason.lowercase()
        if (failure.isTerminal() || normalizedReason.isTerminalSafetyFailure()) {
            return UiAgentRecoveryDecision.Terminal(reason.sanitizeReason())
        }
        if (decision?.isRiskyIrreversible(snapshot) == true) {
            return UiAgentRecoveryDecision.Terminal(
                "Recovery is disabled after a potentially irreversible action.",
            )
        }

        val action =
            when {
                failure.failureReason == UiAgentLoopFailureReason.MISSING_SNAPSHOT ->
                    UiAgentRecoveryAction.WaitForFreshSnapshot("UI snapshot is unavailable.")

                normalizedReason.containsAny(WRONG_CONVERSATION_TOKENS) ->
                    UiAgentRecoveryAction.PressBack("Wrong conversation detected.")

                normalizedReason.containsAny(KEYBOARD_TOKENS) ->
                    UiAgentRecoveryAction.HideKeyboard("Keyboard appears to cover the target.")

                normalizedReason.containsAny(FOREGROUND_OR_ROOT_TOKENS) ->
                    UiAgentRecoveryAction.WaitForFreshSnapshot("Foreground app or root is not ready.")

                normalizedReason.containsAny(EDITABLE_TOKENS) ->
                    UiAgentRecoveryAction.RefreshSnapshot("Editable target may not be ready yet.")

                normalizedReason.containsAny(SEARCH_RESULT_TOKENS) ->
                    snapshot?.scrollableResultListAction()
                        ?: UiAgentRecoveryAction.RefreshSnapshot("Search result is not visible yet.")

                normalizedReason.containsAny(STALE_NODE_TOKENS) ->
                    UiAgentRecoveryAction.RefreshSnapshot("Planner target may be from a stale snapshot.")

                failure is UiAgentRecoverableFailure.Execution && failure.retryable ->
                    UiAgentRecoveryAction.RefreshSnapshot("Execution failed with a retryable device-state error.")

                failure is UiAgentRecoverableFailure.Verification ->
                    UiAgentRecoveryAction.WaitForFreshSnapshot("Expected UI state was not observed yet.")

                failure is UiAgentRecoverableFailure.Transient ->
                    UiAgentRecoveryAction.WaitForFreshSnapshot("Planner encountered a transient failure; retrying.")

                else -> null
            }

        return if (action == null) {
            UiAgentRecoveryDecision.Terminal(reason.sanitizeReason())
        } else {
            UiAgentRecoveryDecision.Retry(action)
        }
    }

    private fun UiAgentRecoverableFailure.isTerminal(): Boolean =
        when (this) {
            is UiAgentRecoverableFailure.Safety -> true
            is UiAgentRecoverableFailure.Execution -> !retryable
            is UiAgentRecoverableFailure.Transient -> false
            is UiAgentRecoverableFailure.Validation,
            is UiAgentRecoverableFailure.Verification,
            -> false
        }

    private fun UiAgentDecision.isRiskyIrreversible(snapshot: UiSnapshot?): Boolean {
        val node =
            when (val selectedAction = action) {
                is UiAgentAction.TapNode ->
                    snapshot?.nodes?.firstOrNull { candidate -> candidate.id == selectedAction.nodeId }
                else -> null
            } ?: return false

        val targetText =
            listOfNotNull(
                node.text,
                node.contentDescription,
                node.viewIdResourceName,
            ).joinToString(separator = " ").lowercase()
        return targetText.containsAny(IRREVERSIBLE_TOKENS)
    }

    private fun UiSnapshot.scrollableResultListAction(): UiAgentRecoveryAction.ScrollResultList? {
        val scrollNode =
            nodes.firstOrNull { node ->
                node.visibleToUser &&
                    node.enabled &&
                    node.boundsInScreen != null &&
                    (UiNodeAction.SCROLL_FORWARD in node.actions || UiNodeAction.SCROLL_BACKWARD in node.actions)
            } ?: return null
        val direction =
            if (UiNodeAction.SCROLL_FORWARD in scrollNode.actions) {
                UiAgentScrollDirection.FORWARD
            } else {
                UiAgentScrollDirection.BACKWARD
            }
        return UiAgentRecoveryAction.ScrollResultList(
            nodeId = scrollNode.id,
            direction = direction,
            reason = "Search result is not visible; scrolling results.",
        )
    }

    private fun String.isTerminalSafetyFailure(): Boolean =
        containsAny(TERMINAL_SAFETY_TOKENS)

    private fun String.containsAny(tokens: Collection<String>): Boolean =
        tokens.any { token -> contains(token) }

    private fun String.sanitizeReason(): String =
        UiTextSanitizer.sanitize(this) ?: "UI-agent recovery stopped."

    private companion object {
        val STALE_NODE_TOKENS =
            listOf(
                "not found",
                "no longer available",
                "stale",
                "missing node",
            )
        val EDITABLE_TOKENS =
            listOf(
                "editable",
                "text field",
                "set text",
            )
        val SEARCH_RESULT_TOKENS =
            listOf(
                "search result",
                "recipient is not visible",
                "contact row",
            )
        val FOREGROUND_OR_ROOT_TOKENS =
            listOf(
                "foreground",
                "root",
                "opens slowly",
                "did not reach",
                "not ready",
            )
        val WRONG_CONVERSATION_TOKENS =
            listOf(
                "wrong conversation",
                "wrong active chat",
                "wrong chat",
            )
        val KEYBOARD_TOKENS =
            listOf(
                "keyboard",
                "ime",
                "covered",
                "obscured",
            )
        val TERMINAL_SAFETY_TOKENS =
            listOf(
                "unsafe",
                "sensitive",
                "ambiguous",
                "wrong draft",
                "draft mismatch",
                "unexpected draft",
                "extra draft",
                "payment",
                "delete",
                "destructive",
                "irreversible",
            )
        val IRREVERSIBLE_TOKENS =
            listOf(
                "send",
                "submit",
                "confirm",
                "delete",
                "remove",
                "pay",
                "payment",
                "purchase",
                "transfer",
            )
    }
}

sealed interface UiAgentRecoverableFailure {
    val reason: String?
    val failureReason: UiAgentLoopFailureReason

    data class Validation(
        override val reason: String,
        override val failureReason: UiAgentLoopFailureReason,
    ) : UiAgentRecoverableFailure

    data class Safety(
        override val reason: String,
    ) : UiAgentRecoverableFailure {
        override val failureReason: UiAgentLoopFailureReason = UiAgentLoopFailureReason.UNSAFE_ACTION
    }

    data class Execution(
        override val reason: String,
        val retryable: Boolean,
    ) : UiAgentRecoverableFailure {
        override val failureReason: UiAgentLoopFailureReason = UiAgentLoopFailureReason.EXECUTION_FAILED
    }

    data class Verification(
        override val reason: String?,
        override val failureReason: UiAgentLoopFailureReason,
    ) : UiAgentRecoverableFailure

    data class Transient(
        override val reason: String,
    ) : UiAgentRecoverableFailure {
        override val failureReason: UiAgentLoopFailureReason = UiAgentLoopFailureReason.TRANSIENT_PLANNER_FAILURE
    }
}

sealed interface UiAgentRecoveryDecision {
    data class Retry(
        val action: UiAgentRecoveryAction,
    ) : UiAgentRecoveryDecision

    data class Terminal(
        val reason: String?,
    ) : UiAgentRecoveryDecision
}

sealed interface UiAgentRecoveryAction {
    val reason: String
    val diagnosticName: String

    data class RefreshSnapshot(
        override val reason: String,
    ) : UiAgentRecoveryAction {
        override val diagnosticName: String = "refresh_snapshot"
    }

    data class WaitForFreshSnapshot(
        override val reason: String,
    ) : UiAgentRecoveryAction {
        override val diagnosticName: String = "wait_for_fresh_snapshot"
    }

    data class PressBack(
        override val reason: String,
    ) : UiAgentRecoveryAction {
        override val diagnosticName: String = "press_back"
    }

    data class HideKeyboard(
        override val reason: String,
    ) : UiAgentRecoveryAction {
        override val diagnosticName: String = "hide_keyboard"
    }

    data class ScrollResultList(
        val nodeId: String,
        val direction: UiAgentScrollDirection,
        override val reason: String,
    ) : UiAgentRecoveryAction {
        override val diagnosticName: String = "scroll_result_list"
    }
}
