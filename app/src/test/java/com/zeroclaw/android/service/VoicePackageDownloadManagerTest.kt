/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.model.CUSTOM_VOICE_PACKAGE_MANIFEST_FILE
import com.zeroclaw.android.model.CustomVoiceModelFileManifest
import com.zeroclaw.android.model.CustomVoicePackageManifest
import com.zeroclaw.android.model.CustomVoiceRuntimeManifest
import com.zeroclaw.android.model.CustomVoiceRuntimeType
import com.zeroclaw.android.model.VoiceModel
import com.zeroclaw.android.model.VoiceModelSource
import com.zeroclaw.android.model.VoiceModelStatus
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@DisplayName("LocalVoiceDownloadManager")
class VoicePackageDownloadManagerTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `downloadVoice downloads imports installs and selects catalog voice`() =
        runTest {
            val packageFile = writeVoicePackage()
            val downloader = FakeVoicePackageDownloader(packageFile)
            val repository =
                LocalVoiceCatalogRepository(
                    initialVoices = listOf(downloadableVoice()),
                )
            val manager =
                LocalVoiceDownloadManager(
                    voiceCatalogRepository = repository,
                    localVoiceStorage = LocalVoiceStorage(File(tempDir, "storage")),
                    downloader = downloader,
                )

            val result = manager.downloadVoice("en-test-download")

            assertTrue(result is VoiceDownloadInstallResult.Success)
            val installed = repository.voice("en-test-download")
            assertEquals(VoiceModelStatus.Installed, installed?.status)
            assertEquals("en-test-download", repository.selectedVoiceId.value)
            val modelUri = (result as VoiceDownloadInstallResult.Success).modelUri
            assertTrue(File(URI(modelUri)).isFile)
            assertEquals(listOf("https://voices.example.com/en-test-download.voicepkg"), downloader.requestedUris)
            assertTrue(downloader.progressEvents.isNotEmpty())
        }

    @Test
    fun `downloadVoice marks voice failed when downloader fails`() =
        runTest {
            val repository =
                LocalVoiceCatalogRepository(
                    initialVoices = listOf(downloadableVoice()),
                )
            val manager =
                LocalVoiceDownloadManager(
                    voiceCatalogRepository = repository,
                    localVoiceStorage = LocalVoiceStorage(File(tempDir, "storage")),
                    downloader = FakeVoicePackageDownloader(failure = "Network unavailable."),
                )

            val result = manager.downloadVoice("en-test-download")

            assertTrue(result is VoiceDownloadInstallResult.Failure)
            assertEquals(
                VoiceModelStatus.Failed("Network unavailable."),
                repository.voice("en-test-download")?.status,
            )
        }

    @Test
    fun `downloadVoice fails when voice is missing from catalog`() =
        runTest {
            val manager =
                LocalVoiceDownloadManager(
                    voiceCatalogRepository = LocalVoiceCatalogRepository(initialVoices = emptyList()),
                    localVoiceStorage = LocalVoiceStorage(File(tempDir, "storage")),
                    downloader = FakeVoicePackageDownloader(writeVoicePackage()),
                )

            val result = manager.downloadVoice("missing")

            assertTrue(result is VoiceDownloadInstallResult.Failure)
            assertEquals(
                "Voice is not in the local catalog.",
                (result as VoiceDownloadInstallResult.Failure).message,
            )
        }

    private fun downloadableVoice(): VoiceModel =
        VoiceModel(
            id = "en-test-download",
            displayName = "Test Download",
            toneLabel = "Clear",
            localeTag = "en-US",
            description = "Downloadable test voice.",
            sizeBytes = 64L,
            source = VoiceModelSource.CATALOG,
            status = VoiceModelStatus.AvailableForDownload,
            sampleText = "Testing local download.",
            downloadUri = "https://voices.example.com/en-test-download.voicepkg",
        )

    private fun writeVoicePackage(): File {
        val packageFile = File(tempDir, "download.voicepkg")
        val modelBytes = byteArrayOf(1, 3, 3, 7)
        val configBytes =
            """
            {
              "phoneme_type": "text",
              "phoneme_id_map": {"_":[0],"^":[1],"$":[2],"t":[3]},
              "audio": {"sample_rate": 22050}
            }
            """.trimIndent().toByteArray()
        val manifest =
            CustomVoicePackageManifest(
                packageId = "en.test.download",
                displayName = "Test Download",
                localeTag = "en-US",
                sampleText = "Testing local download.",
                runtime =
                    CustomVoiceRuntimeManifest(
                        type = CustomVoiceRuntimeType.PIPER_V1,
                        sampleRateHz = 22_050,
                    ),
                model =
                    CustomVoiceModelFileManifest(
                        path = "models/test.onnx",
                        sizeBytes = modelBytes.size.toLong(),
                        sha256 = sha256(modelBytes),
                        configPath = "models/test.json",
                    ),
            )
        ZipOutputStream(packageFile.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(CUSTOM_VOICE_PACKAGE_MANIFEST_FILE))
            zip.write(Json.encodeToString(manifest).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(manifest.model.path))
            zip.write(modelBytes)
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(requireNotNull(manifest.model.configPath)))
            zip.write(configBytes)
            zip.closeEntry()
        }
        return packageFile
    }

    private class FakeVoicePackageDownloader(
        private val packageFile: File? = null,
        private val failure: String? = null,
    ) : VoicePackageDownloader {
        val requestedUris = mutableListOf<String>()
        val progressEvents = mutableListOf<VoiceDownloadProgress>()

        override suspend fun downloadPackage(
            voice: VoiceModel,
            onProgress: (VoiceDownloadProgress) -> Unit,
        ): VoicePackageDownloadResult {
            voice.downloadUri?.let { requestedUris += it }
            if (failure != null) {
                return VoicePackageDownloadResult.Failure(failure)
            }
            val file = requireNotNull(packageFile)
            val progress =
                VoiceDownloadProgress(
                    bytesDownloaded = file.length(),
                    totalBytes = file.length(),
                )
            progressEvents += progress
            onProgress(progress)
            return VoicePackageDownloadResult.Success(
                DownloadedVoicePackage(
                    sourceUri = file.toURI().toString(),
                    sizeBytes = file.length(),
                    sha256 = sha256(file.readBytes()),
                ),
            )
        }
    }

    private companion object {
        fun sha256(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
        }
    }
}
