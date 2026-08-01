/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.model.Agent
import com.zeroclaw.android.model.AgentRole
import com.zeroclaw.android.model.AgentStatus
import com.zeroclaw.android.model.MemoryConflict
import com.zeroclaw.android.model.MemoryHealthResult
import com.zeroclaw.android.model.ServiceState
import com.zeroclaw.ffi.FfiException
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import java.nio.file.Path
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/**
 * Unit tests for [DaemonServiceBridge].
 *
 * Uses MockK to mock the static UniFFI-generated functions so that tests
 * run without loading the native library.
 */
@DisplayName("DaemonServiceBridge")
@OptIn(ExperimentalCoroutinesApi::class)
class DaemonServiceBridgeTest {
    private lateinit var bridge: DaemonServiceBridge
    private lateinit var statusRepository: AgentStatusRepository

    @BeforeEach
    fun setUp() {
        mockkStatic("com.zeroclaw.ffi.Zeroclaw_androidKt")
        every { com.zeroclaw.ffi.stopDaemon() } returns Unit
        every { com.zeroclaw.ffi.getProviderRoutingStatus() } returns "{}"
        statusRepository = AgentStatusRepository()
        bridge = DaemonServiceBridge("/tmp/test", statusRepository, ioDispatcher = UnconfinedTestDispatcher())
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    @DisplayName("initial service state is STOPPED")
    fun `initial service state is STOPPED`() {
        assertEquals(ServiceState.STOPPED, bridge.serviceState.value)
    }

    @Test
    @DisplayName("initial last status is null")
    fun `initial last status is null`() {
        assertEquals(null, bridge.lastStatus.value)
    }

    @Test
    @DisplayName("start transitions state to RUNNING on success")
    fun `start transitions state to RUNNING on success`() =
        runTest {
            every { com.zeroclaw.ffi.startDaemon(any(), any(), any(), any()) } returns Unit

            bridge.start(configToml = "", host = "127.0.0.1", port = 8080u)

            assertEquals(ServiceState.RUNNING, bridge.serviceState.value)
        }

    @Test
    @DisplayName("stop transitions state to STOPPED on success")
    fun `stop transitions state to STOPPED on success`() =
        runTest {
            every { com.zeroclaw.ffi.startDaemon(any(), any(), any(), any()) } returns Unit
            every { com.zeroclaw.ffi.stopDaemon() } returns Unit

            bridge.start(configToml = "", host = "127.0.0.1", port = 8080u)
            bridge.stop()

            assertEquals(ServiceState.STOPPED, bridge.serviceState.value)
        }

    @Test
    @DisplayName("pollStatus parses JSON and updates lastStatus")
    fun `pollStatus parses JSON and updates lastStatus`() =
        runTest {
            val json =
                """
                {
                    "daemon_running": true,
                    "uptime_seconds": 42,
                    "components": {
                        "gateway": {"status": "ok"}
                    }
                }
                """.trimIndent()
            every { com.zeroclaw.ffi.getStatus() } returns json

            val status = bridge.pollStatus()

            assertEquals(true, status.running)
            assertEquals(42L, status.uptimeSeconds)
            assertEquals("ok", status.components["gateway"]?.status)
            assertEquals(status, bridge.lastStatus.value)
            assertEquals(ServiceState.RUNNING, bridge.serviceState.value)
        }

    @Test
    @DisplayName("pollStatus marks bridge stopped when daemon disappears")
    fun `pollStatus marks bridge stopped when daemon disappears`() =
        runTest {
            every { com.zeroclaw.ffi.startDaemon(any(), any(), any(), any()) } returns Unit
            every { com.zeroclaw.ffi.getStatus() } throws
                FfiException.StateException("detail=daemon not running")

            bridge.start(configToml = "", host = "127.0.0.1", port = 8080u)

            assertThrows<FfiException> {
                bridge.pollStatus()
            }

            assertEquals(ServiceState.STOPPED, bridge.serviceState.value)
            assertEquals("detail=daemon not running", bridge.lastError.value)
            assertNull(bridge.lastStatus.value)
        }

    @Test
    @DisplayName("initial lastError is null")
    fun `initial lastError is null`() {
        assertNull(bridge.lastError.value)
    }

    @Test
    @DisplayName("start sets lastError on failure")
    fun `start sets lastError on failure`() =
        runTest {
            every {
                com.zeroclaw.ffi.startDaemon(any(), any(), any(), any())
            } throws FfiException.ConfigException("bad toml at line 1")

            assertThrows<FfiException> {
                bridge.start(configToml = "", host = "127.0.0.1", port = 8080u)
            }

            assertEquals("bad toml at line 1", bridge.lastError.value)
            assertEquals(ServiceState.ERROR, bridge.serviceState.value)
        }

    @Test
    @DisplayName("start clears lastError on success")
    fun `start clears lastError on success`() =
        runTest {
            every {
                com.zeroclaw.ffi.startDaemon(any(), any(), any(), any())
            } throws FfiException.SpawnException("spawn failure")

            assertThrows<FfiException> {
                bridge.start(configToml = "", host = "127.0.0.1", port = 8080u)
            }
            assertEquals("spawn failure", bridge.lastError.value)

            every {
                com.zeroclaw.ffi.startDaemon(any(), any(), any(), any())
            } returns Unit

            bridge.start(configToml = "", host = "127.0.0.1", port = 8080u)

            assertNull(bridge.lastError.value)
            assertEquals(ServiceState.RUNNING, bridge.serviceState.value)
        }

    @Test
    @DisplayName("stop sets lastError on failure")
    fun `stop sets lastError on failure`() =
        runTest {
            every { com.zeroclaw.ffi.startDaemon(any(), any(), any(), any()) } returns Unit
            every {
                com.zeroclaw.ffi.stopDaemon()
            } throws FfiException.ShutdownException("shutdown timeout")

            bridge.start(configToml = "", host = "127.0.0.1", port = 8080u)

            assertThrows<FfiException> { bridge.stop() }

            assertEquals("shutdown timeout", bridge.lastError.value)
            assertEquals(ServiceState.ERROR, bridge.serviceState.value)
        }

    @Test
    @DisplayName("stop clears lastError on success")
    fun `stop clears lastError on success`() =
        runTest {
            every { com.zeroclaw.ffi.startDaemon(any(), any(), any(), any()) } returns Unit
            every { com.zeroclaw.ffi.stopDaemon() } returns Unit

            bridge.start(configToml = "", host = "127.0.0.1", port = 8080u)
            bridge.stop()

            assertNull(bridge.lastError.value)
            assertEquals(ServiceState.STOPPED, bridge.serviceState.value)
        }

    @Test
    @DisplayName("stop treats daemon not running as already stopped")
    fun `stop treats daemon not running as already stopped`() =
        runTest {
            every { com.zeroclaw.ffi.startDaemon(any(), any(), any(), any()) } returns Unit
            every { com.zeroclaw.ffi.stopDaemon() } throws
                FfiException.StateException("detail=daemon not running")

            bridge.start(configToml = "", host = "127.0.0.1", port = 8080u)
            bridge.stop()

            assertEquals(ServiceState.STOPPED, bridge.serviceState.value)
            assertNull(bridge.lastError.value)
            assertNull(bridge.lastStatus.value)
        }

    /**
     * Creates a [DaemonServiceBridge] whose `dataDir` points at the
     * given temporary directory, allowing filesystem-based tests to
     * run in isolation.
     */
    private fun bridgeWithDir(dir: Path): DaemonServiceBridge =
        DaemonServiceBridge(
            dir.toAbsolutePath().toString(),
            AgentStatusRepository(),
            ioDispatcher = UnconfinedTestDispatcher(),
        )

    @Test
    @DisplayName("detectMemoryConflict returns None when no stale files")
    fun `detectMemoryConflict returns None when no stale files`(
        @TempDir tempDir: Path,
    ) {
        val ws = tempDir.resolve("workspace").toFile()
        ws.mkdirs()
        val b = bridgeWithDir(tempDir)

        assertEquals(MemoryConflict.None, b.detectMemoryConflict("sqlite"))
    }

    @Test
    @DisplayName("detectMemoryConflict finds stale markdown when backend is sqlite")
    fun `detectMemoryConflict finds stale markdown when backend is sqlite`(
        @TempDir tempDir: Path,
    ) {
        val ws = tempDir.resolve("workspace").toFile()
        val memDir = java.io.File(ws, "memory")
        memDir.mkdirs()
        java.io.File(memDir, "2026-02-26.md").writeText("log entry")
        java.io.File(memDir, "2026-02-25.md").writeText("older entry")

        val b = bridgeWithDir(tempDir)
        val result = b.detectMemoryConflict("sqlite")

        assertTrue(result is MemoryConflict.StaleData)
        val stale = result as MemoryConflict.StaleData
        assertEquals("sqlite", stale.currentBackend)
        assertEquals("markdown", stale.staleBackend)
        assertEquals(2, stale.staleFileCount)
        assertTrue(stale.staleSizeBytes > 0)
    }

    @Test
    @DisplayName("detectMemoryConflict finds stale sqlite when backend is markdown")
    fun `detectMemoryConflict finds stale sqlite when backend is markdown`(
        @TempDir tempDir: Path,
    ) {
        val ws = tempDir.resolve("workspace").toFile()
        ws.mkdirs()
        java.io.File(ws, "memory.db").writeText("fake db")

        val b = bridgeWithDir(tempDir)
        val result = b.detectMemoryConflict("markdown")

        assertTrue(result is MemoryConflict.StaleData)
        val stale = result as MemoryConflict.StaleData
        assertEquals("markdown", stale.currentBackend)
        assertEquals("sqlite", stale.staleBackend)
        assertEquals(1, stale.staleFileCount)
    }

    @Test
    @DisplayName("detectMemoryConflict returns None for backend none with empty workspace")
    fun `detectMemoryConflict returns None for backend none with empty workspace`(
        @TempDir tempDir: Path,
    ) {
        val ws = tempDir.resolve("workspace").toFile()
        ws.mkdirs()

        val b = bridgeWithDir(tempDir)
        assertEquals(MemoryConflict.None, b.detectMemoryConflict("none"))
    }

    @Test
    @DisplayName("detectMemoryConflict for none backend finds stale data from both backends")
    fun `detectMemoryConflict for none backend finds stale data`(
        @TempDir tempDir: Path,
    ) {
        val ws = tempDir.resolve("workspace").toFile()
        ws.mkdirs()
        java.io.File(ws, "memory.db").writeText("fake db")
        val memDir = java.io.File(ws, "memory")
        memDir.mkdirs()
        java.io.File(memDir, "2026-02-26.md").writeText("log entry")

        val b = bridgeWithDir(tempDir)
        val result = b.detectMemoryConflict("none")

        assertTrue(result is MemoryConflict.StaleData)
        val stale = result as MemoryConflict.StaleData
        assertEquals("none", stale.currentBackend)
        assertEquals("both", stale.staleBackend)
        assertEquals(2, stale.staleFileCount)
    }

    @Test
    @DisplayName("detectMemoryConflict finds stale sqlite in state subdirectory")
    fun `detectMemoryConflict finds stale sqlite in state subdirectory`(
        @TempDir tempDir: Path,
    ) {
        val ws = tempDir.resolve("workspace").toFile()
        val stateDir = java.io.File(ws, "state")
        stateDir.mkdirs()
        java.io.File(stateDir, "memory.db").writeText("fake db")

        val b = bridgeWithDir(tempDir)
        val result = b.detectMemoryConflict("markdown")

        assertTrue(result is MemoryConflict.StaleData)
        val stale = result as MemoryConflict.StaleData
        assertEquals("sqlite", stale.staleBackend)
        assertEquals(1, stale.staleFileCount)
    }

    @Test
    @DisplayName("cleanupStaleMemory deletes sqlite files from state subdirectory")
    fun `cleanupStaleMemory deletes sqlite files from state subdirectory`(
        @TempDir tempDir: Path,
    ) {
        val ws = tempDir.resolve("workspace").toFile()
        val stateDir = java.io.File(ws, "state")
        stateDir.mkdirs()
        val db = java.io.File(stateDir, "memory.db")
        db.writeText("fake db")

        val b = bridgeWithDir(tempDir)
        val conflict =
            MemoryConflict.StaleData(
                currentBackend = "markdown",
                staleBackend = "sqlite",
                staleFileCount = 1,
                staleSizeBytes = db.length(),
            )

        b.cleanupStaleMemory(conflict)

        assertFalse(db.exists())
        assertTrue(stateDir.isDirectory)
    }

    @Test
    @DisplayName("cleanupStaleMemory deletes markdown daily logs")
    fun `cleanupStaleMemory deletes markdown daily logs`(
        @TempDir tempDir: Path,
    ) {
        val ws = tempDir.resolve("workspace").toFile()
        val memDir = java.io.File(ws, "memory")
        memDir.mkdirs()
        val log1 = java.io.File(memDir, "2026-02-26.md")
        val log2 = java.io.File(memDir, "2026-02-25.md")
        log1.writeText("entry1")
        log2.writeText("entry2")

        val b = bridgeWithDir(tempDir)
        val conflict =
            MemoryConflict.StaleData(
                currentBackend = "sqlite",
                staleBackend = "markdown",
                staleFileCount = 2,
                staleSizeBytes = log1.length() + log2.length(),
            )

        b.cleanupStaleMemory(conflict)

        assertFalse(log1.exists())
        assertFalse(log2.exists())
        assertTrue(memDir.isDirectory)
    }

    @Test
    @DisplayName("cleanupStaleMemory deletes sqlite db files")
    fun `cleanupStaleMemory deletes sqlite db files`(
        @TempDir tempDir: Path,
    ) {
        val ws = tempDir.resolve("workspace").toFile()
        ws.mkdirs()
        val db = java.io.File(ws, "memory.db")
        val wal = java.io.File(ws, "memory.db-wal")
        val shm = java.io.File(ws, "memory.db-shm")
        db.writeText("db")
        wal.writeText("wal")
        shm.writeText("shm")

        val b = bridgeWithDir(tempDir)
        val conflict =
            MemoryConflict.StaleData(
                currentBackend = "markdown",
                staleBackend = "sqlite",
                staleFileCount = 3,
                staleSizeBytes = db.length() + wal.length() + shm.length(),
            )

        b.cleanupStaleMemory(conflict)

        assertFalse(db.exists())
        assertFalse(wal.exists())
        assertFalse(shm.exists())
    }

    @Test
    @DisplayName("checkMemoryHealth returns Healthy for none backend")
    fun `checkMemoryHealth returns Healthy for none backend`(
        @TempDir tempDir: Path,
    ) {
        val b = bridgeWithDir(tempDir)
        assertEquals(MemoryHealthResult.Healthy, b.checkMemoryHealth("none"))
    }

    @Test
    @DisplayName("checkMemoryHealth returns Healthy for writable markdown dir")
    fun `checkMemoryHealth returns Healthy for writable markdown dir`(
        @TempDir tempDir: Path,
    ) {
        val memDir = tempDir.resolve("workspace").resolve("memory").toFile()
        memDir.mkdirs()

        val b = bridgeWithDir(tempDir)
        assertEquals(MemoryHealthResult.Healthy, b.checkMemoryHealth("markdown"))
    }

    @Test
    @DisplayName("checkMemoryHealth returns Unhealthy when dir is not writable")
    fun `checkMemoryHealth returns Unhealthy when dir is not writable`(
        @TempDir tempDir: Path,
    ) {
        val ws = tempDir.resolve("workspace").toFile()
        ws.mkdirs()
        val blocker = java.io.File(ws, "memory")
        blocker.writeText("not a directory")

        val b = bridgeWithDir(tempDir)
        val result = b.checkMemoryHealth("markdown")
        assertTrue(result is MemoryHealthResult.Unhealthy)
    }

    @Test
    @DisplayName("checkMemoryHealth returns Healthy for writable sqlite workspace")
    fun `checkMemoryHealth returns Healthy for writable sqlite workspace`(
        @TempDir tempDir: Path,
    ) {
        val ws = tempDir.resolve("workspace").toFile()
        ws.mkdirs()

        val b = bridgeWithDir(tempDir)
        assertEquals(MemoryHealthResult.Healthy, b.checkMemoryHealth("sqlite"))
    }

    @Test
    @DisplayName("emitAgentAssigning limits visible assignments to two sentences")
    fun `emitAgentAssigning limits visible assignments to two sentences`() {
        val sink = RecordingAgentGroupChatSink()
        val master = testAgent(id = "master", name = "Master", role = AgentRole.MASTER, isMaster = true)
        val coder = testAgent(id = "coder", name = "Coder", role = AgentRole.CODER)
        bridge.agentGroupChatSink = sink

        bridge.emitAgentAssigning(
            fromAgent = master,
            toAgent = coder,
            taskSummary = " Review the crash report.\nCapture the failing path. Keep the raw trace private. ",
        )

        assertEquals("Review the crash report. Capture the failing path.", sink.assignmentSummary)
    }

    @Test
    @DisplayName("emitAgentStatusUpdate hides structured payloads behind a generic status")
    fun `emitAgentStatusUpdate hides structured payloads behind a generic status`() {
        val sink = RecordingAgentGroupChatSink()
        val researcher = testAgent(id = "researcher", name = "Researcher", role = AgentRole.RESEARCHER)
        bridge.agentGroupChatSink = sink

        bridge.emitAgentStatusUpdate(
            agent = researcher,
            status = """{"embedding":[0.12,0.98],"metadata":{"scope":"internal"},"status":"running"}""",
        )

        assertEquals("Researcher shared a status update.", sink.statusSummary)
    }

    @Test
    @DisplayName("emitAgentResult limits visible summaries to three sentences")
    fun `emitAgentResult limits visible summaries to three sentences`() {
        val sink = RecordingAgentGroupChatSink()
        val analyst = testAgent(id = "analyst", name = "Analyst", role = AgentRole.ANALYST)
        bridge.agentGroupChatSink = sink

        bridge.emitAgentResult(
            agent = analyst,
            summary = "Checked the API contract. Found a nullability mismatch. Suggested a safer mapper. Preserved the full debug context internally.",
        )

        assertEquals(
            "Checked the API contract. Found a nullability mismatch. Suggested a safer mapper.",
            sink.resultSummary,
        )
    }

    @Test
    @DisplayName("agent live state tracks delegation typing and completion")
    fun `agent live state tracks delegation typing and completion`() {
        val sink = RecordingAgentGroupChatSink()
        val master = testAgent(id = "master", name = "Master", role = AgentRole.MASTER, isMaster = true)
        val coder = testAgent(id = "coder", name = "Coder", role = AgentRole.CODER)
        bridge.agentGroupChatSink = sink

        bridge.emitAgentAssigning(
            fromAgent = master,
            toAgent = coder,
            taskSummary = "Review the failing build and prepare a fix.",
        )
        assertEquals(AgentStatus.DELEGATING, bridge.agentLiveStates.value[master.id]?.status)

        bridge.emitAgentTypingStart(coder.id)
        assertEquals(AgentStatus.THINKING, bridge.agentLiveStates.value[coder.id]?.status)

        bridge.emitAgentTypingStop(coder.id)
        assertEquals(AgentStatus.IDLE, bridge.agentLiveStates.value[coder.id]?.status)

        bridge.emitAgentResult(
            agent = coder,
            summary = "Fixed the null guard. Added coverage. Left the raw logs private.",
        )
        assertEquals(AgentStatus.DONE, bridge.agentLiveStates.value[coder.id]?.status)
    }

    private fun testAgent(
        id: String,
        name: String,
        role: AgentRole,
        isMaster: Boolean = false,
    ): Agent =
        Agent(
            id = id,
            name = name,
            provider = "OpenAI",
            modelName = "gpt-test",
            role = role,
            isMaster = isMaster,
            avatar = "",
            accentColor = 0xFFFFD700,
        )

    private class RecordingAgentGroupChatSink : AgentGroupChatSink {
        var assignmentSummary: String? = null
        var statusSummary: String? = null
        var resultSummary: String? = null

        override fun onAgentAssigning(fromAgent: Agent, toAgent: Agent, taskSummary: String) {
            assignmentSummary = taskSummary
        }

        override fun onAgentStatusUpdate(agent: Agent, status: String) {
            statusSummary = status
        }

        override fun onAgentResult(agent: Agent, summary: String) {
            resultSummary = summary
        }

        override fun onAgentTypingStart(agentId: String) = Unit

        override fun onAgentTypingStop(agentId: String) = Unit

        override fun onAgentStreamingChunk(messageId: String, chunk: String) = Unit
    }
}
