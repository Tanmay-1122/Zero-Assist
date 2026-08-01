/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import android.util.Log
import com.zeroclaw.android.data.repository.ChannelConfigRepository
import com.zeroclaw.android.model.ChannelType
import com.zeroclaw.android.model.ConnectedChannel
import kotlinx.coroutines.flow.firstOrNull

/**
 * Automatically detects and selects appropriate delivery channels.
 *
 * Analyzes user's delivery request and finds configured channels that match.
 * Falls back to primary channel if no specific channel is mentioned.
 */
class ChannelDetector(private val channelConfigRepository: ChannelConfigRepository) {
    companion object {
        private const val TAG = "ChannelDetector"
    }

    /**
     * Find suitable channels for delivery based on request.
     *
     * @param deliveryRequest User's delivery preference (e.g., "Telegram", "send via Discord")
     * @return List of matching channels, or primary channel if no match found
     */
    suspend fun detectChannels(deliveryRequest: String): List<ConnectedChannel> {
        Log.d(TAG, "Detecting channels for: $deliveryRequest")

        // Get all available channels
        val channels = channelConfigRepository.channels.firstOrNull() ?: emptyList()
        if (channels.isEmpty()) {
            Log.w(TAG, "No configured channels available")
            return emptyList()
        }

        // Parse delivery request to extract channel type
        val requestedType = parseChannelType(deliveryRequest)

        return when (requestedType) {
            // User explicitly requested a specific channel type
            "telegram" -> {
                Log.d(TAG, "User requested Telegram")
                channels.filter { it.type == ChannelType.TELEGRAM && it.isEnabled }
            }

            "discord" -> {
                Log.d(TAG, "User requested Discord")
                channels.filter { it.type == ChannelType.DISCORD && it.isEnabled }
            }

            "slack" -> {
                Log.d(TAG, "User requested Slack")
                channels.filter { it.type == ChannelType.SLACK && it.isEnabled }
            }

            "email" -> {
                Log.d(TAG, "User requested Email")
                channels.filter { it.type == ChannelType.EMAIL && it.isEnabled }
            }

            "none" -> {
                Log.d(TAG, "User requested no delivery channel")
                emptyList()
            }

            // No explicit channel - use first available (primary)
            else -> {
                Log.d(TAG, "No specific channel requested, using first available")
                channels.filter { it.isEnabled }.take(1)
            }
        }
    }

    /**
     * Parse channel type from user's delivery request.
     *
     * @return Channel type string or empty if not recognized
     */
    private fun parseChannelType(request: String): String {
        return when {
            request.contains("telegram", ignoreCase = true) -> "telegram"
            request.contains("discord", ignoreCase = true) -> "discord"
            request.contains("slack", ignoreCase = true) -> "slack"
            request.contains("email", ignoreCase = true) || request.contains("mail", ignoreCase = true) -> "email"
            request.contains("none", ignoreCase = true) -> "none"
            else -> ""
        }
    }

    /**
     * Check if requested channel is available.
     */
    suspend fun isChannelAvailable(channelType: String): Boolean {
        val channels = channelConfigRepository.channels.firstOrNull() ?: return false
        return channels.any {
            (it.type.tomlKey.equals(channelType, ignoreCase = true) ||
                it.type.displayName.equals(channelType, ignoreCase = true)) &&
                it.isEnabled
        }
    }

    /**
     * Get channel display name.
     */
    fun getChannelDisplayName(channelType: String): String {
        return when (channelType.lowercase()) {
            "telegram" -> "Telegram"
            "discord" -> "Discord"
            "slack" -> "Slack"
            "email" -> "Email"
            "webhook" -> "Webhook"
            "matrix" -> "Matrix"
            "signal" -> "Signal"
            "imessage" -> "iMessage"
            "mattermost" -> "Mattermost"
            "dingtalk" -> "DingTalk"
            "qq" -> "QQ"
            "lark" -> "Lark"
            "whatsapp" -> "WhatsApp"
            "whatsapp_web" -> "WhatsApp Web"
            else -> channelType.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }
}
