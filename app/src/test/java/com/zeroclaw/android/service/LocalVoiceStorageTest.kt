/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.model.CUSTOM_VOICE_PACKAGE_MANIFEST_FILE
import com.zeroclaw.android.model.CustomVoiceModelFileManifest
import com.zeroclaw.android.model.CustomVoicePackageManifest
import com.zeroclaw.android.model.CustomVoicePhonemizerManifest
import com.zeroclaw.android.model.CustomVoicePhonemizerType
import com.zeroclaw.android.model.CustomVoiceRuntimeManifest
import com.zeroclaw.android.model.CustomVoiceRuntimeType
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@DisplayName("LocalVoiceStorage")
class LocalVoiceStorageTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `importPackage copies file uri into app private storage`() {
        val sourceFile = File(tempDir, "source voice.onnx")
        val sourceBytes = byteArrayOf(1, 2, 3, 4)
        sourceFile.writeBytes(sourceBytes)
        val storageRoot = File(tempDir, "private")
        val storage = LocalVoiceStorage(storageRoot = storageRoot)

        val result =
            storage.importPackage(
                VoicePackageImportRequest(
                    displayName = "Imported Clear.onnx",
                    localeTag = "en-US",
                    sourceUri = sourceFile.toURI().toString(),
                    declaredSizeBytes = sourceFile.length(),
                ),
            ).success()

