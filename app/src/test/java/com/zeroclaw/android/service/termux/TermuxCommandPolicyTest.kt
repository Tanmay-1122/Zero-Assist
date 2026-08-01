/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.termux

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("TermuxCommandPolicy")
class TermuxCommandPolicyTest {
    private val policy = TermuxCommandPolicy()

    @Test
    fun `classifies bounded diagnostics as low risk`() {
        val decision =
            policy.classify(
                TermuxCommandPolicyInput(
                    command = "/data/data/com.termux/files/usr/bin/uname",
                    arguments = listOf("-a"),
                    workingDirectory = "/data/data/com.termux/files/home",
                ),
            )

        assertEquals(TermuxCommandRisk.LOW, decision.risk)
        assertTrue(decision.reason.contains("diagnostic"))
    }

    @Test
    fun `classifies unknown commands as medium risk`() {
        val decision =
            policy.classify(
                TermuxCommandPolicyInput(
                    command = "zero-assist-helper",
                    arguments = listOf("--status"),
                    workingDirectory = "/data/data/com.termux/files/home",
                ),
            )

        assertEquals(TermuxCommandRisk.MEDIUM, decision.risk)
        assertTrue(decision.reason.contains("needs approval"))
    }

    @Test
    fun `classifies touch of simple workspace files as low risk`() {
        val decision =
            policy.classify(
                TermuxCommandPolicyInput(
                    command = "touch",
                    arguments = listOf("test1.txt", "test2.txt"),
                    workingDirectory = DEFAULT_TERMUX_WORKSPACE,
                ),
            )

        assertEquals(TermuxCommandRisk.LOW, decision.risk)
        assertTrue(decision.reason.contains("Termux workspace"))
    }

    @Test
    fun `touch outside relative workspace paths still needs approval`() {
        val decision =
            policy.classify(
                TermuxCommandPolicyInput(
                    command = "touch",
                    arguments = listOf("../outside.txt"),
                    workingDirectory = DEFAULT_TERMUX_WORKSPACE,
                ),
            )

        assertEquals(TermuxCommandRisk.MEDIUM, decision.risk)
    }

    @Test
    fun `classifies destructive commands as high risk`() {
        val decision =
            policy.classify(
                TermuxCommandPolicyInput(
                    command = "rm",
                    arguments = listOf("-rf", "/data/data/com.termux/files/home/tmp"),
                    workingDirectory = "/data/data/com.termux/files/home",
                ),
            )

        assertEquals(TermuxCommandRisk.HIGH, decision.risk)
        assertTrue(decision.reason.contains("alter"))
    }

    @Test
    fun `asks approval for interactive shell commands`() {
        val decision =
            policy.classify(
                TermuxCommandPolicyInput(
                    command = "bash",
                    arguments = listOf("-lc", "echo unsafe"),
                    workingDirectory = "/data/data/com.termux/files/home",
                ),
            )

        assertEquals(TermuxCommandRisk.HIGH, decision.risk)
        assertTrue(decision.reason.contains("approval"))
    }

    @Test
    fun `asks approval for working directories outside Termux app storage`() {
        val decision =
            policy.classify(
                TermuxCommandPolicyInput(
                    command = "whoami",
                    workingDirectory = "/sdcard/Download",
                ),
            )

        assertEquals(TermuxCommandRisk.HIGH, decision.risk)
        assertTrue(decision.reason.contains("approval"))
    }

    @Test
    fun `asks approval for working directory prefix lookalikes`() {
        val decision =
            policy.classify(
                TermuxCommandPolicyInput(
                    command = "whoami",
                    workingDirectory = "/data/data/com.termux/files/homeevil",
                ),
            )

        assertEquals(TermuxCommandRisk.HIGH, decision.risk)
    }

    @Test
    fun `classifies reserved bridge bootstrap request as low risk`() {
        val request =
            TermuxBridgeBootstrapRequestBuilder().build(
                TermuxBridgeBootstrapConfig(
                    port = 8787,
                    token = "test-token",
                ),
            )
        val decision =
            policy.classify(
                TermuxCommandPolicyInput(
                    command = request.commandPath,
                    arguments = request.arguments,
                    workingDirectory = request.workingDirectory,
                ),
            )

        assertEquals(TermuxCommandRisk.LOW, decision.risk)
        assertTrue(decision.reason.contains("bridge bootstrap"))
    }

    @Test
    fun `approval fingerprint binds command arguments and working directory`() {
        val request =
            TermuxCommandPolicyInput(
                command = "pkg",
                arguments = listOf("install", "git"),
                workingDirectory = "/data/data/com.termux/files/home/.zero-assist/workspace",
            )

        val same =
            request.copy(
                command = " pkg ",
                arguments = listOf(" install ", " git "),
                workingDirectory = "/data/data/com.termux/files/home/.zero-assist/workspace/",
            )
        val changed = request.copy(arguments = listOf("install", "nodejs"))

        assertEquals(request.approvalFingerprint(), same.approvalFingerprint())
        assertNotEquals(request.approvalFingerprint(), changed.approvalFingerprint())
    }

    @Test
    fun `parses approval request tool output`() {
        val request =
            TermuxApprovalRequest.fromToolOutput(
                toolName = "termux_run",
                output =
                    """
                    {
                      "approval_required": true,
                      "request_id": "zatap_123",
                      "command": "pkg",
                      "arguments": ["install", "git"],
                      "working_directory": "/data/data/com.termux/files/home/.zero-assist/workspace",
                      "risk": "HIGH",
                      "reason": "Installs a package.",
                      "fingerprint": "abc"
                    }
                    """.trimIndent(),
            )

        assertEquals("zatap_123", request?.id)
        assertEquals(TermuxCommandRisk.HIGH, request?.risk)
        assertEquals("pkg install git", request?.commandPreview)
        assertEquals(false, request?.blocked)
    }

    @Test
    fun `parses legacy blocked tool output as high risk approval request`() {
        val request =
            TermuxApprovalRequest.fromToolOutput(
                toolName = "termux_run",
                output =
                    """
                    {
                      "approval_required": false,
                      "blocked": true,
                      "command": "sh",
                      "arguments": ["-c", "echo ok"],
                      "working_directory": "/data/data/com.termux/files/home/.zero-assist/workspace",
                      "risk": "BLOCKED",
                      "reason": "Legacy bridge marked this as blocked.",
                      "fingerprint": "abc"
                    }
                    """.trimIndent(),
            )

        assertEquals(TermuxCommandRisk.HIGH, request?.risk)
        assertEquals(false, request?.blocked)
        assertEquals("sh -c echo ok", request?.commandPreview)
    }
}
