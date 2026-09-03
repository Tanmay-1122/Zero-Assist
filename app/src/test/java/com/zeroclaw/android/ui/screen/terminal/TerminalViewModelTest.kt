/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.terminal

import com.zeroclaw.android.model.TerminalEntry
import com.zeroclaw.android.model.ToolSpec
import com.zeroclaw.android.service.termux.TermuxBootstrapAvailability
import com.zeroclaw.android.service.termux.TermuxBootstrapState
import com.zeroclaw.android.service.termux.TermuxCapabilitiesSnapshot
import com.zeroclaw.android.service.termux.TermuxCommandCapability
import com.zeroclaw.android.service.termux.TermuxExecutionLimits
import com.zeroclaw.android.service.termux.TermuxExecutionResult
import com.zeroclaw.android.service.termux.TermuxExecutionSnapshot
import com.zeroclaw.android.service.termux.TermuxHealthSnapshot
import com.zeroclaw.android.service.termux.TermuxHealthStatus
import com.zeroclaw.android.service.termux.TermuxPackageAvailability
import com.zeroclaw.android.service.termux.TermuxPackageState
import com.zeroclaw.android.service.termux.TermuxPermissionAvailability
import com.zeroclaw.android.service.termux.TermuxPermissionState
import com.zeroclaw.android.service.termux.TermuxProotState
import com.zeroclaw.android.service.termux.TermuxRuntimeStatus
import com.zeroclaw.android.service.uiagent.UiAgentGoal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

/**
 * Unit tests for [TerminalViewModel] companion utilities and [TerminalState].
 *
 * The ViewModel itself requires an [android.app.Application] and native
 * library loading, so these tests exercise the static helper functions
 * and state mapping logic that can be tested without the Android framework.
 */
@DisplayName("TerminalViewModel")
class TerminalViewModelTest {
    @Nested
    @DisplayName("toBlock")
    inner class ToBlock {
        @Test
        @DisplayName("maps input entry to Input block")
        fun `maps input entry to Input block`() {
            val entry =
                TerminalEntry(
                    id = 1,
                    content = "/status",
                    entryType = "input",
                    timestamp = 1000L,
                )
            val block = TerminalViewModel.toBlock(entry)
            assertTrue(block is TerminalBlock.Input)
            assertEquals("/status", (block as TerminalBlock.Input).text)
            assertEquals(1L, block.id)
            assertEquals(1000L, block.timestamp)
        }

        @Test
        @DisplayName("maps response entry to Response block")
        fun `maps response entry to Response block`() {
            val entry =
                TerminalEntry(
                    id = 2,
                    content = "Daemon is running",
                    entryType = "response",
                    timestamp = 2000L,
                )
            val block = TerminalViewModel.toBlock(entry)
            assertTrue(block is TerminalBlock.Response)
            assertEquals("Daemon is running", (block as TerminalBlock.Response).content)
        }

        @Test
        @DisplayName("maps JSON response to Structured block")
        fun `maps JSON response to Structured block`() {
            val json = """{"daemon_running": true, "uptime": 3600}"""
            val entry =
                TerminalEntry(
                    id = 3,
                    content = json,
                    entryType = "response",
                    timestamp = 3000L,
                )
            val block = TerminalViewModel.toBlock(entry)
            assertTrue(block is TerminalBlock.Structured)
            assertEquals(json, (block as TerminalBlock.Structured).json)
        }

        @Test
        @DisplayName("maps array response to Structured block")
        fun `maps array response to Structured block`() {
            val json = """[{"name": "skill-1"}]"""
            val entry =
                TerminalEntry(
                    id = 4,
                    content = json,
                    entryType = "response",
                    timestamp = 4000L,
                )
            val block = TerminalViewModel.toBlock(entry)
            assertTrue(block is TerminalBlock.Structured)
        }

        @Test
        @DisplayName("maps error entry to Error block")
        fun `maps error entry to Error block`() {
            val entry =
                TerminalEntry(
                    id = 5,
                    content = "Connection refused",
                    entryType = "error",
                    timestamp = 5000L,
                )
            val block = TerminalViewModel.toBlock(entry)
            assertTrue(block is TerminalBlock.Error)
            assertEquals("Connection refused", (block as TerminalBlock.Error).message)
        }

        @Test
        @DisplayName("maps system entry to System block")
        fun `maps system entry to System block`() {
            val entry =
                TerminalEntry(
                    id = 6,
                    content = "ZeroClaw Terminal v0.0.37",
                    entryType = "system",
                    timestamp = 6000L,
                )
            val block = TerminalViewModel.toBlock(entry)
            assertTrue(block is TerminalBlock.System)
            assertEquals("ZeroClaw Terminal v0.0.37", (block as TerminalBlock.System).text)
        }

        @Test
        @DisplayName("maps unknown entry type to System block")
        fun `maps unknown entry type to System block`() {
            val entry =
                TerminalEntry(
                    id = 7,
                    content = "Unknown type",
                    entryType = "unknown",
                    timestamp = 7000L,
                )
            val block = TerminalViewModel.toBlock(entry)
            assertTrue(block is TerminalBlock.System)
        }

        @Test
        @DisplayName("maps input entry with image URIs to Input block with image names")
        fun `maps input entry with image URIs to Input block with image names`() {
            val entry =
                TerminalEntry(
                    id = 8,
                    content = "describe this",
                    entryType = "input",
                    timestamp = 8000L,
                    imageUris = listOf("content://media/external/images/photo.jpg"),
                )
            val block = TerminalViewModel.toBlock(entry)
            assertTrue(block is TerminalBlock.Input)
            val inputBlock = block as TerminalBlock.Input
            assertEquals(1, inputBlock.imageNames.size)
            assertEquals("photo.jpg", inputBlock.imageNames.first())
        }
    }

