/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.network

import java.io.IOException
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("CleartextHttpPolicy")
class CleartextHttpPolicyTest {
    @Test
    fun allowsHttpsCloudUrls() {
        assertTrue(CleartextHttpPolicy.isUrlAllowed("https://api.openai.com/v1/models"))
        assertTrue(CleartextHttpPolicy.isUrlAllowed("https://example.com/callback"))
    }

    @Test
    fun allowsHttpOnlyForLocalHosts() {
        val allowed =
            listOf(
                "http://localhost:11434/api/tags",
                "http://127.0.0.1:8787/health",
                "http://10.0.2.2:8765/health",
                "http://10.1.2.3:11434/api/tags",
                "http://172.16.0.10:1234/v1/models",
                "http://172.31.255.250:8000/v1/models",
                "http://192.168.1.50:11434/api/tags",
                "http://169.254.10.20:8080/v1/models",
                "http://ollama.local:11434/api/tags",
                "http://agent.lan:8080/v1/models",
                "http://home-server.home:1234/v1/models",
                "http://[::1]:8787/health",
                "http://[fd00::1]:11434/api/tags",
                "http://[fe80::1]:11434/api/tags",
            )

        allowed.forEach { url ->
            assertTrue(CleartextHttpPolicy.isUrlAllowed(url), url)
        }
    }

    @Test
    fun rejectsHttpPublicHosts() {
        val blocked =
            listOf(
                "http://api.openai.com/v1/models",
                "http://example.com",
                "http://8.8.8.8:8080",
                "http://172.15.0.10:1234",
                "http://172.32.0.10:1234",
                "http://203.0.113.10:11434",
            )

        blocked.forEach { url ->
            assertFalse(CleartextHttpPolicy.isUrlAllowed(url), url)
        }
    }

    @Test
    fun rejectsUnsupportedAndCredentialUrls() {
        assertFalse(CleartextHttpPolicy.isUrlAllowed("ftp://localhost/file"))
        assertFalse(CleartextHttpPolicy.isUrlAllowed("not a url"))
        assertFalse(CleartextHttpPolicy.isUrlAllowed("http://user:pass@localhost:11434"))
        assertFalse(CleartextHttpPolicy.isUrlAllowed("https://user:pass@example.com"))
    }

    @Test
    fun requireAllowedThrowsNetworkErrorForBlockedUrls() {
        assertThrows<IOException> {
            CleartextHttpPolicy.requireAllowed(
                rawUrl = "http://api.openai.com/v1/models",
                caller = "Test caller",
            )
        }
    }
}
