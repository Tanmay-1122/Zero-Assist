/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Offline Mode prompt")
class OfflineModePromptTest {
    @Test
    fun `excludes runtime context and optional memory by default`() {
        val prompt = buildOfflineModePrompt("Be concise.", memoryContext = null)

        assertTrue(prompt.contains("Offline Mode"))
        assertTrue(prompt.contains("Be concise."))
        assertFalse(prompt.contains("## Memory"))
        assertFalse(prompt.contains("shell: Run a shell command"))
        assertFalse(prompt.contains("MCP server: connected"))
        assertFalse(prompt.contains("Channels: Discord"))
    }

    @Test
    fun `includes only the supplied memory when enabled`() {
        val prompt = buildOfflineModePrompt("Be concise.", "- User prefers short answers.")

        assertTrue(prompt.contains("## Memory"))
        assertTrue(prompt.contains("User prefers short answers."))
        assertFalse(prompt.contains("shell: Run a shell command"))
        assertFalse(prompt.contains("MCP server: connected"))
    }
}
