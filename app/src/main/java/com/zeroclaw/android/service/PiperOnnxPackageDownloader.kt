/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.model.CustomVoiceModelFileManifest
import com.zeroclaw.android.model.CustomVoicePackageManifest
import com.zeroclaw.android.model.CustomVoiceRuntimeManifest
import com.zeroclaw.android.model.CustomVoiceRuntimeType
import com.zeroclaw.android.model.VoiceModel
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Downloads a raw Piper ONNX voice pair from HuggingFace (or any HTTPS host)
 * and packages them on-device into a valid `.voicepkg` ZIP file that
 * [LocalVoiceStorage.importPackage] can process.
 *
 * **URL convention for [VoiceModel.downloadUri]:**
 * Point it at the `.onnx` model URL. The `.onnx.json` config is automatically
 * fetched from `<onnxUrl>.json` (standard HuggingFace layout).
 *
 * The produced `.voicepkg` ZIP contains:
 * ```
 * voice-package.json       <- CustomVoicePackageManifest
 * models/model.onnx        <- Piper ONNX model
 * models/model.onnx.json   <- Piper ONNX config
 * ```
 */
class PiperOnnxPackageDownloader(
    private val cacheRoot: File,
    private val client: OkHttpClient = OkHttpClient(),
) : VoicePackageDownloader {

    override suspend fun downloadPackage(
        voice: VoiceModel,
        onProgress: (VoiceDownloadProgress) -> Unit,
    ): VoicePackageDownloadResult =
        withContext(Dispatchers.IO) {
            val onnxUrl =
                voice.downloadUri?.trim()?.takeIf { it.isNotBlank() }
                    ?: return@withContext VoicePackageDownloadResult.Failure(
                        "Voice download URL is not configured.",
                    )
            if (!onnxUrl.startsWith("https://", ignoreCase = true)) {
                return@withContext VoicePackageDownloadResult.Failure(
                    "Voice packages must be downloaded over HTTPS.",
                )
            }

            val configUrl = "$onnxUrl.json"

            if (!cacheRoot.exists() && !cacheRoot.mkdirs()) {
                return@withContext VoicePackageDownloadResult.Failure(
                    "Could not prepare voice download cache.",
                )
            }

            val ts = System.nanoTime()
            val onnxTemp = File(cacheRoot, "${voice.id}-$ts.onnx.tmp")
            val configTemp = File(cacheRoot, "${voice.id}-$ts.json.tmp")
            val destPackage = File(cacheRoot, "${voice.id}-$ts.voicepkg")

            try {
                // 1. Fetch ONNX model with progress
                val onnxResult =
                    fetchFile(
                        url = onnxUrl,
                        destination = onnxTemp,
                        reportedTotalBytes = voice.sizeBytes.takeIf { it > 0L },
                        onProgress = onProgress,
                        progressOffsetBytes = 0L,
                    )
                if (onnxResult is FetchResult.Failure) {
                    return@withContext VoicePackageDownloadResult.Failure(onnxResult.message)
                }
                val onnx = onnxResult as FetchResult.Success

                // 2. Fetch ONNX JSON config (small — no detailed progress)
                val configResult =
                    fetchFile(
                        url = configUrl,
                        destination = configTemp,
                        reportedTotalBytes = null,
                        onProgress = {},
                        progressOffsetBytes = onnx.sizeBytes,
                    )
                if (configResult is FetchResult.Failure) {
                    return@withContext VoicePackageDownloadResult.Failure(configResult.message)
                }

                // 3. Parse sample rate from config (fallback = 22050)
                val sampleRateHz = parseSampleRate(configTemp) ?: DEFAULT_SAMPLE_RATE_HZ

                // 4. Build manifest
                val manifest =
                    CustomVoicePackageManifest(
                        packageId = safePackageId(voice.id),
                        displayName = voice.displayName,
                        localeTag = voice.localeTag,
                        sampleText = voice.sampleText.ifBlank { "This voice is ready for local playback." },
                        runtime =
                            CustomVoiceRuntimeManifest(
                                type = CustomVoiceRuntimeType.PIPER_V1,
                                sampleRateHz = sampleRateHz,
                            ),
                        model =
                            CustomVoiceModelFileManifest(
                                path = MODEL_PATH,
                                sizeBytes = onnx.sizeBytes,
                                sha256 = onnx.sha256,
                                configPath = CONFIG_PATH,
                            ),
                    )

                // 5. Assemble .voicepkg ZIP
                destPackage.outputStream().use { fos ->
                    ZipOutputStream(fos).use { zos ->
                        zos.putNextEntry(ZipEntry(MANIFEST_PATH))
                        zos.write(
                            Json { encodeDefaults = true }
                                .encodeToString(manifest)
                                .toByteArray(Charsets.UTF_8),
                        )
                        zos.closeEntry()

                        zos.putNextEntry(ZipEntry(MODEL_PATH))
                        onnxTemp.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()

                        zos.putNextEntry(ZipEntry(CONFIG_PATH))
                        configTemp.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }

                VoicePackageDownloadResult.Success(
                    DownloadedVoicePackage(
                        sourceUri = destPackage.toURI().toString(),
                        sizeBytes = destPackage.length(),
                        sha256 = sha256OfFile(destPackage),
                    ),
                )
            } catch (e: IOException) {
                VoicePackageDownloadResult.Failure("Voice download failed: ${e.message}")
            } finally {
                onnxTemp.delete()
                configTemp.delete()
                // destPackage is kept for the importer; cleaned up by LocalVoiceDownloadManager
                // after importPackage() succeeds or fails.
            }
        }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun fetchFile(
        url: String,
        destination: File,
        reportedTotalBytes: Long?,
        onProgress: (VoiceDownloadProgress) -> Unit,
        progressOffsetBytes: Long,
    ): FetchResult =
        try {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                if (!response.isSuccessful) {
                    return FetchResult.Failure("HTTP ${response.code} downloading $url")
                }
                val body = response.body
                    ?: return FetchResult.Failure("Empty response body: $url")
                val contentLength = body.contentLength().takeIf { it > 0L }
                val totalBytes = reportedTotalBytes ?: contentLength

                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var copied = 0L

                destination.outputStream().use { out ->
                    body.byteStream().use { inp ->
                        while (true) {
                            val n = inp.read(buffer)
                            if (n <= 0) break
                            copied += n
                            if (copied > MAX_BYTES) {
                                throw IOException("Voice model exceeds 512 MB limit.")
                            }
                            digest.update(buffer, 0, n)
                            out.write(buffer, 0, n)
                            onProgress(
                                VoiceDownloadProgress(
                                    bytesDownloaded = progressOffsetBytes + copied,
                                    totalBytes = totalBytes,
                                ),
                            )
                        }
                    }
                }

                if (copied == 0L) {
                    return FetchResult.Failure("Downloaded file was empty: $url")
                }
                FetchResult.Success(
                    sizeBytes = copied,
                    sha256 = digest.digest().joinToString("") { "%02x".format(it) },
                )
            }
        } catch (e: IOException) {
            FetchResult.Failure("Network error fetching $url: ${e.message}")
        }

    private fun parseSampleRate(configFile: File): Int? =
        runCatching {
            Regex(""""sample_rate"\s*:\s*(\d+)""")
                .find(configFile.readText(Charsets.UTF_8))
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
        }.getOrNull()

    private fun sha256OfFile(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        file.inputStream().use { inp ->
            while (true) {
                val n = inp.read(buffer)
                if (n <= 0) break
                digest.update(buffer, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun safePackageId(raw: String): String =
        raw.lowercase().replace(Regex("[^a-z0-9._-]"), "-").take(64)

    private sealed interface FetchResult {
        data class Success(val sizeBytes: Long, val sha256: String) : FetchResult
        data class Failure(val message: String) : FetchResult
    }

    private companion object {
        private const val MANIFEST_PATH = "voice-package.json"
        private const val MODEL_PATH = "models/model.onnx"
        private const val CONFIG_PATH = "models/model.onnx.json"
        private const val MAX_BYTES = 512L * 1024L * 1024L
        private const val DEFAULT_SAMPLE_RATE_HZ = 22050
    }
}
