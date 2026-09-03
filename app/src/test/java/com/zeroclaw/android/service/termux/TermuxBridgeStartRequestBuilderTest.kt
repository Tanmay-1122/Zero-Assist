/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.termux

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("TermuxBridgeStartRequestBuilder")
class TermuxBridgeStartRequestBuilderTest {
    private val builder = TermuxBridgeStartRequestBuilder()

    @Test
    fun `builds single run command request that installs and starts bundled bridge`() {
        val request =
            builder.build(
                TermuxBridgeStartConfig(
                    token = "token-'quoted'",
                    scriptContent = "print('bridge')\n",
                    port = 8787,
                ),
            )

        assertEquals(TermuxBridgeStartRequestBuilder.DEFAULT_SHELL_PATH, request.commandPath)
        assertEquals("-lc", request.arguments[0])
        assertEquals(TermuxBridgeBootstrapRequestBuilder.DEFAULT_WORKING_DIRECTORY, request.workingDirectory)
        assertEquals(true, request.background)
        assertEquals("Zero-Assist Termux bridge", request.commandLabel)

        val shellScript = request.arguments[1]
        assertTrue(shellScript.contains("ZERO_ASSIST_TERMUX_BRIDGE_B64"))
        assertTrue(shellScript.contains("allow-external-apps = true"))
        assertTrue(shellScript.contains("termux-reload-settings"))
        assertTrue(shellScript.contains("pkg install -y python"))
        assertTrue(shellScript.contains("could not find python3 after automatic recovery"))
        assertTrue(shellScript.contains("base64.b64decode"))
        assertTrue(shellScript.contains(TermuxBridgeBootstrapRequestBuilder.DEFAULT_BRIDGE_SCRIPT_PATH))
        assertTrue(shellScript.contains("/proc/[0-9]*/cmdline"))
        assertTrue(shellScript.contains("kill \"${'$'}zero_assist_pid\""))
        assertTrue(shellScript.contains("--host '127.0.0.1' --port 8787"))
        assertTrue(shellScript.contains("--token 'token-'\"'\"'quoted'\"'\"''"))
    }

    @Test
    fun `rejects blank script content before building start request`() {
        assertThrows(IllegalArgumentException::class.java) {
            builder.build(
                TermuxBridgeStartConfig(
                    token = "token-123",
                    scriptContent = " ",
                ),
            )
        }
    }

    @Test
    fun `rejects blank token before building start request`() {
        assertThrows(IllegalArgumentException::class.java) {
            builder.build(
                TermuxBridgeStartConfig(
                    token = " ",
                    scriptContent = "print('bridge')",
                ),
            )
        }
    }
}
