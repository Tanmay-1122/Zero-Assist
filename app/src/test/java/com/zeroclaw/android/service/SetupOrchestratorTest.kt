/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import android.content.Context
import com.zeroclaw.android.ui.screen.setup.SetupStepStatus
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import java.nio.file.Path
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@DisplayName("SetupOrchestrator")
class SetupOrchestratorTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    @DisplayName("full setup stops before workspace scaffold when config validation fails")
    fun `full setup stops before workspace scaffold when config validation fails`() =
        runTest {
            val daemonBridge = mockk<DaemonServiceBridge>(relaxed = true)
            val orchestrator =
                SetupOrchestrator(
                    daemonBridge = daemonBridge,
                    healthBridge = mockk(relaxed = true),
                    configValidationBridge = ConfigValidationBridge { "invalid TOML" },
                )

            orchestrator.runFullSetup(
                context = testContext(),
                configToml = "not valid",
                agentName = "Zero Assist",
                userName = "User",
                timezone = "UTC",
                communicationStyle = "direct",
                expectedChannels = emptyList(),
                port = 42617u,
            )

            assertEquals(
                SetupStepStatus.Failed(error = "invalid TOML"),
                orchestrator.progress.value.configValidation,
            )
            assertEquals(SetupStepStatus.Pending, orchestrator.progress.value.workspaceScaffold)
            coVerify(exactly = 0) { daemonBridge.ensureWorkspace(any(), any(), any(), any()) }
            coVerify(exactly = 0) { daemonBridge.stop() }
        }

    @Test
    @DisplayName("hot reload validates config before stopping daemon")
    fun `hot reload validates config before stopping daemon`() =
        runTest {
            val daemonBridge = mockk<DaemonServiceBridge>(relaxed = true)
            val orchestrator =
                SetupOrchestrator(
                    daemonBridge = daemonBridge,
                    healthBridge = mockk(relaxed = true),
                    configValidationBridge = ConfigValidationBridge { "invalid TOML" },
                )

            orchestrator.runHotReload(
                context = testContext(),
                configToml = "not valid",
                expectedChannels = emptyList(),
                port = 42617u,
            )

            assertEquals(
                SetupStepStatus.Failed(error = "invalid TOML"),
                orchestrator.progress.value.configValidation,
            )
            assertEquals(SetupStepStatus.Success, orchestrator.progress.value.workspaceScaffold)
            coVerify(exactly = 0) { daemonBridge.stop() }
        }

    private fun testContext(): Context {
        val context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns tempDir.toFile()
        return context
    }
}