        val copiedFile = File(URI(result.localModelUri))
        assertTrue(copiedFile.exists())
        assertTrue(
            copiedFile.canonicalPath.startsWith(
                File(storageRoot, "imported-voices").canonicalPath,
            ),
        )
        assertEquals("file", URI(result.localModelUri).scheme)
        assertArrayEquals(sourceBytes, copiedFile.readBytes())
        assertEquals(sourceBytes.size.toLong(), result.sizeBytes)
    }

    @Test
    fun `importPackage keeps stable private path for same source`() {
        val sourceFile = File(tempDir, "stable.voice")
        sourceFile.writeBytes(byteArrayOf(1, 2, 3))
        val storage = LocalVoiceStorage(storageRoot = File(tempDir, "private"))
        val request =
            VoicePackageImportRequest(
                displayName = "Stable Voice",
                localeTag = "en-US",
                sourceUri = sourceFile.toURI().toString(),
                declaredSizeBytes = sourceFile.length(),
            )

        val first = storage.importPackage(request).success()
        sourceFile.writeBytes(byteArrayOf(9, 8, 7))
        val second = storage.importPackage(request).success()

        assertEquals(first.localModelUri, second.localModelUri)
        assertArrayEquals(byteArrayOf(9, 8, 7), File(URI(second.localModelUri)).readBytes())
    }

    @Test
    fun `importPackage copies content uri through injected opener`() {
        val sourceUri = "content://voice/imported-ready.onnx"
        val sourceBytes = byteArrayOf(8, 6, 7, 5)
        val opener = RecordingVoicePackageInputOpener(mapOf(sourceUri to sourceBytes))
        val storage =
            LocalVoiceStorage(
                storageRoot = File(tempDir, "private"),
                inputOpener = opener,
            )

        val result =
            storage.importPackage(
                VoicePackageImportRequest(
                    displayName = "Imported Ready",
                    localeTag = "en-US",
                    sourceUri = sourceUri,
                    declaredSizeBytes = sourceBytes.size.toLong(),
                ),
            ).success()

        assertEquals(listOf(sourceUri), opener.openedUris)
        assertArrayEquals(sourceBytes, File(URI(result.localModelUri)).readBytes())
    }

    @Test
    fun `importSelectedFiles wraps matching Piper onnx pair as local package`() {
        val modelFile = File(tempDir, "en_US-robot-medium.onnx")
        val configFile = File(tempDir, "en_US-robot-medium.onnx.json")
        val modelBytes = byteArrayOf(2, 4, 6, 8)
        val configBytes = piperConfigBytes(sampleRateHz = 22_050)
        modelFile.writeBytes(modelBytes)
        configFile.writeBytes(configBytes)
        val storageRoot = File(tempDir, "private")
        val storage = LocalVoiceStorage(storageRoot = storageRoot)

        val result =
            storage.importSelectedFiles(
                VoicePackageImportSelectionRequest(
                    localeTag = "en-US",
                    files =
                        listOf(
                            VoicePackageImportFile(
                                displayName = modelFile.name,
                                sourceUri = modelFile.toURI().toString(),
                                declaredSizeBytes = modelFile.length(),
                            ),
                            VoicePackageImportFile(
                                displayName = configFile.name,
                                sourceUri = configFile.toURI().toString(),
                                declaredSizeBytes = configFile.length(),
                            ),
                        ),
                ),
            ).success()

        val storedManifestFile = File(URI(result.localModelUri))
        val storedPackageRoot = requireNotNull(storedManifestFile.parentFile)
        val manifest = Json.decodeFromString<CustomVoicePackageManifest>(storedManifestFile.readText())
        assertTrue(storedManifestFile.exists())
        assertTrue(
            storedManifestFile.canonicalPath.startsWith(
                File(storageRoot, "imported-voices").canonicalPath,
            ),
        )
        assertEquals("en US robot medium", result.displayName)
        assertEquals(CustomVoiceRuntimeType.PIPER_V1, result.runtimeType)
        assertEquals(22_050, manifest.runtime.sampleRateHz)
        assertEquals("models/model.onnx", manifest.model.path)
        assertEquals("models/model.onnx.json", manifest.model.configPath)
        assertEquals(modelBytes.size.toLong(), manifest.model.sizeBytes)
        assertEquals(modelBytes.sha256(), manifest.model.sha256)
        assertArrayEquals(modelBytes, File(storedPackageRoot, manifest.model.path).readBytes())
        assertArrayEquals(
            configBytes,
            File(storedPackageRoot, requireNotNull(manifest.model.configPath)).readBytes(),
        )
    }

    @Test
    fun `deleteStoredVoice removes stored Piper package directory`() {
        val modelFile = File(tempDir, "en_US-robot-medium.onnx")
        val configFile = File(tempDir, "en_US-robot-medium.onnx.json")
        modelFile.writeBytes(byteArrayOf(2, 4, 6, 8))
        configFile.writeBytes(piperConfigBytes())
        val storage = LocalVoiceStorage(storageRoot = File(tempDir, "private"))
        val result =
            storage.importSelectedFiles(
                VoicePackageImportSelectionRequest(
                    localeTag = "en-US",
                    files =
                        listOf(
                            VoicePackageImportFile(
                                displayName = modelFile.name,
                                sourceUri = modelFile.toURI().toString(),
                                declaredSizeBytes = modelFile.length(),
                            ),
                            VoicePackageImportFile(
                                displayName = configFile.name,
                                sourceUri = configFile.toURI().toString(),
                                declaredSizeBytes = configFile.length(),
                            ),
                        ),
                ),
            ).success()
        val manifestFile = File(URI(result.localModelUri))
        val packageDir = requireNotNull(manifestFile.parentFile)

        assertTrue(storage.deleteStoredVoice(result.localModelUri))

        assertFalse(packageDir.exists())
    }

    @Test
    fun `deleteStoredVoice rejects paths outside private storage`() {
        val externalVoice = File(tempDir, "external.onnx")
        externalVoice.writeBytes(byteArrayOf(1, 2, 3))
        val storage = LocalVoiceStorage(storageRoot = File(tempDir, "private"))

        assertFalse(storage.deleteStoredVoice(externalVoice.toURI().toString()))
        assertTrue(externalVoice.exists())
    }

    @Test
    fun `importSelectedFiles rejects single Piper model without config`() {
        val modelFile = File(tempDir, "solo.onnx")
        modelFile.writeBytes(byteArrayOf(1, 2, 3))
        val storageRoot = File(tempDir, "private")
        val storage = LocalVoiceStorage(storageRoot = storageRoot)

        val result =
            storage.importSelectedFiles(
                VoicePackageImportSelectionRequest(
                    localeTag = "en-US",
                    files =
                        listOf(
                            VoicePackageImportFile(
                                displayName = modelFile.name,
                                sourceUri = modelFile.toURI().toString(),
                                declaredSizeBytes = modelFile.length(),
                            ),
                        ),
                ),
            )

        assertTrue(result is VoicePackageImportResult.Failure)
        assertTrue((result as VoicePackageImportResult.Failure).message.contains("both"))
        assertNoStoredManifest(storageRoot)
    }

    @Test
    fun `importSelectedFiles rejects mismatched Piper pair`() {
        val modelFile = File(tempDir, "alpha.onnx")
        val configFile = File(tempDir, "beta.onnx.json")
        modelFile.writeBytes(byteArrayOf(1, 2, 3))
        configFile.writeBytes(piperConfigBytes())
        val storageRoot = File(tempDir, "private")
        val storage = LocalVoiceStorage(storageRoot = storageRoot)

        val result =
            storage.importSelectedFiles(
                VoicePackageImportSelectionRequest(
                    localeTag = "en-US",
                    files =
                        listOf(
                            VoicePackageImportFile(
                                displayName = modelFile.name,
                                sourceUri = modelFile.toURI().toString(),
                                declaredSizeBytes = modelFile.length(),
                            ),
                            VoicePackageImportFile(
                                displayName = configFile.name,
                                sourceUri = configFile.toURI().toString(),
                                declaredSizeBytes = configFile.length(),
                            ),
                        ),
                ),
            )

        assertTrue(result is VoicePackageImportResult.Failure)
        assertTrue((result as VoicePackageImportResult.Failure).message.contains("filenames"))
        assertNoStoredManifest(storageRoot)
    }

    @Test
    fun `importPackage extracts validated voice package bundle`() {
        val modelBytes = byteArrayOf(4, 8, 15, 16, 23, 42)
        val configBytes = """{"audio":{"sample_rate":22050}}""".toByteArray()
        val phonemizerBytes = """{"words":{"hello":["h","i"]}}""".toByteArray()
        val packageFile = File(tempDir, "clear-operator.voicepkg")
        val manifest =
            validPackageManifest(
                modelBytes = modelBytes,
                phonemizerBytes = phonemizerBytes,
                sampleText = "I am ready locally.",
            )
        writeVoicePackage(
            packageFile = packageFile,
            manifest = manifest,
            entries =
                mapOf(
                    manifest.model.path to modelBytes,
                    requireNotNull(manifest.model.configPath) to configBytes,
                    requireNotNull(manifest.phonemizer).path to phonemizerBytes,
                ),
        )
        val storageRoot = File(tempDir, "private")
        val storage = LocalVoiceStorage(storageRoot = storageRoot)

        val result =
            storage.importPackage(
                VoicePackageImportRequest(
                    displayName = packageFile.name,
                    localeTag = "en-US",
                    sourceUri = packageFile.toURI().toString(),
                    declaredSizeBytes = packageFile.length(),
                ),
            ).success()

        val storedManifestFile = File(URI(result.localModelUri))
        val storedPackageRoot = requireNotNull(storedManifestFile.parentFile)
        assertTrue(storedManifestFile.exists())
        assertTrue(
            storedManifestFile.canonicalPath.startsWith(
                File(storageRoot, "imported-voices").canonicalPath,
            ),
        )
        assertArrayEquals(modelBytes, File(storedPackageRoot, manifest.model.path).readBytes())
        assertArrayEquals(configBytes, File(storedPackageRoot, requireNotNull(manifest.model.configPath)).readBytes())
        assertArrayEquals(
            phonemizerBytes,
            File(storedPackageRoot, requireNotNull(manifest.phonemizer).path).readBytes(),
        )
        assertEquals("Clear Operator", result.displayName)
        assertEquals("en-US", result.localeTag)
        assertEquals("I am ready locally.", result.sampleText)
        assertEquals(CustomVoiceRuntimeType.PIPER_V1, result.runtimeType)
        assertEquals(modelBytes.size.toLong(), result.sizeBytes)
    }

    @Test
    fun `importPackage rejects network uri without opening source`() {
        val sourceUri = "https://voices.example.com/imported-ready.onnx"
        val opener = RecordingVoicePackageInputOpener(mapOf(sourceUri to byteArrayOf(1)))
        val storage =
            LocalVoiceStorage(
                storageRoot = File(tempDir, "private"),
                inputOpener = opener,
            )

        val result =
            storage.importPackage(
                VoicePackageImportRequest(
                    displayName = "Imported Ready",
                    localeTag = "en-US",
                    sourceUri = sourceUri,
                    declaredSizeBytes = 1L,
                ),
            )

        assertTrue(result is VoicePackageImportResult.Failure)
        assertTrue((result as VoicePackageImportResult.Failure).message.contains("local"))
        assertTrue(opener.openedUris.isEmpty())
        assertFalse(File(tempDir, "private/imported-voices").exists())
    }

    @Test
    fun `importPackage rejects cloud voice package bundle`() {
        val modelBytes = byteArrayOf(1, 2, 3)
        val packageFile = File(tempDir, "cloud.voicepkg")
        writeVoicePackage(
            packageFile = packageFile,
            manifest =
                validPackageManifest(
                    modelBytes = modelBytes,
                    runtime =
                        CustomVoiceRuntimeManifest(
                            type = CustomVoiceRuntimeType.PIPER_V1,
                            sampleRateHz = 22_050,
                            requiresNetwork = true,
                        ),
                ),
            entries = mapOf("models/clear.onnx" to modelBytes),
        )
        val storageRoot = File(tempDir, "private")
        val storage = LocalVoiceStorage(storageRoot = storageRoot)

        val result =
            storage.importPackage(
                VoicePackageImportRequest(
                    displayName = packageFile.name,
                    localeTag = "en-US",
                    sourceUri = packageFile.toURI().toString(),
                    declaredSizeBytes = packageFile.length(),
                ),
            )

        assertTrue(result is VoicePackageImportResult.Failure)
        assertTrue((result as VoicePackageImportResult.Failure).message.contains("invalid"))
        assertNoStoredManifest(storageRoot)
    }

    @Test
    fun `importPackage rejects package bundle when phonemizer checksum does not match`() {
        val modelBytes = byteArrayOf(1, 2, 3)
        val phonemizerBytes = """{"words":{"hello":["h"]}}""".toByteArray()
        val packageFile = File(tempDir, "phonemizer-mismatch.voicepkg")
        writeVoicePackage(
            packageFile = packageFile,
            manifest =
                validPackageManifest(
                    modelBytes = modelBytes,
                    phonemizerBytes = phonemizerBytes,
                    phonemizerSha256 = "0".repeat(64),
                ),
            entries =
                mapOf(
                    "models/clear.onnx" to modelBytes,
                    "models/clear.json" to """{"audio":{"sample_rate":22050}}""".toByteArray(),
                    "phonemizers/en-lexicon.json" to phonemizerBytes,
                ),
        )
        val storageRoot = File(tempDir, "private")
        val storage = LocalVoiceStorage(storageRoot = storageRoot)

        val result =
            storage.importPackage(
                VoicePackageImportRequest(
                    displayName = packageFile.name,
                    localeTag = "en-US",
                    sourceUri = packageFile.toURI().toString(),
                    declaredSizeBytes = packageFile.length(),
                ),
            )

        assertTrue(result is VoicePackageImportResult.Failure)
        assertTrue((result as VoicePackageImportResult.Failure).message.contains("phonemizer checksum"))
        assertNoStoredManifest(storageRoot)
    }

    @Test
    fun `importPackage rejects package bundle when model checksum does not match`() {
        val modelBytes = byteArrayOf(1, 2, 3)
        val packageFile = File(tempDir, "mismatch.voicepkg")
        writeVoicePackage(
            packageFile = packageFile,
            manifest =
                validPackageManifest(
                    modelBytes = modelBytes,
                    sha256 = "0".repeat(64),
                ),
            entries = mapOf("models/clear.onnx" to modelBytes),
        )
        val storageRoot = File(tempDir, "private")
        val storage = LocalVoiceStorage(storageRoot = storageRoot)

        val result =
            storage.importPackage(
                VoicePackageImportRequest(
                    displayName = packageFile.name,
                    localeTag = "en-US",
                    sourceUri = packageFile.toURI().toString(),
                    declaredSizeBytes = packageFile.length(),
                ),
            )

        assertTrue(result is VoicePackageImportResult.Failure)
        assertTrue((result as VoicePackageImportResult.Failure).message.contains("checksum"))
        assertNoStoredManifest(storageRoot)
    }

    @Test
    fun `importPackage rejects non English package without opening source`() {
        val sourceUri = "content://voice/hindi.onnx"
        val opener = RecordingVoicePackageInputOpener(mapOf(sourceUri to byteArrayOf(1)))
        val storage =
            LocalVoiceStorage(
                storageRoot = File(tempDir, "private"),
                inputOpener = opener,
            )

        val result =
            storage.importPackage(
                VoicePackageImportRequest(
                    displayName = "Hindi Voice",
                    localeTag = "hi-IN",
                    sourceUri = sourceUri,
                    declaredSizeBytes = 1L,
                ),
            )

        assertTrue(result is VoicePackageImportResult.Failure)
        assertTrue((result as VoicePackageImportResult.Failure).message.contains("English"))
        assertTrue(opener.openedUris.isEmpty())
        assertFalse(File(tempDir, "private/imported-voices").exists())
    }

    private fun VoicePackageImportResult.success(): StoredVoicePackage {
        assertTrue(this is VoicePackageImportResult.Success)
        return (this as VoicePackageImportResult.Success).voicePackage
    }

    private fun validPackageManifest(
        modelBytes: ByteArray,
        phonemizerBytes: ByteArray? = null,
        sampleText: String = "I am ready locally.",
        runtime: CustomVoiceRuntimeManifest =
            CustomVoiceRuntimeManifest(
                type = CustomVoiceRuntimeType.PIPER_V1,
                sampleRateHz = 22_050,
            ),
        sha256: String = modelBytes.sha256(),
        phonemizerSha256: String = phonemizerBytes?.sha256().orEmpty(),
    ): CustomVoicePackageManifest =
        CustomVoicePackageManifest(
            packageId = "en.clear.operator",
            displayName = "Clear Operator",
            localeTag = "en-US",
            sampleText = sampleText,
            runtime = runtime,
            model =
                CustomVoiceModelFileManifest(
                    path = "models/clear.onnx",
                    sizeBytes = modelBytes.size.toLong(),
                    sha256 = sha256,
                    configPath = "models/clear.json",
                ),
            phonemizer =
                phonemizerBytes?.let { bytes ->
                    CustomVoicePhonemizerManifest(
                        type = CustomVoicePhonemizerType.PIPER_LEXICON_V1,
                        path = "phonemizers/en-lexicon.json",
                        sizeBytes = bytes.size.toLong(),
                        sha256 = phonemizerSha256,
                    )
                },
        )

    private fun writeVoicePackage(
        packageFile: File,
        manifest: CustomVoicePackageManifest,
        entries: Map<String, ByteArray>,
    ) {
        ZipOutputStream(packageFile.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(CUSTOM_VOICE_PACKAGE_MANIFEST_FILE))
            zip.write(Json.encodeToString(manifest).toByteArray())
            zip.closeEntry()
            entries.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    private fun piperConfigBytes(sampleRateHz: Int = 22_050): ByteArray =
        """
        {
          "audio": { "sample_rate": $sampleRateHz },
          "phoneme_type": "text",
          "phoneme_id_map": {
            "_": [0],
            "^": [1],
            "${'$'}": [2],
            "a": [3]
          }
        }
        """.trimIndent().toByteArray()

    private fun assertNoStoredManifest(storageRoot: File) {
        val importedRoot = File(storageRoot, "imported-voices")
        val storedManifests =
            if (importedRoot.exists()) {
                importedRoot
                    .walkTopDown()
                    .filter { file -> file.name == CUSTOM_VOICE_PACKAGE_MANIFEST_FILE }
                    .toList()
            } else {
                emptyList()
            }
        assertTrue(storedManifests.isEmpty())
    }

    private fun ByteArray.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(this)
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }

    private class RecordingVoicePackageInputOpener(
        private val bytesByUri: Map<String, ByteArray>,
    ) : VoicePackageInputOpener {
        val openedUris = mutableListOf<String>()

        override fun open(sourceUri: String) =
            bytesByUri[sourceUri]?.let { bytes ->
                openedUris += sourceUri
                ByteArrayInputStream(bytes)
            }
    }
}
