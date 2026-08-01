/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.uiagent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("UiSnapshotMapper")
class UiSnapshotMapperTest {
    @Test
    fun `maps raw tree to flat snapshot with parent and child links`() {
        val snapshot =
            UiSnapshotMapper.toSnapshot(
                RawUiSnapshot(
                    roots =
                        listOf(
                            RawUiNode(
                                packageName = "com.chat.app",
                                className = "android.widget.LinearLayout",
                                children =
                                    listOf(
                                        RawUiNode(
                                            text = "Send",
                                            clickable = true,
                                            actions = listOf(UiNodeAction.CLICK),
                                        ),
                                    ),
                            ),
                        ),
                    capturedAtEpochMs = 123L,
                    foregroundPackageName = "com.chat.app",
                ),
            )

        val root = snapshot.nodes.single { it.id == snapshot.rootNodeIds.single() }
        val child = snapshot.nodes.single { it.parentId == root.id }

        assertEquals("com.chat.app", snapshot.foregroundPackageName)
        assertEquals("com.chat.app", root.packageName)
        assertEquals(listOf(child.id), root.childIds)
        assertEquals("Send", child.text)
        assertTrue(child.clickable)
        assertEquals(listOf(UiNodeAction.CLICK), child.actions)
    }

    @Test
    fun `redacts emails phone numbers long numbers and password fields`() {
        val snapshot =
            UiSnapshotMapper.toSnapshot(
                RawUiSnapshot(
                    roots =
                        listOf(
                            RawUiNode(
                                text = "Email me at person@example.com or call +1 555 123 4567",
                                contentDescription = "Card 4111111111111111",
                                children =
                                    listOf(
                                        RawUiNode(
                                            viewIdResourceName = "login_password_input",
                                            text = "secret-value",
                                            contentDescription = "password secret",
                                            editable = true,
                                        ),
                                    ),
                            ),
                        ),
                    capturedAtEpochMs = 1L,
                ),
            )

        val root = snapshot.nodes.single { it.id == snapshot.rootNodeIds.single() }
        val password = snapshot.nodes.single { it.parentId == root.id }

        assertEquals(UiTextSanitizer.REDACTED_VALUE, root.text)
        assertEquals(UiTextSanitizer.REDACTED_VALUE, root.contentDescription)
        assertTrue(root.sensitive)
        assertEquals(UiTextSanitizer.REDACTED_VALUE, password.text)
        assertEquals(UiTextSanitizer.REDACTED_VALUE, password.contentDescription)
        assertTrue(password.sensitive)
    }

    @Test
    fun `marks payment text nodes as sensitive`() {
        val snapshot =
            UiSnapshotMapper.toSnapshot(
                RawUiSnapshot(
                    roots =
                        listOf(
                            RawUiNode(
                                text = "Pay now with card",
                                contentDescription = "Enter CVV",
                                clickable = true,
                                actions = listOf(UiNodeAction.CLICK),
                            ),
                        ),
                    capturedAtEpochMs = 1L,
                ),
            )

        val node = snapshot.nodes.single()

        assertTrue(node.sensitive)
        assertEquals(UiTextSanitizer.REDACTED_VALUE, node.text)
        assertEquals(UiTextSanitizer.REDACTED_VALUE, node.contentDescription)
    }

    @Test
    fun `drops blank text and caps long prompt text`() {
        val snapshot =
            UiSnapshotMapper.toSnapshot(
                RawUiSnapshot(
                    roots =
                        listOf(
                            RawUiNode(
                                text = "   ",
                                contentDescription = "x".repeat(300),
                            ),
                        ),
                    capturedAtEpochMs = 1L,
                ),
            )

        val root = snapshot.nodes.single()

        assertNull(root.text)
        assertNotNull(root.contentDescription)
        assertEquals(180, root.contentDescription?.length)
        assertFalse(root.sensitive)
    }
}
