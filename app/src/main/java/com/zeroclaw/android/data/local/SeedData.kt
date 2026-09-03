/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.local

import com.zeroclaw.android.data.local.entity.PluginEntity
import com.zeroclaw.android.model.CommunityPlugins
import com.zeroclaw.android.model.OfficialPlugins
import com.zeroclaw.android.model.PluginCategory
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Provides seed data for first-install database population.
 *
 * These functions return the same sample data previously defined in the
 * in-memory repositories, ensuring a seamless migration experience.
 */
object SeedData {
    /**
     * Returns all seed plugin entities: official built-in plugins plus
     * community sample plugins.
     *
     * @return List of pre-configured [PluginEntity] instances.
     */
    @Suppress("LongMethod")
    fun seedPlugins(): List<PluginEntity> = officialPluginEntities() + communityPluginEntities()

    @Suppress("LongMethod")
    private fun officialPluginEntities(): List<PluginEntity> =
        listOf(
            PluginEntity(
                id = OfficialPlugins.WEB_SEARCH,
                name = "Web Search",
                description = "Search the web via DuckDuckGo or Brave.",
                version = "1.0.0",
                author = "Zero-Assist",
                category = PluginCategory.TOOL.name,
                isInstalled = true,
                isEnabled = false,
                configJson = "{}",
            ),
            PluginEntity(
                id = OfficialPlugins.WEB_FETCH,
                name = "Web Fetch",
                description = "Fetch and read web page content.",
                version = "1.0.0",
                author = "Zero-Assist",
                category = PluginCategory.TOOL.name,
                isInstalled = true,
                isEnabled = false,
                configJson = "{}",
            ),
            PluginEntity(
                id = OfficialPlugins.HTTP_REQUEST,
                name = "HTTP Request",
                description = "Make HTTP calls to external APIs.",
                version = "1.0.0",
                author = "Zero-Assist",
                category = PluginCategory.TOOL.name,
                isInstalled = true,
                isEnabled = false,
                configJson = "{}",
            ),
            PluginEntity(
                id = OfficialPlugins.BROWSER,
                name = "Browser",
                description = "Web browser automation for agent-driven navigation and interaction.",
                version = "1.0.0",
                author = "Zero-Assist",
                category = PluginCategory.TOOL.name,
                isInstalled = true,
                isEnabled = false,
                configJson = "{}",
            ),
            PluginEntity(
                id = OfficialPlugins.COMPOSIO,
                name = "Composio",
                description = "Third-party tool integrations via Composio.",
                version = "1.0.0",
                author = "Zero-Assist",
                category = PluginCategory.TOOL.name,
                isInstalled = true,
                isEnabled = false,
                configJson = "{}",
            ),
            PluginEntity(
                id = OfficialPlugins.SHARED_FOLDER,
                name = "Shared Folder",
                description = "Access a user-selected folder on Android via Storage Access Framework. Provides tools to list, read, and write files in the shared folder.",
                version = "1.0.0",
                author = "Zero-Assist",
                category = PluginCategory.TOOL.name,
                isInstalled = true,
                isEnabled = false,
                configJson = "{}",
            ),
            PluginEntity(
                id = OfficialPlugins.WORKFLOW_FOLDER,
                name = "Workflow Folder",
                description = "Access the default app workflow folder or a user-selected workflow folder. Provides tools to list, read, and write workflow files.",
                version = "1.0.0",
                author = "Zero-Assist",
                category = PluginCategory.TOOL.name,
                isInstalled = true,
                isEnabled = true,
                configJson = "{}",
            ),
            PluginEntity(
                id = OfficialPlugins.LINUX_SANDBOX,
                name = "Linux Sandbox",
                description = "Self-contained Alpine Linux environment via proot. The default shell backend — ALL shell commands run inside this sandbox.",
                version = "1.0.0",
                author = "Zero-Assist",
                category = PluginCategory.TOOL.name,
                isInstalled = true,
                isEnabled = true,
                configJson = "{}",
            ),
            PluginEntity(
                id = OfficialPlugins.GOOGLE_WORKSPACE,
                name = "Google Workspace",
                description = "Access Gmail, Drive, Calendar, Sheets, Docs, and more via the gws CLI in the Linux sandbox.",
                version = "1.0.0",
                author = "Zero-Assist",
                category = PluginCategory.TOOL.name,
                isInstalled = false,
                isEnabled = false,
                configJson = "{}",
            ),
            PluginEntity(
                id = OfficialPlugins.TERMUX,
                name = "Termux",
                description = "LEGACY, OPT-IN. Run shell commands via the Termux Android terminal emulator instead of the Linux sandbox. Only enable when you explicitly need Termux-specific tools or streaming output.",
                version = "1.0.0",
                author = "Zero-Assist",
                category = PluginCategory.TOOL.name,
                isInstalled = false,
                isEnabled = false,
                configJson = "{}",
            ),
            PluginEntity(
                id = OfficialPlugins.PROOT_BROWSER,
                name = "PRoot Browser",
                description = "Browser automation tools (agent-browser, Chromium, text browsers) running inside PRoot Alpine Linux environment. Supports on-demand ChromeDriver for WebDriver-based automation.",
                version = "1.0.0",
                author = "Zero-Assist",
                category = PluginCategory.TOOL.name,
                isInstalled = false,
                isEnabled = false,
                configJson = Json.encodeToString(
                    mapOf(
                        "distro" to "alpine",
                        "backend" to "agent_browser",
                        "session_name" to "zeroclaw",
                        "chrome_driver_port" to "9515",
                    )
                ),
            ),
        )

    private fun communityPluginEntities(): List<PluginEntity> =
        listOf(
            PluginEntity(
                id = "plugin-http-channel",
                name = "HTTP Channel",
                description = "REST API channel for agent communication.",
                version = "1.0.0",
                author = "Zero-Assist",
                category = PluginCategory.CHANNEL.name,
                isInstalled = true,
                isEnabled = true,
                configJson = Json.encodeToString(mapOf("port" to "8080", "host" to "0.0.0.0")),
            ),
        )
}
