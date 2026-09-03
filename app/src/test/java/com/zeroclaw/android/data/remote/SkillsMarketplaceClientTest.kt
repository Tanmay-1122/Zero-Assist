/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.remote

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("Skills marketplace package validation")
class SkillsMarketplaceClientTest {
    @Test
    @DisplayName("marketplace package keeps manifest and markdown instructions intact")
    fun `marketplace package is validated without synthesizing skill toml`() =
        withTempSkillDir { skillDir ->
            val manifest = skillDir.resolve("manifest.toml")
            manifest.writeText("[skill]\nname = \"example\"\n")
            val skillMarkdown = skillDir.resolve("SKILL.md")
            skillMarkdown.writeText("# Example\n\nUse this skill for testing.\n")

            validateMarketplaceSkillPackage(skillDir)

            assertTrue(manifest.exists())
            assertTrue(skillMarkdown.exists())
            assertTrue(!skillDir.resolve("SKILL.toml").exists())
        }

    @Test
    @DisplayName("missing SKILL.md throws a clear error")
    fun `missing skill markdown throws`() =
        withTempSkillDir { skillDir ->
            skillDir.resolve("manifest.toml").writeText("[skill]\nname = \"marketplace\"\n")

            val error =
                assertThrows<IllegalStateException> {
                    validateMarketplaceSkillPackage(skillDir)
                }

            assertEquals("Marketplace skill is missing SKILL.md", error.message)
        }

    @Test
    @DisplayName("missing manifest throws a clear error")
    fun `missing manifest throws`() =
        withTempSkillDir { skillDir ->
            val error =
                assertThrows<IllegalStateException> {
                    validateMarketplaceSkillPackage(skillDir)
                }

            assertEquals("Marketplace skill is missing manifest.toml", error.message)
        }

    @Test
    @DisplayName("official marketplace ref is immutable")
    fun `official marketplace ref is immutable`() {
        assertDoesNotThrow {
            SkillsMarketplaceSecurityPolicy.validateImmutableRepoRef()
        }

        val error =
            assertThrows<IllegalStateException> {
                SkillsMarketplaceSecurityPolicy.validateImmutableRepoRef("master")
            }

        assertEquals("Marketplace repository ref must be an immutable commit SHA", error.message)
    }

    @Test
    @DisplayName("API URLs must stay on official GitHub repo and pinned ref")
    fun `marketplace api urls require official repo and immutable ref`() {
        assertDoesNotThrow {
            SkillsMarketplaceSecurityPolicy.validateMarketplaceApiUrl(
                "https://api.github.com/repos/zeroclaw-labs/zeroclaw-skills/contents/skills/example" +
                    "?ref=${SkillsMarketplaceSecurityPolicy.OFFICIAL_REPO_REF}",
            )
        }

        val movingRefError =
            assertThrows<IllegalStateException> {
                SkillsMarketplaceSecurityPolicy.validateMarketplaceApiUrl(
                    "https://api.github.com/repos/zeroclaw-labs/zeroclaw-skills/contents/skills/example" +
                        "?ref=master",
                )
            }

        assertEquals(
            "Marketplace API URL must be pinned to the official immutable ref",
            movingRefError.message,
        )

        val wrongRepoError =
            assertThrows<IllegalStateException> {
                SkillsMarketplaceSecurityPolicy.validateMarketplaceApiUrl(
                    "https://api.github.com/repos/other/zeroclaw-skills/contents/skills/example" +
                        "?ref=${SkillsMarketplaceSecurityPolicy.OFFICIAL_REPO_REF}",
                )
            }

        assertEquals(
            "Marketplace API URL must stay inside the official skills directory",
            wrongRepoError.message,
        )
    }

    @Test
    @DisplayName("raw file URLs must stay on official pinned skills tree")
    fun `marketplace download urls require official raw host and immutable ref`() {
        assertDoesNotThrow {
            SkillsMarketplaceSecurityPolicy.validateDownloadUrl(
                "https://raw.githubusercontent.com/zeroclaw-labs/zeroclaw-skills/" +
                    "${SkillsMarketplaceSecurityPolicy.OFFICIAL_REPO_REF}/skills/example/SKILL.md",
            )
        }

        val wrongRefError =
            assertThrows<IllegalStateException> {
                SkillsMarketplaceSecurityPolicy.validateDownloadUrl(
                    "https://raw.githubusercontent.com/zeroclaw-labs/zeroclaw-skills/master/skills/example/SKILL.md",
                )
            }

        assertEquals(
            "Marketplace file URL must stay inside the official pinned skills tree",
            wrongRefError.message,
        )

        val wrongHostError =
            assertThrows<IllegalStateException> {
                SkillsMarketplaceSecurityPolicy.validateDownloadUrl(
                    "https://example.com/zeroclaw-labs/zeroclaw-skills/" +
                        "${SkillsMarketplaceSecurityPolicy.OFFICIAL_REPO_REF}/skills/example/SKILL.md",
                )
            }

        assertEquals("Marketplace file URL must use raw.githubusercontent.com", wrongHostError.message)
    }

    @Test
    @DisplayName("unsupported package file types are rejected")
    fun `marketplace package rejects unsupported file extensions`() =
        withTempSkillDir { skillDir ->
            skillDir.resolve("manifest.toml").writeText("[skill]\nname = \"marketplace\"\n")
            skillDir.resolve("SKILL.md").writeText("# Marketplace\n")
            skillDir.resolve("payload.exe").writeText("not allowed")

            val error =
                assertThrows<IllegalStateException> {
                    validateMarketplaceSkillPackage(skillDir)
                }

            assertEquals("Marketplace file type is not allowed: payload.exe", error.message)
        }

    @Test
    @DisplayName("oversized package files are rejected")
    fun `marketplace package rejects oversized files`() =
        withTempSkillDir { skillDir ->
            skillDir.resolve("manifest.toml").writeText("[skill]\nname = \"marketplace\"\n")
            skillDir.resolve("SKILL.md").writeText("# Marketplace\n")
            skillDir.resolve("large.md").writeBytes(
                ByteArray(SkillsMarketplaceSecurityPolicy.MAX_FILE_BYTES.toInt() + 1),
            )

            val error =
                assertThrows<IllegalStateException> {
                    validateMarketplaceSkillPackage(skillDir)
                }

            assertTrue(error.message?.startsWith("Marketplace file is too large:") == true)
        }

    private fun withTempSkillDir(block: (java.io.File) -> Unit) {
        val tempDir = Files.createTempDirectory("skills-marketplace-test").toFile()
        try {
            block(tempDir)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
