/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.workspace

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zeroclaw.android.ZeroClawApplication
import com.zeroclaw.android.model.Workspace
import com.zeroclaw.android.model.WorkspaceContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel for workspace management and switching.
 *
 * Coordinates workspace creation, deletion, switching, and agent assignment.
 * Exposes workspace state streams for UI consumption.
 *
 * @param application Application context for accessing repository.
 */
class WorkspaceViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as ZeroClawApplication
    private val workspaceRepository = app.workspaceRepository

    val allWorkspaces: StateFlow<List<Workspace>> =
        workspaceRepository.allWorkspaces.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            emptyList(),
        )

    val activeWorkspace: StateFlow<Workspace?> =
        workspaceRepository.activeWorkspace.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            null,
        )

    val workspaceContext: StateFlow<WorkspaceContext> =
        workspaceRepository.workspaceContext.stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            WorkspaceContext("", isolateHistory = false, isolateAgents = false),
        )

    /**
     * Create a new workspace.
     *
     * @param name Display name for the workspace.
     * @param description Optional description.
     * @param icon Optional emoji/character icon.
     * @param color Optional accent color (ARGB).
     */
    fun createWorkspace(
        name: String,
        description: String = "",
        icon: String = "📁",
        color: Long = 0xFF6200EE,
    ) {
        viewModelScope.launch {
            val workspace = Workspace.new(name, description).copy(
                icon = icon,
                color = color,
            )
            workspaceRepository.create(workspace)
            workspaceRepository.switchToWorkspace(workspace.id)
        }
    }

    /**
     * Switch to a different workspace.
     *
     * @param workspaceId ID of the workspace to activate.
     */
    fun switchWorkspace(workspaceId: String) {
        viewModelScope.launch {
            workspaceRepository.switchToWorkspace(workspaceId)
            workspaceRepository.updateLastUsed(workspaceId)
        }
    }

    /**
     * Rename a workspace.
     *
     * @param workspaceId ID of the workspace.
     * @param newName New display name.
     */
    fun renameWorkspace(workspaceId: String, newName: String) {
        viewModelScope.launch {
            val workspace = workspaceRepository.getById(workspaceId)
            if (workspace != null) {
                workspaceRepository.update(workspace.copy(name = newName))
            }
        }
    }

    /**
     * Update workspace description.
     *
     * @param workspaceId ID of the workspace.
     * @param description New description.
     */
    fun updateDescription(workspaceId: String, description: String) {
        viewModelScope.launch {
            val workspace = workspaceRepository.getById(workspaceId)
            if (workspace != null) {
                workspaceRepository.update(workspace.copy(description = description))
            }
        }
    }

    /**
     * Update workspace icon and color.
     *
     * @param workspaceId ID of the workspace.
     * @param icon New icon emoji/character.
     * @param color New accent color (ARGB).
     */
    fun updateAppearance(workspaceId: String, icon: String, color: Long) {
        viewModelScope.launch {
            val workspace = workspaceRepository.getById(workspaceId)
            if (workspace != null) {
                workspaceRepository.update(
                    workspace.copy(icon = icon, color = color),
                )
            }
        }
    }

    /**
     * Delete a workspace.
     *
     * @param workspaceId ID of the workspace to delete.
     * @param deleteConversations If true, also delete all conversations in this workspace.
     */
    fun deleteWorkspace(workspaceId: String, deleteConversations: Boolean = true) {
        viewModelScope.launch {
            workspaceRepository.deleteWorkspace(workspaceId, deleteConversations)
        }
    }

    /**
     * Add an agent to a workspace.
     *
     * @param workspaceId ID of the workspace.
     * @param agentId ID of the agent to add.
     */
    fun addAgentToWorkspace(workspaceId: String, agentId: String) {
        viewModelScope.launch {
            workspaceRepository.addAgentToWorkspace(workspaceId, agentId)
        }
    }

    /**
     * Remove an agent from a workspace.
     *
     * @param workspaceId ID of the workspace.
     * @param agentId ID of the agent to remove.
     */
    fun removeAgentFromWorkspace(workspaceId: String, agentId: String) {
        viewModelScope.launch {
            workspaceRepository.removeAgentFromWorkspace(workspaceId, agentId)
        }
    }
}
