/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.model

/**
 * Represents a preset template for creating agents.
 *
 * @property id Unique identifier for the template.
 * @property name Display name of the template.
 * @property role The agent role for this template.
 * @property description Brief description of what this agent does.
 * @property defaultSystemPrompt Pre-filled system prompt for the agent.
 * @property defaultTemperature Default temperature setting (0.0-1.0).
 * @property defaultMaxDepth Default maximum depth for agent reasoning.
 * @property tags List of tags categorizing this template.
 * @property accentColor Unique color for this agent role.
 * @property avatar Emoji avatar representing this template.
 */
data class AgentTemplate(
    val id: String,
    val name: String,
    val role: AgentRole,
    val description: String,
    val defaultSystemPrompt: String,
    val defaultTemperature: Float,
    val defaultMaxDepth: Int,
    val tags: List<String>,
    val accentColor: Long,
    val avatar: String,
)

/**
 * Collection of predefined agent templates.
 */
object AgentTemplates {
    /** All available starter templates. */
    val ALL = listOf(
        AgentTemplate(
            id = "master",
            name = "Master",
            role = AgentRole.MASTER,
            description = "Orchestrates all other agents. Assigns tasks, monitors progress, and synthesizes results.",
            defaultSystemPrompt = "You are the master orchestrator. Break down complex tasks, delegate subtasks to specialized agents, and synthesize their outputs into a coherent result. Always be concise when delegating and assign the task clearly.",
            defaultTemperature = 0.4f,
            defaultMaxDepth = 10,
            tags = listOf("orchestrator", "supervisor", "router"),
            accentColor = 0xFFFFD700,
            avatar = "👑",
        ),
        AgentTemplate(
            id = "researcher",
            name = "Researcher",
            role = AgentRole.RESEARCHER,
            description = "Searches the web, gathers information, and returns structured findings.",
            defaultSystemPrompt = "You are a research specialist. When given a topic or question, search thoroughly, cross-reference sources, and return a concise factual summary.",
            defaultTemperature = 0.3f,
            defaultMaxDepth = 5,
            tags = listOf("search", "data", "web"),
            accentColor = 0xFF00BCD4,
            avatar = "🔍",
        ),
        AgentTemplate(
            id = "coder",
            name = "Coder",
            role = AgentRole.CODER,
            description = "Writes, reviews, and fixes code in any language.",
            defaultSystemPrompt = "You are a coding expert. Write clean, production-ready code. Always include comments when they clarify intent, and fix bugs precisely without breaking other functionality.",
            defaultTemperature = 0.2f,
            defaultMaxDepth = 8,
            tags = listOf("code", "debug", "build"),
            accentColor = 0xFF4CAF50,
            avatar = "💻",
        ),
        AgentTemplate(
            id = "planner",
            name = "Planner",
            role = AgentRole.PLANNER,
            description = "Breaks down goals into structured action plans and timelines.",
            defaultSystemPrompt = "You are a strategic planner. Given a goal, break it into clear ordered steps with dependencies, time estimates, and success criteria.",
            defaultTemperature = 0.5f,
            defaultMaxDepth = 6,
            tags = listOf("plan", "strategy", "organize"),
            accentColor = 0xFFFF9800,
            avatar = "📋",
        ),
        AgentTemplate(
            id = "writer",
            name = "Writer",
            role = AgentRole.WRITER,
            description = "Drafts, edits, and polishes any written content.",
            defaultSystemPrompt = "You are a professional writer. Produce clear, engaging, well-structured content tailored to the requested tone and audience.",
            defaultTemperature = 0.7f,
            defaultMaxDepth = 5,
            tags = listOf("content", "draft", "edit"),
            accentColor = 0xFFE91E63,
            avatar = "✍️",
        ),
        AgentTemplate(
            id = "analyst",
            name = "Analyst",
            role = AgentRole.ANALYST,
            description = "Analyzes data, identifies patterns, and generates insights.",
            defaultSystemPrompt = "You are a data analyst. Given data or context, identify patterns, anomalies, and actionable insights. Present findings clearly.",
            defaultTemperature = 0.3f,
            defaultMaxDepth = 7,
            tags = listOf("data", "insights", "analysis"),
            accentColor = 0xFF9C27B0,
            avatar = "📊",
        ),
        AgentTemplate(
            id = "executor",
            name = "Executor",
            role = AgentRole.EXECUTOR,
            description = "Executes concrete tasks such as file operations, API calls, and code runs.",
            defaultSystemPrompt = "You are an executor agent. When given a specific task with clear inputs, execute it precisely and return the result without unnecessary explanation.",
            defaultTemperature = 0.1f,
            defaultMaxDepth = 4,
            tags = listOf("execute", "run", "action"),
            accentColor = 0xFFF44336,
            avatar = "⚡",
        ),
    )

    /**
     * Finds a template by its ID.
     */
    fun findById(id: String): AgentTemplate? = ALL.firstOrNull { it.id == id }

    /**
     * Finds all templates with a given role.
     */
    fun findByRole(role: AgentRole): List<AgentTemplate> = ALL.filter { it.role == role }
}
