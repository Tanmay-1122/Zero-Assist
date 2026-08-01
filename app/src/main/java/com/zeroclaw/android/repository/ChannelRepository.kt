/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.repository

import com.zeroclaw.android.model.ChannelConfiguration
import com.zeroclaw.android.model.ChannelCredentials
import com.zeroclaw.android.model.ChannelSyncEvent
import com.zeroclaw.android.model.ChannelSettings
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for channel configuration management.
 *
 * Provides CRUD operations, sync scheduling, credentials management, and multi-workspace isolation.
 */
interface ChannelRepository {

    /**
     * Create a new channel configuration.
     *
     * @param channel The channel to create.
     * @return Channel ID.
     */
    suspend fun createChannel(channel: ChannelConfiguration): String

    /**
     * Update an existing channel configuration.
     *
     * @param channel The updated channel.
     */
    suspend fun updateChannel(channel: ChannelConfiguration)

    /**
     * Delete a channel by ID.
     *
     * @param channelId Channel ID to delete.
     */
    suspend fun deleteChannel(channelId: String)

    /**
     * Get a channel by ID.
     *
     * @param channelId Channel ID to retrieve.
     * @return Channel configuration or null if not found.
     */
    suspend fun getChannel(channelId: String): ChannelConfiguration?

    /**
     * Observe all channels in a workspace.
     *
     * @param workspaceId Workspace to query.
     * @return Flow of channel lists.
     */
    fun observeChannelsByWorkspace(workspaceId: String): Flow<List<ChannelConfiguration>>

    /**
     * Get all active channels in a workspace.
     *
     * @param workspaceId Workspace to query.
     * @return List of active channels.
     */
    suspend fun getActiveChannels(workspaceId: String): List<ChannelConfiguration>

    /**
     * Get channels of a specific type.
     *
     * @param workspaceId Workspace to query.
     * @param type Channel type ("reddit", "twitter", etc).
     * @return List of matching channels.
     */
    suspend fun getChannelsByType(workspaceId: String, type: String): List<ChannelConfiguration>

    /**
     * Get channels that need syncing.
     *
     * @param workspaceId Workspace to query.
     * @return List of channels exceeding sync interval.
     */
    suspend fun getChannelsNeedingSync(workspaceId: String): List<ChannelConfiguration>

    /**
     * Update connection status for a channel.
     *
     * @param channelId Channel to update.
     * @param connected Whether the channel is connected.
     * @param errorMessage Optional error message if disconnected.
     */
    suspend fun updateConnectionStatus(
        channelId: String,
        connected: Boolean,
        errorMessage: String? = null,
    )

    /**
     * Record a sync completion.
     *
     * @param channelId Channel that was synced.
     * @param itemsProcessed Count of items processed.
     * @param errors Count of errors encountered.
     */
    suspend fun recordSync(
        channelId: String,
        itemsProcessed: Int,
        errors: Int,
    )

    /**
     * Store encrypted credentials for a channel.
     *
     * @param channelId Channel ID.
     * @param credentials Credentials object to encrypt and store.
     */
    suspend fun storeCredentials(channelId: String, credentials: ChannelCredentials)

    /**
     * Retrieve and decrypt credentials for a channel.
     *
     * @param channelId Channel ID.
     * @return Decrypted credentials or null if not configured.
     */
    suspend fun getCredentials(channelId: String): ChannelCredentials?

    /**
     * Update channel settings.
     *
     * @param channelId Channel ID.
     * @param settings Settings object to store.
     */
    suspend fun updateSettings(channelId: String, settings: ChannelSettings)

    /**
     * Retrieve channel settings.
     *
     * @param channelId Channel ID.
     * @return Parsed settings object or empty if not configured.
     */
    suspend fun getSettings(channelId: String): ChannelSettings?

    /**
     * Toggle channel active state.
     *
     * @param channelId Channel to toggle.
     */
    suspend fun toggleChannelActive(channelId: String)

    /**
     * Get channel count in workspace.
     *
     * @param workspaceId Workspace to query.
     * @return Number of channels.
     */
    suspend fun getChannelCount(workspaceId: String): Int

    /**
     * Clear all channels in a workspace.
     *
     * @param workspaceId Workspace to clear.
     */
    suspend fun clearWorkspaceChannels(workspaceId: String)

    /**
     * Get channel sync statistics.
     *
     * @param workspaceId Workspace to query.
     * @return Map with counts by channel type and connection status.
     */
    suspend fun getChannelSyncStats(workspaceId: String): Map<String, Any>

    /**
     * Define default available channel types.
     */
    companion object {
        val AVAILABLE_TYPES = listOf(
            "reddit",
            "twitter",
            "notion",
            "webhook",
            "voice_call",
            "bluesky",
        )
    }
}
