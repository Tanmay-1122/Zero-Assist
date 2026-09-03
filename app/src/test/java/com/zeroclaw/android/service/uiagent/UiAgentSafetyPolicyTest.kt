/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.uiagent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("UiAgentSafetyPolicy")
class UiAgentSafetyPolicyTest {
    private val policy = UiAgentSafetyPolicy()

    @Test
    fun `allows explicit send when draft and recipient context are visible`() {
        val result =
            policy.evaluate(
                action = UiAgentAction.TapNode("send"),
                snapshot =
                    messagingSnapshot(
                        recipientText = "Sweetheart",
                        draftText = "hi there",
                    ),
                goal =
                    UiAgentGoal.SendMessage(
                        recipient = "Sweetheart",
                        message = "hi there",
                        targetPackageName = "com.whatsapp",
                    ),
            )

        assertEquals(UiAgentSafetyResult.Allowed, result)
    }

    @Test
    fun `allows drafting requested message in verified active conversation`() {
        val result =
            policy.evaluate(
                action = UiAgentAction.SetText("draft", "hi there"),
                snapshot =
                    messagingSnapshot(
                        recipientText = "Sweetheart",
                        draftText = "",
                    ),
                goal =
                    UiAgentGoal.SendMessage(
                        recipient = "Sweetheart",
                        message = "hi there",
                        targetPackageName = "com.whatsapp",
                    ),
            )

        assertEquals(UiAgentSafetyResult.Allowed, result)
    }

    @Test
    fun `blocks drafting requested message in wrong active conversation`() {
        val result =
            policy.evaluate(
                action = UiAgentAction.SetText("draft", "hi there"),
                snapshot =
                    messagingSnapshot(
                        recipientText = "Different Chat",
                        draftText = "",
                    ),
                goal =
                    UiAgentGoal.SendMessage(
                        recipient = "Sweetheart",
                        message = "hi there",
                        targetPackageName = "com.whatsapp",
                    ),
            )

        assertTrue(result is UiAgentSafetyResult.Blocked)
    }

    @Test
    fun `blocks drafting text that differs from requested message`() {
        val result =
            policy.evaluate(
                action = UiAgentAction.SetText("draft", "wrong message"),
                snapshot =
                    messagingSnapshot(
                        recipientText = "Sweetheart",
                        draftText = "",
                    ),
                goal =
                    UiAgentGoal.SendMessage(
                        recipient = "Sweetheart",
                        message = "hi there",
                        targetPackageName = "com.whatsapp",
                    ),
            )

        assertTrue(result is UiAgentSafetyResult.Blocked)
    }

    @Test
    fun `blocks drafting requested message into non-draft node`() {
        val result =
            policy.evaluate(
                action = UiAgentAction.SetText("message-bubble", "hi there"),
                snapshot =
                    messagingSnapshot(
                        recipientText = "Sweetheart",
                        draftText = "",
                    ).copy(
                        nodes =
                            messagingSnapshot(
                                recipientText = "Sweetheart",
                                draftText = "",
                            ).nodes +
                                UiNode(
                                    id = "message-bubble",
                                    packageName = "com.whatsapp",
                                    viewIdResourceName = "com.whatsapp:id/message_text",
                                    text = "existing text",
                                    visibleToUser = true,
                                ),
                    ),
                goal =
                    UiAgentGoal.SendMessage(
                        recipient = "Sweetheart",
                        message = "hi there",
                        targetPackageName = "com.whatsapp",
                    ),
            )

        assertTrue(result is UiAgentSafetyResult.Blocked)
    }

    @Test
    fun `blocks send when requested recipient is not visible`() {
        val result =
            policy.evaluate(
                action = UiAgentAction.TapNode("send"),
                snapshot =
                    messagingSnapshot(
                        recipientText = "Different Chat",
                        draftText = "hi there",
                    ),
                goal =
                    UiAgentGoal.SendMessage(
                        recipient = "Sweetheart",
                        message = "hi there",
                        targetPackageName = "com.whatsapp",
                    ),
            )

        assertTrue(result is UiAgentSafetyResult.Blocked)
    }

    @Test
    fun `blocks send when recipient appears only in contact row`() {
        val result =
            policy.evaluate(
                action = UiAgentAction.TapNode("send"),
                snapshot =
                    messagingSnapshot(
                        recipientText = "Different Chat",
                        draftText = "hi there",
                    ).copy(
                        nodes =
                            messagingSnapshot(
                                recipientText = "Different Chat",
                                draftText = "hi there",
                            ).nodes +
                                UiNode(
                                    id = "contact-row",
                                    packageName = "com.whatsapp",
                                    viewIdResourceName = "com.whatsapp:id/contact_row_container",
                                    text = "Sweetheart",
                                    boundsInScreen = UiBounds(left = 0, top = 400, right = 720, bottom = 480),
                                    enabled = true,
                                    clickable = true,
                                    visibleToUser = true,
                                ),
                    ),
                goal =
                    UiAgentGoal.SendMessage(
                        recipient = "Sweetheart",
                        message = "hi there",
                        targetPackageName = "com.whatsapp",
                    ),
            )

        assertTrue(result is UiAgentSafetyResult.Blocked)
    }

    @Test
    fun `blocks send when draft text is not visible`() {
        val result =
            policy.evaluate(
                action = UiAgentAction.TapNode("send"),
                snapshot =
                    messagingSnapshot(
                        recipientText = "Sweetheart",
                        draftText = "wrong draft",
                    ),
                goal =
                    UiAgentGoal.SendMessage(
                        recipient = "Sweetheart",
                        message = "hi there",
                        targetPackageName = "com.whatsapp",
                    ),
            )

        assertTrue(result is UiAgentSafetyResult.Blocked)
    }

