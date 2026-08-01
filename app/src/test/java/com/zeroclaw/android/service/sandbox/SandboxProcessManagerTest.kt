/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.sandbox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

@DisplayName("SandboxProcessManager")
class SandboxProcessManagerTest {

    @Nested
    @DisplayName("Session")
    inner class SessionTest {

        @Test
        @DisplayName("Session data class holds expected fields")
        fun `Session data class holds expected fields`() {
            val session = SandboxProcessManager.Session(
                id = "bg-1",
                command = "sleep 10",
                startTime = System.currentTimeMillis(),
            )
            assertEquals("bg-1", session.id)
            assertEquals("sleep 10", session.command)
            assertEquals("", session.stdout)
            assertEquals(false, session.finished)
            assertNull(session.exitCode)
            assertEquals(false, session.timedOut)
            assertEquals(false, session.cancelled.get())
            assertNull(session.executor)
        }

        @Test
        @DisplayName("Session cancelled flag starts false")
        fun `Session cancelled flag starts false`() {
            val session = SandboxProcessManager.Session(
                id = "bg-2",
                command = "echo hello",
                startTime = 0L,
            )
            assertFalse(session.cancelled.get())
            session.cancelled.set(true)
            assertTrue(session.cancelled.get())
        }

        @Test
        @DisplayName("Session executor starts null")
        fun `Session executor starts null`() {
            val session = SandboxProcessManager.Session(
                id = "bg-3",
                command = "ls",
                startTime = 0L,
            )
            assertNull(session.executor)
        }

        @Test
        @DisplayName("Session stdout and stderr default to empty")
        fun `Session stdout and stderr default to empty`() {
            val session = SandboxProcessManager.Session(
                id = "bg-4",
                command = "cat /dev/null",
                startTime = 0L,
            )
            assertEquals("", session.stdout)
            assertEquals("", session.stderr)
        }
    }
}
