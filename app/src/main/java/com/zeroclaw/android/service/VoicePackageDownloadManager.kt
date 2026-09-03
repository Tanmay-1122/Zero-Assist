/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.model.VoiceModel
import com.zeroclaw.android.model.VoiceModelStatus
import java.io.File
import java.io.IOException
import java.net.URI
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

data class VoiceDownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long?,
)

data class DownloadedVoicePackage(
    val sourceUri: String,
    val sizeBytes: Long,
    val sha256: String,
)

sealed interface VoicePackageDownloadResult {
    data class Success(val packageFile: DownloadedVoicePackage) : VoicePackageDownloadResult

    data class Failure(val message: String) : VoicePackageDownloadResult
}

sealed interface VoiceDownloadInstallResult {
    data class Success(val modelUri: String) : VoiceDownloadInstallResult

    data class Failure(val message: String) : VoiceDownloadInstallResult
}

interface VoicePackageDownloader {
    suspend fun downloadPackage(
        voice: VoiceModel,
        onProgress: (VoiceDownloadProgress) -> Unit,
    ): VoicePackageDownloadResult
}

interface VoiceDownloadManager {
    suspend fun downloadVoice(voiceId: String): VoiceDownloadInstallResult
}

class LocalVoiceDownloadManager(
    private val voiceCatalogRepository: LocalVoiceCatalogRepository,
    private val localVoiceStorage: LocalVoiceStorage,
    private val downloader: VoicePackageDownloader,
) : VoiceDownloadManager {
    override suspend fun downloadVoice(voiceId: String): VoiceDownloadInstallResult {
        val voice =
            voiceCatalogRepository.voice(voiceId)
                ?: return VoiceDownloadInstallResult.Failure("Voice is not in the local catalog.")
        if (voice.status == VoiceModelStatus.Installed && !voice.modelUri.isNullOrBlank()) {
            return VoiceDownloadInstallResult.Success(voice.modelUri)
        }
        if (!voiceCatalogRepository.startDownload(voiceId)) {
            return fail(voiceId, "Voice download could not be started.")
        }

        val downloaded =
            when (
                val downloadResult =
                    downloader.downloadPackage(voice) { progress ->
                        voiceCatalogRepository.updateDownloadProgress(
                            voiceId = voiceId,
                            bytesDownloaded = progress.bytesDownloaded,
                            totalBytes = progress.totalBytes,
                        )
                    }
            ) {
                is VoicePackageDownloadResult.Failure ->
                    return fail(voiceId, downloadResult.message)
                is VoicePackageDownloadResult.Success -> downloadResult.packageFile
            }

        // Resolve the temp file so we can delete it after import regardless of outcome.
        val tempPackageFile =
            runCatching {
                val uri = java.net.URI(downloaded.sourceUri)
                if (uri.scheme?.lowercase() == "file") java.io.File(uri) else null
            }.getOrNull()

        try {
            val storedPackage =
                when (
                    val importResult =
                        localVoiceStorage.importPackage(
                            VoicePackageImportRequest(
                                displayName = voice.displayName,
                                localeTag = voice.localeTag,
                                sourceUri = downloaded.sourceUri,
                                declaredSizeBytes = downloaded.sizeBytes,
                            ),
                        )
                ) {
                    is VoicePackageImportResult.Failure ->
                        return fail(voiceId, importResult.message)
                    is VoicePackageImportResult.Success -> importResult.voicePackage
                }

            return if (voiceCatalogRepository.markInstalled(voiceId, storedPackage.localModelUri)) {
                VoiceDownloadInstallResult.Success(storedPackage.localModelUri)
            } else {
                fail(voiceId, "Downloaded voice metadata is invalid.")
            }
        } finally {
            // Clean up the temp .voicepkg from cacheDir — its contents are now in filesDir.
            tempPackageFile?.delete()
        }
    }

    private fun fail(
        voiceId: String,
        message: String,
    ): VoiceDownloadInstallResult.Failure {
        voiceCatalogRepository.markDownloadFailed(voiceId, message)
        return VoiceDownloadInstallResult.Failure(message)
    }
}

