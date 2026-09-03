/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.sandbox

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.io.File

@DisplayName("SandboxFiles")
class SandboxFilesTest {

    private val homeRoot = File("/fake/home").absolutePath

    @Nested
    @DisplayName("resolveSandboxFile")
    inner class ResolveSandboxFile {

        @Test
        @DisplayName("resolves simple relative path")
        fun `resolves simple relative path`() {
            val result = resolveSandboxFile(homeRoot, "docs/file.txt")
            assertNotNull(result)
            assertTrue(result!!.absolutePath.endsWith("docs${File.separator}file.txt"))
        }

        @Test
        @DisplayName("rejects blank path")
        fun `rejects blank path`() {
            assertNull(resolveSandboxFile(homeRoot, ""))
            assertNull(resolveSandboxFile(homeRoot, "   "))
        }

        @Test
        @DisplayName("rejects absolute path")
        fun `rejects absolute path`() {
            assertNull(resolveSandboxFile(homeRoot, "/etc/passwd"))
        }

        @Test
        @DisplayName("rejects path traversal with ..")
        fun `rejects path traversal with dotdot`() {
            assertNull(resolveSandboxFile(homeRoot, "../../etc/passwd"))
            assertNull(resolveSandboxFile(homeRoot, "docs/../../../etc/passwd"))
        }

        @Test
        @DisplayName("rejects .. as single component")
        fun `rejects dotdot as single component`() {
            assertNull(resolveSandboxFile(homeRoot, ".."))
        }

        @Test
        @DisplayName("resolves single component")
        fun `resolves single component`() {
            val result = resolveSandboxFile(homeRoot, "myfile.txt")
            assertNotNull(result)
            assertTrue(result!!.absolutePath.endsWith("myfile.txt"))
        }
    }

    @Nested
    @DisplayName("resolveSandboxAbsolute")
    inner class ResolveSandboxAbsolute {

        private val rootfsPath = File("/fake/rootfs").absolutePath

        @Test
        @DisplayName("resolves /root path to home")
        fun `resolves root path to home`() {
            val result = resolveSandboxAbsolute(rootfsPath, homeRoot, "/root/docs/file.txt")
            assertNotNull(result)
            assertTrue(result!!.absolutePath.contains("home"))
        }

        @Test
        @DisplayName("resolves /etc path to rootfs")
        fun `resolves etc path to rootfs`() {
            val result = resolveSandboxAbsolute(rootfsPath, homeRoot, "/etc/passwd")
            assertNotNull(result)
            assertTrue(result!!.absolutePath.contains("rootfs"))
        }

        @Test
        @DisplayName("rejects traversal in absolute path")
        fun `rejects traversal in absolute path`() {
            assertNull(resolveSandboxAbsolute(rootfsPath, homeRoot, "/root/../../etc/passwd"))
        }

        @Test
        @DisplayName("rejects non-starting-slash path")
        fun `rejects non-starting-slash path`() {
            assertNull(resolveSandboxAbsolute(rootfsPath, homeRoot, "etc/passwd"))
        }
    }

    @Nested
    @DisplayName("guessMimeType")
    inner class GuessMimeType {

        @Test
        @DisplayName("detects common types")
        fun `detects common types`() {
            assertEquals("text/plain", guessMimeType("file.txt"))
            assertEquals("application/json", guessMimeType("config.json"))
            assertEquals("image/png", guessMimeType("photo.png"))
        }

        @Test
        @DisplayName("returns wildcard for unknown extension")
        fun `returns wildcard for unknown extension`() {
            assertEquals("*/*", guessMimeType("file.xyz123"))
        }

        @Test
        @DisplayName("handles case insensitivity")
        fun `handles case insensitivity`() {
            assertEquals("image/png", guessMimeType("photo.PNG"))
        }
    }
}
