/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.data.repository.ChannelConfigRepository
import com.zeroclaw.android.model.ChannelType
import kotlinx.coroutines.flow.first

/**
 * Minimal channel inventory exposed to AI sessions.
 *
 * Values come from non-secret channel metadata only. Secrets remain in the
 * channel repository and are never inserted into prompts.
 */
interface ChannelStatusBridge {
    suspend fun listChannels(): List<ChannelStatus>
}

/** No-op channel inventory for tests and call sites without repository access. */
object EmptyChannelStatusBridge : ChannelStatusBridge {
    override suspend fun listChannels(): List<ChannelStatus> = emptyList()
}

/** Channel inventory backed by the Android connected-channel repository. */
class RepositoryChannelStatusBridge(
    private val repository: ChannelConfigRepository,
) : ChannelStatusBridge {
    override suspend fun listChannels(): List<ChannelStatus> =
        repository.channels.first().map { channel ->
            val details =
                when (channel.type) {
                    ChannelType.MQTT -> mqttDetails(channel.configValues)
                    else -> ""
                }
            ChannelStatus(
                typeName = channel.type.tomlKey,
                displayName = channel.type.displayName,
                isEnabled = channel.isEnabled,
                details = details,
            )
        }

    private fun mqttDetails(values: Map<String, String>): String {
        val topics = values["topics"]?.takeIf { it.isNotBlank() }
        return if (topics == null) {
            "SOP listener"
        } else {
            "SOP listener subscribed to: $topics"
        }
    }
}

data class ChannelStatus(
    val typeName: String,
    val displayName: String,
    val isEnabled: Boolean,
    val details: String = "",
)
