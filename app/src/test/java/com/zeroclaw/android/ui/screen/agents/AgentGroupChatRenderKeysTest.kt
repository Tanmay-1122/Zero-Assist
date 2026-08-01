/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.agents

import com.zeroclaw.android.model.AgentChatMessage
import com.zeroclaw.android.model.AgentRole
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Agent group chat render keys")
class AgentGroupChatRenderKeysTest {
    @Test
    fun `duplicate persisted message ids keep unique render keys`() {
        val message =
            AgentChatMessage.summary(
                senderId = "worker",
                senderName = "Worker",
                senderAvatar = "W",
                senderColor = 0xFF00C8B4L,
                senderRole = AgentRole.GENERAL,
                content = "Processing request",
            ).copy(
                id = "b8250413-4dbb-4e62-a9f5-aedd32d41786",
                timestamp = 123,
            )

        assertNotEquals(
            agentChatMessageRenderKey(message, 0),
            agentChatMessageRenderKey(message, 1),
        )
    }
}