    @Nested
    @DisplayName("stripThinkingTags")
    inner class StripThinkingTags {
        @Test
        @DisplayName("removes think tags from response")
        fun `removes think tags from response`() {
            val input = "<think>Let me reason about this.</think>The answer is 42."
            val result = TerminalViewModel.stripThinkingTags(input)
            assertEquals("The answer is 42.", result)
        }

        @Test
        @DisplayName("removes thinking tags from response")
        fun `removes thinking tags from response`() {
            val input = "<thinking>Internal reasoning here.</thinking>Final answer."
            val result = TerminalViewModel.stripThinkingTags(input)
            assertEquals("Final answer.", result)
        }

        @Test
        @DisplayName("removes commentary tags from response")
        fun `removes commentary tags from response`() {
            val input = "<commentary>Internal notes.</commentary>The answer."
            val result = TerminalViewModel.stripThinkingTags(input)
            assertEquals("The answer.", result)
        }

        @Test
        @DisplayName("removes tool_output tags from response")
        fun `removes tool_output tags from response`() {
            val input = "<tool_output>curl result here</tool_output>Summary."
            val result = TerminalViewModel.stripThinkingTags(input)
            assertEquals("Summary.", result)
        }

        @Test
        @DisplayName("removes analysis tags from response")
        fun `removes analysis tags from response`() {
            val input = "<analysis>We have curl and can reach...</analysis>Yes, I can."
            val result = TerminalViewModel.stripThinkingTags(input)
            assertEquals("Yes, I can.", result)
        }

        @Test
        @DisplayName("passes through text without thinking tags")
        fun `passes through text without thinking tags`() {
            val input = "Plain response text"
            val result = TerminalViewModel.stripThinkingTags(input)
            assertEquals("Plain response text", result)
        }
    }

    @Nested
    @DisplayName("stripToolCallTags")
    inner class StripToolCallTags {
        @Test
        @DisplayName("removes tool_call tags from response")
        fun `removes tool_call tags from response`() {
            val input = "Result: <tool_call>{\"name\":\"test\"}</tool_call>done"
            val result = TerminalViewModel.stripToolCallTags(input)
            assertEquals("Result: done", result)
        }

        @Test
        @DisplayName("removes unclosed tool_call tags")
        fun `removes unclosed tool_call tags`() {
            val input = "Partial: <tool_call>{\"name\":\"test\"}"
            val result = TerminalViewModel.stripToolCallTags(input)
            assertEquals("Partial:", result)
        }

        @Test
        @DisplayName("passes through text without tool call tags")
        fun `passes through text without tool call tags`() {
            val input = "Normal response"
            val result = TerminalViewModel.stripToolCallTags(input)
            assertEquals("Normal response", result)
        }
    }

