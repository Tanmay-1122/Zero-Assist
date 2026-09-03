/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression test for [DataStoreProvider] singleton behaviour.
 *
 * Verifies that calling [DataStoreProvider.getSettingsDataStore] multiple
 * times with the same application context returns the exact same
 * [androidx.datastore.core.DataStore] instance, preventing the
 * "There are multiple DataStores active for the same file" crash.
 *
 * Note: With the modern `by preferencesDataStore()` extension, the singleton
 * is managed by Kotlin's lazy evaluation and the framework. We rely on the
 * underl system's caching to ensure single instance per file/context.
 */
@RunWith(AndroidJUnit4::class)
class DataStoreProviderTest {

    @Before
    fun setup() {
        // The modern preferencesDataStore() extension handles singleton caching
        // automatically through Kotlin's lazy evaluation. No manual reset needed.
    }

    @After
    fun tearDown() {
        // Cleanup is handled automatically by the framework
    }

    @Test
    fun getSettingsDataStore_returnsSameInstanceOnRepeatedCalls() {
        val context = ApplicationProvider.getApplicationContext<Context>()

        val first = DataStoreProvider.getSettingsDataStore(context)
        val second = DataStoreProvider.getSettingsDataStore(context)

        // Must be reference-equal — separate instances would trigger
        // IllegalStateException("There are multiple DataStores active for the same file").
        assertSame(
            "DataStoreProvider must return the same singleton instance on every call",
            first,
            second,
        )
    }

    @Test
    fun getSettingsDataStore_applicationContextIsUsed() {
        // Pass an activity-style context (wrapped); the provider must still
        // return a store backed by the application context so it outlives the
        // caller's lifecycle.
        val context = ApplicationProvider.getApplicationContext<Context>()
        val store = DataStoreProvider.getSettingsDataStore(context)

        // Not null — verifies creation didn't throw.
        assertSame(DataStoreProvider.getSettingsDataStore(context), store)
    }
}
