package com.zeroclaw.android.service.devicecontrol

/**
 * Observes the current screen and produces compact, grounded descriptions
 * for the planner.
 *
 * Phase 7 enhancements:
 * - Nodes ranked by relevance (actionable > text-bearing > structural)
 * - Invisible/empty non-actionable nodes filtered out (done in traverse)
 * - Goal-keyword matching for highlighting
 * - Fingerprint computation for change detection
 * - Compact per-node lines with supported actions
 */
class ScreenObserver(
    private val maxNodes: Int = 25,
    private val maxLabelLength: Int = 40,
) {
    /**
     * Describes the current screen for the planner.
     *
     * Returns a Triple of:
     * - The text description for the planner prompt
     * - The screen fingerprint for change detection
     * - The raw node list for validation
     */
    fun describeWithFingerprint(
        service: DeviceControlServiceBridge,
        goal: String,
    ): Triple<String, ScreenFingerprint, List<UiNodeSnapshot>> {
        val nodes = service.snapshot()
        val pkg = service.currentPackage()
        val fingerprint = ScreenFingerprint.compute(nodes, pkg)

        val keywords = goal.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length >= 3 }
            .toSet()

        // Rank nodes: actionable first, then text-bearing, then structural
        val ranked = nodes.sortedWith(
            compareByDescending<UiNodeSnapshot> { it.clickable || it.editable }
                .thenByDescending { it.scrollable }
                .thenByDescending { it.label.isNotBlank() }
                .thenByDescending { keywords.any { kw -> it.label.contains(kw, true) } }
                .thenBy { it.depth }
        ).take(maxNodes)

        // Re-index so that index [0..N-1] in prompt matches array position exactly
        val reIndexedNodes = ranked.mapIndexed { idx, node -> node.copy(index = idx) }

        val description = buildString {
            appendLine("PKG: $pkg")
            appendLine("SCREEN_CHANGED: ${fingerprint.contentHash != 0}")
            val hasKeyboard = nodes.any { (it.editable || it.focusable) && it.focused }
            appendLine("KEYBOARD_VISIBLE: $hasKeyboard")
            appendLine("NODES (${nodes.size} total, showing ${reIndexedNodes.size} ranked):")
            reIndexedNodes.forEach { n ->
                val label = n.label.let { l ->
                    val clean = l.replace(Regex("\\s+"), " ").trim()
                    if (clean.length > maxLabelLength) clean.take(maxLabelLength) + "\u2026" else clean
                }
                val highlight = label.isNotBlank() && keywords.any { label.contains(it, true) }
                val line = n.toCompactString(highlight)
                    .let { if (it.length > 96) it.take(96) + "\u2026" else it }
                appendLine(line)
            }
        }

        return Triple(description, fingerprint, reIndexedNodes)
    }

    /**
     * Legacy describe method for backward compatibility.
     * Returns just the text description.
     */
    fun describe(service: DeviceControlServiceBridge, goal: String): String =
        describeWithFingerprint(service, goal).first
}
