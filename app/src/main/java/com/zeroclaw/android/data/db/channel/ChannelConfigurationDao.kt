/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.db.channel

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.zeroclaw.android.model.ChannelConfiguration
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for ChannelConfiguration persistence.
 *
 * Handles CRUD operations for channel configurations, with workspace-scoped queries
 * for multi-workspace isolation.
 */
@Dao
interface ChannelConfigurationDao {

    /**
     * Insert a new channel configuration.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(channel: ChannelConfiguration): Long

    /**
     * Insert multiple channels in batch.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(channels: List<ChannelConfiguration>)

    /**
     * Update an existing channel configuration.
     */
    @Update
    suspend fun update(channel: ChannelConfiguration)

    /**
     * Delete a channel configuration by ID.
     */
    @Query("DELETE FROM channels_new WHERE id = :channelId")
    suspend fun deleteById(channelId: String)

    /**
     * Delete a channel by object reference.
     */
    @Delete
    suspend fun delete(channel: ChannelConfiguration)

    /**
     * Retrieve a channel by ID.
     */
    @Query("SELECT * FROM channels_new WHERE id = :channelId LIMIT 1")
    suspend fun getById(channelId: String): ChannelConfiguration?

    /**
     * Get all channels in a workspace.
     */
    @Query("""
        SELECT * FROM channels_new 
        WHERE workspaceId = :workspaceId
        ORDER BY createdAt DESC
    """)
    fun getByWorkspace(workspaceId: String): Flow<List<ChannelConfiguration>>

    /**
     * Get all active channels in a workspace (single-shot query).
     */
    @Query("""
        SELECT * FROM channels_new 
        WHERE workspaceId = :workspaceId AND isActive = 1
        ORDER BY createdAt DESC
    """)
    suspend fun getActiveChannelsByWorkspace(workspaceId: String): List<ChannelConfiguration>

    /**
     * Get all channels of a specific type in a workspace.
     */
    @Query("""
        SELECT * FROM channels_new 
        WHERE workspaceId = :workspaceId AND type = :channelType
        ORDER BY createdAt DESC
    """)
    suspend fun getByType(workspaceId: String, channelType: String): List<ChannelConfiguration>

    /**
     * Get channels that need syncing (autoSync enabled, lastSyncAt older than syncIntervalMinutes).
     */
    @Query("""
        SELECT * FROM channels_new 
        WHERE workspaceId = :workspaceId 
        AND isActive = 1 
        AND autoSync = 1 
        AND (lastSyncAt IS NULL OR lastSyncAt < datetime('now', '-' || syncIntervalMinutes || ' minutes'))
        ORDER BY lastSyncAt ASC
    """)
    suspend fun getChannelsNeedingSync(workspaceId: String): List<ChannelConfiguration>

    /**
     * Update connection status for a channel.
     */
    @Query("""
        UPDATE channels_new 
        SET connectionStatus = :status, errorMessage = :errorMessage, lastSyncAt = :syncTime
        WHERE id = :channelId
    """)
    suspend fun updateConnectionStatus(
        channelId: String,
        status: String,
        errorMessage: String?,
        syncTime: String,
    )

    /**
     * Update sync timestamp for a channel.
     */
    @Query("""
        UPDATE channels_new 
        SET lastSyncAt = :syncTime, connectionStatus = 'connected'
        WHERE id = :channelId
    """)
    suspend fun updateLastSync(channelId: String, syncTime: String)

    /**
     * Update credentials for a channel (encrypted).
     */
    @Query("""
        UPDATE channels_new 
        SET credentials_json = :encryptedCredentials
        WHERE id = :channelId
    """)
    suspend fun updateCredentials(channelId: String, encryptedCredentials: String)

    /**
     * Update channel settings.
     */
    @Query("""
        UPDATE channels_new 
        SET settings_json = :settings
        WHERE id = :channelId
    """)
    suspend fun updateSettings(channelId: String, settings: String)

    /**
     * Toggle channel active state.
     */
    @Query("""
        UPDATE channels_new 
        SET isActive = NOT isActive
        WHERE id = :channelId
    """)
    suspend fun toggleActive(channelId: String)

    /**
     * Get count of channels in workspace.
     */
    @Query("SELECT COUNT() FROM channels_new WHERE workspaceId = :workspaceId")
    suspend fun getChannelCount(workspaceId: String): Int

    /**
     * Get count of active channels in workspace.
     */
    @Query("SELECT COUNT() FROM channels_new WHERE workspaceId = :workspaceId AND isActive = 1")
    suspend fun getActiveChannelCount(workspaceId: String): Int

    /**
     * Delete all channels in a workspace.
     */
    @Query("DELETE FROM channels_new WHERE workspaceId = :workspaceId")
    suspend fun deleteByWorkspace(workspaceId: String)

    /**
     * Get channels by connection status.
     */
    @Query("""
        SELECT * FROM channels_new 
        WHERE workspaceId = :workspaceId AND connectionStatus = :status
        ORDER BY lastSyncAt DESC
    """)
    suspend fun getByConnectionStatus(workspaceId: String, status: String): List<ChannelConfiguration>
}
