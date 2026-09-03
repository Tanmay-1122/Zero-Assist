/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Persistent cache for device capability probe results (Phase 11).
 * Stores tier detection and benchmark data to avoid repeated probing.
 */
class VoiceDeviceCapabilityCache(
    private val context: Context,
    private val prefs: SharedPreferences =
        context.getSharedPreferences("voice_device_capability", Context.MODE_PRIVATE),
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun getCachedCapability(): VoiceDeviceCapability? {
        return try {
            val rawJson = prefs.getString(PREF_KEY_CAPABILITY, null) ?: return null
            val cached = rawJson.parseToSerialized(json)
            cached.toCapability()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load cached device capability", e)
            null
        }
    }

    fun cacheCapability(capability: VoiceDeviceCapability) {
        try {
            val serialized = SerializedVoiceDeviceCapability.fromCapability(capability)
            val rawJson = json.encodeToString(SerializedVoiceDeviceCapability.serializer(), serialized)
            prefs.edit().putString(PREF_KEY_CAPABILITY, rawJson).apply()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cache device capability", e)
        }
    }

    fun clearCache() {
        prefs.edit().remove(PREF_KEY_CAPABILITY).apply()
    }

    companion object {
        private const val TAG = "VoiceDeviceCapabilityCache"
        private const val PREF_KEY_CAPABILITY = "device_capability_v1"
    }
}

/**
 * Serializable version of VoiceDeviceCapability for persistent storage.
 */
@Serializable
private data class SerializedVoiceDeviceCapability(
    @SerialName("tier")
    val tier: String,
    @SerialName("core_count")
    val coreCount: Int,
    @SerialName("memory_class_mb")
    val memoryClassMb: Int,
    @SerialName("realtime_factor")
    val realtimeFactor: Double,
    @SerialName("benchmark_time_ms")
    val benchmarkTimeMs: Long,
    @SerialName("benchmark_audio_duration_ms")
    val benchmarkAudioDurationMs: Long,
    @SerialName("timestamp")
    val timestamp: Long,
) {
    fun toCapability(): VoiceDeviceCapability =
        VoiceDeviceCapability(
            tier = VoiceCapabilityTier.valueOf(tier),
            coreCount = coreCount,
            memoryClassMb = memoryClassMb,
            realtimeFactor = realtimeFactor,
            benchmarkTimeMs = benchmarkTimeMs,
            benchmarkAudioDurationMs = benchmarkAudioDurationMs,
            timestamp = timestamp,
        )

    companion object {
        fun fromCapability(capability: VoiceDeviceCapability): SerializedVoiceDeviceCapability =
            SerializedVoiceDeviceCapability(
                tier = capability.tier.name,
                coreCount = capability.coreCount,
                memoryClassMb = capability.memoryClassMb,
                realtimeFactor = capability.realtimeFactor,
                benchmarkTimeMs = capability.benchmarkTimeMs,
                benchmarkAudioDurationMs = capability.benchmarkAudioDurationMs,
                timestamp = capability.timestamp,
            )
    }
}

// Extension function to parse JSON string
private fun String.parseToSerialized(json: Json): SerializedVoiceDeviceCapability {
    return json.decodeFromString(SerializedVoiceDeviceCapability.serializer(), this)
}
