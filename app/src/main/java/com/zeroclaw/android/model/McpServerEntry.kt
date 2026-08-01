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
)
