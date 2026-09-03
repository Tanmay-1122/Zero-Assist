/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.channels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zeroclaw.android.model.ChannelConfiguration
import com.zeroclaw.android.model.ChannelCredentials
import com.zeroclaw.android.model.ChannelSettings
import com.zeroclaw.android.repository.ChannelRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for channel configuration and management.
 *
 * Manages channel CRUD operations, sync scheduling, and connection states.
 *
 * @param application Application context.
 */
class ChannelViewModel(application: Application) : AndroidViewModel(application) {
    private val channelRepository: ChannelRepository =
        (application as com.zeroclaw.android.ZeroClawApplication).channelRepository

    private val _allChannels = MutableStateFlow<List<ChannelConfiguration>>(emptyList())
    val allChannels: StateFlow<List<ChannelConfiguration>> = _allChannels.asStateFlow()

    private val _selectedChannel = MutableStateFlow<ChannelConfiguration?>(null)
    val selectedChannel: StateFlow<ChannelConfiguration?> = _selectedChannel.asStateFlow()

    private val _channelStats = MutableStateFlow<Map<String, Any>>(emptyMap())
    val channelStats: StateFlow<Map<String, Any>> = _channelStats.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _syncInProgress = MutableStateFlow(setOf<String>())
    val syncInProgress: StateFlow<Set<String>> = _syncInProgress.asStateFlow()

    /**
     * Load all channels for a workspace.
     *
     * @param workspaceId Workspace to load channels for.
     */
    fun loadChannels(workspaceId: String = "default") {
        _isLoading.value = true

        viewModelScope.launch {
            try {
                channelRepository.observeChannelsByWorkspace(workspaceId)
                    .collect { _allChannels.value = it }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _isLoading.value = false
            }
        }
    }

    /**
     * Create a new channel.
     *
     * @param type Channel type.
     * @param name Channel display name.
     * @param credentials Channel credentials.
     * @param workspaceId Workspace to create channel in.
     */
    fun createChannel(
        type: String,
        name: String,
        credentials: ChannelCredentials,
        workspaceId: String = "default",
    ) {
        viewModelScope.launch {
            try {
                val channel = ChannelConfiguration(
                    id = java.util.UUID.randomUUID().toString(),
                    type = type,
                    name = name,
                    workspaceId = workspaceId,
                    createdAt = java.time.Instant.now().toString(),
                    isActive = true,
                    connectionStatus = "not_configured",
                )
                val id = channelRepository.createChannel(channel)
                channelRepository.storeCredentials(id, credentials)
                _allChannels.value = _allChannels.value + channel.copy(id = id)
                refreshStats(workspaceId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Update channel settings.
     *
     * @param channelId Channel to update.
     * @param settings New settings.
     */
    fun updateChannelSettings(channelId: String, settings: ChannelSettings) {
        viewModelScope.launch {
            try {
                channelRepository.updateSettings(channelId, settings)
                val updated = _allChannels.value.map { if (it.id == channelId) it else it }
                _allChannels.value = updated
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Delete a channel.
     *
     * @param channelId Channel to delete.
     */
    fun deleteChannel(channelId: String, workspaceId: String = "default") {
        viewModelScope.launch {
            try {
                channelRepository.deleteChannel(channelId)
                _allChannels.value = _allChannels.value.filterNot { it.id == channelId }
                if (_selectedChannel.value?.id == channelId) {
                    _selectedChannel.value = null
                }
                refreshStats(workspaceId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Toggle channel active state.
     *
     * @param channelId Channel to toggle.
     */
    fun toggleChannelActive(channelId: String, workspaceId: String = "default") {
        viewModelScope.launch {
            try {
                channelRepository.toggleChannelActive(channelId)
                _allChannels.value = _allChannels.value.map {
                    if (it.id == channelId) it.copy(isActive = !it.isActive) else it
                }
                refreshStats(workspaceId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Manually sync a channel.
     *
     * @param channelId Channel to sync.
     */
    fun syncChannel(channelId: String) {
        viewModelScope.launch {
            _syncInProgress.value = _syncInProgress.value + channelId

            try {
                val channel = channelRepository.getChannel(channelId) ?: return@launch
                // Record sync attempt
                channelRepository.recordSync(channelId, itemsProcessed = 0, errors = 0)
                // Update last sync time
                val now = java.time.Instant.now().toString()
                channelRepository.updateChannel(channel.copy(lastSyncAt = now))
                // Refresh UI
                _allChannels.value = _allChannels.value.map {
                    if (it.id == channelId) it.copy(lastSyncAt = now) else it
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                _syncInProgress.value = _syncInProgress.value - channelId
            }
        }
    }

    /**
     * Get sync statistics.
     *
     * @param workspaceId Workspace to query.
     */
    fun refreshStats(workspaceId: String = "default") {
        viewModelScope.launch {
            try {
                val stats = channelRepository.getChannelSyncStats(workspaceId)
                _channelStats.value = stats
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Select a channel for detailed view.
     *
     * @param channel Channel to select.
     */
    fun selectChannel(channel: ChannelConfiguration) {
        _selectedChannel.value = channel
    }

    /**
     * Clear selected channel.
     */
    fun clearSelection() {
        _selectedChannel.value = null
    }
}
