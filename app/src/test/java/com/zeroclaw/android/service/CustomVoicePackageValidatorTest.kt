/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.model.CustomVoiceModelFileManifest
import com.zeroclaw.android.model.CustomVoicePackageManifest
import com.zeroclaw.android.model.CustomVoicePhonemizerManifest
import com.zeroclaw.android.model.CustomVoicePhonemizerType
import com.zeroclaw.android.model.CustomVoiceRuntimeManifest
import com.zeroclaw.android.model.CustomVoiceRuntimeType
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("CustomVoicePackageValidator")
class CustomVoicePackageValidatorTest {
    private val validator = CustomVoicePackageValidator()

    @Test
    fun `validates local English Piper package manifest`() {
        val result = validator.validate(validManifest())

        assertTrue(result.isValid)
        assertTrue(result.issues.isEmpty())
    }

    @Test
    fun `decodes manifest schema and validates it`() {
        val json =
            """
            {
              "formatVersion": 1,
              "packageId": "en.clear.operator",
              "displayName": "Clear Operator",
              "localeTag": "en-US",
              "sampleText": "I am ready locally.",
              "runtime": {
                "type": "piper-v1",
                "sampleRateHz": 22050
              },
              "model": {
                "path": "models/clear.onnx",
                "sizeBytes": 4096,
                "sha256": "$VALID_SHA256",
                "configPath": "models/clear.json"
              }
            }
            """.trimIndent()

        val manifest = Json.decodeFromString<CustomVoicePackageManifest>(json)
        val result = validator.validate(manifest)

        assertTrue(result.isValid)
    }

    @Test
    fun `validates local Piper package with packaged lexicon phonemizer`() {
        val result = validator.validate(validManifest(phonemizer = validPhonemizer()))

        assertTrue(result.isValid)
    }

    @Test
    fun `rejects non English package`() {
        val result = validator.validate(validManifest(localeTag = "hi-IN"))

        assertFalse(result.isValid)
        assertIssue(result, "localeTag")
    }

    @Test
    fun `rejects unsupported or cloud runtime contract`() {
        val result =
            validator.validate(
                validManifest(
                    runtime =
                        CustomVoiceRuntimeManifest(
                            type = "elevenlabs-cloud",
                            sampleRateHz = 22_050,
                            requiresNetwork = true,
                        ),
                ),
            )

        assertFalse(result.isValid)
        assertIssue(result, "runtime.type")
        assertIssue(result, "runtime.requiresNetwork")
    }

    @Test
    fun `rejects external hardware runtime contract`() {
        val result =
            validator.validate(
                validManifest(
                    runtime =
                        CustomVoiceRuntimeManifest(
                            type = CustomVoiceRuntimeType.PIPER_V1,
                            sampleRateHz = 22_050,
                            requiresExternalHardware = true,
                        ),
                ),
            )

        assertFalse(result.isValid)
        assertIssue(result, "runtime.requiresExternalHardware")
    }

    @Test
    fun `rejects invalid sample rates`() {
        assertIssue(
            validator.validate(validManifest(runtime = validRuntime(sampleRateHz = 0))),
            "runtime.sampleRateHz",
        )
        assertIssue(
            validator.validate(validManifest(runtime = validRuntime(sampleRateHz = 96_000))),
            "runtime.sampleRateHz",
        )
    }

    @Test
    fun `rejects blank display and sample text`() {
        val result =
            validator.validate(
                validManifest(
                    displayName = " ",
                    sampleText = "",
                ),
            )

        assertFalse(result.isValid)
        assertIssue(result, "displayName")
        assertIssue(result, "sampleText")
    }

    @Test
    fun `rejects missing model file metadata`() {
        val result =
            validator.validate(
                validManifest(
                    model =
                        CustomVoiceModelFileManifest(
                            path = "",
                            sizeBytes = 0L,
                            sha256 = "not-a-sha",
                        ),
                ),
            )

        assertFalse(result.isValid)
        assertIssue(result, "model.path")
        assertIssue(result, "model.sizeBytes")
        assertIssue(result, "model.sha256")
    }

