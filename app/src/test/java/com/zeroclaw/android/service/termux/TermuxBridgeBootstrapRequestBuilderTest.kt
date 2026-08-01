/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.termux

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("TermuxBridgeBootstrapRequestBuilder")
class TermuxBridgeBootstrapRequestBuilderTest {
    private val builder = TermuxBridgeBootstrapRequestBuilder()

    @Test
    fun `builds future bridge script command request without launching it`() {
        val request =
            builder.build(
                TermuxBridgeBootstrapConfig(
                    port = 8787,
                    token = "token-123",
                ),
            )

        assertEquals(TermuxBridgeBootstrapRequestBuilder.DEFAULT_PYTHON_PATH, request.commandPath)
        assertEquals(
            listOf(
                TermuxBridgeBootstrapRequestBuilder.DEFAULT_BRIDGE_SCRIPT_PATH,
                "--host",
                "127.0.0.1",
                "--port",
                "8787",
                "--token",
                "token-123",
            ),
            request.arguments,
        )
        assertEquals(TermuxBridgeBootstrapRequestBuilder.DEFAULT_WORKING_DIRECTORY, request.workingDirectory)
        assertEquals(true, request.background)
        assertEquals("Zero-Assist Termux bridge", request.commandLabel)
        assertEquals(null, request.sessionAction)
    }

    @Test
    fun `rejects invalid port before building request`() {
        assertThrows(IllegalArgumentException::class.java) {
            builder.build(
                TermuxBridgeBootstrapConfig(
                    port = 0,
                    token = "token-123",
                ),
            )
        }
    }

    @Test
    fun `rejects blank token before building request`() {
        assertThrows(IllegalArgumentException::class.java) {
            builder.build(
                TermuxBridgeBootstrapConfig(
                    port = 8787,
                    token = " ",
                ),
            )
        }
    }
}
