/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.uiagent

import java.util.Locale

/**
 * First-pass planner for common, high-risk phone-control flows.
 *
 * The model planner remains available for open-ended UI work, but messaging and simple tap/type
 * commands get deterministic handling first so Zero-Assist does not draft or send in the wrong app
 * or conversation because of a model-selected generic row.
 */
class HybridUiAgentPlanner(
    private val deterministicPlanner: DeterministicUiAgentPlanner = DeterministicUiAgentPlanner(),
    private val fallbackPlanner: UiAgentPlanner,
) : UiAgentPlanner {
    override suspend fun decide(
        prompt: UiPrompt,
        context: UiAgentSessionContext,
    ): UiAgentDecision =
        deterministicPlanner.decideOrNull(prompt = prompt, context = context)
            ?: fallbackPlanner.decide(prompt = prompt, context = context)
}

class DeterministicUiAgentPlanner {
    fun decideOrNull(
        prompt: UiPrompt,
        context: UiAgentSessionContext,
    ): UiAgentDecision? =
        when (val goal = prompt.goal) {
            is UiAgentGoal.SendMessage -> decideSendMessage(goal, prompt.snapshot, context)
            is UiAgentGoal.Generic -> decideGeneric(goal, prompt.snapshot)
        }

    private fun decideSendMessage(
        goal: UiAgentGoal.SendMessage,
        snapshot: UiSnapshot,
        context: UiAgentSessionContext,
    ): UiAgentDecision? {
        val profile =
            goal.messagingProfile(snapshot)
                ?: return null
        if (!profile.deterministicSendEnabled) {
            return abort(
                profile.disabledReason
                    ?: "${profile.displayName} deterministic sends are disabled.",
            )
        }
        val targetPackageName = profile.packageName
        val recipient = goal.recipient.normalizedGoalText() ?: return abort("Messaging goal is missing a recipient.")
        val message = goal.message.normalizedGoalText() ?: return abort("Messaging goal is missing message text.")
        if (context.lastVerifiedSendTap(profile)) {
            return decision(
                action = UiAgentAction.NoOp("Requested message was sent."),
                rationale = "The previous verified action tapped the send control for this message.",
                confidence = 0.96f,
            )
        }

        if (!snapshot.matchesTargetPackage(targetPackageName)) {
            return decision(
                action = UiAgentAction.OpenPackage(targetPackageName),
                expectedState = UiExpectedState.ForegroundPackage(targetPackageName),
                rationale = "Open ${profile.displayName} before acting.",
                confidence = 0.95f,
            )
        }

        val draftComposer = snapshot.findDraftComposer(profile)
        if (draftComposer != null && snapshot.hasDifferentConversationContext(goal, profile)) {
            return if (context.lastActionWasBack()) {
                abort("Active conversation still does not match the requested recipient.")
            } else {
                decision(
                    action = UiAgentAction.PressGlobal(UiAgentGlobalAction.BACK),
                    expectedState = UiExpectedState.RootReady,
                    rationale = "Leave the wrong active conversation before selecting the requested recipient.",
                    confidence = 0.9f,
                )
            }
        }

        if (draftComposer != null && snapshot.hasActiveRecipientContext(goal, profile)) {
            val sendButton = snapshot.findSendButton(profile)
            if (draftComposer.labels().containsNormalized(message) && sendButton != null) {
                return decision(
                    action = UiAgentAction.TapNode(sendButton.id),
                    expectedState = UiExpectedState.TextVisible(message, targetPackageName),
                    rationale = "Requested message is drafted in the verified conversation; send it.",
                    confidence = 0.95f,
                )
            }

            return decision(
                action = UiAgentAction.SetText(draftComposer.id, message),
                expectedState = UiExpectedState.TextVisible(message, targetPackageName),
                rationale = "Verified conversation is open; draft the requested message.",
                confidence = 0.95f,
            )
        }

        return when (val recipientMatch = snapshot.findRecipientNode(recipient, profile)) {
            is RecipientNodeMatch.Found ->
                decision(
                    action = UiAgentAction.TapNode(recipientMatch.node.id),
                    expectedState = UiExpectedState.TextVisible(recipient, targetPackageName),
                    rationale = "Tap the visible requested recipient.",
                    confidence = 0.9f,
                )

            RecipientNodeMatch.Ambiguous ->
                abort("Multiple visible recipients match '$recipient'; refusing to choose one.")

            RecipientNodeMatch.Missing ->
                snapshot.findSearchField(profile)?.let { searchField ->
                    decision(
                        action = UiAgentAction.SetText(searchField.id, recipient),
                        expectedState = UiExpectedState.TextVisible(recipient, targetPackageName),
                        rationale = "Search for the requested recipient.",
                        confidence = 0.86f,
                    )
                } ?: snapshot.findConversationEntryButton(profile)?.let { entryButton ->
                    decision(
                        action = UiAgentAction.TapNode(entryButton.id),
                        expectedState = UiExpectedState.RootReady,
                        rationale = "Open ${profile.displayName} messages before selecting the requested recipient.",
                        confidence = 0.84f,
                    )
                } ?: snapshot.findSearchButton(profile)?.let { searchButton ->
                    decision(
                        action = UiAgentAction.TapNode(searchButton.id),
                        expectedState = UiExpectedState.RootReady,
                        rationale = "Open search before selecting the requested recipient.",
                        confidence = 0.82f,
                    )
                } ?: abort("Requested recipient is not visible and no safe search control is available.")
        }
    }

