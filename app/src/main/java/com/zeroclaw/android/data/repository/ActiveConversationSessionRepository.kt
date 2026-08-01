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
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repository for the currently active agent conversation session ID.
 */
interface ActiveConversationSessionRepository {
    /** Current active `familyId`, or null when no conversation is active. */
    val activeFamilyId: Flow<String?>

    /**
     * Persists the currently active `familyId`.
     *
     * @param familyId Conversation identifier to mark active.
     */
    suspend fun setActiveFamilyId(familyId: String)

    /**
     * Clears the persisted active conversation marker.
     */
    suspend fun clearActiveFamilyId()
}

private val Context.activeConversationSessionDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "active_conversation_session",
)

/**
 * DataStore-backed implementation of [ActiveConversationSessionRepository].
 */
class DataStoreActiveConversationSessionRepository(
    private val context: Context,
) : ActiveConversationSessionRepository {
    override val activeFamilyId: Flow<String?> =
        context.activeConversationSessionDataStore.data.map { prefs ->
            prefs[KEY_ACTIVE_FAMILY_ID]
        }

    override suspend fun setActiveFamilyId(familyId: String) {
        context.activeConversationSessionDataStore.edit { prefs ->
            prefs[KEY_ACTIVE_FAMILY_ID] = familyId
        }
    }

    override suspend fun clearActiveFamilyId() {
        context.activeConversationSessionDataStore.edit { prefs ->
            prefs.remove(KEY_ACTIVE_FAMILY_ID)
        }
    }

    companion object {
        /** Preference key storing the active conversation `familyId`. */
        val KEY_ACTIVE_FAMILY_ID = stringPreferencesKey("active_family_id")
    }
}
