package com.zeroclaw.android.service.devicecontrol

/**
 * Lightweight semantic fingerprint of the current screen state.
 *
 * Used to detect:
 * - unchanged UI (same fingerprint → no need to replan)
 * - meaningful transitions (fingerprint changed → planner can continue)
 * - repeated failure states (same fingerprint appearing in a loop)
 * - loop detection (A→B→A→B)
 */
data class ScreenFingerprint(
    /** Hash of all actionable node labels + packages combined. */
    val contentHash: Int,
    /** Foreground package name. */
    val packageName: String?,
    /** Count of actionable nodes. */
    val actionableNodeCount: Int,
    /** Whether the screen has an editable field visible. */
    val hasEditableField: Boolean,
    /** Number of unique text labels on screen. */
    val uniqueLabelCount: Int,
) {
    /** True if the screen state is semantically identical. */
    fun isSameScreen(other: ScreenFingerprint): Boolean =
        contentHash == other.contentHash && packageName == other.packageName

    /** True if the screen has meaningfully changed. */
    fun hasChanged(other: ScreenFingerprint): Boolean = !isSameScreen(other)

    /** Compact string for logging (no sensitive content). */
    fun toLogString(): String =
        "fp(hash=$contentHash,pkg=$packageName,nodes=$actionableNodeCount," +
            "editable=$hasEditableField,labels=$uniqueLabelCount)"

    companion object {
        /**
         * Compute a fingerprint from a list of UI node snapshots.
         * Uses only non-sensitive structural data.
         */
        fun compute(nodes: List<UiNodeSnapshot>, packageName: String?): ScreenFingerprint {
            val labels = nodes.map { it.label }.filter { it.isNotBlank() }.toSet()
            val contentHash = nodes.sumOf { node ->
                var h = node.className.hashCode()
                h = 31 * h + node.label.hashCode()
                h = 31 * h + if (node.clickable) 1 else 0
                h = 31 * h + if (node.editable) 1 else 0
                h = 31 * h + if (node.scrollable) 1 else 0
                h = 31 * h + node.bounds.top * 31 + node.bounds.left
                h
            }
            return ScreenFingerprint(
                contentHash = contentHash,
                packageName = packageName,
                actionableNodeCount = nodes.count { it.clickable || it.editable || it.scrollable },
                hasEditableField = nodes.any { it.editable },
                uniqueLabelCount = labels.size,
            )
        }
    }
}
