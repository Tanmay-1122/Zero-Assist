/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.uiagent

/** Raw UI tree used by Android-specific extractors before prompt-safe shaping. */
data class RawUiNode(
    val packageName: String? = null,
    val className: String? = null,
    val viewIdResourceName: String? = null,
    val text: String? = null,
    val contentDescription: String? = null,
    val boundsInScreen: UiBounds? = null,
    val actions: List<UiNodeAction> = emptyList(),
    val enabled: Boolean = false,
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val focused: Boolean = false,
    val selected: Boolean = false,
    val checkable: Boolean = false,
    val checked: Boolean = false,
    val visibleToUser: Boolean = true,
    val password: Boolean = false,
    val children: List<RawUiNode> = emptyList(),
)

data class RawUiSnapshot(
    val roots: List<RawUiNode>,
    val capturedAtEpochMs: Long,
    val foregroundPackageName: String? = null,
    val foregroundWindowTitle: String? = null,
)

/** Converts raw Android UI data into compact, redacted, JSON-safe snapshots. */
object UiSnapshotMapper {
    fun toSnapshot(raw: RawUiSnapshot): UiSnapshot {
        val builder = SnapshotBuilder()
        val rootIds = raw.roots.map { root -> builder.addNode(root, parentId = null) }
        return UiSnapshot(
            capturedAtEpochMs = raw.capturedAtEpochMs,
            foregroundPackageName = raw.foregroundPackageName.sanitizeIdentifier(),
            foregroundWindowTitle = UiTextSanitizer.sanitize(raw.foregroundWindowTitle),
            rootNodeIds = rootIds,
            nodes = builder.nodes,
        )
    }

    private class SnapshotBuilder {
        private var nextId = 1
        private val mutableNodes = mutableListOf<UiNode>()

        val nodes: List<UiNode>
            get() = mutableNodes

        fun addNode(
            raw: RawUiNode,
            parentId: String?,
        ): String {
            val nodeId = "node-${nextId++}"
            val sensitive = raw.isSensitive()
            val childIds = raw.children.map { child -> addNode(child, parentId = nodeId) }
            mutableNodes +=
                UiNode(
                    id = nodeId,
                    parentId = parentId,
                    packageName = raw.packageName.sanitizeIdentifier(),
                    className = raw.className.sanitizeIdentifier(),
                    viewIdResourceName = raw.viewIdResourceName.sanitizeIdentifier(),
                    text = UiTextSanitizer.sanitize(raw.text, sensitive = sensitive),
                    contentDescription =
                        UiTextSanitizer.sanitize(
                            raw.contentDescription,
                            sensitive = sensitive,
                        ),
                    boundsInScreen = raw.boundsInScreen,
                    actions = raw.actions.distinct().sortedBy { it.name },
                    childIds = childIds,
                    enabled = raw.enabled,
                    clickable = raw.clickable,
                    editable = raw.editable,
                    focused = raw.focused,
                    selected = raw.selected,
                    checkable = raw.checkable,
                    checked = raw.checked,
                    visibleToUser = raw.visibleToUser,
                    sensitive = sensitive,
                )
            return nodeId
        }
    }
}

object UiTextSanitizer {
    fun sanitize(
        value: String?,
        sensitive: Boolean = false,
    ): String? {
        val normalized =
            value
                ?.replace(Regex("""\s+"""), " ")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: return null
        if (sensitive) {
            return REDACTED_VALUE
        }

        return normalized
            .replace(EMAIL_PATTERN, REDACTED_EMAIL)
            .replace(LONG_NUMBER_PATTERN, REDACTED_NUMBER)
            .replace(PHONE_PATTERN, REDACTED_PHONE)
            .take(MAX_TEXT_CHARS)
    }

    const val REDACTED_VALUE = "[redacted]"
    const val REDACTED_EMAIL = "[redacted-email]"
    const val REDACTED_PHONE = "[redacted-phone]"
    const val REDACTED_NUMBER = "[redacted-number]"

    private const val MAX_TEXT_CHARS = 180
    private val EMAIL_PATTERN =
        Regex("""[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}""", RegexOption.IGNORE_CASE)
    private val PHONE_PATTERN =
        Regex("""(?<!\d)(?:\+?\d[\d\s().-]{7,}\d)(?!\d)""")
    private val LONG_NUMBER_PATTERN =
        Regex("""(?<!\d)\d{12,}(?!\d)""")
}

private fun RawUiNode.isSensitive(): Boolean =
    password ||
        className.containsSensitiveToken() ||
        viewIdResourceName.containsSensitiveToken() ||
        text.containsSensitiveToken() ||
        contentDescription.containsSensitiveToken()

private fun String?.containsSensitiveToken(): Boolean {
    val normalized = this?.lowercase().orEmpty()
    return normalized.contains("password") ||
        normalized.contains("passcode") ||
        normalized.contains("pin") ||
        normalized.contains("otp") ||
        normalized.contains("one_time") ||
        normalized.contains("card") ||
        normalized.contains("cvv") ||
        normalized.contains("payment") ||
        normalized.contains("pay now")
}

private fun String?.sanitizeIdentifier(): String? =
    this
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.take(MAX_IDENTIFIER_CHARS)

private const val MAX_IDENTIFIER_CHARS = 160
