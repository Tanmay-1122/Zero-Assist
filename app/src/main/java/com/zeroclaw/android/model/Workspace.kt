/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents an isolated workspace for organizing agents and conversations.
 *
 * Workspaces allow users to separate different projects or use cases,
 * with each workspace maintaining its own agent configuration, conversation history,
 * and execution state.
 *
 * @property id Unique identifier for the workspace (UUID format).
 * @property name Display name for the workspace (e.g., "Project X", "Research").
 * @property description Optional description of the workspace purpose.
 * @property isActive Whether this workspace is currently selected.
 * @property createdAt Timestamp (ISO-8601) when workspace was created.
 * @property lastUsedAt Timestamp (ISO-8601) of last interaction in this workspace.
 * @property agentIds List of agent IDs configured for this workspace.
 * @property color Accent color for the workspace (ARGB format).
 * @property icon Unicode character or emoji representing the workspace.
 * @property settings Workspace-specific configuration overrides.
 */
@Serializable
data class Workspace(
    val id: String,
    val name: String,
    val description: String = "",
    val isActive: Boolean = false,
    val createdAt: String = "",
    val lastUsedAt: String = "",
    val agentIds: List<String> = emptyList(),
    val color: Long = 0xFF6200EE,
    val icon: String = "📁",
    val settings: WorkspaceSettings = WorkspaceSettings(),
) {
    companion object {
        fun new(name: String, description: String = ""): Workspace =
            Workspace(
                id = java.util.UUID.randomUUID().toString(),
                name = name,
                description = description,
                createdAt = java.time.Instant.now().toString(),
                lastUsedAt = java.time.Instant.now().toString(),
            )
    }
}

/**
 * Workspace-specific configuration overrides.
 *
 * @property isolateHistory If true, conversations in this workspace are separate from others.
 * @property isolateAgents If true, agents are only visible within this workspace.
 * @property autoArchiveEnabled If true, old conversations auto-archive after configured days.
 * @property autoArchiveDays Number of days before conversations auto-archive.
 * @property allowCrossWorkspaceTools If true, agents can access tools from other workspaces.
 * @property customSystemPrompt Workspace-wide system prompt prepended to all agents.
 * @property defaultTemperature Default temperature for agents in this workspace.
 * @property memoryScope Scope of memory isolation: "workspace", "shared", or "local".
 */
@Serializable
data class WorkspaceSettings(
    val isolateHistory: Boolean = true,
    val isolateAgents: Boolean = true,
    val autoArchiveEnabled: Boolean = false,
    val autoArchiveDays: Int = 30,
    val allowCrossWorkspaceTools: Boolean = false,
    val customSystemPrompt: String = "",
    val defaultTemperature: Float = 0.7f,
    val memoryScope: String = "workspace", // "workspace", "shared", "local"
)

/**
 * Represents workspace metadata for quick display in UI.
 *
 * @property workspace The full Workspace object.
 * @property agentCount Number of agents in this workspace.
 * @property conversationCount Number of conversations in this workspace.
 * @property lastActivityTime Human-readable last activity (e.g., "2 hours ago").
 */
@Serializable
data class WorkspaceMetadata(
    val workspace: Workspace,
    val agentCount: Int = 0,
    val conversationCount: Int = 0,
    val lastActivityTime: String = "Never",
)

/**
 * Workspace context passed to repositories to scope queries.
 *
 * @property workspaceId Current workspace ID.
 * @property isolateHistory If true, queries are scoped to this workspace only.
 * @property isolateAgents If true, agent queries are scoped to this workspace only.
 */
data class WorkspaceContext(
    val workspaceId: String,
    val isolateHistory: Boolean = true,
    val isolateAgents: Boolean = true,
)
