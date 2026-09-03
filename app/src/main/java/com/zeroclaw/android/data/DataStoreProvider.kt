/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

/**
 * Provider for a singleton [DataStore] instance shared across the application.
 *
 * This ensures that only a single DataStore instance is created for app settings
 * preferences, preventing IllegalStateException: "There are multiple DataStores active
 * for the same file" errors.
 *
 * Uses Kotlin's lazy initialization pattern with by preferencesDataStore() extension,
 * which is the modern recommended approach. The system automatically handles:
 * - Creating the file at the correct location
 * - Caching the singleton instance
 * - Thread-safe access
 * - Proper scope management
 *
 * Usage:
 * ```kotlin
 * val settingsDataStore = DataStoreProvider.getSettingsDataStore(context)
 * ```
 */
object DataStoreProvider {
    private var dataStore: DataStore<Preferences>? = null
    private val lock = Any()

    /**
     * Gets the singleton [DataStore] for app settings.
     *
     * Uses double-checked locking to ensure thread-safe singleton creation.
     * The first call initializes the DataStore, and subsequent calls return the cached instance.
     *
     * @param context Any context (will be converted to applicationContext).
     * @return Singleton DataStore instance for app settings preferences.
     */
    fun getSettingsDataStore(context: Context): DataStore<Preferences> {
        return dataStore ?: synchronized(lock) {
            dataStore ?: DataStoreHelper(context.applicationContext).dataStore.also { dataStore = it }
        }
    }

    /**
     * Private helper class that uses the preferencesDataStore delegate in a controlled scope.
     * The extension property is scoped only to this private helper class.
     */
    private class DataStoreHelper(context: Context) {
        private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
            name = "app_settings",
        )

        val dataStore: DataStore<Preferences> = context.settingsDataStore
    }
}
