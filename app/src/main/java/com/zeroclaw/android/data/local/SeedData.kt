/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.local

import com.zeroclaw.android.data.local.entity.AgentEntity
import com.zeroclaw.android.data.local.entity.PluginEntity
import com.zeroclaw.android.model.AgentRole
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
     * Returns all seed agent entities: a master agent and one agent per
     * specialized role, all disabled except the master.
     *
     * @return List of pre-configured [AgentEntity] instances.
     */
    fun seedAgents(): List<AgentEntity> =
        listOf(
            AgentEntity(
                id = "master",
                name = "Master",
                provider = "openai",
                modelName = "gpt-4o",
                isEnabled = true,
                systemPrompt = "You are the master orchestrator. Break down complex tasks, delegate subtasks to specialized agents, and synthesize their outputs into a coherent result. Always be concise when delegating and assign the task clearly.",
                channelsJson = "[]",
                temperature = 0.4f,
                maxDepth = 10,
                role = AgentRole.MASTER.name,
                avatar = AgentRole.MASTER.icon,
                tagsJson = """["orchestrator","supervisor","router"]""",
                isMaster = true,
                priority = 100,
                accentColor = 0xFFFFD700,
            ),
            AgentEntity(
                id = "researcher",
                name = "Researcher",
                provider = "openai",
                modelName = "gpt-4o",
                isEnabled = false,
                systemPrompt = "You are a research specialist. When given a topic or question, search thoroughly, cross-reference sources, and return a concise factual summary.",
                channelsJson = "[]",
                temperature = 0.3f,
                maxDepth = 5,
                role = AgentRole.RESEARCHER.name,
                avatar = AgentRole.RESEARCHER.icon,
                tagsJson = """["search","data","web"]""",
                isMaster = false,
                priority = 80,
                accentColor = 0xFF00BCD4,
            ),
            AgentEntity(
                id = "coder",
                name = "Coder",
                provider = "openai",
                modelName = "gpt-4o",
                isEnabled = false,
                systemPrompt = "You are a coding expert. Write clean, production-ready code. Always include comments when they clarify intent, and fix bugs precisely without breaking other functionality.",
                channelsJson = "[]",
                temperature = 0.2f,
                maxDepth = 8,
                role = AgentRole.CODER.name,
                avatar = AgentRole.CODER.icon,
                tagsJson = """["code","debug","build"]""",
                isMaster = false,
                priority = 70,
                accentColor = 0xFF4CAF50,
            ),
            AgentEntity(
                id = "planner",
                name = "Planner",
                provider = "openai",
                modelName = "gpt-4o",
                isEnabled = false,
                systemPrompt = "You are a strategic planner. Given a goal, break it into clear ordered steps with dependencies, time estimates, and success criteria.",
                channelsJson = "[]",
                temperature = 0.5f,
                maxDepth = 6,
                role = AgentRole.PLANNER.name,
                avatar = AgentRole.PLANNER.icon,
                tagsJson = """["plan","strategy","organize"]""",
                isMaster = false,
                priority = 60,
                accentColor = 0xFFFF9800,
            ),
            AgentEntity(
                id = "writer",
                name = "Writer",
                provider = "openai",
                modelName = "gpt-4o",
                isEnabled = false,
                systemPrompt = "You are a professional writer. Produce clear, engaging, well-structured content tailored to the requested tone and audience.",
                channelsJson = "[]",
                temperature = 0.7f,
                maxDepth = 5,
                role = AgentRole.WRITER.name,
                avatar = AgentRole.WRITER.icon,
                tagsJson = """["content","draft","edit"]""",
                isMaster = false,
                priority = 50,
                accentColor = 0xFFE91E63,
            ),
            AgentEntity(
                id = "analyst",
                name = "Analyst",
                provider = "openai",
                modelName = "gpt-4o",
                isEnabled = false,
                systemPrompt = "You are a data analyst. Given data or context, identify patterns, anomalies, and actionable insights. Present findings clearly.",
                channelsJson = "[]",
                temperature = 0.3f,
                maxDepth = 7,
                role = AgentRole.ANALYST.name,
                avatar = AgentRole.ANALYST.icon,
                tagsJson = """["data","insights","analysis"]""",
                isMaster = false,
                priority = 40,
                accentColor = 0xFF9C27B0,
            ),
            AgentEntity(
                id = "executor",
                name = "Executor",
                provider = "openai",
                modelName = "gpt-4o",
                isEnabled = false,
                systemPrompt = "You are an executor agent. When given a specific task with clear inputs, execute it precisely and return the result without unnecessary explanation.",
                channelsJson = "[]",
                temperature = 0.1f,
                maxDepth = 4,
                role = AgentRole.EXECUTOR.name,
                avatar = AgentRole.EXECUTOR.icon,
                tagsJson = """["execute","run","action"]""",
                isMaster = false,
                priority = 30,
                accentColor = 0xFFF44336,
            ),
        )

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
                description = "Self-contained Alpine Linux environment via proot. Provides a full Linux shell for the AI to execute commands, install packages, and run scripts.",
                version = "1.0.0",
                author = "Zero-Assist",
                category = PluginCategory.TOOL.name,
                isInstalled = false,
                isEnabled = false,
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
                description = "Run shell commands via the Termux Android terminal emulator. Provides command execution, streaming output, and a full Linux environment.",
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
