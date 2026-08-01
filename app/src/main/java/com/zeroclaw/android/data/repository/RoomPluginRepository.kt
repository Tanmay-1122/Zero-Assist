/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.repository

import com.zeroclaw.android.backup.SyncRepository
import com.zeroclaw.android.data.local.dao.PluginDao
import com.zeroclaw.android.data.local.entity.PluginEntity
import com.zeroclaw.android.data.local.entity.toModel
import com.zeroclaw.android.model.AppSettings
import com.zeroclaw.android.model.OfficialPlugins
import com.zeroclaw.android.model.Plugin
import com.zeroclaw.android.model.RemotePlugin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Room-backed implementation of [PluginRepository].
 *
 * @param dao           DAO for all plugin CRUD operations.
 * @param syncRepository Optional backup/sync repository; when present, mutations call
 *                       [SyncRepository.markPendingSync] so Drive backup stays current.
 */
class RoomPluginRepository(
    private val dao: PluginDao,
    private val syncRepository: SyncRepository? = null,
) : PluginRepository {

    private val json = Json { ignoreUnknownKeys = true }

    /** Observable list of all known plugins ordered by name. */
    override val plugins: Flow<List<Plugin>> =
        dao.observeAll().map { entities -> entities.map { it.toModel() } }

    override suspend fun getById(id: String): Plugin? =
        dao.getById(id)?.toModel()

    override fun observeById(id: String): Flow<Plugin?> =
        dao.observeById(id).map { it?.toModel() }

    override suspend fun install(id: String) {
        dao.setInstalled(id)
        syncRepository?.markPendingSync()
    }

    override suspend fun uninstall(id: String) {
        dao.uninstall(id)
        syncRepository?.markPendingSync()
    }

    override suspend fun toggleEnabled(id: String) {
        dao.toggleEnabled(id)
        syncRepository?.markPendingSync()
    }

    override suspend fun setEnabled(id: String, enabled: Boolean) {
        dao.setEnabled(id, enabled)
        syncRepository?.markPendingSync()
    }

    override suspend fun updateConfig(pluginId: String, key: String, value: String) {
        val entity = dao.getById(pluginId) ?: return
        val existing: Map<String, String> = try {
            val obj = json.parseToJsonElement(entity.configJson).jsonObject
            obj.entries.associate { (k, v) -> k to v.jsonPrimitive.content }
        } catch (_: Exception) {
            emptyMap()
        }
        val merged = existing.toMutableMap().apply { put(key, value) }
        val jsonStr = JsonObject(merged.mapValues { (_, v) -> JsonPrimitive(v) }).toString()
        dao.updateConfigJson(pluginId, jsonStr)
        syncRepository?.markPendingSync()
    }

    override suspend fun mergeRemotePlugins(remotePlugins: List<RemotePlugin>) {
        if (remotePlugins.isEmpty()) return
        val existingIds = dao.getExistingIds(remotePlugins.map { it.id }).toSet()
        val toInsert = mutableListOf<PluginEntity>()
        for (remote in remotePlugins) {
            if (remote.id in existingIds) {
                dao.updateMetadata(
                    id = remote.id,
                    name = remote.name,
                    description = remote.description,
                    version = remote.version,
                    author = remote.author,
                    category = remote.category,
                    remoteVersion = remote.version,
                )
            } else {
                toInsert.add(
                    PluginEntity(
                        id = remote.id,
                        name = remote.name,
                        description = remote.description,
                        version = remote.version,
                        author = remote.author,
                        category = remote.category,
                        isInstalled = false,
                        isEnabled = false,
                        configJson = "{}",
                        remoteVersion = remote.version,
                    )
                )
            }
        }
        if (toInsert.isNotEmpty()) dao.insertAllIgnoreConflicts(toInsert)
    }

    override suspend fun syncOfficialPluginStates(settings: AppSettings) {
        val states = mapOf(
            OfficialPlugins.WEB_SEARCH      to settings.webSearchEnabled,
            OfficialPlugins.WEB_FETCH       to settings.webFetchEnabled,
            OfficialPlugins.HTTP_REQUEST    to settings.httpRequestEnabled,
            OfficialPlugins.BROWSER         to settings.browserEnabled,
            OfficialPlugins.COMPOSIO        to settings.composioEnabled,
            OfficialPlugins.SHARED_FOLDER   to settings.sharedFolderEnabled,
            OfficialPlugins.WORKFLOW_FOLDER to settings.workflowFolderEnabled,
            OfficialPlugins.LINUX_SANDBOX   to settings.linuxSandboxEnabled,
            OfficialPlugins.GOOGLE_WORKSPACE to settings.googleWorkspaceEnabled,
            OfficialPlugins.TERMUX          to settings.termuxEnabled,
            OfficialPlugins.PROOT_BROWSER   to settings.prootBrowserEnabled,
        )
        for ((id, enabled) in states) {
            dao.setEnabled(id, enabled)
        }
    }
}
