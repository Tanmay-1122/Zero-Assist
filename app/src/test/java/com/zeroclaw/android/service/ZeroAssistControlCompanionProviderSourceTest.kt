/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import java.io.File
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("Zero-Assist control companion provider source")
class ZeroAssistControlCompanionProviderSourceTest {
    @Test
    fun `exported companion provider does not execute text input writes`() {
        val source = providerSource()

        assertTrue(source.contains("\"keyboard/input\" -> throw SecurityException"))
        assertFalse(source.contains("executeAsync("))
        assertFalse(source.contains("base64_text"))
    }

    private fun providerSource(): String {
        val candidates =
            listOf(
                File("src/main/java/com/zeroclaw/android/service/ZeroAssistControlCompanionProvider.kt"),
                File("app/src/main/java/com/zeroclaw/android/service/ZeroAssistControlCompanionProvider.kt"),
            )
        return candidates.first { it.exists() }.readText()
    }
}
