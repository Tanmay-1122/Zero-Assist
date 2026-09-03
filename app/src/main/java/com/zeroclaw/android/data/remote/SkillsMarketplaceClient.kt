/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.remote

import com.zeroclaw.android.model.MarketplaceSkill
import com.zeroclaw.android.model.MarketplaceSkillRegistry
import java.io.File
import java.net.URI
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Remote catalog client for the official ZeroClaw skills marketplace.
 */
interface SkillsMarketplaceClient {
    suspend fun fetchRegistry(): List<MarketplaceSkill>

    suspend fun downloadSkill(skillName: String, destinationDir: File)
}

/**
 * OkHttp-backed implementation for the official GitHub-hosted skills marketplace.
 */
class OkHttpSkillsMarketplaceClient(
    private val client: OkHttpClient = OkHttpClient(),
) : SkillsMarketplaceClient {
    private val json = Json { ignoreUnknownKeys = true }

    @Suppress("InjectDispatcher")
    override suspend fun fetchRegistry(): List<MarketplaceSkill> =
        withContext(Dispatchers.IO) {
            val request =
                Request
                    .Builder()
                    .url(REGISTRY_URL)
                    .header("User-Agent", USER_AGENT)
                    .get()
                    .build()
            val body = executeText(request, "skills registry")
            json.decodeFromString<MarketplaceSkillRegistry>(body).skills
        }

    @Suppress("InjectDispatcher")
    override suspend fun downloadSkill(
        skillName: String,
        destinationDir: File,
    ) = withContext(Dispatchers.IO) {
        require(SKILL_NAME_REGEX.matches(skillName)) {
            "Invalid marketplace skill name: $skillName"
        }
        SkillsMarketplaceSecurityPolicy.validateImmutableRepoRef(REPO_REF)
        destinationDir.deleteRecursively()
        destinationDir.mkdirs()

        downloadDirectory(
            apiUrl = "$SKILLS_DIRECTORY_API/$skillName?ref=$REPO_REF",
            destinationDir = destinationDir,
            depth = 0,
            budget = MarketplaceDownloadBudget(),
        )
        validateMarketplaceSkillPackage(destinationDir)
    }

    private fun downloadDirectory(
        apiUrl: String,
        destinationDir: File,
        depth: Int,
        budget: MarketplaceDownloadBudget,
    ) {
        SkillsMarketplaceSecurityPolicy.validateDirectoryDepth(depth)
        SkillsMarketplaceSecurityPolicy.validateMarketplaceApiUrl(apiUrl)
        val request =
            Request
                .Builder()
                .url(apiUrl)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
        val body = executeText(request, "skills directory")
        val entries = json.decodeFromString<List<GitHubContentEntry>>(body)
        check(entries.isNotEmpty()) { "Marketplace skill directory is empty" }
        check(entries.size <= SkillsMarketplaceSecurityPolicy.MAX_DIRECTORY_ENTRIES) {
            "Marketplace directory has too many entries: ${entries.size}"
        }

        entries.forEach { entry ->
            validateEntryName(entry.name)
            when (entry.type) {
                "dir" -> {
                    val childDir = destinationDir.resolve(entry.name)
                    childDir.mkdirs()
                    downloadDirectory(
                        apiUrl = entry.apiUrl,
                        destinationDir = childDir,
                        depth = depth + 1,
                        budget = budget,
                    )
                }

                "file" -> {
                    SkillsMarketplaceSecurityPolicy.validateFileName(entry.name)
                    entry.size?.let(SkillsMarketplaceSecurityPolicy::validateFileSize)
                    val downloadUrl =
                        entry.downloadUrl
                            ?: error("Marketplace file is missing a download URL: ${entry.path}")
                    downloadFile(
                        downloadUrl = downloadUrl,
                        destinationFile = destinationDir.resolve(entry.name),
                        budget = budget,
                    )
                }

                else -> error("Unsupported marketplace entry type: ${entry.type}")
            }
        }
    }

    private fun downloadFile(
        downloadUrl: String,
        destinationFile: File,
        budget: MarketplaceDownloadBudget,
    ) {
        SkillsMarketplaceSecurityPolicy.validateDownloadUrl(downloadUrl)
        val request =
            Request
                .Builder()
                .url(downloadUrl)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
        destinationFile.parentFile?.mkdirs()

        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) {
                "Marketplace file download failed: HTTP ${response.code}"
            }
            val body = response.body ?: error("Marketplace file download returned no body")
            body.contentLength().takeIf { it >= 0 }?.let(SkillsMarketplaceSecurityPolicy::validateFileSize)
            val bytes = body.bytes()
            SkillsMarketplaceSecurityPolicy.recordDownloadedFile(
                budget = budget,
                fileName = destinationFile.name,
                byteCount = bytes.size.toLong(),
            )
            destinationFile.writeBytes(bytes)
        }
    }

    private fun executeText(
        request: Request,
        label: String,
    ): String =
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) {
                "$label fetch failed: HTTP ${response.code}"
            }
            response.body?.string() ?: error("Empty response body from $label")
        }

    private fun validateEntryName(name: String) {
        SkillsMarketplaceSecurityPolicy.validatePathSegment(name)
    }

    @Serializable
    private data class GitHubContentEntry(
        val name: String,
        val path: String,
        val type: String,
        val size: Long? = null,
        @SerialName("download_url")
        val downloadUrl: String? = null,
        @SerialName("url")
        val apiUrl: String,
    )

    private companion object {
        const val USER_AGENT = "ZeroClaw-Android"
        const val REPO_REF = SkillsMarketplaceSecurityPolicy.OFFICIAL_REPO_REF
        val REGISTRY_URL =
            "https://raw.githubusercontent.com/zeroclaw-labs/zeroclaw-skills/$REPO_REF/registry.json"
        const val SKILLS_DIRECTORY_API =
            "https://api.github.com/repos/zeroclaw-labs/zeroclaw-skills/contents/skills"
        val SKILL_NAME_REGEX = Regex("^[a-z0-9][a-z0-9-]*$")
    }
}

