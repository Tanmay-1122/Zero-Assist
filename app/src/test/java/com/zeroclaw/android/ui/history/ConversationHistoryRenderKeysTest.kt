/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.history

import com.zeroclaw.android.model.ConversationEntry
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Conversation history render keys")
class ConversationHistoryRenderKeysTest {
    @Test
    fun `starred and recent keys differ for the same conversation`() {
        val entry = conversationEntry(id = "b8250413-4dbb-4e62-a9f5-aedd32d41786")

        assertNotEquals(
            starredConversationRenderKey(entry, 0),
            recentConversationRenderKey(entry.workspaceName, entry, 0),
        )
    }

    @Test
    fun `duplicate entries in one section keep unique render keys`() {
        val entry = conversationEntry(id = "b8250413-4dbb-4e62-a9f5-aedd32d41786")

        assertNotEquals(
            recentConversationRenderKey(entry.workspaceName, entry, 0),
            recentConversationRenderKey(entry.workspaceName, entry, 1),
        )
    }

    private fun conversationEntry(id: String): ConversationEntry =
        ConversationEntry(
            id = id,
            workspaceName = "Zero-Assist",
            preview = "Task history",
            timestamp = 123,
            agentName = "Assistant",
            isStarred = true,
        )
}
