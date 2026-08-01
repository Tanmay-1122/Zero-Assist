/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.remote

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("McpServerProbe")
class McpServerProbeTest {
    @Nested
    @DisplayName("messageFor")
    inner class MessageFor {
        @Test
        @DisplayName("Reachable maps to a success message")
        fun `Reachable maps to a success message`() {
            val result = McpServerProbe.messageFor(McpProbeOutcome.Reachable("Server responding (HTTP 200) - 123ms"))
            assertTrue(result.isSuccess)
            assertEquals("Server responding (HTTP 200) - 123ms", result.getOrNull())
        }

        @Test
        @DisplayName("AuthRequired maps to a failure mentioning credentials")
        fun `AuthRequired maps to a failure mentioning credentials`() {
            val result = McpServerProbe.messageFor(McpProbeOutcome.AuthRequired(401))
            assertTrue(result.isFailure)
            assertEquals(
                "HTTP 401 Auth Required - add an Authorization header",
                result.exceptionOrNull()?.message,
            )
        }

        @Test
        @DisplayName("NotMcp maps to a failure with the reason")
        fun `NotMcp maps to a failure with the reason`() {
            val reason = "Server rejects MCP requests"
            val result = McpServerProbe.messageFor(McpProbeOutcome.NotMcp(reason))
            assertTrue(result.isFailure)
            assertEquals(reason, result.exceptionOrNull()?.message)
        }

        @Test
        @DisplayName("Unreachable maps to a failure with connectivity wording")
        fun `Unreachable maps to a failure with connectivity wording`() {
            val result =
                McpServerProbe.messageFor(McpProbeOutcome.Unreachable("failed to connect"))
            assertTrue(result.isFailure)
            assertEquals(
                "Could not reach server - failed to connect",
                result.exceptionOrNull()?.message,
            )
        }
    }
}