    @Test
    fun `blocks send when draft contains extra text`() {
        val result =
            policy.evaluate(
                action = UiAgentAction.TapNode("send"),
                snapshot =
                    messagingSnapshot(
                        recipientText = "Sweetheart",
                        draftText = "hi there extra",
                    ),
                goal =
                    UiAgentGoal.SendMessage(
                        recipient = "Sweetheart",
                        message = "hi there",
                        targetPackageName = "com.whatsapp",
                    ),
            )

        assertTrue(result is UiAgentSafetyResult.Blocked)
    }

    @Test
    fun `blocks send when requested text is only visible as a message bubble`() {
        val result =
            policy.evaluate(
                action = UiAgentAction.TapNode("send"),
                snapshot =
                    messagingSnapshot(
                        recipientText = "Sweetheart",
                        draftText = "",
                    ).copy(
                        nodes =
                            messagingSnapshot(
                                recipientText = "Sweetheart",
                                draftText = "",
                            ).nodes.filterNot { node -> node.id == "draft" } +
                                UiNode(
                                    id = "message-bubble",
                                    packageName = "com.whatsapp",
                                    viewIdResourceName = "com.whatsapp:id/message_text",
                                    text = "hi there",
                                    visibleToUser = true,
                                ),
                    ),
                goal =
                    UiAgentGoal.SendMessage(
                        recipient = "Sweetheart",
                        message = "hi there",
                        targetPackageName = "com.whatsapp",
                    ),
            )

        assertTrue(result is UiAgentSafetyResult.Blocked)
    }

    @Test
    fun `continues blocking unrelated risky activation labels`() {
        val snapshot =
            messagingSnapshot(
                recipientText = "Sweetheart",
                draftText = "hi there",
            ).copy(
                nodes =
                    messagingSnapshot(
                        recipientText = "Sweetheart",
                        draftText = "hi there",
                    ).nodes +
                        UiNode(
                            id = "delete",
                            packageName = "com.whatsapp",
                            text = "Delete",
                            enabled = true,
                            clickable = true,
                            visibleToUser = true,
                        ),
            )

        val result =
            policy.evaluate(
                action = UiAgentAction.TapNode("delete"),
                snapshot = snapshot,
                goal =
                    UiAgentGoal.SendMessage(
                        recipient = "Sweetheart",
                        message = "hi there",
                        targetPackageName = "com.whatsapp",
                    ),
            )

        assertTrue(result is UiAgentSafetyResult.Blocked)
    }

    @Test
    fun `blocks send-like control with non-send risky token`() {
        val snapshot =
            messagingSnapshot(
                recipientText = "Sweetheart",
                draftText = "hi there",
            ).copy(
                nodes =
                    messagingSnapshot(
                        recipientText = "Sweetheart",
                        draftText = "hi there",
                    ).nodes.filterNot { node -> node.id == "send" } +
                        UiNode(
                            id = "send-payment",
                            packageName = "com.whatsapp",
                            text = "Send payment",
                            enabled = true,
                            clickable = true,
                            visibleToUser = true,
                        ),
            )

        val result =
            policy.evaluate(
                action = UiAgentAction.TapNode("send-payment"),
                snapshot = snapshot,
                goal =
                    UiAgentGoal.SendMessage(
                        recipient = "Sweetheart",
                        message = "hi there",
                        targetPackageName = "com.whatsapp",
                    ),
            )

        assertTrue(result is UiAgentSafetyResult.Blocked)
    }

    @Test
    fun `blocks sensitive label target even when node is not flagged sensitive`() {
        val result =
            policy.evaluate(
                action = UiAgentAction.SetText("payment-card", "4111111111111111"),
                snapshot =
                    messagingSnapshot(
                        recipientText = "Sweetheart",
                        draftText = "",
                    ).copy(
                        nodes =
                            messagingSnapshot(
                                recipientText = "Sweetheart",
                                draftText = "",
                            ).nodes +
                                UiNode(
                                    id = "payment-card",
                                    packageName = "com.whatsapp",
                                    text = "Payment card",
                                    enabled = true,
                                    editable = true,
                                    visibleToUser = true,
                                ),
                    ),
                goal = UiAgentGoal.Generic("type card", targetPackageName = "com.whatsapp"),
            )

        assertTrue(result is UiAgentSafetyResult.Blocked)
    }

    private fun messagingSnapshot(
        recipientText: String,
        draftText: String,
    ): UiSnapshot =
        UiSnapshot(
            capturedAtEpochMs = 1L,
            foregroundPackageName = "com.whatsapp",
            foregroundWindowTitle = recipientText,
            rootNodeIds = listOf("root"),
            nodes =
                listOf(
                    UiNode(
                        id = "root",
                        packageName = "com.whatsapp",
                        visibleToUser = true,
                    ),
                    UiNode(
                        id = "recipient",
                        parentId = "root",
                        packageName = "com.whatsapp",
                        viewIdResourceName = "com.whatsapp:id/conversation_contact_name",
                        text = recipientText,
                        boundsInScreen = UiBounds(left = 0, top = 0, right = 720, bottom = 120),
                        visibleToUser = true,
                    ),
                    UiNode(
                        id = "draft",
                        parentId = "root",
                        packageName = "com.whatsapp",
                        viewIdResourceName = "com.whatsapp:id/entry",
                        text = draftText,
                        editable = true,
                        visibleToUser = true,
                    ),
                    UiNode(
                        id = "send",
                        parentId = "root",
                        packageName = "com.whatsapp",
                        contentDescription = "Send",
                        enabled = true,
                        clickable = true,
                        visibleToUser = true,
                    ),
                ),
        )
}