internal data class MarketplaceDownloadBudget(
    var fileCount: Int = 0,
    var totalBytes: Long = 0,
)

internal object SkillsMarketplaceSecurityPolicy {
    const val OFFICIAL_REPO_REF = "97192eca72c4bf02e02460a0ea2d84df854ab0c0"
    const val MAX_DIRECTORY_DEPTH = 4
    const val MAX_DIRECTORY_ENTRIES = 64
    const val MAX_FILE_COUNT = 64
    const val MAX_FILE_BYTES = 512L * 1024L
    const val MAX_TOTAL_BYTES = 2L * 1024L * 1024L

    private const val OFFICIAL_OWNER = "zeroclaw-labs"
    private const val OFFICIAL_REPO = "zeroclaw-skills"
    private const val API_HOST = "api.github.com"
    private const val RAW_HOST = "raw.githubusercontent.com"
    private val IMMUTABLE_REF = Regex("^[0-9a-f]{40}$")
    private val ALLOWED_FILE_EXTENSIONS =
        setOf(
            "md",
            "toml",
            "json",
            "yaml",
            "yml",
            "txt",
            "py",
            "js",
            "ts",
            "jsx",
            "tsx",
            "sh",
            "ps1",
            "bat",
            "png",
            "jpg",
            "jpeg",
            "webp",
            "svg",
        )
    private val ALLOWED_EXTENSIONLESS_FILES = setOf("license", "notice")

    fun validateImmutableRepoRef(ref: String = OFFICIAL_REPO_REF) {
        check(IMMUTABLE_REF.matches(ref)) {
            "Marketplace repository ref must be an immutable commit SHA"
        }
    }

    fun validateMarketplaceApiUrl(apiUrl: String) {
        val uri = parseHttpsUri(apiUrl)
        check(uri.host == API_HOST) {
            "Marketplace API URL must use $API_HOST"
        }
        val expectedPrefix = "/repos/$OFFICIAL_OWNER/$OFFICIAL_REPO/contents/skills"
        val path = uri.rawPath.orEmpty()
        check(path == expectedPrefix || path.startsWith("$expectedPrefix/")) {
            "Marketplace API URL must stay inside the official skills directory"
        }
        check(!path.contains("..")) {
            "Marketplace API URL must not contain traversal segments"
        }
        check(queryParameter(uri, "ref") == OFFICIAL_REPO_REF) {
            "Marketplace API URL must be pinned to the official immutable ref"
        }
    }