    private fun decideGeneric(
        goal: UiAgentGoal.Generic,
        snapshot: UiSnapshot,
    ): UiAgentDecision? {
        val instruction = goal.instruction.trim()
        val scrollDirection = instruction.scrollDirection()
        if (scrollDirection != null) {
            return snapshot.findScrollableNode(goal.targetPackageName, scrollDirection)?.let { node ->
                decision(
                    action = UiAgentAction.ScrollNode(node.id, scrollDirection),
                    expectedState = UiExpectedState.RootReady,
                    rationale = "Scroll the visible scrollable area.",
                    confidence = 0.82f,
                )
            }
        }

        val tapTarget = TAP_PATTERN.matchEntire(instruction)?.groupValues?.get(1)?.trim()
        if (!tapTarget.isNullOrBlank()) {
            return snapshot.findTapTarget(tapTarget, goal.targetPackageName)?.let { node ->
                decision(
                    action = UiAgentAction.TapNode(node.id),
                    expectedState = UiExpectedState.RootReady,
                    rationale = "Tap the visible requested control.",
                    confidence = 0.84f,
                )
            }
        }

        val typeMatch = TYPE_PATTERN.matchEntire(instruction)
        if (typeMatch != null) {
            val text = typeMatch.groupValues[1].trim()
            val targetLabel = typeMatch.groupValues.getOrNull(2)?.trim()?.takeIf { it.isNotEmpty() }
            val targetNode =
                if (targetLabel == null) {
                    snapshot.findEditableTextTarget(goal.targetPackageName)
                } else {
                    snapshot.findEditableTextTarget(goal.targetPackageName, targetLabel)
                }
            if (text.isNotBlank() && targetNode != null) {
                return decision(
                    action = UiAgentAction.SetText(targetNode.id, text),
                    expectedState = UiExpectedState.TextVisible(text, goal.targetPackageName),
                    rationale = "Type into the visible requested field.",
                    confidence = 0.84f,
                )
            }
        }

        return null
    }

    private fun decision(
        action: UiAgentAction,
        expectedState: UiExpectedState? = null,
        rationale: String,
        confidence: Float,
    ): UiAgentDecision =
        UiAgentDecision(
            action = action,
            expectedState = expectedState,
            rationale = rationale,
            confidence = confidence,
        )

    private fun abort(reason: String): UiAgentDecision =
        UiAgentDecision(
            action = UiAgentAction.Abort(reason),
            rationale = reason,
            confidence = 1f,
        )

    private fun UiAgentSessionContext.lastActionWasBack(): Boolean =
        (history.lastOrNull()?.decision?.action as? UiAgentAction.PressGlobal)
            ?.action == UiAgentGlobalAction.BACK

    private fun UiAgentSessionContext.lastVerifiedSendTap(profile: MessagingAppUiProfile): Boolean {
        val previousStep = history.lastOrNull() ?: return false
        val tap = previousStep.decision.action as? UiAgentAction.TapNode ?: return false
        if (previousStep.executionResult !is UiAgentExecutionResult.Succeeded) {
            return false
        }
        if (previousStep.verificationResult?.matched != true) {
            return false
        }
        val tappedNode = previousStep.snapshot.nodes.firstOrNull { node -> node.id == tap.nodeId } ?: return false
        return tappedNode.looksLikeSendButton(profile)
    }

