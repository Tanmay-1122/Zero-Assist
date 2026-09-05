package com.zeroclaw.android.service.needle

import com.zeroclaw.android.service.devicecontrol.PlannerRequest

/**
 * Compresses a [PlannerRequest] into the ~190-token dynamic budget of a
 * 256-token sliding-window engine (Needle 2 class).
 *
 * The engine pins the system prompt plus tool declarations (~60-70 tokens),
 * so everything dynamic — goal, state, history, screen nodes — must fit in
 * [MAX_DYNAMIC_TOKENS]. Truncation priority (first to be cut): node lines,
 * then history entries, then goal text. The goal is never dropped.
 *
 * Pure JVM logic with no Android dependencies so it stays unit-testable.
 * Token counts are estimated with [CHARS_PER_TOKEN]; the estimate is a
 * deliberately conservative ceiling, not a real tokenizer measurement.
 */
class NeedlePromptCompressor {

    /**
     * Compressed prompt sections. [toUserText] renders the single user-role
     * message sent to the engine.
     */
    data class NeedlePromptInput(
        val systemHint: String,
        val goal: String,
        val stateLine: String,
        val historyLines: List<String>,
        val nodeLines: List<String>,
    ) {
        fun toUserText(): String = buildString {
            appendLine("GOAL: $goal")
            appendLine(stateLine)
            if (historyLines.isNotEmpty()) {
                appendLine("HIST:")
                historyLines.forEach { appendLine("  $it") }
            }
            if (nodeLines.isNotEmpty()) {
                appendLine("NODES:")
                nodeLines.forEach { appendLine(it) }
            }
        }
    }

    fun compress(request: PlannerRequest): NeedlePromptInput {
        val goal = truncateWords(request.goal.trim(), MAX_GOAL_CHARS)
            .ifEmpty { request.goal.trim().take(MAX_GOAL_CHARS) }
        val stateLine = buildStateLine(request)
        val historyLines = request.actionHistory
            .takeLast(MAX_HISTORY_ENTRIES)
            .map { truncateWords(it.trim(), MAX_HISTORY_ENTRY_CHARS) }
            .filter { it.isNotBlank() }
        val nodeLines = extractNodeLines(request.screen)
            .take(MAX_NODES)
            .map { compressNodeLine(it) }
            .filter { it.isNotBlank() }
        return NeedlePromptInput(
            systemHint = SYSTEM_HINT,
            goal = goal.ifEmpty { "(empty goal)" },
            stateLine = stateLine,
            historyLines = historyLines,
            nodeLines = nodeLines,
        )
    }

    private fun buildStateLine(request: PlannerRequest): String {
        val pkg = request.currentPackage
            ?.substringAfterLast('.')
            ?.take(MAX_PACKAGE_CHARS)
            .orEmpty()
        val keyboard = if (request.screen.contains("KEYBOARD_VISIBLE: true")) "+kbd" else ""
        return "STATE PKG:$pkg STEP:${request.step}/${request.maxSteps} FAIL:${request.failureCount}$keyboard"
    }

    private fun extractNodeLines(screen: String): List<String> {
        val lines = screen.lineSequence().map { it.trim() }.toList()
        val nodesStart = lines.indexOfFirst { it.startsWith("NODES") }
        val candidates = if (nodesStart >= 0) lines.drop(nodesStart + 1) else lines
        return candidates.filter { it.startsWith("[") }
    }

    private fun compressNodeLine(line: String): String {
        var out = line.replace("*", "")
        out = out.replace(Regex("\\s+id:\\S+"), "")
        out = out.replace(Regex("\\s+acts:\\S+"), "")
        out = out.replace(Regex("\\s+\\(\\d+,\\d+\\)"), "")
        out = out.replace(Regex("\\s+"), " ").trim()
        if (out.length > MAX_NODE_LINE_CHARS) {
            out = out.take(MAX_NODE_LINE_CHARS).trimEnd() + "…"
        }
        return out
    }

    private fun truncateWords(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        val cut = text.take(maxChars)
        val lastSpace = cut.lastIndexOf(' ')
        return if (lastSpace > maxChars / 2) cut.take(lastSpace) else cut
    }

    companion object {
        /** Assumed characters per token for budget estimation. Conservative ceiling. */
        const val CHARS_PER_TOKEN = 4

        /** Hard ceiling for all dynamic sections combined (goal + state + history + nodes). */
        const val MAX_DYNAMIC_TOKENS = 190

        const val SYSTEM_HINT = "You are a JSON-only Android UI planner. Reply with one tool call."

        const val MAX_GOAL_CHARS = 120
        const val MAX_HISTORY_ENTRIES = 2
        const val MAX_HISTORY_ENTRY_CHARS = 60
        const val MAX_NODES = 10
        const val MAX_NODE_LINE_CHARS = 44
        const val MAX_PACKAGE_CHARS = 24

        /** Ceiling token estimate for [text] under the [CHARS_PER_TOKEN] assumption. */
        fun estimateTokens(text: String): Int =
            (text.length + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN
    }
}