class OkHttpVoicePackageDownloader(
    private val cacheRoot: File,
    private val client: OkHttpClient = OkHttpClient(),
) : VoicePackageDownloader {
    override suspend fun downloadPackage(
        voice: VoiceModel,
        onProgress: (VoiceDownloadProgress) -> Unit,
    ): VoicePackageDownloadResult =
        withContext(Dispatchers.IO) {
            val downloadUri =
                voice.downloadUri?.trim()?.takeIf { it.isNotBlank() }
                    ?: return@withContext VoicePackageDownloadResult.Failure(
                        "Voice download URL is not configured.",
                    )
            val uri =
                runCatching { URI(downloadUri) }.getOrNull()
                    ?: return@withContext VoicePackageDownloadResult.Failure(
                        "Voice download URL is invalid.",
                    )
            if (!uri.scheme.equals("https", ignoreCase = true)) {
                return@withContext VoicePackageDownloadResult.Failure(
                    "Voice packages must be downloaded over HTTPS.",
                )
            }
            if (!cacheRoot.exists() && !cacheRoot.mkdirs()) {
                return@withContext VoicePackageDownloadResult.Failure(
                    "Could not prepare voice download cache.",
                )
            }
            if (!cacheRoot.isDirectory) {
                return@withContext VoicePackageDownloadResult.Failure(
                    "Voice download cache path is invalid.",
                )
            }

            val destination = File(cacheRoot, "${voice.id}-${System.nanoTime()}.voicepkg")
            val request = Request.Builder().url(downloadUri).get().build()
            try {
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        return@withContext VoicePackageDownloadResult.Failure(
                            "Voice package download failed with HTTP ${response.code}.",
                        )
                    }
                    val body =
                        response.body
                            ?: return@withContext VoicePackageDownloadResult.Failure(
                                "Voice package download was empty.",
                            )
                    val downloaded =
                        body.byteStream().use { input ->
                            destination.outputStream().use { output ->
                                copyWithDigest(
                                    input = input,
                                    output = output,
                                    totalBytes = body.contentLength().takeIf { it > 0L },
                                    onProgress = onProgress,
                                )
                            }
                        }
                    if (downloaded.sizeBytes == 0L) {
                        return@withContext VoicePackageDownloadResult.Failure(
                            "Voice package download was empty.",
                        )
                    }
                    val expectedSha = voice.packageSha256?.trim().orEmpty()
                    if (expectedSha.isNotBlank() && !downloaded.sha256.equals(expectedSha, ignoreCase = true)) {
                        return@withContext VoicePackageDownloadResult.Failure(
                            "Voice package checksum did not match.",
                        )
                    }
                    VoicePackageDownloadResult.Success(
                        DownloadedVoicePackage(
                            sourceUri = destination.toURI().toString(),
                            sizeBytes = downloaded.sizeBytes,
                            sha256 = downloaded.sha256,
                        ),
                    )
                }
            } catch (e: IOException) {
                VoicePackageDownloadResult.Failure("Voice package download failed: ${e.message}")
            }.also { result ->
                if (result is VoicePackageDownloadResult.Failure && destination.exists()) {
                    destination.delete()
                }
            }
        }

    private fun copyWithDigest(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        totalBytes: Long?,
        onProgress: (VoiceDownloadProgress) -> Unit,
    ): CopiedDownload {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var copiedBytes = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            copiedBytes += read
            if (copiedBytes > MAX_VOICE_PACKAGE_BYTES) {
                throw IOException("Voice package exceeds the 512MB limit.")
            }
            digest.update(buffer, 0, read)
            output.write(buffer, 0, read)
            onProgress(
                VoiceDownloadProgress(
                    bytesDownloaded = copiedBytes,
                    totalBytes = totalBytes,
                ),
            )
        }
        return CopiedDownload(
            sizeBytes = copiedBytes,
            sha256 = digest.digest().toHexString(),
        )
    }

    private fun ByteArray.toHexString(): String =
        joinToString(separator = "") { byte -> "%02x".format(byte) }

    private data class CopiedDownload(
        val sizeBytes: Long,
        val sha256: String,
    )

    private companion object {
        private const val MAX_VOICE_PACKAGE_BYTES = 512L * 1024L * 1024L
    }
}
