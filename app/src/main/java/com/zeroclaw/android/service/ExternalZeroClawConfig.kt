/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileNotFoundException

/**
 * Installs and applies a repository-managed TOML overlay for ZeroClaw.
 *
 * The overlay is stored in the project-level `zeroclaw-config/assets/overlay.toml`,
 * bundled into APK assets at build time, and copied to:
 * `<filesDir>/zeroclaw-config/overlay.toml` on app startup.
 *
 * This keeps app-specific config additions outside the vendored upstream
 * `zeroclaw/` sources so upstream updates stay low-risk.
 */
object ExternalZeroClawConfig {
    private const val TAG = "ExternalZeroClawConfig"
    private const val CONFIG_DIR_NAME = "zeroclaw-config"
    private const val OVERLAY_FILE_NAME = "overlay.toml"
    private const val ASSET_OVERLAY_PATH = "overlay.toml"
    private const val WEB_ASSETS_DIR_NAME = "web"
    private const val WEB_ASSETS_ASSET_PATH = "web/dist"

    internal const val OVERLAY_BEGIN_MARKER = "# --- zero-assist external overlay (begin) ---"
    internal const val OVERLAY_END_MARKER = "# --- zero-assist external overlay (end) ---"

    /**
     * Copies the bundled overlay asset into app-internal storage when present.
     *
     * Missing asset is not an error; overlay remains optional.
     */
    fun installBundledOverlay(context: Context) {
        val assetText =
            try {
                context.assets.open(ASSET_OVERLAY_PATH).bufferedReader().use { it.readText() }
            } catch (_: FileNotFoundException) {
                return
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read bundled overlay asset: ${e.message}")
                return
            }

        val target = overlayFile(context.filesDir.absolutePath)
        val parent = target.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            Log.w(TAG, "Failed to create overlay directory: ${parent.absolutePath}")
            return
        }

        val existing = target.takeIf { it.exists() }?.readText().orEmpty()
        if (existing == assetText) return

        runCatching {
            target.writeText(assetText)
            Log.i(TAG, "Installed external ZeroClaw overlay to ${target.absolutePath}")
        }.onFailure { error ->
            Log.w(TAG, "Failed to install external overlay: ${error.message}")
        }
    }

    /**
     * Reads overlay TOML from app storage and appends it to [baseToml].
     */
    fun applyOverlay(
        baseToml: String,
        dataDir: String,
    ): String {
        val overlayToml =
            runCatching {
                val file = overlayFile(dataDir)
                if (!file.exists()) "" else file.readText()
            }.getOrElse { error ->
                Log.w(TAG, "Failed to read external overlay: ${error.message}")
                ""
            }
        return compose(baseToml, overlayToml)
    }

    internal fun compose(
        baseToml: String,
        overlayToml: String,
    ): String {
        if (baseToml.contains(OVERLAY_BEGIN_MARKER)) return baseToml

        val overlay = overlayToml.trim()
        if (overlay.isEmpty()) return baseToml

        val normalizedBase = baseToml.trimEnd('\r', '\n')
        return buildString {
            append(normalizedBase)
            appendLine()
            appendLine()
            appendLine(OVERLAY_BEGIN_MARKER)
            appendLine(overlay)
            appendLine(OVERLAY_END_MARKER)
        }
    }

    private fun overlayFile(dataDir: String): File = File(File(dataDir, CONFIG_DIR_NAME), OVERLAY_FILE_NAME)

    /**
     * Returns the on-disk path where extracted web assets live.
     */
    fun webDistDir(context: Context): String =
        File(context.filesDir, WEB_ASSETS_DIR_NAME).absolutePath

    /**
     * Copies the bundled web dashboard assets from APK assets into internal
     * storage so the Rust gateway can serve them at runtime.
     *
     * Only copies if the target directory doesn't already contain the files
     * (first-run or update).
     */
    fun extractWebAssets(context: Context) {
        val targetDir = File(context.filesDir, WEB_ASSETS_DIR_NAME)
        val indexFile = File(targetDir, "index.html")
        if (indexFile.isFile) return

        try {
            if (indexFile.isDirectory) indexFile.deleteRecursively()
            val assetManager = context.assets
            copyAssetDir(assetManager, WEB_ASSETS_ASSET_PATH, targetDir)
            if (indexFile.isFile) {
                Log.i(TAG, "Extracted web assets to ${targetDir.absolutePath}")
            } else {
                Log.w(TAG, "Web assets extraction completed but index.html missing — assets may not be bundled")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract web assets: ${e.message}")
        }
    }

    private fun copyAssetDir(
        assetManager: android.content.res.AssetManager,
        assetPath: String,
        targetDir: File,
    ) {
        if (!targetDir.exists()) targetDir.mkdirs()
        val entries = assetManager.list(assetPath) ?: return
        if (entries.isEmpty()) {
            // Leaf file — assetPath is full path like "web/dist/index.html"
            val fileName = assetPath.substringAfterLast('/')
            assetManager.open(assetPath).use { input ->
                File(targetDir, fileName).outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        } else {
            for (entry in entries) {
                val childPath = "$assetPath/$entry"
                val childTarget = File(targetDir, entry)
                val subEntries = assetManager.list(childPath)
                if (subEntries.isNullOrEmpty()) {
                    if (!targetDir.exists()) targetDir.mkdirs()
                    assetManager.open(childPath).use { input ->
                        childTarget.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                } else {
                    copyAssetDir(assetManager, childPath, childTarget)
                }
            }
        }
    }
}
