/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.repository

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.zeroclaw.android.model.Workspace
import com.zeroclaw.android.model.WorkspaceContext
import com.zeroclaw.android.model.WorkspaceSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val Context.workspaceDataStore by preferencesDataStore(name = "workspaces")

private val json = Json { ignoreUnknownKeys = true }

// DataStore preference keys
private val KEY_ACTIVE_WORKSPACE_ID = stringPreferencesKey("active_workspace_id")
private val KEY_WORKSPACES_JSON = stringPreferencesKey("workspaces_json")
private val KEY_WORKSPACE_COUNT = intPreferencesKey("workspace_count")

/**
 * DataStore-backed implementation of WorkspaceRepository.
 *
 * Persists workspaces and active workspace selection to encrypted SharedPreferences
 * via DataStore. Provides workspace isolation for agents and conversations.
 *
 * @param context Application context for accessing DataStore.
 */
class DataStoreWorkspaceRepository(
    private val context: Context,
) : WorkspaceRepository {

    // Phase 1: the old init block ran ensureDefaultWorkspace() via runBlocking,
    // stalling the main thread on every cold start (this repo is built in
    // Application.onCreate). The default-workspace seed now runs from an IO
    // coroutine in ZeroClawApplication.onCreate; all readers observe Flows,
    // so they update reactively once the seed completes.

    // NOTE: activeWorkspaceId MUST be declared before activeWorkspace because
    // activeWorkspace's initializer references it.
    private val activeWorkspaceId: Flow<String> =
        context.workspaceDataStore.data.map { prefs ->
            prefs[KEY_ACTIVE_WORKSPACE_ID] ?: ""
        }

    override val allWorkspaces: Flow<List<Workspace>> =
        context.workspaceDataStore.data.map { prefs ->
            val raw = prefs[KEY_WORKSPACES_JSON] ?: "[]"
            try {
                json.decodeFromString<List<Workspace>>(raw)
            } catch (e: Exception) {
                emptyList()
            }
        }

    override val activeWorkspace: Flow<Workspace?> =
        combine(allWorkspaces, activeWorkspaceId) { workspaces, activeId ->
            workspaces.find { it.id == activeId }
        }

    override val workspaceContext: Flow<WorkspaceContext> =
        combine(activeWorkspace, allWorkspaces) { active, _ ->
            val workspace = active ?: return@combine WorkspaceContext(
                workspaceId = "default",
                isolateHistory = false,
                isolateAgents = false,
            )
            WorkspaceContext(
                workspaceId = workspace.id,
                isolateHistory = workspace.settings.isolateHistory,
                isolateAgents = workspace.settings.isolateAgents,
            )
        }

    override suspend fun getById(id: String): Workspace? = withContext(Dispatchers.IO) {
        val raw = context.workspaceDataStore.data.first()[KEY_WORKSPACES_JSON] ?: "[]"
        try {
            json.decodeFromString<List<Workspace>>(raw).find { it.id == id }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun create(workspace: Workspace): Unit = withContext(Dispatchers.IO) {
        context.workspaceDataStore.edit { prefs ->
            val existing = prefs[KEY_WORKSPACES_JSON] ?: "[]"
            val workspaces = try {
                json.decodeFromString<MutableList<Workspace>>(existing)
            } catch (e: Exception) {
                mutableListOf()
            }
            workspaces.add(workspace)
            prefs[KEY_WORKSPACES_JSON] = json.encodeToString<List<Workspace>>(workspaces)
            prefs[KEY_WORKSPACE_COUNT] = workspaces.size
        }
        Unit
    }

    override suspend fun update(workspace: Workspace): Unit = withContext(Dispatchers.IO) {
        context.workspaceDataStore.edit { prefs ->
            val existing = prefs[KEY_WORKSPACES_JSON] ?: "[]"
            val workspaces = try {
                json.decodeFromString<MutableList<Workspace>>(existing)
            } catch (e: Exception) {
                mutableListOf()
            }
            val index = workspaces.indexOfFirst { it.id == workspace.id }
            if (index >= 0) {
                workspaces[index] = workspace
                prefs[KEY_WORKSPACES_JSON] = json.encodeToString<List<Workspace>>(workspaces)
            }
        }
        Unit
    }

    override suspend fun switchToWorkspace(workspaceId: String): Unit = withContext(Dispatchers.IO) {
        context.workspaceDataStore.edit { prefs ->
            prefs[KEY_ACTIVE_WORKSPACE_ID] = workspaceId
        }
        Unit
    }

    override suspend fun deleteWorkspace(
        workspaceId: String,
        deleteConversations: Boolean,
    ): Unit = withContext(Dispatchers.IO) {
        context.workspaceDataStore.edit { prefs ->
            val existing = prefs[KEY_WORKSPACES_JSON] ?: "[]"
            val workspaces = try {
                json.decodeFromString<MutableList<Workspace>>(existing)
            } catch (e: Exception) {
                mutableListOf()
            }
            workspaces.removeAll { it.id == workspaceId }
            prefs[KEY_WORKSPACES_JSON] = json.encodeToString<List<Workspace>>(workspaces)
            prefs[KEY_WORKSPACE_COUNT] = workspaces.size
        }
        Unit
    }

    override suspend fun addAgentToWorkspace(workspaceId: String, agentId: String) {
        // Workspace→agent membership is tracked at the agent level; no-op here.
    }

    override suspend fun removeAgentFromWorkspace(workspaceId: String, agentId: String) {
        // Workspace→agent membership is tracked at the agent level; no-op here.
    }

    override suspend fun updateLastUsed(workspaceId: String): Unit = withContext(Dispatchers.IO) {
        context.workspaceDataStore.edit { prefs ->
            val existing = prefs[KEY_WORKSPACES_JSON] ?: "[]"
            val workspaces = try {
                json.decodeFromString<MutableList<Workspace>>(existing)
            } catch (e: Exception) {
                mutableListOf()
            }
            val index = workspaces.indexOfFirst { it.id == workspaceId }
            if (index >= 0) {
                workspaces[index] = workspaces[index].copy(
                    lastUsedAt = System.currentTimeMillis().toString(),
                )
                prefs[KEY_WORKSPACES_JSON] = json.encodeToString<List<Workspace>>(workspaces)
            }
        }
        Unit
    }

    override suspend fun ensureDefaultWorkspace() {
        val raw = context.workspaceDataStore.data.first()[KEY_WORKSPACES_JSON] ?: "[]"
        val workspaces = try {
            json.decodeFromString<List<Workspace>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
        if (workspaces.none { it.id == "default" }) {
            val defaultWorkspace = Workspace(
                id = "default",
                name = "Default Workspace",
                description = "System default workspace",
                settings = WorkspaceSettings(
                    isolateHistory = false,
                    isolateAgents = false,
                ),
                createdAt = System.currentTimeMillis().toString(),
            )
            create(defaultWorkspace)
            switchToWorkspace("default")
        }
    }
}
