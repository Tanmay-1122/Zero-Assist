/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.uiagent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("InMemoryForegroundWindowTracker")
class ForegroundWindowTrackerTest {
    @Test
    fun `updateForeground records package and resets root readiness`() {
        val tracker =
            InMemoryForegroundWindowTracker(
                ForegroundWindowState(
                    packageName = "old",
                    rootReady = true,
                    updatedAtEpochMs = 1L,
                ),
            )

        val state =
            tracker.updateForeground(
                packageName = " com.chat.app ",
                windowTitle = " Chat ",
                timestampMs = 2L,
            )

        assertEquals("com.chat.app", state.packageName)
        assertEquals("Chat", state.windowTitle)
        assertFalse(state.rootReady)
        assertEquals(state, tracker.state.value)
    }

    @Test
    fun `markRootReady preserves previous package when package is absent`() {
        val tracker = InMemoryForegroundWindowTracker()
        tracker.updateForeground(
            packageName = "com.chat.app",
            windowTitle = "Chat",
            timestampMs = 10L,
        )

        val state =
            tracker.markRootReady(
                packageName = null,
                windowTitle = null,
                timestampMs = 11L,
            )

        assertEquals("com.chat.app", state.packageName)
        assertEquals("Chat", state.windowTitle)
        assertTrue(state.rootReady)
        assertEquals(11L, state.updatedAtEpochMs)
    }

    @Test
    fun `markRootReady can update title when package is absent`() {
        val tracker = InMemoryForegroundWindowTracker()
        tracker.updateForeground(
            packageName = "com.chat.app",
            windowTitle = "Chat",
            timestampMs = 10L,
        )

        val state =
            tracker.markRootReady(
                packageName = null,
                windowTitle = "Rohit",
                timestampMs = 11L,
            )

        assertEquals("com.chat.app", state.packageName)
        assertEquals("Rohit", state.windowTitle)
        assertTrue(state.rootReady)
    }

    @Test
    fun `transient system and keyboard packages do not replace stable foreground app`() {
        val tracker = InMemoryForegroundWindowTracker()
        tracker.updateForeground(
            packageName = "com.instagram.android",
            windowTitle = "Instagram",
            timestampMs = 10L,
        )

        val keyboardState =
            tracker.updateForeground(
                packageName = "com.google.android.inputmethod.latin",
                timestampMs = 11L,
            )
        val systemState =
            tracker.updateForeground(
                packageName = "com.android.systemui",
                timestampMs = 12L,
            )

        assertEquals("com.instagram.android", keyboardState.packageName)
        assertEquals("Instagram", keyboardState.windowTitle)
        assertFalse(keyboardState.rootReady)
        assertEquals("com.instagram.android", systemState.packageName)
        assertEquals("Instagram", systemState.windowTitle)
        assertFalse(systemState.rootReady)
    }

    @Test
    fun `system ui and input method packages remain distinguishable for snapshot selection`() {
        assertTrue("com.android.systemui".isTransientForegroundPackage())
        assertTrue("com.android.systemui".isSystemUiPackage())
        assertFalse("com.android.systemui".isInputMethodPackage())

        assertTrue("com.google.android.inputmethod.latin".isTransientForegroundPackage())
        assertFalse("com.google.android.inputmethod.latin".isSystemUiPackage())
        assertTrue("com.google.android.inputmethod.latin".isInputMethodPackage())
    }

    @Test
    fun `transient ready roots do not replace stable foreground app`() {
        val tracker = InMemoryForegroundWindowTracker()
        tracker.updateForeground(
            packageName = "com.instagram.android",
            windowTitle = "Instagram",
            timestampMs = 10L,
        )

        val state =
            tracker.markRootReady(
                packageName = "com.android.systemui",
                windowTitle = "Notifications",
                timestampMs = 11L,
            )

        assertEquals("com.instagram.android", state.packageName)
        assertEquals("Instagram", state.windowTitle)
        assertTrue(state.rootReady)
        assertEquals(11L, state.updatedAtEpochMs)
    }

    @Test
    fun `clear removes foreground state`() {
        val tracker = InMemoryForegroundWindowTracker()
        tracker.updateForeground("com.chat.app", "Chat", timestampMs = 1L)

        val state = tracker.clear(timestampMs = 2L)

        assertNull(state.packageName)
        assertNull(state.windowTitle)
        assertFalse(state.rootReady)
        assertEquals(2L, state.updatedAtEpochMs)
    }
}
