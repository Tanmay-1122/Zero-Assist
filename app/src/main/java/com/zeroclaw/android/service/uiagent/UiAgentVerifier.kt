/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.uiagent

class UiAgentVerifier {
    fun verify(
        expectedState: UiExpectedState?,
        snapshot: UiSnapshot?,
    ): UiVerificationResult {
        if (snapshot == null) {
            return UiVerificationResult(
                matched = false,
                expectedState = expectedState,
                reason = "UI snapshot is unavailable.",
            )
        }
        if (expectedState == null) {
            return UiVerificationResult(
                matched = true,
                expectedState = null,
                reason = "No expected state was requested.",
            )
        }

        return when (expectedState) {
            is UiExpectedState.ForegroundPackage ->
                verifyForegroundPackage(expectedState, snapshot)

            is UiExpectedState.TextVisible ->
                verifyTextVisible(expectedState, snapshot)

            is UiExpectedState.NodeAvailable ->
                verifyNodeAvailable(expectedState, snapshot)

            UiExpectedState.RootReady ->
                verifyRootReady(snapshot)
        }
    }

    private fun verifyForegroundPackage(
        expectedState: UiExpectedState.ForegroundPackage,
        snapshot: UiSnapshot,
    ): UiVerificationResult {
        val matched = snapshot.foregroundPackageName == expectedState.packageName
        return UiVerificationResult(
            matched = matched,
            expectedState = expectedState,
            reason =
                if (matched) {
                    null
                } else {
                    "Expected foreground package ${expectedState.packageName}, " +
                        "but observed ${snapshot.foregroundPackageName ?: "none"}. " +
                        snapshot.diagnosticSummary()
                },
        )
    }

    private fun verifyTextVisible(
        expectedState: UiExpectedState.TextVisible,
        snapshot: UiSnapshot,
    ): UiVerificationResult {
        val expectedText = expectedState.text.trim()
        if (expectedText.isEmpty()) {
            return UiVerificationResult(
                matched = false,
                expectedState = expectedState,
                reason = "Expected visible text is blank.",
            )
        }

        val matched =
            snapshot.nodes.any { node ->
                node.visibleToUser &&
                    node.matchesPackage(expectedState.packageName, snapshot) &&
                    listOfNotNull(node.text, node.contentDescription)
                        .any { value -> value.contains(expectedText, ignoreCase = true) }
            }

        return UiVerificationResult(
            matched = matched,
            expectedState = expectedState,
            reason =
                if (matched) {
                    null
                } else {
                    "Expected visible text ${expectedText.privacySummary()} was not found. " +
                        snapshot.diagnosticSummary()
                },
        )
    }

    private fun verifyNodeAvailable(
        expectedState: UiExpectedState.NodeAvailable,
        snapshot: UiSnapshot,
    ): UiVerificationResult {
        val matched =
            snapshot.nodes.any { node ->
                node.id == expectedState.nodeId && node.visibleToUser
            } || snapshot.nodes.any { node ->
                node.visibleToUser && node.matchesExpectedNode(expectedState, snapshot)
            }
        return UiVerificationResult(
            matched = matched,
            expectedState = expectedState,
            reason =
                if (matched) {
                    null
                } else {
                    buildMissingNodeReason(expectedState)
                },
        )
    }

    private fun verifyRootReady(snapshot: UiSnapshot): UiVerificationResult {
        val nodeIds = snapshot.nodes.mapTo(mutableSetOf()) { node -> node.id }
        val matched =
            snapshot.rootNodeIds.isNotEmpty() &&
                snapshot.rootNodeIds.all { rootId -> rootId in nodeIds }
        return UiVerificationResult(
            matched = matched,
            expectedState = UiExpectedState.RootReady,
            reason =
                if (matched) {
                    null
                } else {
                    "Expected at least one mapped root node."
                },
        )
    }

    private fun UiNode.matchesPackage(
        expectedPackageName: String?,
        snapshot: UiSnapshot,
    ): Boolean =
        expectedPackageName == null ||
            packageName == expectedPackageName ||
            snapshot.foregroundPackageName == expectedPackageName

    private fun UiNode.matchesExpectedNode(
        expectedState: UiExpectedState.NodeAvailable,
        snapshot: UiSnapshot,
    ): Boolean {
        if (!matchesPackage(expectedState.packageName, snapshot)) {
            return false
        }

        val viewIdMatches =
            expectedState.viewIdResourceName != null &&
                viewIdResourceName == expectedState.viewIdResourceName
        val expectedText = expectedState.text?.trim()?.takeIf { it.isNotEmpty() }
        val expectedDescription =
            expectedState.contentDescription
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        val textMatches =
            expectedText != null &&
                listOfNotNull(text, contentDescription).any { value ->
                    value.contains(expectedText, ignoreCase = true)
                }
        val descriptionMatches =
            expectedDescription != null &&
                listOfNotNull(text, contentDescription).any { value ->
                    value.contains(expectedDescription, ignoreCase = true)
                }
        val labelMatches = textMatches || descriptionMatches

        if (expectedText != null || expectedDescription != null) {
            return labelMatches &&
                (expectedState.viewIdResourceName == null || viewIdMatches)
        }

        return viewIdMatches
    }

    private fun buildMissingNodeReason(
        expectedState: UiExpectedState.NodeAvailable,
    ): String {
        val stableHint =
            expectedState.viewIdResourceName
                ?: expectedState.text
                ?: expectedState.contentDescription
        return if (stableHint != null) {
            "Expected visible node ${expectedState.nodeId} ($stableHint) was not found."
        } else {
            "Expected visible node ${expectedState.nodeId} was not found."
        }
    }
}

private fun UiSnapshot.diagnosticSummary(): String =
    "roots=${rootNodeIds.size}, nodes=${nodes.size}, titlePresent=${!foregroundWindowTitle.isNullOrBlank()}."

private fun String.privacySummary(): String =
    "len=${length},hash=${hashCode().toString(16)}"

data class UiVerificationResult(
    val matched: Boolean,
    val expectedState: UiExpectedState?,
    val reason: String? = null,
)
