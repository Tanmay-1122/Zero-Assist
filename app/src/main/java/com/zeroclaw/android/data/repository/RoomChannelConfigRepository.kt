/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.repository

import android.content.SharedPreferences
import com.zeroclaw.android.backup.SyncRepository
import com.zeroclaw.android.data.local.dao.ConnectedChannelDao
import com.zeroclaw.android.data.local.entity.toEntity
import com.zeroclaw.android.data.local.entity.toModel
import com.zeroclaw.android.model.ChannelType
import com.zeroclaw.android.model.ConnectedChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class RoomChannelConfigRepository(
    private val dao: ConnectedChannelDao,
    private val securePrefs: SharedPreferences,
    private val syncRepository: SyncRepository? = null,
) : ChannelConfigRepository {
    override val channels: Flow<List<ConnectedChannel>> =
        dao.observeAll().map { entities ->
            entities.mapNotNull { it.toModel() }
        }

    override suspend fun getById(id: String): ConnectedChannel? = dao.getById(id)?.toModel()

    override suspend fun getByType(type: ChannelType): ConnectedChannel? = dao.getByType(type.name)?.toModel()

    override suspend fun existsForType(type: ChannelType): Boolean = dao.getByType(type.name) != null

    override suspend fun save(
        channel: ConnectedChannel,
        secrets: Map<String, String>,
    ) {
        dao.upsert(channel.toEntity())
        val editor = securePrefs.edit()
        secrets.forEach { (key, value) ->
            editor.putString(secretKey(channel.id, key), value)
        }
        editor.apply()
        syncRepository?.markPendingSync()
    }

    override suspend fun delete(id: String) {
        val entity = dao.getById(id)
        dao.deleteById(id)
        if (entity != null) {
            val model = entity.toModel()
            if (model != null) {
                val editor = securePrefs.edit()
                model.type.fields
                    .filter { it.isSecret }
                    .forEach { field ->
                        editor.remove(secretKey(id, field.key))
                    }
                editor.apply()
            }
        }
        syncRepository?.markPendingSync()
    }

    override suspend fun toggleEnabled(id: String) {
        dao.toggleEnabled(id)
        syncRepository?.markPendingSync()
    }

    override suspend fun setEnabled(
        id: String,
        enabled: Boolean,
    ) {
        dao.setEnabled(id, enabled)
        syncRepository?.markPendingSync()
    }

    override fun getSecrets(channelId: String): Map<String, String> {
        val all = securePrefs.all
        val prefix = "channel_${channelId}_"
        return all.entries
            .filter { it.key.startsWith(prefix) }
            .associate { (key, value) ->
                key.removePrefix(prefix) to (value as? String).orEmpty()
            }
    }

    override suspend fun getEnabledWithSecrets(): List<Pair<ConnectedChannel, Map<String, String>>> {
        val enabledChannels = dao.getAllEnabled().mapNotNull { it.toModel() }
        val allSecrets = securePrefs.all
        return enabledChannels.map { channel ->
            val prefix = "channel_${channel.id}_"
            val secrets =
                allSecrets.entries
                    .filter { it.key.startsWith(prefix) }
                    .associate { (key, value) ->
                        key.removePrefix(prefix) to (value as? String).orEmpty()
                    }
            val merged = channel.configValues + secrets
            channel to merged
        }
    }

    private fun secretKey(
        channelId: String,
        fieldKey: String,
    ): String = "channel_${channelId}_$fieldKey"
}