    @Nested
    @DisplayName("bindResultPattern")
    inner class BindResultPattern {
        @Test
        @DisplayName("matches valid bind result for telegram")
        fun `matches valid bind result for telegram`() {
            val input = "Bound alice to telegram (allowed_users). Restart daemon to apply."
            val match = TerminalViewModel.BIND_RESULT_PATTERN.find(input)
            assertNotNull(match) { "Failed to match bind result pattern for: $input" }
            val result = match!!
            assertEquals("alice", result.destructured.component1())
            assertEquals("telegram", result.destructured.component2())
            assertEquals("allowed_users", result.destructured.component3())
        }

        @Test
        @DisplayName("matches bind result for whatsapp with phone number")
        fun `matches bind result for whatsapp with phone number`() {
            val input =
                "Bound +1234567890 to whatsapp (allowed_numbers). Restart daemon to apply."
            val match = TerminalViewModel.BIND_RESULT_PATTERN.find(input)
            assertNotNull(match) { "Failed to match bind result pattern for: $input" }
            val result = match!!
            assertEquals("+1234567890", result.destructured.component1())
            assertEquals("whatsapp", result.destructured.component2())
            assertEquals("allowed_numbers", result.destructured.component3())
        }

        @Test
        @DisplayName("does not match already bound message")
        fun `does not match already bound message`() {
            val input = "alice is already bound to telegram"
            assertNull(TerminalViewModel.BIND_RESULT_PATTERN.find(input))
        }

        @Test
        @DisplayName("does not match unrelated text")
        fun `does not match unrelated text`() {
            val input = "Daemon is running"
            assertNull(TerminalViewModel.BIND_RESULT_PATTERN.find(input))
        }
    }

    @Nested
    @DisplayName("TerminalState defaults")
    inner class StateDefaults {
        @Test
        @DisplayName("default state has empty blocks and loading false")
        fun `default state has empty blocks and loading false`() {
            val state = TerminalState()
            assertTrue(state.blocks.isEmpty())
            assertEquals(false, state.isLoading)
            assertTrue(state.pendingImages.isEmpty())
            assertEquals(false, state.isProcessingImages)
        }

        @Test
        @DisplayName("default setup progress has empty purged channels")
        fun `default setup progress has empty purged channels`() {
            val progress =
                com.zeroclaw.android.ui.screen.setup
                    .SetupProgress()
            assertTrue(progress.purgedChannels.isEmpty())
        }
    }

