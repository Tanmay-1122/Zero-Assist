/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.backup

import kotlinx.serialization.Serializable

/**
 * Backup representation of an agent configuration.
 *
 * Serializable data class for storing agent state in backups.
 *
 * @property id Unique identifier for the agent.
 * @property name Display name of the agent.
 * @property provider Model provider (e.g., "openai").
 * @property modelName The specific model identifier.
 * @property isEnabled Whether the agent is currently active.
 * @property systemPrompt System instructions for the agent.
 * @property channelsJson Serialized channel configuration.
 * @property temperature Temperature parameter for model inference.
 * @property maxDepth Maximum recursion depth for agent operations.
 * @property thinkingLevel Default thinking level for agent conversations.
 */
@Serializable
data class AgentBackup(
    val id: String,
    val name: String,
    val provider: String = "",
    val modelName: String = "",
    val isEnabled: Boolean = true,
    val systemPrompt: String = "",
    val channelsJson: String = "[]",
    val temperature: Float? = null,
    val maxDepth: Int = 3,
    val thinkingLevel: String = "HIGH",
    val role: String = "GENERAL",
    val avatar: String = "",
    val tagsJson: String = "[]",
    val isMaster: Boolean = false,
    val priority: Int = 0,
    val templateId: String? = null,
    val accentColor: Long = 0xFF6200EE,
)

/**
 * Backup representation of a plugin configuration.
 *
 * Serializable data class for storing plugin state in backups.
 *
 * @property id Unique identifier for the plugin.
 * @property name Display name of the plugin.
 * @property description Plugin description text.
 * @property version Current plugin version.
 * @property author Plugin author information.
 * @property category Plugin category classification.
 * @property isInstalled Whether the plugin is installed.
 * @property isEnabled Whether the plugin is currently active.
 * @property configJson Plugin-specific configuration.
 * @property remoteVersion Latest available version on remote.
 */
@Serializable
data class PluginBackup(
    val id: String,
    val name: String,
    val description: String,
    val version: String,
    val author: String,
    val category: String,
    val isInstalled: Boolean,
    val isEnabled: Boolean,
    val configJson: String,
    val remoteVersion: String?,
)

/**
 * Backup representation of a connected channel configuration.
 *
 * Serializable data class for storing channel state in backups.
 *
 * @property id Unique identifier for the channel.
 * @property type Channel type identifier (e.g., "slack", "discord").
 * @property isEnabled Whether the channel connection is active.
 * @property configJson Channel-specific configuration and credentials.
 * @property createdAt Timestamp when the channel was created (millis).
 */
@Serializable
data class ConnectedChannelBackup(
    val id: String,
    val type: String,
    val isEnabled: Boolean,
    val configJson: String,
    val createdAt: Long,
)

/**
 * Complete backup snapshot of application data.
 *
 * Serializable data class containing all restorable state for agents, plugins,
 * channels, settings, and secrets. Used for Google Drive sync and local backups.
 *
 * @property version Backup format version for migration support.
 * @property timestamp Backup creation timestamp (milliseconds since epoch).
 * @property agents List of backed-up agent configurations.
 * @property plugins List of backed-up plugin configurations.
 * @property connectedChannels List of backed-up channel connections.
 * @property settings Map of user settings as string key-value pairs.
 * @property secrets Map of encrypted secrets and API keys.
 */
@Serializable
data class BackupData(
    val version: Int = 1,
    val timestamp: Long = System.currentTimeMillis(),
    val agents: List<AgentBackup> = emptyList(),
    val plugins: List<PluginBackup> = emptyList(),
    val connectedChannels: List<ConnectedChannelBackup> = emptyList(),
    val settings: Map<String, String> = emptyMap(),
    val secrets: Map<String, String> = emptyMap(),
)
