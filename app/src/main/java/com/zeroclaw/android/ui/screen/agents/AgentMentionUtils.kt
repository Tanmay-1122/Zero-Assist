/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.agents

import com.zeroclaw.android.model.Agent

internal data class MentionTargetMatch(
    val agent: Agent?,
    val mentionText: String? = null,
)

internal fun findTrailingMentionQuery(text: String): String? {
    val atIndex = text.lastIndexOf('@')
    if (atIndex == -1) return null
    if (atIndex > 0 && !text[atIndex - 1].isWhitespace()) return null

    val rawQuery = text.substring(atIndex + 1)
    if (rawQuery.startsWith(" ")) return null
    if (rawQuery.contains('\n')) return null
    if (rawQuery.contains(' ')) return null
    if (rawQuery.endsWith(" ")) return null
    return rawQuery
}

internal fun replaceTrailingMention(text: String, agentName: String): String {
    val atIndex = text.lastIndexOf('@')
    if (atIndex == -1) {
        return if (text.isBlank()) "@$agentName " else "$text @$agentName "
    }

    val prefix = text.substring(0, atIndex)
    return "$prefix@$agentName "
}

internal fun removeMentionToken(text: String, agentName: String): String {
    val updated = mentionRegex(agentName).replaceFirst(text, "$1").replace(Regex("\\s{2,}"), " ")
    return updated.trim()
}

internal fun resolveMentionTarget(
    text: String,
    selectedTarget: Agent?,
    agents: Collection<Agent>,
): MentionTargetMatch {
    val matchedAgent =
        agents
            .sortedByDescending { it.name.length }
            .firstOrNull { agent -> mentionRegex(agent.name).containsMatchIn(text) }

    return when {
        matchedAgent != null -> MentionTargetMatch(matchedAgent, "@${matchedAgent.name}")
        selectedTarget != null -> MentionTargetMatch(selectedTarget, "@${selectedTarget.name}")
        else -> MentionTargetMatch(agent = null)
    }
}

internal fun matchesMentionToken(text: String, agentName: String): Boolean =
    mentionRegex(agentName).containsMatchIn(text)

private fun mentionRegex(agentName: String): Regex =
    Regex("(?i)(^|\\s)@${Regex.escape(agentName)}(?=\\s|$|[.,!?])")
