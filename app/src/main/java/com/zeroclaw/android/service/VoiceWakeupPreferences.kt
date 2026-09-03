/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.data.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Stores the user's local wake-word preference without starting background listening. */
interface VoiceWakeupPreferences {
    val wakeupRequested: Flow<Boolean>

    suspend fun setWakeupRequested(enabled: Boolean)
}

/** Bridges voice wake-up preference reads/writes to the app settings store. */
class SettingsVoiceWakeupPreferences(
    private val settingsRepository: SettingsRepository,
) : VoiceWakeupPreferences {
    override val wakeupRequested: Flow<Boolean> =
        settingsRepository.settings
            .map { settings -> settings.voiceWakeupRequested }
            .distinctUntilChanged()

    override suspend fun setWakeupRequested(enabled: Boolean) {
        settingsRepository.setVoiceWakeupRequested(enabled)
    }
}

/** In-memory fallback used by tests and call sites without a settings store. */
class InMemoryVoiceWakeupPreferences(
    initialWakeupRequested: Boolean = false,
) : VoiceWakeupPreferences {
    private val requested = MutableStateFlow(initialWakeupRequested)

    override val wakeupRequested: Flow<Boolean> = requested

    override suspend fun setWakeupRequested(enabled: Boolean) {
        requested.value = enabled
    }
}
