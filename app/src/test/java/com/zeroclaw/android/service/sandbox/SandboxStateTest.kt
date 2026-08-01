/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.sandbox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("SandboxState")
class SandboxStateTest {

    @Nested
    @DisplayName("Error")
    inner class ErrorState {

        @Test
        @DisplayName("recoverable defaults to true")
        fun `recoverable defaults to true`() {
            val state = SandboxState.Error("something broke")
            assertTrue(state.recoverable)
        }

        @Test
        @DisplayName("recoverable can be set to false")
        fun `recoverable can be set to false`() {
            val state = SandboxState.Error("proot missing", recoverable = false)
            assertFalse(state.recoverable)
        }

        @Test
        @DisplayName("message is accessible")
        fun `message is accessible`() {
            val state = SandboxState.Error("disk full")
            assertEquals("disk full", state.message)
        }
    }

    @Nested
    @DisplayName("Downloading")
    inner class DownloadingState {

        @Test
        @DisplayName("progress is accessible")
        fun `progress is accessible`() {
            val state = SandboxState.Downloading(0.5f)
            assertEquals(0.5f, state.progress)
        }
    }

    @Nested
    @DisplayName("Installing")
    inner class InstallingState {

        @Test
        @DisplayName("detail defaults to empty")
        fun `detail defaults to empty`() {
            val state = SandboxState.Installing()
            assertEquals("", state.detail)
        }

        @Test
        @DisplayName("detail is accessible")
        fun `detail is accessible`() {
            val state = SandboxState.Installing("Installing python3...")
            assertEquals("Installing python3...", state.detail)
        }
    }

    @Nested
    @DisplayName("State identity")
    inner class StateIdentity {

        @Test
        @DisplayName("NotInstalled is a singleton")
        fun `NotInstalled is a singleton`() {
            assertTrue(SandboxState.NotInstalled === SandboxState.NotInstalled)
        }

        @Test
        @DisplayName("Extracting is a singleton")
        fun `Extracting is a singleton`() {
            assertTrue(SandboxState.Extracting === SandboxState.Extracting)
        }

        @Test
        @DisplayName("Ready is a singleton")
        fun `Ready is a singleton`() {
            assertTrue(SandboxState.Ready === SandboxState.Ready)
        }
    }
}