    @Test
    fun `rejects unsafe absolute traversal and network model paths`() {
        val unsafePaths =
            listOf(
                "../model.onnx",
                "/sdcard/model.onnx",
                "C:\\Users\\voice\\model.onnx",
                "https://voices.example.com/model.onnx",
            )

        unsafePaths.forEach { path ->
            val result = validator.validate(validManifest(model = validModel(path = path)))

            assertFalse(result.isValid, "Expected unsafe path to fail: $path")
            assertIssue(result, "model.path")
        }
    }

    @Test
    fun `rejects unsafe optional config path`() {
        val result =
            validator.validate(
                validManifest(
                    model = validModel(configPath = "file:///sdcard/config.json"),
                ),
            )

        assertFalse(result.isValid)
        assertIssue(result, "model.configPath")
    }

    @Test
    fun `rejects model or config paths that overwrite manifest`() {
        assertIssue(
            validator.validate(validManifest(model = validModel(path = "voice-package.json"))),
            "model.path",
        )
        assertIssue(
            validator.validate(validManifest(model = validModel(configPath = "voice-package.json"))),
            "model.configPath",
        )
    }

    @Test
    fun `rejects unsafe or unsupported phonemizer metadata`() {
        val result =
            validator.validate(
                validManifest(
                    phonemizer =
                        validPhonemizer(
                            type = "cloud-phonemizer",
                            path = "https://voices.example.com/phonemizer.json",
                            sha256 = "bad",
                        ),
                ),
            )

        assertFalse(result.isValid)
        assertIssue(result, "phonemizer.type")
        assertIssue(result, "phonemizer.path")
        assertIssue(result, "phonemizer.sha256")
    }

    private fun validManifest(
        packageId: String = "en.clear.operator",
        displayName: String = "Clear Operator",
        localeTag: String = "en-US",
        sampleText: String = "I am ready locally.",
        runtime: CustomVoiceRuntimeManifest = validRuntime(),
        model: CustomVoiceModelFileManifest = validModel(),
        phonemizer: CustomVoicePhonemizerManifest? = null,
    ): CustomVoicePackageManifest =
        CustomVoicePackageManifest(
            packageId = packageId,
            displayName = displayName,
            localeTag = localeTag,
            sampleText = sampleText,
            runtime = runtime,
            model = model,
            phonemizer = phonemizer,
        )

    private fun validRuntime(sampleRateHz: Int = 22_050): CustomVoiceRuntimeManifest =
        CustomVoiceRuntimeManifest(
            type = CustomVoiceRuntimeType.PIPER_V1,
            sampleRateHz = sampleRateHz,
        )

    private fun validModel(
        path: String = "models/clear.onnx",
        configPath: String? = "models/clear.json",
    ): CustomVoiceModelFileManifest =
        CustomVoiceModelFileManifest(
            path = path,
            sizeBytes = 4_096L,
            sha256 = VALID_SHA256,
            configPath = configPath,
        )

    private fun validPhonemizer(
        type: String = CustomVoicePhonemizerType.PIPER_LEXICON_V1,
        path: String = "phonemizers/en-lexicon.json",
        sizeBytes: Long = 128L,
        sha256: String = VALID_SHA256,
    ): CustomVoicePhonemizerManifest =
        CustomVoicePhonemizerManifest(
            type = type,
            path = path,
            sizeBytes = sizeBytes,
            sha256 = sha256,
        )

    private fun assertIssue(
        result: CustomVoicePackageValidationResult,
        field: String,
    ) {
        assertTrue(
            result.issues.any { issue -> issue.field == field },
            "Expected validation issue for $field, got ${result.issues}",
        )
    }

    private companion object {
        private const val VALID_SHA256 =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
