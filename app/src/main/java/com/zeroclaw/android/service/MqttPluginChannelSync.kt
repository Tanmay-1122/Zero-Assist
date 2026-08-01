/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.data.repository.ChannelConfigRepository
import com.zeroclaw.android.data.repository.PluginRepository
import com.zeroclaw.android.model.ChannelType
import com.zeroclaw.android.model.CommunityPlugins
import com.zeroclaw.android.model.ConnectedChannel
import java.util.UUID

/**
 * Keeps the legacy MQTT plugin row aligned with the real connected-channel
 * configuration used by the daemon.
 */
object MqttPluginChannelSync {
    suspend fun setEnabled(
        pluginRepository: PluginRepository,
        channelRepository: ChannelConfigRepository,
        enabled: Boolean,
    ) {
        pluginRepository.setEnabled(CommunityPlugins.MQTT_CHANNEL, enabled)
        val existing = channelRepository.getByType(ChannelType.MQTT)

        if (enabled) {
            if (existing == null) {
                channelRepository.save(defaultMqttChannel(), secrets = emptyMap())
            } else if (!existing.isEnabled) {
                channelRepository.setEnabled(existing.id, true)
            }
        } else if (existing?.isEnabled == true) {
            channelRepository.setEnabled(existing.id, false)
        }
    }

    private fun defaultMqttChannel(): ConnectedChannel =
        ConnectedChannel(
            id = UUID.randomUUID().toString(),
            type = ChannelType.MQTT,
            configValues =
                mapOf(
                    "broker_url" to "mqtt://localhost:1883",
                    "client_id" to "zero-assist-android",
                    "topics" to "#",
                    "qos" to "1",
                    "use_tls" to "false",
                    "keep_alive_secs" to "30",
                ),
        )
}