    private fun UiAgentGoal.SendMessage.messagingProfile(snapshot: UiSnapshot): MessagingAppUiProfile? =
        targetPackageName
            ?.let(MessagingAppUiProfiles::profileForPackageName)
            ?: if (targetPackageName == null) {
                MessagingAppUiProfiles.profileForPackageName(snapshot.foregroundPackageName)
            } else {
                null
            }

    private fun UiSnapshot.matchesTargetPackage(packageName: String?): Boolean =
        packageName == null || foregroundPackageName == packageName

    private fun UiSnapshot.findDraftComposer(profile: MessagingAppUiProfile): UiNode? =
        nodes
            .asSequence()
            .filter { node ->
                node.visibleToUser &&
                    node.enabled &&
                    node.matchesPackage(profile.packageName, this) &&
                    node.looksLikeDraftComposer(profile)
            }
            .sortedWith(
                compareByDescending<UiNode> { node -> node.focused }
                    .thenByDescending { node -> node.boundsInScreen?.top ?: 0 },
            )
            .firstOrNull()

    private fun UiSnapshot.findSendButton(profile: MessagingAppUiProfile): UiNode? =
        nodes
            .asSequence()
            .filter { node ->
                node.visibleToUser &&
                    node.enabled &&
                    node.matchesPackage(profile.packageName, this) &&
                    node.isClickableTarget() &&
                    node.looksLikeSendButton(profile)
            }
            .minByOrNull { node -> node.boundsInScreen?.top ?: Int.MAX_VALUE }

    private fun UiSnapshot.hasActiveRecipientContext(
        goal: UiAgentGoal.SendMessage,
        profile: MessagingAppUiProfile,
    ): Boolean {
        val recipient = goal.recipient.normalizedGoalText() ?: return false
        foregroundWindowTitle
            ?.meaningfulConversationTitle(profile)
            ?.takeIf { title -> title.contains(recipient, ignoreCase = true) }
            ?.let { return true }

        return nodes.any { node ->
            node.visibleToUser &&
                node.matchesPackage(profile.packageName, this) &&
                node.looksLikeConversationHeader(profile) &&
                node.labels().any { label -> label.contains(recipient, ignoreCase = true) }
        }
    }

    private fun UiSnapshot.hasDifferentConversationContext(
        goal: UiAgentGoal.SendMessage,
        profile: MessagingAppUiProfile,
    ): Boolean {
        val recipient = goal.recipient.normalizedGoalText() ?: return false
        foregroundWindowTitle
            ?.meaningfulConversationTitle(profile)
            ?.let { title ->
                if (!title.contains(recipient, ignoreCase = true)) {
                    return true
                }
            }

        val headerLabels =
            nodes
                .asSequence()
                .filter { node ->
                    node.visibleToUser &&
                        node.matchesPackage(profile.packageName, this) &&
                        node.looksLikeConversationHeader(profile)
                }
                .flatMap { node -> node.labels().asSequence() }
                .mapNotNull { label -> label.meaningfulConversationTitle(profile) }
                .toList()
        return headerLabels.any { label -> !label.contains(recipient, ignoreCase = true) }
    }

    private fun UiSnapshot.findRecipientNode(
        recipient: String,
        profile: MessagingAppUiProfile,
    ): RecipientNodeMatch {
        val candidates =
            nodes
                .asSequence()
                .filter { node ->
                    node.visibleToUser &&
                        node.matchesPackage(profile.packageName, this) &&
                        !node.looksLikeDraftComposer(profile) &&
                        !node.looksLikeSearchField(profile) &&
                        node.labels().any { label -> label.contains(recipient, ignoreCase = true) }
                }
                .toList()
        if (candidates.isEmpty()) {
            return RecipientNodeMatch.Missing
        }

        val exactMatches =
            candidates.filter { node ->
                node.labels().any { label -> label.equals(recipient, ignoreCase = true) }
            }
        if (exactMatches.size > 1) {
            return RecipientNodeMatch.Ambiguous
        }
        if (exactMatches.size == 1) {
            return RecipientNodeMatch.Found(exactMatches.single())
        }

        val best =
            candidates
                .sortedWith(
                    compareByDescending<UiNode> { node -> findClickableAncestor(node.id) != null }
                        .thenBy { node -> node.shortestMatchingLabelLength(recipient) },
                )
                .first()
        return RecipientNodeMatch.Found(best)
    }

    private fun UiSnapshot.findSearchField(profile: MessagingAppUiProfile): UiNode? =
        nodes.firstOrNull { node ->
            node.visibleToUser &&
                node.enabled &&
                node.matchesPackage(profile.packageName, this) &&
                node.looksLikeSearchField(profile) &&
                (node.editable || UiNodeAction.SET_TEXT in node.actions)
        }

