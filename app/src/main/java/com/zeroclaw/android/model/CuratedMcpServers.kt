/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.model

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Warning
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * Curated catalog of popular MCP servers for quick one-tap addition.
 *
 * Each entry provides enough info to pre-fill the add-server form.
 * Stdio servers show a warning about requiring Node.js.
 */
object CuratedMcpServers {

    /**
     * A pre-configured MCP server the user can add with minimal input.
     *
     * @property name Display name.
     * @property description Short description.
     * @property icon Material icon for the catalog card.
     * @property transport Transport type.
     * @property command Executable (stdio only).
     * @property args Command arguments (stdio only).
     * @property url Server URL (http/sse only).
     * @property envKeys Required environment variable keys the user must fill in.
     * @property note Optional warning or info note shown below the description.
     */
    data class CuratedServer(
        val name: String,
        val description: String,
        val icon: ImageVector,
        val transport: McpTransportType,
        val command: String = "",
        val args: List<String> = emptyList(),
        val url: String = "",
        val envKeys: List<String> = emptyList(),
        val note: String? = null,
    )

    val servers: List<CuratedServer> = listOf(
        CuratedServer(
            name = "GitHub",
            description = "Repositories, issues, pull requests, and code search",
            icon = Icons.Default.Code,
            transport = McpTransportType.HTTP,
            url = "https://api.githubcopilot.com/mcp/",
            envKeys = listOf("GITHUB_TOKEN"),
            note = "Uses GitHub's hosted remote MCP server. Requires a GitHub Personal Access Token with repo scope.",
        ),
        CuratedServer(
            name = "Brave Search",
            description = "Web and news search via Brave Search API",
            icon = Icons.Default.Search,
            transport = McpTransportType.HTTP,
            url = "https://api.search.brave.com/mcp",
            envKeys = listOf("BRAVE_API_KEY"),
        ),
        CuratedServer(
            name = "Memory",
            description = "Persistent knowledge graph memory across sessions",
            icon = Icons.Default.Memory,
            transport = McpTransportType.STDIO,
            command = "npx",
            args = listOf("-y", "@modelcontextprotocol/server-memory"),
            note = "Requires Node.js (npx). May not work without the Linux Sandbox.",
        ),
        CuratedServer(
            name = "Filesystem",
            description = "Read, write, and manage local files",
            icon = Icons.Default.Folder,
            transport = McpTransportType.STDIO,
            command = "npx",
            args = listOf("-y", "@modelcontextprotocol/server-filesystem"),
            note = "Requires Node.js (npx). Path argument needed.",
        ),
        CuratedServer(
            name = "PostgreSQL",
            description = "Query databases and inspect schemas",
            icon = Icons.Default.Storage,
            transport = McpTransportType.STDIO,
            command = "npx",
            args = listOf("-y", "@modelcontextprotocol/server-postgres"),
            envKeys = listOf("POSTGRES_CONNECTION_STRING"),
            note = "Requires Node.js (npx).",
        ),
        CuratedServer(
            name = "Sentry",
            description = "Issue tracking and error monitoring",
            icon = Icons.Default.Warning,
            transport = McpTransportType.HTTP,
            url = "https://mcp.sentry.dev/sse",
            envKeys = listOf("SENTRY_AUTH_TOKEN"),
        ),
    )
}
