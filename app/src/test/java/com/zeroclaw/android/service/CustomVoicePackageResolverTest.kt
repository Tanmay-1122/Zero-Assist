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
import java.io.File
import java.security.MessageDigest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@DisplayName("CustomVoicePackageResolver")
class CustomVoicePackageResolverTest {
    @TempDir
    lateinit var tempDir: File

    private val resolver = CustomVoicePackageResolver()

    @Test
    fun `resolve returns local model and config files for validated package`() {
        val modelBytes = byteArrayOf(1, 2, 3, 4)
        val configBytes = """{"audio":{"sample_rate":22050}}""".toByteArray()
        val packageRoot =
            writeStoredPackage(
                modelBytes = modelBytes,
                configBytes = configBytes,
            )

        val result =
            resolver.resolve(
                File(packageRoot, CUSTOM_VOICE_PACKAGE_MANIFEST_FILE).toURI().toString(),
            ).success()

        assertEquals(packageRoot.canonicalFile, result.packageRoot.canonicalFile)
        assertEquals(
            File(packageRoot, "models/clear.onnx").canonicalFile,
            result.modelFile.canonicalFile,
        )
        assertEquals(
            File(packageRoot, "models/clear.json").canonicalFile,
            result.configFile?.canonicalFile,
        )
        assertEquals(CustomVoiceRuntimeType.PIPER_V1, result.manifest.runtime.type)
        assertEquals(22_050, result.manifest.runtime.sampleRateHz)
    }

    @Test
    fun `resolve returns local phonemizer file for packaged lexicon`() {
        val phonemizerBytes = """{"words":{"hello":["h","i"]}}""".toByteArray()
        val packageRoot =
            writeStoredPackage(
                modelBytes = byteArrayOf(1, 2, 3, 4),
                phonemizerBytes = phonemizerBytes,
                manifestTransform = { manifest ->
                    manifest.copy(
                        phonemizer =
                            CustomVoicePhonemizerManifest(
                                type = CustomVoicePhonemizerType.PIPER_LEXICON_V1,
                                path = "phonemizers/en-lexicon.json",
                                sizeBytes = phonemizerBytes.size.toLong(),
                                sha256 = phonemizerBytes.sha256(),
                            ),
                    )
                },
            )

        val result =
            resolver.resolve(
                File(packageRoot, CUSTOM_VOICE_PACKAGE_MANIFEST_FILE).toURI().toString(),
            ).success()

        assertEquals(
            File(packageRoot, "phonemizers/en-lexicon.json").canonicalFile,
            result.phonemizerFile?.canonicalFile,
        )
    }

    @Test
    fun `resolve rejects network manifest uri`() {
        val result = resolver.resolve("https://voices.example.com/voice-package.json")

        assertFailureContains(result, "local file URI")
    }

    @Test
    fun `resolve rejects raw model file uri`() {
        val rawModel = File(tempDir, "clear.onnx")
        rawModel.writeBytes(byteArrayOf(1, 2, 3))

        val result = resolver.resolve(rawModel.toURI().toString())

        assertFailureContains(result, "manifest URI")
    }

    @Test
    fun `resolve rejects package with missing model file`() {
        val packageRoot = writeStoredPackage(modelBytes = byteArrayOf(1, 2, 3))
        File(packageRoot, "models/clear.onnx").delete()

        val result =
            resolver.resolve(
                File(packageRoot, CUSTOM_VOICE_PACKAGE_MANIFEST_FILE).toURI().toString(),
            )

        assertFailureContains(result, "model file is missing")
    }

    @Test
    fun `resolve rejects package when model checksum changed`() {
        val packageRoot = writeStoredPackage(modelBytes = byteArrayOf(1, 2, 3))
        File(packageRoot, "models/clear.onnx").writeBytes(byteArrayOf(3, 2, 1))

        val result =
            resolver.resolve(
                File(packageRoot, CUSTOM_VOICE_PACKAGE_MANIFEST_FILE).toURI().toString(),
            )

        assertFailureContains(result, "checksum")
    }

    @Test
    fun `resolve rejects package with missing declared config file`() {
        val packageRoot =
            writeStoredPackage(
                modelBytes = byteArrayOf(1, 2, 3),
                configBytes = byteArrayOf(4, 5, 6),
            )
        File(packageRoot, "models/clear.json").delete()

        val result =
            resolver.resolve(
                File(packageRoot, CUSTOM_VOICE_PACKAGE_MANIFEST_FILE).toURI().toString(),
            )

        assertFailureContains(result, "config file is missing")
    }

    @Test
    fun `resolve rejects invalid manifest contract`() {
        val packageRoot =
            writeStoredPackage(
                modelBytes = byteArrayOf(1, 2, 3),
                manifestTransform = { manifest ->
                    manifest.copy(localeTag = "hi-IN")
                },
            )

        val result =
            resolver.resolve(
                File(packageRoot, CUSTOM_VOICE_PACKAGE_MANIFEST_FILE).toURI().toString(),
            )

        assertFailureContains(result, "invalid")
    }

    private fun writeStoredPackage(
        modelBytes: ByteArray,
        configBytes: ByteArray? = """{"audio":{"sample_rate":22050}}""".toByteArray(),
        phonemizerBytes: ByteArray? = null,
        manifestTransform: (CustomVoicePackageManifest) -> CustomVoicePackageManifest = { it },
    ): File {
        val packageRoot = File(tempDir, "package-${System.nanoTime()}")
        val modelFile = File(packageRoot, "models/clear.onnx")
        modelFile.parentFile?.mkdirs()
        modelFile.writeBytes(modelBytes)
        if (configBytes != null) {
            File(packageRoot, "models/clear.json").writeBytes(configBytes)
        }
        if (phonemizerBytes != null) {
            val phonemizerFile = File(packageRoot, "phonemizers/en-lexicon.json")
            phonemizerFile.parentFile?.mkdirs()
            phonemizerFile.writeBytes(phonemizerBytes)
        }

        val manifest =
            manifestTransform(
                CustomVoicePackageManifest(
                    packageId = "en.clear.operator",
                    displayName = "Clear Operator",
                    localeTag = "en-US",
                    sampleText = "I am ready locally.",
                    runtime =
                        CustomVoiceRuntimeManifest(
                            type = CustomVoiceRuntimeType.PIPER_V1,
                            sampleRateHz = 22_050,
                        ),
                    model =
                        CustomVoiceModelFileManifest(
                            path = "models/clear.onnx",
                            sizeBytes = modelBytes.size.toLong(),
                            sha256 = modelBytes.sha256(),
                            configPath = "models/clear.json",
                        ),
                ),
            )
        File(packageRoot, CUSTOM_VOICE_PACKAGE_MANIFEST_FILE)
            .writeText(Json.encodeToString(manifest))
        return packageRoot
    }

    private fun CustomVoicePackageResolveResult.success(): ResolvedCustomVoicePackage {
        assertTrue(this is CustomVoicePackageResolveResult.Success)
        return (this as CustomVoicePackageResolveResult.Success).voicePackage
    }

    private fun assertFailureContains(
        result: CustomVoicePackageResolveResult,
        expectedMessage: String,
    ) {
        assertTrue(result is CustomVoicePackageResolveResult.Failure)
        assertTrue(
            (result as CustomVoicePackageResolveResult.Failure).message.contains(expectedMessage),
            "Expected failure to contain '$expectedMessage', got '${result.message}'",
        )
    }

    private fun ByteArray.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(this)
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