    private fun UiSnapshot.findSearchButton(profile: MessagingAppUiProfile): UiNode? =
        nodes.firstOrNull { node ->
            node.visibleToUser &&
                node.enabled &&
                node.matchesPackage(profile.packageName, this) &&
                node.isClickableTarget() &&
                node.labels().any { label -> label.normalizedForComparison() in profile.searchLabels }
        }

    private fun UiSnapshot.findConversationEntryButton(profile: MessagingAppUiProfile): UiNode? =
        nodes.firstOrNull { node ->
            node.visibleToUser &&
                node.enabled &&
                node.matchesPackage(profile.packageName, this) &&
                node.isClickableTarget() &&
                node.looksLikeConversationEntry(profile)
        }

    private fun UiSnapshot.findScrollableNode(
        packageName: String?,
        direction: UiAgentScrollDirection,
    ): UiNode? =
        nodes
            .asSequence()
            .filter { node ->
                node.visibleToUser &&
                    node.enabled &&
                    node.matchesPackage(packageName, this) &&
                    node.boundsInScreen != null &&
                    node.canScroll(direction)
            }
            .maxWithOrNull(
                compareBy<UiNode> { node -> node.boundsInScreen?.height ?: 0 }
                    .thenBy { node -> node.boundsInScreen?.width ?: 0 },
            )

    private fun UiSnapshot.findTapTarget(
        label: String,
        packageName: String?,
    ): UiNode? {
        val normalizedLabel = label.normalizedGoalText() ?: return null
        return nodes
            .asSequence()
            .filter { node ->
                node.visibleToUser &&
                    node.enabled &&
                    node.matchesPackage(packageName, this) &&
                    (node.isClickableTarget() || findClickableAncestor(node.id) != null) &&
                    node.labels().any { candidate -> candidate.contains(normalizedLabel, ignoreCase = true) }
            }
            .sortedWith(
                compareByDescending<UiNode> { node ->
                    node.labels().any { candidate -> candidate.equals(normalizedLabel, ignoreCase = true) }
                }.thenBy { node -> node.shortestMatchingLabelLength(normalizedLabel) },
            )
            .firstOrNull()
    }

    private fun UiSnapshot.findEditableTextTarget(
        packageName: String?,
        label: String? = null,
    ): UiNode? =
        nodes
            .asSequence()
            .filter { node ->
                node.visibleToUser &&
                    node.enabled &&
                    node.matchesPackage(packageName, this) &&
                    (node.editable || UiNodeAction.SET_TEXT in node.actions) &&
                    (label == null || node.labels().any { value -> value.contains(label, ignoreCase = true) })
            }
            .sortedWith(
                compareByDescending<UiNode> { node -> node.focused }
                    .thenBy { node -> if (label == null) 1 else 0 },
            )
            .firstOrNull()

    private fun UiSnapshot.findClickableAncestor(nodeId: String): UiNode? {
        val nodesById = nodes.associateBy(UiNode::id)
        var current = nodesById[nodeId]?.parentId?.let(nodesById::get)
        while (current != null) {
            if (current.enabled && current.isClickableTarget()) {
                return current
            }
            current = current.parentId?.let(nodesById::get)
        }
        return null
    }

    private fun UiNode.matchesPackage(
        packageName: String?,
        snapshot: UiSnapshot,
    ): Boolean =
        packageName == null ||
            this.packageName == packageName ||
            snapshot.foregroundPackageName == packageName

    private fun UiNode.isClickableTarget(): Boolean =
        clickable || UiNodeAction.CLICK in actions

    private fun UiNode.canScroll(direction: UiAgentScrollDirection): Boolean =
        when (direction) {
            UiAgentScrollDirection.FORWARD -> UiNodeAction.SCROLL_FORWARD in actions
            UiAgentScrollDirection.BACKWARD -> UiNodeAction.SCROLL_BACKWARD in actions
        }

    private fun UiNode.looksLikeDraftComposer(profile: MessagingAppUiProfile): Boolean {
        if (looksLikeSearchField(profile)) {
            return false
        }
        val id = viewIdResourceName.orEmpty()
        val className = className.orEmpty()
        return editable ||
            UiNodeAction.SET_TEXT in actions ||
            className.contains("EditText", ignoreCase = true) ||
            profile.draftViewIdHints.any { hint -> id.contains(hint, ignoreCase = true) }
    }

