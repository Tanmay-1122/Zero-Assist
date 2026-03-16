/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data

import android.content.SharedPreferences
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * Unit tests for [MapSharedPreferences], the read-only in-memory fallback
 * used when the Android Keystore is completely unusable.
 *
 * [SecurePrefsProvider] itself requires Android Keystore APIs and is
 * tested via instrumented tests. These tests verify the read-only
 * [SharedPreferences] implementation that runs in the [StorageHealth.Degraded] path.
 */
@DisplayName("MapSharedPreferences")
class SecurePrefsProviderTest {
    @Test
    @DisplayName("put string is a no-op, getString returns default")
    fun `put string is a no-op`() {
        val prefs = MapSharedPreferences()
        prefs.edit().putString("key", "value").apply()
        assertEquals("default", prefs.getString("key", "default"))
    }

    @Test
    @DisplayName("put boolean is a no-op, getBoolean returns default")
    fun `put boolean is a no-op`() {
        val prefs = MapSharedPreferences()
        prefs.edit().putBoolean("flag", true).apply()
        assertFalse(prefs.getBoolean("flag", false))
    }

    @Test
    @DisplayName("put int is a no-op, getInt returns default")
    fun `put int is a no-op`() {
        val prefs = MapSharedPreferences()
        prefs.edit().putInt("count", 42).apply()
        assertEquals(0, prefs.getInt("count", 0))
    }

    @Test
    @DisplayName("put long is a no-op, getLong returns default")
    fun `put long is a no-op`() {
        val prefs = MapSharedPreferences()
        prefs.edit().putLong("time", 123456789L).apply()
        assertEquals(0L, prefs.getLong("time", 0L))
    }

    @Test
    @DisplayName("remove is a no-op on empty prefs")
    fun `remove is a no-op`() {
        val prefs = MapSharedPreferences()
        prefs.edit().remove("key").apply()
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    @DisplayName("clear is a no-op on empty prefs")
    fun `clear is a no-op`() {
        val prefs = MapSharedPreferences()
        prefs.edit().clear().apply()
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    @DisplayName("commit returns false")
    fun `commit returns false`() {
        val prefs = MapSharedPreferences()
        val result = prefs.edit().putString("key", "value").commit()
        assertFalse(result)
    }

    @Test
    @DisplayName("getAll returns empty map since writes are no-ops")
    fun `getAll returns empty map`() {
        val prefs = MapSharedPreferences()
        prefs.edit().putString("a", "1").apply()
        prefs.edit().putInt("b", 2).apply()
        assertTrue(prefs.all.isEmpty())
    }

    @Test
    @DisplayName("contains returns false since writes are no-ops")
    fun `contains returns false`() {
        val prefs = MapSharedPreferences()
        prefs.edit().putString("exists", "yes").apply()
        assertFalse(prefs.contains("exists"))
    }

    @Test
    @DisplayName("listener is NOT notified since no changes happen")
    fun `listener is not notified`() {
        val prefs = MapSharedPreferences()
        val changed = mutableListOf<String?>()
        val listener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                changed.add(key)
            }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        prefs.edit().putString("test", "value").apply()
        assertTrue(changed.isEmpty())
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }
}
