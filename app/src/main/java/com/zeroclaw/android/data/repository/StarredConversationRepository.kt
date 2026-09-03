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
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Repository interface for locally persisted starred conversation IDs.
 */
interface StarredConversationRepository {
    /** Set of conversation IDs pinned by the user. */
    val starredConversationIds: Flow<Set<String>>

    /**
     * Marks the given conversation as starred.
     *
     * @param conversationId Stable history entry identifier.
     */
    suspend fun starConversation(conversationId: String)

    /**
     * Removes the starred state for the given conversation.
     *
     * @param conversationId Stable history entry identifier.
     */
    suspend fun unstarConversation(conversationId: String)
}

/** Extension property providing the singleton [DataStore] for starred conversation IDs. */
private val Context.starredConversationDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "starred_conversations",
)

/**
 * [StarredConversationRepository] backed by Jetpack DataStore Preferences.
 *
 * @param context Application context for DataStore initialization.
 */
class DataStoreStarredConversationRepository(
    private val context: Context,
) : StarredConversationRepository {
    override val starredConversationIds: Flow<Set<String>> =
        context.starredConversationDataStore.data.map { prefs ->
            prefs[KEY_STARRED_IDS].orEmpty()
        }

    override suspend fun starConversation(conversationId: String) {
        context.starredConversationDataStore.edit { prefs ->
            prefs[KEY_STARRED_IDS] = prefs[KEY_STARRED_IDS].orEmpty() + conversationId
        }
    }

    override suspend fun unstarConversation(conversationId: String) {
        context.starredConversationDataStore.edit { prefs ->
            prefs[KEY_STARRED_IDS] = prefs[KEY_STARRED_IDS].orEmpty() - conversationId
        }
    }

    companion object {
        /** Preference key storing the full starred conversation ID set. */
        val KEY_STARRED_IDS = stringSetPreferencesKey("starred_conversation_ids")
    }
}