    private fun UiNode.looksLikeSearchField(profile: MessagingAppUiProfile): Boolean =
        labels().any { label -> label.normalizedForComparison() in profile.searchLabels } ||
            viewIdResourceName.orEmpty().contains("search", ignoreCase = true)

    private fun UiNode.looksLikeSendButton(profile: MessagingAppUiProfile): Boolean =
        labels()
            .map { label -> label.normalizedForComparison() }
            .any { label ->
                label in profile.sendButtonLabels ||
                    profile.sendButtonLabels.any { sendLabel -> label.contains(sendLabel) }
            }

    private fun UiNode.looksLikeConversationHeader(profile: MessagingAppUiProfile): Boolean {
        if (looksLikeListOrSearchResult(profile)) {
            return false
        }
        val id = viewIdResourceName.orEmpty()
        val className = className.orEmpty()
        return profile.headerViewIdHints.any { hint -> id.contains(hint, ignoreCase = true) } ||
            className.contains("Toolbar", ignoreCase = true) ||
            (boundsInScreen?.top ?: Int.MAX_VALUE) <= HEADER_MAX_TOP
    }

    private fun UiNode.looksLikeConversationEntry(profile: MessagingAppUiProfile): Boolean {
        val id = viewIdResourceName.orEmpty()
        return labels().any { label -> label.normalizedForComparison() in profile.conversationEntryLabels } ||
            profile.conversationEntryViewIdHints.any { hint -> id.contains(hint, ignoreCase = true) }
    }

    private fun UiNode.looksLikeListOrSearchResult(profile: MessagingAppUiProfile): Boolean {
        val id = viewIdResourceName.orEmpty()
        return profile.listOrSearchResultViewIdHints.any { hint -> id.contains(hint, ignoreCase = true) }
    }

    private fun UiNode.shortestMatchingLabelLength(value: String): Int =
        labels()
            .filter { label -> label.contains(value, ignoreCase = true) }
            .minOfOrNull(String::length)
            ?: Int.MAX_VALUE

    private fun UiNode.labels(): List<String> =
        listOfNotNull(text, contentDescription, viewIdResourceName)
            .map { label -> label.trim() }
            .filter { label -> label.isNotEmpty() }

    private fun List<String>.containsNormalized(value: String): Boolean =
        any { label -> label.normalizedForComparison().contains(value.normalizedForComparison()) }

    private fun String?.normalizedGoalText(): String? =
        this
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    private fun String.normalizedForComparison(): String =
        replace(Regex("\\s+"), " ")
            .trim()
            .lowercase(Locale.US)

    private fun String.meaningfulConversationTitle(profile: MessagingAppUiProfile): String? {
        val normalized = normalizedGoalText() ?: return null
        val comparable = normalized.lowercase(Locale.US)
        return normalized.takeUnless { comparable in profile.genericWindowTitles }
    }

    private fun String.scrollDirection(): UiAgentScrollDirection? {
        val normalized = normalizedForComparison()
        return when {
            SCROLL_FORWARD_PATTERNS.any { pattern -> pattern.matches(normalized) } -> UiAgentScrollDirection.FORWARD
            SCROLL_BACKWARD_PATTERNS.any { pattern -> pattern.matches(normalized) } -> UiAgentScrollDirection.BACKWARD
            else -> null
        }
    }

    private sealed interface RecipientNodeMatch {
        data class Found(
            val node: UiNode,
        ) : RecipientNodeMatch

        data object Ambiguous : RecipientNodeMatch

        data object Missing : RecipientNodeMatch
    }

    private companion object {
        val TAP_PATTERN = Regex("""(?:tap|click|press|select)\s+(.+)""", RegexOption.IGNORE_CASE)
        val TYPE_PATTERN =
            Regex("""(?:set\s+text|type|enter)\s+(.+?)(?:\s+(?:into|in|on|to)\s+(.+))?""", RegexOption.IGNORE_CASE)
        val SCROLL_FORWARD_PATTERNS =
            listOf(
                Regex("""(?:scroll|swipe)\s+(?:down|forward)"""),
                Regex("""show\s+(?:more|next|older).+"""),
            )
        val SCROLL_BACKWARD_PATTERNS =
            listOf(
                Regex("""(?:scroll|swipe)\s+(?:up|back|backward)"""),
                Regex("""show\s+(?:previous|newer).+"""),
            )
        const val HEADER_MAX_TOP = 260
    }
}
