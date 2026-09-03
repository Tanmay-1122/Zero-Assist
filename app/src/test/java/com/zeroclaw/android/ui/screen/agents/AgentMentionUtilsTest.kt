/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.agents

import com.zeroclaw.android.model.Agent
import com.zeroclaw.android.model.AgentRole
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class AgentMentionUtilsTest {
    private val coder =
        Agent(
            id = "coder-1",
            name = "Coder",
            provider = "openai",
            modelName = "gpt-4.1",
            role = AgentRole.CODER,
            avatar = "\uD83D\uDCBB",
            accentColor = 0xFF0074D9,
        )

    private val researcher =
        Agent(
            id = "researcher-1",
            name = "Researcher",
            provider = "openai",
            modelName = "gpt-4.1",
            role = AgentRole.RESEARCHER,
            avatar = "\uD83D\uDD0D",
            accentColor = 0xFF2ECC40,
        )

    @Test
    fun findTrailingMentionQuery_returnsQueryAtCursor() {
        assertEquals("Cod", findTrailingMentionQuery("please ask @Cod"))
        assertNull(findTrailingMentionQuery("please ask @Coder now"))
    }

    @Test
    fun replaceTrailingMention_replacesLastDraftMention() {
        assertEquals(
            "please ask @Coder ",
            replaceTrailingMention("please ask @Cod", "Coder"),
        )
    }

    @Test
    fun resolveMentionTarget_prefersExplicitMentionMatch() {
        val match =
            resolveMentionTarget(
                text = "Please review this @Researcher",
                selectedTarget = coder,
                agents = listOf(coder, researcher),
            )

        assertEquals(researcher.id, match.agent?.id)
        assertEquals("@Researcher", match.mentionText)
    }

    @Test
    fun removeMentionToken_stripsSelectedMentionFromText() {
        assertEquals(
            "Please review this",
            removeMentionToken("Please review this @Coder", "Coder"),
        )
    }
}
