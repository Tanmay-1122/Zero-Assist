/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.uiagent

/**
 * Verifies that a send action is aimed at the requested conversation and draft.
 *
 * This deliberately treats contact rows, search results, and message bubbles as insufficient
 * evidence. A visible send button is allowed only when the active chat context and editable draft
 * both match the requested send_message goal.
 */
class UiMessageSendVerifier {
    fun verify(
        snapshot: UiSnapshot,
        goal: UiAgentGoal.SendMessage,
    ): UiMessageSendVerification {
        if (!snapshot.matchesPackage(goal.targetPackageName)) {
            return UiMessageSendVerification.Blocked(
                "Target messaging app is not in the foreground.",
            )
        }
        if (!snapshot.hasEditableDraft(goal)) {
            return UiMessageSendVerification.Blocked(
                "Requested message text is not visible in an editable draft field.",
            )
        }
        if (!snapshot.hasConversationRecipientContext(goal)) {
            return UiMessageSendVerification.Blocked(
                "Requested recipient is not verified as the active conversation.",
            )
        }
        return UiMessageSendVerification.Allowed
    }

    fun verifyDraftTarget(
        snapshot: UiSnapshot,
        targetNode: UiNode?,
        goal: UiAgentGoal.SendMessage,
        text: String,
    ): UiMessageSendVerification {
        if (!snapshot.matchesPackage(goal.targetPackageName)) {
            return UiMessageSendVerification.Blocked(
                "Target messaging app is not in the foreground.",
            )
        }
        if (text.normalizedText() != goal.message.normalizedText()) {
            return UiMessageSendVerification.Blocked(
                "Draft text does not match the requested message.",
            )
        }
        if (targetNode == null ||
            !targetNode.visibleToUser ||
            !targetNode.matchesPackage(goal.targetPackageName, snapshot.foregroundPackageName) ||
            !targetNode.looksLikeDraftField()
        ) {
            return UiMessageSendVerification.Blocked(
                "Requested message target is not a verified editable draft field.",
            )
        }
        if (!snapshot.hasConversationRecipientContext(goal)) {
            return UiMessageSendVerification.Blocked(
                "Requested recipient is not verified as the active conversation.",
            )
        }
        return UiMessageSendVerification.Allowed
    }

    private fun UiSnapshot.matchesPackage(expectedPackageName: String?): Boolean =
        expectedPackageName == null ||
            foregroundPackageName == expectedPackageName ||
            nodes.any { node ->
                node.visibleToUser && node.packageName == expectedPackageName
            }

    private fun UiSnapshot.hasEditableDraft(goal: UiAgentGoal.SendMessage): Boolean {
        val message = goal.message.normalizedText()
        if (message.isBlank()) return false
        return nodes.any { node ->
            node.visibleToUser &&
                node.matchesPackage(goal.targetPackageName, foregroundPackageName) &&
                node.looksLikeDraftField() &&
                node.labels().any { label -> label.normalizedText() == message }
        }
    }

    private fun UiSnapshot.hasConversationRecipientContext(goal: UiAgentGoal.SendMessage): Boolean {
        val recipient = goal.recipient?.normalizedText()?.takeIf { it.isNotBlank() } ?: return true
        val title = foregroundWindowTitle.normalizedText()
        if (title.isNotBlank() && title.contains(recipient)) {
            return true
        }

        return nodes.any { node ->
            node.visibleToUser &&
                node.matchesPackage(goal.targetPackageName, foregroundPackageName) &&
                node.looksLikeConversationHeader() &&
                node.labels().any { label -> label.normalizedText().contains(recipient) }
        }
    }

    private fun UiNode.looksLikeDraftField(): Boolean {
        if (editable || focused || UiNodeAction.SET_TEXT in actions) {
            return true
        }
        val viewId = viewIdResourceName.normalizedText()
        val classNameValue = className.normalizedText()
        return classNameValue.contains("edittext") ||
            DRAFT_FIELD_HINTS.any { hint -> viewId.contains(hint) }
    }

    private fun UiNode.looksLikeConversationHeader(): Boolean {
        if (looksLikeListOrSearchResult()) {
            return false
        }
        val viewId = viewIdResourceName.normalizedText()
        val classNameValue = className.normalizedText()
        return HEADER_HINTS.any { hint -> viewId.contains(hint) } ||
            classNameValue.contains("toolbar") ||
            boundsInScreen?.top?.let { top -> top <= HEADER_TOP_BOUNDARY_PX } == true
    }

    private fun UiNode.looksLikeListOrSearchResult(): Boolean {
        val viewId = viewIdResourceName.normalizedText()
        return LIST_OR_SEARCH_HINTS.any { hint -> viewId.contains(hint) }
    }

    private fun UiNode.matchesPackage(
        expectedPackageName: String?,
        foregroundPackageName: String?,
    ): Boolean =
        expectedPackageName == null ||
            packageName == expectedPackageName ||
            foregroundPackageName == expectedPackageName

    private fun UiNode.labels(): List<String> =
        listOfNotNull(text, contentDescription, viewIdResourceName)

    private fun String?.normalizedText(): String =
        orEmpty()
            .lowercase()
            .replace(Regex("""\s+"""), " ")
            .trim()

    private companion object {
        const val HEADER_TOP_BOUNDARY_PX = 260

        val DRAFT_FIELD_HINTS =
            listOf(
                "compose",
                "draft",
                "edit",
                "entry",
                "input",
            )

        val HEADER_HINTS =
            listOf(
                "actionbar",
                "chat_title",
                "conversation",
                "header",
                "name",
                "profile",
                "subtitle",
                "title",
                "toolbar",
            )

        val LIST_OR_SEARCH_HINTS =
            listOf(
                "contact_row",
                "list",
                "recycler",
                "result",
                "row",
                "search",
            )
    }
}

sealed interface UiMessageSendVerification {
    data object Allowed : UiMessageSendVerification

    data class Blocked(
        val reason: String,
    ) : UiMessageSendVerification
}
