/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import java.nio.file.Files
import kotlin.io.path.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ExternalZeroClawConfig")
class ExternalZeroClawConfigTest {
    @Test
    @DisplayName("compose appends overlay with guard markers")
    fun compose_appendsOverlayWithMarkers() {
        val composed =
            ExternalZeroClawConfig.compose(
                baseToml = "default_provider = \"openai\"",
                overlayToml = "[web_search]\nenabled = true",
            )

        assertTrue(composed.contains("default_provider = \"openai\""))
        assertTrue(composed.contains(ExternalZeroClawConfig.OVERLAY_BEGIN_MARKER))
        assertTrue(composed.contains("[web_search]\nenabled = true"))
        assertTrue(composed.contains(ExternalZeroClawConfig.OVERLAY_END_MARKER))
    }

    @Test
    @DisplayName("compose ignores blank overlay")
    fun compose_ignoresBlankOverlay() {
        val base = "default_provider = \"openai\""
        val composed = ExternalZeroClawConfig.compose(base, "  \n ")

        assertEquals(base, composed)
    }

    @Test
    @DisplayName("compose is idempotent for already-composed config")
    fun compose_isIdempotent() {
        val alreadyComposed =
            """
            default_provider = "openai"
            ${ExternalZeroClawConfig.OVERLAY_BEGIN_MARKER}
            [skills]
            open_skills_enabled = true
            ${ExternalZeroClawConfig.OVERLAY_END_MARKER}
            """.trimIndent()

        val recomposed = ExternalZeroClawConfig.compose(alreadyComposed, "[skills]\nopen_skills_enabled = false")

        assertEquals(alreadyComposed, recomposed)
    }

    @Test
    @DisplayName("applyOverlay reads runtime overlay file")
    fun applyOverlay_readsRuntimeOverlayFile() {
        val tempDir = Files.createTempDirectory("zc-overlay")
        val overlayDir = Path(tempDir.toString(), "zeroclaw-config").createDirectories()
        val overlayFile = overlayDir.resolve("overlay.toml")
        overlayFile.writeText("[skills]\nopen_skills_enabled = true")

        val composed =
            ExternalZeroClawConfig.applyOverlay(
                baseToml = "default_provider = \"openai\"",
                dataDir = tempDir.toString(),
            )

        assertTrue(composed.contains("[skills]\nopen_skills_enabled = true"))
    }
}
