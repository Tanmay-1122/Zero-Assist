/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repository interface for tracking the dashboard's daily welcome state.
 */
interface WelcomeRepository {
    /** Last ISO date (`yyyy-MM-dd`) on which the welcome was shown. */
    val lastShownDate: Flow<String?>

    /** ISO date of the last AI greeting call. */
    val aiCallsDate: Flow<String?>

    /** Number of AI greeting calls made on [aiCallsDate]. */
    val aiCallsCount: Flow<Int>

    suspend fun setLastShownDate(isoDate: String)

    /** Returns true if under the 3/day limit and increments the counter. */
    suspend fun tryConsumeAiCall(isoDate: String): Boolean
}

/** Extension property providing the singleton [DataStore] for the dashboard welcome. */
private val Context.dashboardWelcomeDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "dashboard_welcome",
)

/**
 * [WelcomeRepository] backed by Jetpack DataStore Preferences.
 *
 * @param context Application context for DataStore initialization.
 */
class DataStoreWelcomeRepository(
    private val context: Context,
) : WelcomeRepository {
    override val lastShownDate: Flow<String?> =
        context.dashboardWelcomeDataStore.data.map { prefs ->
            prefs[KEY_LAST_SHOWN_DATE]
        }

    override val aiCallsDate: Flow<String?> =
        context.dashboardWelcomeDataStore.data.map { prefs ->
            prefs[KEY_AI_CALLS_DATE]
        }

    override val aiCallsCount: Flow<Int> =
        context.dashboardWelcomeDataStore.data.map { prefs ->
            prefs[KEY_AI_CALLS_COUNT] ?: 0
        }

    override suspend fun setLastShownDate(isoDate: String) {
        context.dashboardWelcomeDataStore.edit { prefs ->
            prefs[KEY_LAST_SHOWN_DATE] = isoDate
        }
    }

    override suspend fun tryConsumeAiCall(isoDate: String): Boolean {
        var consumed = false
        context.dashboardWelcomeDataStore.edit { prefs ->
            val currentDate = prefs[KEY_AI_CALLS_DATE]
            val currentCount = if (currentDate == isoDate) (prefs[KEY_AI_CALLS_COUNT] ?: 0) else 0
            if (currentCount < MAX_AI_CALLS_PER_DAY) {
                prefs[KEY_AI_CALLS_DATE] = isoDate
                prefs[KEY_AI_CALLS_COUNT] = currentCount + 1
                consumed = true
            }
        }
        return consumed
    }

    companion object {
        val KEY_LAST_SHOWN_DATE = stringPreferencesKey("last_shown_date")
        val KEY_AI_CALLS_DATE = stringPreferencesKey("ai_calls_date")
        val KEY_AI_CALLS_COUNT = intPreferencesKey("ai_calls_count")
        const val MAX_AI_CALLS_PER_DAY = 3
    }
}
