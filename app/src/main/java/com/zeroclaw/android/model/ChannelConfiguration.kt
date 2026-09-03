/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.model

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents a configured channel for agent communication.
 *
 * Channels enable agents to connect to external services (Reddit, Twitter, Notion, etc).
 * Each channel configuration stores credentials, settings, and connection state.
 *
 * @property id Unique identifier for this channel configuration.
 * @property type Channel type: "reddit", "twitter", "notion", "webhook", "voice_call", "bluesky".
 * @property workspaceId Workspace that owns this channel (for isolation).
 * @property name Display name for the channel.
 * @property description Purpose or memo for this channel.
 * @property isActive Whether the channel is actively monitored.
 * @property credentials Encrypted JSON containing API keys, tokens, usernames, etc.
 * @property settings Channel-specific configuration (JSON format).
 * @property lastSyncAt ISO-8601 timestamp of last synchronization.
 * @property connectionStatus Current status: "connected", "disconnected", "error", "not_configured".
 * @property errorMessage Last error encountered, if any.
 * @property autoSync Whether to automatically sync messages/data.
 * @property syncIntervalMinutes How often to sync in minutes (0 = disabled).
 * @property createdAt ISO-8601 timestamp of creation.
 */
@Entity(tableName = "channels_new")
@Serializable
data class ChannelConfiguration(
    @PrimaryKey
    val id: String,

    val type: String, // "reddit", "twitter", "notion", "webhook", "voice_call", "bluesky"

    val workspaceId: String = "default",

    val name: String,

    val description: String = "",

    val isActive: Boolean = true,

    @ColumnInfo(name = "credentials_json")
    @SerialName("credentials_json")
    val credentials: String = "", // Encrypted JSON

    @ColumnInfo(name = "settings_json")
    @SerialName("settings_json")
    val settings: String = "", // Channel-specific settings as JSON

    val lastSyncAt: String? = null,

    val connectionStatus: String = "not_configured", // connected, disconnected, error

    val errorMessage: String? = null,

    val autoSync: Boolean = true,

    val syncIntervalMinutes: Int = 15,

    val createdAt: String = "",
)

/**
 * Channel-specific credentials wrapper.
 *
 * Used to validate and store API keys, tokens, usernames, passwords securely.
 */
@Serializable
data class ChannelCredentials(
    val type: String,
    val apiKey: String? = null,              // For generic API key channels
    val accessToken: String? = null,         // OAuth token
    val refreshToken: String? = null,        // OAuth refresh token
    val username: String? = null,            // Username (Reddit, Twitter)
    val password: String? = null,            // Password (encrypted)
    val webhookUrl: String? = null,          // Webhook endpoint URL
    val webhookSecret: String? = null,       // Webhook signing secret
    val phoneNumber: String? = null,         // For voice calls
    val customHeaders: Map<String, String> = emptyMap(), // Additional HTTP headers
)

/**
 * Channel sync event record.
 *
 * Tracks what was synced from each channel for audit and recovery.
 */
@Serializable
data class ChannelSyncEvent(
    val channelId: String,
    val channelType: String,
    val itemsProcessed: Int,
    val errorCount: Int,
    val syncStartedAt: String,
    val syncCompletedAt: String,
    val status: String, // "success", "partial", "failed"
    val errorMessage: String? = null,
)

/**
 * Channel-specific settings template.
 *
 * Provides default settings structure for each channel type.
 */
@Serializable
data class ChannelSettings(
    // Reddit
    val redditSubreddit: String? = null,
    val redditSearchQuery: String? = null,
    val redditSortBy: String? = "new", // new, hot, top, rising

    // Twitter
    val twitterSearchQuery: String? = null,
    val twitterFollowAccounts: List<String> = emptyList(),
    val twitterIncludeRetweets: Boolean = true,

    // Notion
    val notionDatabaseId: String? = null,
    val notionWorkspaceName: String? = null,

    // Webhook
    val webhookEventTypes: List<String> = emptyList(), // "message_received", "agent_updated", etc
    val webhookRetryPolicy: String? = null,

    // Voice Call
    val voiceCallProvider: String? = null, // "twilio", "asterisk", etc
    val voiceCallTranscription: Boolean = true,

    // Bluesky
    val blueskyFeedUri: String? = null,
    val blueskyFollowAccounts: List<String> = emptyList(),
)
