/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.repository

import com.zeroclaw.android.model.Workspace
import com.zeroclaw.android.model.WorkspaceContext
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for workspace CRUD and management operations.
 *
 * Workspaces isolate agents, conversations, and execution state from each other,
 * allowing users to organize multiple projects or use cases independently.
 */
interface WorkspaceRepository {
    /** Observable list of all workspaces. */
    val allWorkspaces: Flow<List<Workspace>>

    /** Observable currently active workspace. */
    val activeWorkspace: Flow<Workspace?>

    /** Observable workspace context for scoping queries. */
    val workspaceContext: Flow<WorkspaceContext>

    /**
     * Returns the workspace with the given [id], or null if not found.
     *
     * @param id Unique workspace identifier.
     * @return The matching [Workspace] or null.
     */
    suspend fun getById(id: String): Workspace?

    /**
     * Creates a new workspace.
     *
     * @param workspace The workspace to create.
     */
    suspend fun create(workspace: Workspace)

    /**
     * Updates an existing workspace.
     *
     * @param workspace The workspace to update.
     */
    suspend fun update(workspace: Workspace)

    /**
     * Switches to the specified workspace, making it active.
     *
     * @param workspaceId ID of the workspace to activate.
     */
    suspend fun switchToWorkspace(workspaceId: String)

    /**
     * Deletes a workspace and optionally its associated data.
     *
     * @param workspaceId ID of the workspace to delete.
     * @param deleteConversations If true, also delete all conversations in this workspace.
     */
    suspend fun deleteWorkspace(workspaceId: String, deleteConversations: Boolean = true)

    /**
     * Adds an agent to a workspace.
     *
     * @param workspaceId The workspace ID.
     * @param agentId The agent ID to add.
     */
    suspend fun addAgentToWorkspace(workspaceId: String, agentId: String)

    /**
     * Removes an agent from a workspace.
     *
     * @param workspaceId The workspace ID.
     * @param agentId The agent ID to remove.
     */
    suspend fun removeAgentFromWorkspace(workspaceId: String, agentId: String)

    /**
     * Updates workspace last-used timestamp.
     *
     * @param workspaceId The workspace ID.
     */
    suspend fun updateLastUsed(workspaceId: String)

    /**
     * Ensures a default workspace exists.
     * If no workspaces exist, creates a "Default" workspace and activates it.
     */
    suspend fun ensureDefaultWorkspace()
}
