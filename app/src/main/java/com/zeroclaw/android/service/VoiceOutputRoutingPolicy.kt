/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import android.app.ActivityManager
import android.content.Context
import com.zeroclaw.android.model.VoiceModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

enum class VoicePerformanceMode {
    AUTO,
    FAST,
    BALANCED,
    QUALITY,
    ;

    companion object {
        fun fromStoredValue(value: String?): VoicePerformanceMode =
            values().firstOrNull { mode -> mode.name == value } ?: AUTO
    }
}

interface VoiceOutputPreferences {
    val performanceMode: StateFlow<VoicePerformanceMode>

    fun setPerformanceMode(mode: VoicePerformanceMode)
}

class InMemoryVoiceOutputPreferences(
    initialMode: VoicePerformanceMode = VoicePerformanceMode.AUTO,
) : VoiceOutputPreferences {
    private val mode = MutableStateFlow(initialMode)

    override val performanceMode: StateFlow<VoicePerformanceMode> = mode

    override fun setPerformanceMode(mode: VoicePerformanceMode) {
        this.mode.value = mode
    }
}

class SharedPreferencesVoiceOutputPreferences(
    context: Context,
) : VoiceOutputPreferences {
    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val mode =
        MutableStateFlow(
            VoicePerformanceMode.fromStoredValue(preferences.getString(KEY_PERFORMANCE_MODE, null)),
        )

    override val performanceMode: StateFlow<VoicePerformanceMode> = mode

    override fun setPerformanceMode(mode: VoicePerformanceMode) {
        this.mode.value = mode
        preferences
            .edit()
            .putString(KEY_PERFORMANCE_MODE, mode.name)
            .apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "voice_output_preferences"
        private const val KEY_PERFORMANCE_MODE = "performance_mode"
    }
}

enum class VoiceDeviceTier {
    LOW,
    MID,
    HIGH,
}

data class VoiceDeviceProfile(
    val tier: VoiceDeviceTier,
    val totalRamMb: Long?,
    val availableProcessors: Int,
)

object AndroidVoiceDeviceProfile {
    fun current(context: Context): VoiceDeviceProfile {
        val activityManager = context.getSystemService(ActivityManager::class.java)
        val totalRamMb =
            runCatching {
                ActivityManager.MemoryInfo().also { info ->
                    activityManager?.getMemoryInfo(info)
                }.totalMem
                    .takeIf { it > 0L }
                    ?.let { bytes -> bytes / BYTES_PER_MEGABYTE }
            }.getOrNull()
        val processors = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
        return VoiceDeviceProfile(
            tier = VoiceDeviceProfileClassifier.classify(totalRamMb, processors),
            totalRamMb = totalRamMb,
            availableProcessors = processors,
        )
    }

    private const val BYTES_PER_MEGABYTE = 1024L * 1024L
}

object VoiceDeviceProfileClassifier {
    fun classify(
        totalRamMb: Long?,
        availableProcessors: Int,
    ): VoiceDeviceTier {
        val processors = availableProcessors.coerceAtLeast(1)
        return when {
            totalRamMb != null && totalRamMb < LOW_RAM_MB -> VoiceDeviceTier.LOW
            processors <= LOW_CORE_COUNT -> VoiceDeviceTier.LOW
            totalRamMb != null && totalRamMb >= HIGH_RAM_MB && processors >= HIGH_CORE_COUNT ->
                VoiceDeviceTier.HIGH
            else -> VoiceDeviceTier.MID
        }
    }

    private const val LOW_RAM_MB = 4_096L
    private const val HIGH_RAM_MB = 7_168L
    private const val LOW_CORE_COUNT = 4
    private const val HIGH_CORE_COUNT = 8
}

class VoiceOutputRoutingPolicy(
    private val mode: VoicePerformanceMode = VoicePerformanceMode.AUTO,
    private val deviceProfile: VoiceDeviceProfile =
        VoiceDeviceProfile(
            tier = VoiceDeviceTier.MID,
            totalRamMb = null,
            availableProcessors = Runtime.getRuntime().availableProcessors().coerceAtLeast(1),
        ),
    private val modeProvider: () -> VoicePerformanceMode = { mode },
) {
    fun orderedDrivers(
        voice: VoiceModel,
        drivers: List<LocalSpeechOutputDriver>,
    ): List<LocalSpeechOutputDriver> {
        val preferredEngine = preferredEngine(voice)
        return drivers.sortedWith(
            compareBy<LocalSpeechOutputDriver> { driver ->
                if (driver.engine == preferredEngine) 0 else 1
            }.thenBy { driver ->
                fallbackRank(driver.engine)
            },
        )
    }

    private fun preferredEngine(voice: VoiceModel): LocalSpeechOutputEngine {
        val modelUri = voice.modelUri.orEmpty()
        if (modelUri.startsWith(AndroidLocalSpeechOutputDriver.ANDROID_TTS_URI_PREFIX)) {
            return LocalSpeechOutputEngine.ANDROID_TTS
        }

        return when (modeProvider()) {
            VoicePerformanceMode.FAST -> LocalSpeechOutputEngine.ANDROID_TTS
            VoicePerformanceMode.QUALITY -> LocalSpeechOutputEngine.CUSTOM_VOICE
            VoicePerformanceMode.BALANCED ->
                if (deviceProfile.tier == VoiceDeviceTier.LOW) {
                    LocalSpeechOutputEngine.ANDROID_TTS
                } else {
                    LocalSpeechOutputEngine.CUSTOM_VOICE
                }
            VoicePerformanceMode.AUTO ->
                if (deviceProfile.tier == VoiceDeviceTier.LOW) {
                    LocalSpeechOutputEngine.ANDROID_TTS
                } else {
                    LocalSpeechOutputEngine.CUSTOM_VOICE
                }
        }
    }

    private fun fallbackRank(engine: LocalSpeechOutputEngine): Int =
        when (engine) {
            LocalSpeechOutputEngine.CUSTOM_VOICE -> 0
            LocalSpeechOutputEngine.ANDROID_TTS -> 1
            LocalSpeechOutputEngine.OTHER -> 2
        }
}
