/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

@file:Suppress("MagicNumber", "ReturnCount")

package com.zeroclaw.android.data.saf

import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Base64
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * File operations for the app-owned default Workflow Folder.
 *
 * Custom workflow folders still use [SharedFolderSafHelper]. This helper covers the
 * startup-default folder that Android can access without prompting the user.
 */
class WorkflowFolderFileHelper {
    private val json = Json { prettyPrint = false }

    fun list(
        root: File,
        path: String,
    ): String {
        return try {
            val rootDir = ensureRoot(root)
            val target = resolvePath(rootDir, path) ?: return errorJson("Path escapes workflow folder: $path")
            if (!target.exists()) return errorJson("Path not found: $path")
            if (!target.isDirectory) return errorJson("Path is not a directory: $path")

            val entries =
                target
                    .listFiles()
                    .orEmpty()
                    .sortedBy { it.name.lowercase() }
                    .map { file ->
                        FolderEntry(
                            name = file.name,
                            type = if (file.isDirectory) "directory" else "file",
                            sizeBytes = if (file.isDirectory) 0L else file.length(),
                            lastModified = formatTimestamp(file.lastModified()),
                        )
                    }
            json.encodeToString(entries)
        } catch (e: IOException) {
            errorJson("Failed to list workflow folder: ${e.message}")
        }
    }

    fun read(
        root: File,
        path: String,
    ): String {
        return try {
            val rootDir = ensureRoot(root)
            val target = resolvePath(rootDir, path) ?: return errorJson("Path escapes workflow folder: $path")
            if (!target.exists() || !target.isFile) return errorJson("File not found: $path")

            val isText = isTextPath(target.name)
            val maxSize = if (isText) MAX_TEXT_READ_BYTES else MAX_BINARY_READ_BYTES
            if (target.length() > maxSize) {
                return errorJson("File too large (${target.length() / BYTES_PER_MB}MB). Limit: ${maxSize / BYTES_PER_MB}MB.")
            }

            val bytes = target.readBytes()
            if (bytes.size > maxSize) {
                return errorJson("File too large (${bytes.size / BYTES_PER_MB}MB). Limit: ${maxSize / BYTES_PER_MB}MB.")
            }
            if (isText) {
                json.encodeToString(TextReadResult("text", bytes.toString(Charsets.UTF_8)))
            } else {
                json.encodeToString(
                    BinaryReadResult(
                        type = "binary",
                        mimeType = guessMimeType(target.name),
                        contentBase64 = Base64.getEncoder().encodeToString(bytes),
                    ),
                )
            }
        } catch (e: IOException) {
            errorJson("Failed to read workflow file: ${e.message}")
        }
    }