    @Nested
    @DisplayName("Termux formatting")
    inner class TermuxFormatting {
        @Test
        @DisplayName("formats ready status with capabilities next step")
        fun `formats ready status with capabilities next step`() {
            val output =
                TerminalViewModel.formatTermuxStatus(
                    readyTermuxStatus(),
                )

            assertTrue(output.contains("Termux status:"))
            assertTrue(output.contains("Package: installed"))
            assertTrue(output.contains("Bridge: ready"))
            assertTrue(output.contains("Workspace: /data/data/com.termux/files/home/.zero-assist/workspace"))
            assertTrue(output.contains("/termux capabilities"))
        }

        @Test
        @DisplayName("formats missing permission with next action")
        fun `formats missing permission with next action`() {
            val output =
                TerminalViewModel.formatTermuxDoctor(
                    readyTermuxStatus(
                        permissionAvailability = TermuxPermissionAvailability.DENIED,
                        health =
                            TermuxHealthSnapshot(
                                status = TermuxHealthStatus.UNAVAILABLE,
                                reason = "permission denied",
                            ),
                    ),
                )

            assertTrue(output.contains("Termux doctor:"))
            assertTrue(output.contains("FAIL RUN_COMMAND permission"))
            assertTrue(output.contains("Grant Zero-Assist the Termux RUN_COMMAND permission"))
        }

        @Test
        @DisplayName("storage reset recovery script restores external access and python")
        fun `storage reset recovery script restores external access and python`() {
            val script = TerminalViewModel.termuxStorageResetRecoveryScript()

            assertTrue(script.contains("allow-external-apps = true"))
            assertTrue(script.contains("termux-reload-settings"))
            assertTrue(script.contains("pkg install -y python"))
            assertTrue(script.contains("python3 --version"))
        }

        @Test
        @DisplayName("formats setup recovery with paste-ready script")
        fun `formats setup recovery with paste ready script`() {
            val output =
                TerminalViewModel.formatTermuxSetupResult(
                    initialStatus =
                        readyTermuxStatus(
                            health =
                                TermuxHealthSnapshot(
                                    status = TermuxHealthStatus.UNAVAILABLE,
                                    reason = "Termux bridge health endpoint is not reachable.",
                                ),
                        ),
                    startResult = null,
                    finalStatus =
                        readyTermuxStatus(
                            health =
                                TermuxHealthSnapshot(
                                    status = TermuxHealthStatus.UNAVAILABLE,
                                    reason = "Termux bridge health endpoint is not reachable.",
                                ),
                        ),
                    scriptCopied = true,
                    termuxOpened = true,
                )

            assertTrue(output.contains("Termux automated setup:"))
            assertTrue(output.contains("Recovery script: copied to clipboard"))
            assertTrue(output.contains("Termux app: opened"))
            assertTrue(output.contains("allow-external-apps = true"))
        }

        @Test
        @DisplayName("formats setup success without recovery script")
        fun `formats setup success without recovery script`() {
            val output =
                TerminalViewModel.formatTermuxSetupResult(
                    initialStatus = readyTermuxStatus(),
                    startResult = null,
                    finalStatus = readyTermuxStatus(),
                    scriptCopied = false,
                    termuxOpened = false,
                )

            assertTrue(output.contains("After: ready"))
            assertTrue(output.contains("/termux capabilities"))
            assertFalse(output.contains("allow-external-apps = true"))
        }

        @Test
        @DisplayName("formats capabilities with commands and AI tool status")
        fun `formats capabilities with commands and AI tool status`() {
            val output =
                TerminalViewModel.formatTermuxCapabilities(
                    snapshot =
                        TermuxCapabilitiesSnapshot(
                            endpoint = "http://127.0.0.1:8787/capabilities",
                            bridgeVersion = "0.1.0",
                            workspaceRoot = "/data/data/com.termux/files/home/.zero-assist/workspace",
                            termuxHome = "/data/data/com.termux/files/home",
                            termuxUsr = "/data/data/com.termux/files/usr",
                            commands =
                                listOf(
                                    TermuxCommandCapability(
                                        name = "python3",
                                        available = true,
                                        path = "/data/data/com.termux/files/usr/bin/python3",
                                        version = "Python 3.13.13",
                                    ),
                                    TermuxCommandCapability(name = "git", available = false),
                                ),
                            pythonVersion = "3.13.13",
                            pythonExecutable = "/data/data/com.termux/files/usr/bin/python3",
                            proot = TermuxProotState(available = true, distros = listOf("debian")),
                            limits =
                                TermuxExecutionLimits(
                                    approvalRequired = true,
                                    timeoutSeconds = 30,
                                    maxTimeoutSeconds = 120,
                                    maxOutputBytes = 65536,
                                    executionMode = "argv_only_low_risk",
                                ),
                        ),
                    termuxTools =
                        listOf(
                            ToolSpec(
                                name = "termux_get_capabilities",
                                description = "Inspect Termux",
                                source = "termux",
                                parametersJson = "{}",
                                isActive = true,
                                inactiveReason = "",
                            ),
                        ),
                )

            assertTrue(output.contains("python3: available - Python 3.13.13"))
            assertTrue(output.contains("git: missing"))
            assertTrue(output.contains("termux_get_capabilities: active"))
            assertTrue(output.contains("approval required"))
        }

        @Test
        @DisplayName("formats successful smoke execution")
        fun `formats successful smoke execution`() {
            val output =
                TerminalViewModel.formatTermuxSmokeResult(
                    TermuxExecutionResult.Success(
                        TermuxExecutionSnapshot(
                            success = true,
                            id = "zatx_123",
                            status = "completed",
                            argv = listOf("python3", "--version"),
                            workingDirectory = "/data/data/com.termux/files/home/.zero-assist/workspace",
                            exitCode = 0,
                            stdout = "Python 3.13.13\n",
                            stderr = "",
                            durationMs = 42,
                            stdoutTruncated = false,
                            stderrTruncated = false,
                        ),
                    ),
                )

            assertTrue(output.contains("Termux smoke:"))
            assertTrue(output.contains("Command: python3 --version"))
            assertTrue(output.contains("Result: passed"))
            assertTrue(output.contains("Output: Python 3.13.13"))
            assertTrue(output.contains("termux_run"))
        }

        @Test
        @DisplayName("formats smoke failure reason")
        fun `formats smoke failure reason`() {
            val output =
                TerminalViewModel.formatTermuxSmokeResult(
                    TermuxExecutionResult.Failure("Termux bridge execution endpoint is not reachable."),
                )

            assertTrue(output.contains("Result: failed"))
            assertTrue(output.contains("not reachable"))
        }
    }

