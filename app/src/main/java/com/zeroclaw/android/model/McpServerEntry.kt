/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.model

import kotlinx.serialization.Serializable
import java.util.UUID

/**
 * Transport type for an MCP server connection.
 */
@Serializable
enum class McpTransportType {
    /** Spawn a local process and communicate over stdin/stdout. */
    STDIO,

    /** Connect via HTTP POST (Streamable HTTP). */
    HTTP,

    /** Connect via HTTP POST + Server-Sent Events. */
    SSE,

    /** Spawn a local process and bridge stdin/stdout via a localhost HTTP shim. */
    LOCALHOST_STDIO,
    ;

    /** Lowercase value for TOML emission. */
    val tomlValue: String get() = name.lowercase()
}

/**
 * Optional transport-level behaviors for HTTP/SSE MCP servers, emitted as
 * `[mcp.servers.transport_options]` TOML and consumed by the daemon's
 * HTTP transport. All fields are optional; unset means "default behavior".
 */
@Serializable
data class McpTransportOptions(
    /**
     * Rewrite the HTTP Host header of every request to this value.
     *
     * Some MCP servers (e.g. Autodesk Fusion 360's local MCP server) only
     * accept requests whose Host header matches their own address exactly
     * (e.g. `127.0.0.1:27182`). When the URL points at a gateway, LAN IP,
     * adb reverse, or port forward, set this to the value the server
     * expects.
     */
    val rewriteHost: String? = null,

    /**
     * Add an Origin header matching the configured URL on every request.
     * Useful for upstreams that validate Origin/CORS-style headers.
     */
    val rewriteOrigin: Boolean = false,

    /**
     * Track and echo the `Mcp-Session-Id` header across requests.
     * Defaults to true (session-preserving). Disable only for upstreams
     * that are stateless.
     */
    val preserveSession: Boolean = true,
)

/**
 * Configuration for a single external MCP server.
 *
 * Stored as a JSON-serialized list in AppSettings. Emitted as
 * `[[mcp.servers]]` TOML blocks by ConfigTomlBuilder.
 *
 * @property id Stable UUID for list reordering and diffing.
 * @property name Display name and tool prefix (`<name>__<tool>`).
 * @property enabled Whether this server is active.
 * @property transport Transport type.
 * @property command Executable to spawn (stdio only).
 * @property args Command arguments (stdio only).
 * @property env Environment variables (stdio only).
 * @property url Server URL (http/sse only).
 * @property headers HTTP headers (http/sse only).
 * @property toolTimeoutSecs Per-call timeout override in seconds.
 * @property transportOptions Optional transport behaviors (http/sse only).
 */
@Serializable
data class McpServerEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val enabled: Boolean = true,
    val transport: McpTransportType = McpTransportType.HTTP,
    val command: String = "",
    val args: List<String> = emptyList(),
    val env: Map<String, String> = emptyMap(),
    val url: String = "",
    val headers: Map<String, String> = emptyMap(),
    val toolTimeoutSecs: Long? = null,
    val description: String = "",
    val transportOptions: McpTransportOptions? = null,
)