    fun write(
        root: File,
        path: String,
        content: String?,
        isBase64: Boolean,
        mkdir: Boolean,
    ): String {
        return try {
            val rootDir = ensureRoot(root)
            val target = resolvePath(rootDir, path) ?: return errorJson("Path escapes workflow folder: $path")

            if (mkdir) {
                if (!target.exists() && !target.mkdirs()) {
                    return errorJson("Failed to create directory: $path")
                }
                if (!target.isDirectory) return errorJson("Path exists and is not a directory: $path")
                return json.encodeToString(WriteResult(path, 0L))
            }

            val bytes =
                if (isBase64 && content != null) {
                    Base64.getDecoder().decode(content)
                } else {
                    content?.toByteArray(Charsets.UTF_8) ?: ByteArray(0)
                }

            if (bytes.size > MAX_WRITE_BYTES) {
                return errorJson("Write too large (${bytes.size / BYTES_PER_MB}MB). Limit: ${MAX_WRITE_BYTES / BYTES_PER_MB}MB.")
            }
            if (isPdfPath(path) && !hasPdfHeader(bytes)) {
                return errorJson(
                    "Refusing to write invalid PDF: .pdf files must contain binary PDF data " +
                        "starting with %PDF-. Use a real PDF generator and write base64 PDF bytes.",
                )
            }

            target.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) {
                    return errorJson("Failed to create parent directory: ${parent.name}")
                }
            }
            if (target.exists() && target.isDirectory) {
                return errorJson("Path exists and is a directory: $path")
            }
            target.writeBytes(bytes)
            json.encodeToString(WriteResult(path, bytes.size.toLong()))
        } catch (e: IllegalArgumentException) {
            errorJson("Failed to decode base64 content: ${e.message}")
        } catch (e: IOException) {
            errorJson("Failed to write workflow file: ${e.message}")
        }
    }

    private fun ensureRoot(root: File): File {
        if (!root.exists() && !root.mkdirs()) {
            throw IOException("cannot create ${root.absolutePath}")
        }
        if (!root.isDirectory) {
            throw IOException("${root.absolutePath} is not a directory")
        }
        return root.canonicalFile
    }

    private fun resolvePath(
        root: File,
        path: String,
    ): File? {
        val trimmed = path.replace('\\', '/').trim('/')
        if (trimmed.isBlank()) return root
        val parts = trimmed.split('/').filter { it.isNotBlank() }
        if (parts.any { it == "." || it == ".." }) return null
        val target = File(root, parts.joinToString(File.separator)).canonicalFile
        return if (target.toPath().startsWith(root.toPath())) target else null
    }

    private fun formatTimestamp(millis: Long): String {
        if (millis <= 0L) return ""
        return DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(millis))
    }

    private fun isTextPath(fileName: String): Boolean =
        fileName.substringAfterLast('.', "").lowercase() in TEXT_EXTENSIONS

    private fun guessMimeType(fileName: String): String =
        MIME_MAP[fileName.substringAfterLast('.', "").lowercase()] ?: "application/octet-stream"

    private fun errorJson(message: String): String = json.encodeToString(ErrorResult(message))

    @Serializable
    private data class FolderEntry(
        val name: String,
        val type: String,
        @SerialName("size_bytes")
        val sizeBytes: Long,
        @SerialName("last_modified")
        val lastModified: String,
    )

    @Serializable
    private data class TextReadResult(
        val type: String,
        val content: String,
    )

    @Serializable
    private data class BinaryReadResult(
        val type: String,
        @SerialName("mime_type")
        val mimeType: String,
        @SerialName("content_base64")
        val contentBase64: String,
    )

    @Serializable
    private data class WriteResult(
        val path: String,
        @SerialName("bytes_written")
        val bytesWritten: Long,
    )

    @Serializable
    private data class ErrorResult(
        val error: String,
    )

    private companion object {
        private const val BYTES_PER_MB = 1024 * 1024
        private const val MAX_TEXT_READ_BYTES = 10L * 1024 * 1024
        private const val MAX_BINARY_READ_BYTES = 2L * 1024 * 1024
        private const val MAX_WRITE_BYTES = 50L * 1024 * 1024
        private val PDF_MAGIC_BYTES = byteArrayOf(0x25, 0x50, 0x44, 0x46, 0x2D)

        private val TEXT_EXTENSIONS =
            setOf(
                "txt",
                "md",
                "json",
                "xml",
                "html",
                "htm",
                "csv",
                "toml",
                "yaml",
                "yml",
                "kt",
                "java",
                "rs",
                "js",
                "ts",
                "css",
            )

        private val MIME_MAP =
            mapOf(
                "txt" to "text/plain",
                "md" to "text/markdown",
                "json" to "application/json",
                "xml" to "application/xml",
                "html" to "text/html",
                "csv" to "text/csv",
                "png" to "image/png",
                "jpg" to "image/jpeg",
                "jpeg" to "image/jpeg",
                "gif" to "image/gif",
                "webp" to "image/webp",
                "zip" to "application/zip",
                "pdf" to "application/pdf",
            )

        private fun isPdfPath(path: String): Boolean =
            path.trim().substringAfterLast('/').endsWith(".pdf", ignoreCase = true)

        private fun hasPdfHeader(bytes: ByteArray): Boolean =
            bytes.size >= PDF_MAGIC_BYTES.size &&
                PDF_MAGIC_BYTES.indices.all { index -> bytes[index] == PDF_MAGIC_BYTES[index] }
    }
}