    fun validateDownloadUrl(downloadUrl: String) {
        val uri = parseHttpsUri(downloadUrl)
        check(uri.host == RAW_HOST) {
            "Marketplace file URL must use $RAW_HOST"
        }
        val expectedPrefix = "/$OFFICIAL_OWNER/$OFFICIAL_REPO/$OFFICIAL_REPO_REF/skills/"
        check(uri.rawPath.orEmpty().startsWith(expectedPrefix)) {
            "Marketplace file URL must stay inside the official pinned skills tree"
        }
        check(!uri.rawPath.orEmpty().contains("..")) {
            "Marketplace file URL must not contain traversal segments"
        }
    }

    fun validatePathSegment(name: String) {
        require(name.isNotBlank() && !name.contains("..") && !name.contains('/') && !name.contains('\\')) {
            "Invalid marketplace path segment: $name"
        }
    }

    fun validateFileName(fileName: String) {
        validatePathSegment(fileName)
        val lowerName = fileName.lowercase()
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
        check(
            extension in ALLOWED_FILE_EXTENSIONS ||
                lowerName in ALLOWED_EXTENSIONLESS_FILES,
        ) {
            "Marketplace file type is not allowed: $fileName"
        }
    }

    fun validateDirectoryDepth(depth: Int) {
        check(depth <= MAX_DIRECTORY_DEPTH) {
            "Marketplace skill directory is too deep: $depth"
        }
    }

    fun validateFileSize(byteCount: Long) {
        check(byteCount <= MAX_FILE_BYTES) {
            "Marketplace file is too large: $byteCount bytes"
        }
    }

    fun recordDownloadedFile(
        budget: MarketplaceDownloadBudget,
        fileName: String,
        byteCount: Long,
    ) {
        validateFileName(fileName)
        validateFileSize(byteCount)
        budget.fileCount += 1
        check(budget.fileCount <= MAX_FILE_COUNT) {
            "Marketplace skill has too many files: ${budget.fileCount}"
        }
        budget.totalBytes += byteCount
        check(budget.totalBytes <= MAX_TOTAL_BYTES) {
            "Marketplace skill is too large: ${budget.totalBytes} bytes"
        }
    }

    fun validatePackageTree(skillDir: File) {
        val root = skillDir.canonicalFile.toPath()
        val budget = MarketplaceDownloadBudget()
        skillDir.walkTopDown().forEach { file ->
            if (file == skillDir) return@forEach

            val canonicalPath = file.canonicalFile.toPath()
            check(canonicalPath.startsWith(root)) {
                "Marketplace package path escapes the skill directory"
            }

            val relativePath = root.relativize(canonicalPath)
            relativePath.forEach { segment ->
                validatePathSegment(segment.toString())
            }

            if (file.isDirectory) {
                validateDirectoryDepth(relativePath.nameCount)
            } else {
                validateDirectoryDepth((relativePath.nameCount - 1).coerceAtLeast(0))
                recordDownloadedFile(
                    budget = budget,
                    fileName = file.name,
                    byteCount = file.length(),
                )
            }
        }
    }

    private fun parseHttpsUri(rawUrl: String): URI {
        val uri = URI(rawUrl)
        check(uri.scheme == "https") {
            "Marketplace URL must use HTTPS"
        }
        return uri
    }

    private fun queryParameter(uri: URI, key: String): String? =
        uri.rawQuery
            ?.split('&')
            ?.mapNotNull { part ->
                val separator = part.indexOf('=')
                if (separator <= 0) {
                    null
                } else {
                    part.substring(0, separator) to part.substring(separator + 1)
                }
            }
            ?.firstOrNull { (name, _) -> name == key }
            ?.second
}

/**
 * Validates the official marketplace skill package layout.
 *
 * Marketplace skills ship metadata in `manifest.toml` plus instructions in `SKILL.md`.
 * We intentionally do not synthesize `SKILL.toml`, because doing so would make the runtime
 * prefer the copied TOML metadata over the real markdown instructions.
 */
internal fun validateMarketplaceSkillPackage(skillDir: File) {
    SkillsMarketplaceSecurityPolicy.validatePackageTree(skillDir)

    val marketplaceManifest = skillDir.resolve("manifest.toml")
    check(marketplaceManifest.exists()) {
        "Marketplace skill is missing manifest.toml"
    }

    val skillMarkdown = skillDir.resolve("SKILL.md")
    check(skillMarkdown.exists()) {
        "Marketplace skill is missing SKILL.md"
    }
}
