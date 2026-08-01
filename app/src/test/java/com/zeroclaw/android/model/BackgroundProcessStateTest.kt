/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("BackgroundProcessState")
class BackgroundProcessStateTest {
    @Test
    fun `addProcess replaces an existing process with the same id`() {
        val first =
            BackgroundProcessEntry(
                id = "process-1",
                type = ProcessType.TOOL_EXEC,
                description = "Processing request",
                timestamp = 100,
                status = ProcessStatus.ACTIVE,
            )
        val replacement =
            first.copy(
                description = "Processing message",
                timestamp = 200,
            )

        val state =
            BackgroundProcessState()
                .addProcess(first)
                .addProcess(replacement)

        assertEquals(listOf(replacement), state.processes)
    }

    @Test
    fun `updateProcess compacts duplicate ids and keeps the newest entry`() {
        val older =
            BackgroundProcessEntry(
                id = "process-1",
                type = ProcessType.TOOL_EXEC,
                description = "Old entry",
                timestamp = 100,
                status = ProcessStatus.ACTIVE,
            )
        val newer =
            older.copy(
                description = "New entry",
                timestamp = 200,
            )

        val state =
            BackgroundProcessState(
                processes = listOf(older, newer),
            ).updateProcess(
                id = "process-1",
                status = ProcessStatus.COMPLETED,
                durationMs = 42,
            )

        assertEquals(1, state.processes.size)
        assertEquals("New entry", state.processes.single().description)
        assertEquals(ProcessStatus.COMPLETED, state.processes.single().status)
        assertEquals(42, state.processes.single().durationMs)
    }
}
