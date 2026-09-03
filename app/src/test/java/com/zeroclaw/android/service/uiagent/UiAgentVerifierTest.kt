/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.uiagent

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("UiAgentVerifier")
class UiAgentVerifierTest {
    private val verifier = UiAgentVerifier()

    @Test
    fun `root ready requires mapped root node`() {
        val snapshot =
            UiSnapshot(
                capturedAtEpochMs = 1L,
                rootNodeIds = listOf("root"),
                nodes = listOf(UiNode(id = "root")),
            )

        assertTrue(verifier.verify(UiExpectedState.RootReady, snapshot).matched)
        assertFalse(
            verifier.verify(
                UiExpectedState.RootReady,
                snapshot.copy(rootNodeIds = listOf("missing")),
            ).matched,
        )
    }

    @Test
    fun `text visible matches visible node text within expected package`() {
        val snapshot =
            UiSnapshot(
                capturedAtEpochMs = 1L,
                foregroundPackageName = "com.chat.app",
                rootNodeIds = listOf("root"),
                nodes =
                    listOf(
                        UiNode(id = "root", packageName = "com.chat.app"),
                        UiNode(
                            id = "message",
                            packageName = "com.chat.app",
                            text = "Delivery complete",
                        ),
                    ),
            )

        assertTrue(
            verifier.verify(
                UiExpectedState.TextVisible("delivery", packageName = "com.chat.app"),
                snapshot,
            ).matched,
        )
        assertFalse(
            verifier.verify(
                UiExpectedState.TextVisible("delivery", packageName = "com.other.app"),
                snapshot,
            ).matched,
        )
    }

    @Test
    fun `node available requires visible node id`() {
        val snapshot =
            UiSnapshot(
                capturedAtEpochMs = 1L,
                nodes =
                    listOf(
                        UiNode(id = "visible", visibleToUser = true),
                        UiNode(id = "hidden", visibleToUser = false),
                    ),
            )

        assertTrue(verifier.verify(UiExpectedState.NodeAvailable("visible"), snapshot).matched)
        assertFalse(verifier.verify(UiExpectedState.NodeAvailable("hidden"), snapshot).matched)
    }

    @Test
    fun `node available falls back to stable identity when node id changes`() {
        val snapshot =
            UiSnapshot(
                capturedAtEpochMs = 1L,
                foregroundPackageName = "com.whatsapp",
                nodes =
                    listOf(
                        UiNode(
                            id = "node-40",
                            packageName = "com.whatsapp",
                            viewIdResourceName = "com.whatsapp:id/contact_name",
                            text = "Sweetheart",
                            visibleToUser = true,
                        ),
                    ),
            )

        assertTrue(
            verifier.verify(
                UiExpectedState.NodeAvailable(
                    nodeId = "node-25",
                    packageName = "com.whatsapp",
                    viewIdResourceName = "com.whatsapp:id/contact_name",
                    text = "Sweetheart",
                ),
                snapshot,
            ).matched,
        )
    }

    @Test
    fun `node available does not accept repeated view id when visible label is different`() {
        val snapshot =
            UiSnapshot(
                capturedAtEpochMs = 1L,
                foregroundPackageName = "com.whatsapp",
                nodes =
                    listOf(
                        UiNode(
                            id = "node-40",
                            packageName = "com.whatsapp",
                            viewIdResourceName = "com.whatsapp:id/contact_row_container",
                            text = "Someone else",
                            visibleToUser = true,
                        ),
                    ),
            )

        assertFalse(
            verifier.verify(
                UiExpectedState.NodeAvailable(
                    nodeId = "node-25",
                    packageName = "com.whatsapp",
                    viewIdResourceName = "com.whatsapp:id/contact_row_container",
                    text = "Sweetheart",
                ),
                snapshot,
            ).matched,
        )
    }
}