    @Nested
    @DisplayName("session recovery matcher")
    inner class SessionRecoveryMatcher {
        @Test
        @DisplayName("retries when Rust reports no active session")
        fun `retries when Rust reports no active session`() {
            val shouldRetry =
                TerminalViewModel.shouldRetryWithFreshSessionDetail(
                    errorMessage = "no active session; call session_start first",
                    daemonRunning = true,
                )

            assertTrue(shouldRetry)
        }

        @Test
        @DisplayName("does not retry cancelled turns")
        fun `does not retry cancelled turns`() {
            val shouldRetry =
                TerminalViewModel.shouldRetryWithFreshSessionDetail(
                    errorMessage = "Request cancelled",
                    daemonRunning = true,
                )

            assertFalse(shouldRetry)
        }

        @Test
        @DisplayName("retries daemon-not-running only when service says running")
        fun `retries daemon-not-running only when service says running`() {
            val shouldRetryWhenRunning =
                TerminalViewModel.shouldRetryWithFreshSessionDetail(
                    errorMessage = "daemon not running",
                    daemonRunning = true,
                )
            val shouldRetryWhenStopped =
                TerminalViewModel.shouldRetryWithFreshSessionDetail(
                    errorMessage = "daemon not running",
                    daemonRunning = false,
                )

            assertTrue(shouldRetryWhenRunning)
            assertFalse(shouldRetryWhenStopped)
        }
    }

    private fun readyTermuxStatus(
        packageAvailability: TermuxPackageAvailability = TermuxPackageAvailability.INSTALLED,
        permissionAvailability: TermuxPermissionAvailability = TermuxPermissionAvailability.GRANTED,
        bootstrapAvailability: TermuxBootstrapAvailability = TermuxBootstrapAvailability.AVAILABLE,
        health: TermuxHealthSnapshot =
            TermuxHealthSnapshot(
                status = TermuxHealthStatus.READY,
                reason = "Zero Assist Termux bridge is ready.",
                details =
                    com.zeroclaw.android.service.termux.TermuxBridgeHealthDetails(
                        endpoint = "http://127.0.0.1:8787/health",
                        version = "0.1.0",
                        workspace = "/data/data/com.termux/files/home/.zero-assist/workspace",
                        proot = TermuxProotState(available = true, distros = listOf("debian")),
                    ),
            ),
    ): TermuxRuntimeStatus =
        TermuxRuntimeStatus(
            packageState = TermuxPackageState(availability = packageAvailability, versionName = "0.118.3"),
            permissionState = TermuxPermissionState(availability = permissionAvailability),
            bootstrapState = TermuxBootstrapState(availability = bootstrapAvailability),
            health = health,
        )
}
