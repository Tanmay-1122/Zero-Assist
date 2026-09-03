/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.util

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LogSanitizerTest {
    @Test
    fun sanitizeLogMessageRedactsComposioKeys() {
        val raw =
            """
            [composio]
            api_key = "ck_Gr3eqkgGPKNbw8LOD1Jz"
            fallback_api_key = ak_projectSecret123
            cli_user_key = uak_userSecret123
            x-consumer-api-key: ck_headerSecret123
            """.trimIndent()

        val sanitized = LogSanitizer.sanitizeLogMessage(raw)

        assertFalse(sanitized.contains("ck_Gr3eqkgGPKNbw8LOD1Jz"))
        assertFalse(sanitized.contains("ak_projectSecret123"))
        assertFalse(sanitized.contains("uak_userSecret123"))
        assertFalse(sanitized.contains("ck_headerSecret123"))
        assertTrue(sanitized.contains("[REDACTED]"))
    }

    @Test
    fun sanitizeLogMessageRedactsProviderHeadersTokensAndCredentialUrls() {
        val githubToken = "ghp_" + "a".repeat(40)
        val googleKey = "AIza" + "b".repeat(35)
        val credentialUrl = "https://example.com/callback?token=" + "c".repeat(70)
        val raw =
            """
            Authorization: Bearer ${"d".repeat(32)}
            x-api-key: sk-${"e".repeat(20)}
            provider_api_key = "$googleKey"
            github_pat = $githubToken
            redirect = "$credentialUrl"
            """.trimIndent()

        val sanitized = LogSanitizer.sanitizeLogMessage(raw)

        assertFalse(sanitized.contains(githubToken))
        assertFalse(sanitized.contains(googleKey))
        assertFalse(sanitized.contains(credentialUrl))
        assertFalse(sanitized.contains("sk-${"e".repeat(20)}"))
        assertFalse(sanitized.contains("Bearer ${"d".repeat(32)}"))
        assertTrue(sanitized.contains("[REDACTED]"))
        assertTrue(sanitized.contains("[REDACTED_URL]"))
    }
}
