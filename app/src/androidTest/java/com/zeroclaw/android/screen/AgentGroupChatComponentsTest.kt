/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.screen

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.zeroclaw.android.model.AgentChatMessage
import com.zeroclaw.android.model.AgentRole
import com.zeroclaw.android.ui.screen.agents.AgentGroupChatMessageList
import com.zeroclaw.android.ui.screen.terminal.StreamingState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentGroupChatComponentsTest {
    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun groupChatMessages_renderUrlTextAcrossAgentSurfaces() {
        val summaryUrl = "https://www.youtube.com/watch?v=abc123"
        val driveUrl = "https://drive.google.com/file/d/abc/view?usp=sharing"
        val approvalUrl = "https://example.com/approval"
        val messages =
            listOf(
                AgentChatMessage.summary(
                    senderId = "master",
                    senderName = "ZeroClaw",
                    senderAvatar = "Z",
                    senderColor = 0xFF3366FF,
                    senderRole = AgentRole.MASTER,
                    content = "Summary: $summaryUrl",
                ),
                AgentChatMessage.onDeviceResult(
                    content = "Result: $driveUrl",
                ),
                AgentChatMessage.approvalRequest(
                    senderId = "worker",
                    senderName = "Worker",
                    senderAvatar = "W",
                    senderColor = 0xFF00AA88,
                    senderRole = AgentRole.GENERAL,
                    content = "Approve opening $approvalUrl",
                    targetAgentId = "master",
                ),
            )

        composeTestRule.setContent {
            AgentGroupChatMessageList(
                messages = messages,
                typingAgentIds = emptySet(),
                agentNameForId = { id -> id ?: "agent" },
                onApproveMessage = {},
                onRejectMessage = {},
                masterStreamingState = StreamingState(),
            )
        }

        composeTestRule.onNodeWithText("Summary: $summaryUrl").assertIsDisplayed()
        composeTestRule.onNodeWithText("Result: $driveUrl").assertIsDisplayed()
        composeTestRule.onNodeWithText("Approve opening $approvalUrl").assertIsDisplayed()
    }
}
