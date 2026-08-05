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
        @DisplayName("AuthenticationRequired maps to a failure mentioning credentials")
        fun `AuthenticationRequired maps to a failure mentioning credentials`() {
            val result =
                McpServerProbe.messageFor(McpProbeOutcome.AuthenticationRequired(401, "Bearer", "HTTP 401 requires credentials"))
            assertTrue(result.isFailure)
            val message = result.exceptionOrNull()?.message
            assertTrue(message!!.contains("401"))
            assertTrue(message.contains("Authorization"))
            assertTrue(message.contains("challenge"))
        }

        @Test
        @DisplayName("InvalidHostHeader maps to a failure explaining Host rejection")
        fun `InvalidHostHeader maps to a failure explaining Host rejection`() {
            val result =
                McpServerProbe.messageFor(McpProbeOutcome.InvalidHostHeader("Invalid Host header"))
            assertTrue(result.isFailure)
            val message = result.exceptionOrNull()?.message
            assertTrue(message!!.contains("Host"))
            assertTrue(message.contains("rewritten"))
        }

        @Test
        @DisplayName("UnsupportedTransport maps to a failure with the reason")
        fun `UnsupportedTransport maps to a failure with the reason`() {
            val reason = "Server rejects MCP requests"
            val result = McpServerProbe.messageFor(McpProbeOutcome.UnsupportedTransport(reason))
            assertTrue(result.isFailure)
            assertEquals(reason, result.exceptionOrNull()?.message)
        }

        @Test
        @DisplayName("NetworkFailure maps to a failure with connectivity wording")
        fun `NetworkFailure maps to a failure with connectivity wording`() {
            val result = McpServerProbe.messageFor(McpProbeOutcome.NetworkFailure("failed to connect"))
            assertTrue(result.isFailure)
            assertEquals("Could not reach server - failed to connect", result.exceptionOrNull()?.message)
        }

        @Test
        @DisplayName("Timeout maps to a timeout-specific failure")
        fun `Timeout maps to a timeout-specific failure`() {
            val result = McpServerProbe.messageFor(McpProbeOutcome.Timeout("request timed out"))
            assertTrue(result.isFailure)
            assertTrue(result.exceptionOrNull()?.message!!.contains("timed out"))
        }
    }

    @Nested
    @DisplayName("classifyFailure")
    inner class ClassifyFailure {
        @Test
        @DisplayName("401 with WWW-Authenticate is authentication")
        fun `401 with WWW-Authenticate is authentication`() {
            val result =
                McpServerProbe.classifyFailure(401, mapOf("WWW-Authenticate" to "Bearer realm=\"mcp\""), "")
            assertTrue(result is McpProbeOutcome.AuthenticationRequired)
            assertEquals("Bearer realm=\"mcp\"", (result as McpProbeOutcome.AuthenticationRequired).wwwAuthenticate)
        }

        @Test
        @DisplayName("418 with no hints is UnknownFailure")
        fun `418 with no hints is UnknownFailure`() {
            val result = McpServerProbe.classifyFailure(418, emptyMap(), "")
            assertTrue(result is McpProbeOutcome.UnknownFailure)
            assertEquals(418, (result as McpProbeOutcome.UnknownFailure).status)
        }

        @Test
        @DisplayName("429 is ServerUnavailable")
        fun `429 is ServerUnavailable`() {
            val result = McpServerProbe.classifyFailure(429, emptyMap(), "")
            assertTrue(result is McpProbeOutcome.ServerUnavailable)
        }

        @Test
        @DisplayName("405 is UnsupportedEndpoint")
        fun `405 is UnsupportedEndpoint`() {
            val result = McpServerProbe.classifyFailure(405, emptyMap(), "")
            assertTrue(result is McpProbeOutcome.UnsupportedEndpoint)
        }

        @Test
        @DisplayName("Invalid Host header body on 403 is InvalidHostHeader, not auth")
        fun `Invalid Host header body on 403 is InvalidHostHeader not auth`() {
            val result =
                McpServerProbe.classifyFailure(403, emptyMap(), """{"error":"Invalid Host header"}""")
            assertTrue(result is McpProbeOutcome.InvalidHostHeader)
        }

        @Test
        @DisplayName("Missing Authorization on 403 is AuthenticationRequired")
        fun `Missing Authorization on 403 is AuthenticationRequired`() {
            val result = McpServerProbe.classifyFailure(403, emptyMap(), """{"error":"Missing authorization token"}""")
            assertTrue(result is McpProbeOutcome.AuthenticationRequired)
        }

        @Test
        @DisplayName("404 with session in body is InvalidSession")
        fun `404 with session in body is InvalidSession`() {
            val result = McpServerProbe.classifyFailure(404, emptyMap(), "No session found")
            assertTrue(result is McpProbeOutcome.InvalidSession)
        }

        @Test
        @DisplayName("404 without session is McpNotFound")
        fun `404 without session is McpNotFound`() {
            val result = McpServerProbe.classifyFailure(404, emptyMap(), "")
            assertTrue(result is McpProbeOutcome.McpNotFound)
        }

        @Test
        @DisplayName("400 Unsupported protocol version is InvalidProtocolVersion")
        fun `400 Unsupported protocol version is InvalidProtocolVersion`() {
            val result =
                McpServerProbe.classifyFailure(
                    400,
                    emptyMap(),
                    """{"jsonrpc":"2.0","error":{"code":-32600,"message":"Unsupported MCP protocol version"}}""",
                )
            assertTrue(result is McpProbeOutcome.InvalidProtocolVersion)
        }

        @Test
        @DisplayName("400 Missing session header is InvalidSession")
        fun `400 Missing session header is InvalidSession`() {
            val result =
                McpServerProbe.classifyFailure(
                    400,
                    emptyMap(),
                    """{"error":"Missing MCP-Session-Id header"}""",
                )
            assertTrue(result is McpProbeOutcome.InvalidSession)
        }

        @Test
        @DisplayName("400 generic bad request is InvalidJsonRpc")
        fun `400 generic bad request is InvalidJsonRpc`() {
            val result =
                McpServerProbe.classifyFailure(
                    400,
                    emptyMap(),
                    """{"jsonrpc":"2.0","error":{"code":-32700,"message":"Parse error"}}""",
                )
            assertTrue(result is McpProbeOutcome.InvalidJsonRpc)
        }
    }

    @Nested
    @DisplayName("extractErrorText")
    inner class ExtractErrorText {
        @Test
        @DisplayName("JSON-RPC error.message is extracted")
        fun `JSON-RPC error message is extracted`() {
            assertEquals(
                "Unsupported MCP protocol version",
                McpServerProbe.extractErrorText(
                    """{"jsonrpc":"2.0","error":{"code":-32601,"message":"Unsupported MCP protocol version"}}""",
                ),
            )
        }

        @Test
        @DisplayName("Plain JSON error string is extracted")
        fun `Plain JSON error string is extracted`() {
            assertEquals("Invalid Host header", McpServerProbe.extractErrorText("""{"error": "Invalid Host header"}"""))
        }

        @Test
        @DisplayName("Plain text body returns its first line")
        fun `Plain text body returns its first line`() {
            assertEquals("403 Forbidden", McpServerProbe.extractErrorText("403 Forbidden\nnginx"))
        }

        @Test
        @DisplayName("Blank input yields no text")
        fun `Blank input yields no text`() {
            assertTrue(McpServerProbe.extractErrorText("").isBlank())
        }
    }
}