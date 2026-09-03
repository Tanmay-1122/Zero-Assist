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
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@DisplayName("PiperPackageLexiconPhonemizer")
class PiperPackageLexiconPhonemizerTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun `local piper phonemizer uses package lexicon for espeak voices`() {
        val voicePackage =
            writeVoicePackage(
                lexiconJson =
                    """
                    {
                      "words": {
                        "hello": ["h", "i"]
                      }
                    }
                    """.trimIndent(),
            )

        val result =
            LocalPiperPhonemizer()
                .encode("Hello", espeakConfig(), voicePackage)

        assertTrue(result is PiperPhonemeEncodeResult.Success)
        assertArrayEquals(
            longArrayOf(1L, 0L, 10L, 0L, 11L, 0L, 2L),
            (result as PiperPhonemeEncodeResult.Success).inputIds,
        )
    }

    @Test
    fun `package lexicon resolves hard g through Piper script g ids`() {
        val voicePackage =
            writeVoicePackage(
                lexiconJson =
                    """
                    {
                      "words": {
                        "sing": ["s", "i", "n", "g"]
                      }
                    }
                    """.trimIndent(),
            )

        val result =
            PiperPackageLexiconPhonemizer()
                .encode(
                    "sing",
                    espeakConfig(
                        extraPhonemeIds =
                            mapOf(
                                "s" to listOf(12L),
                                "n" to listOf(13L),
                                "\u0261" to listOf(14L),
                            ),
                    ),
                    voicePackage,
                )

        assertTrue(result is PiperPhonemeEncodeResult.Success)
        assertArrayEquals(
            longArrayOf(1L, 0L, 12L, 0L, 11L, 0L, 13L, 0L, 14L, 0L, 2L),
            (result as PiperPhonemeEncodeResult.Success).inputIds,
        )
    }

    @Test
    fun `local piper phonemizer falls back to English rules when lexicon misses a word`() {
        val voicePackage =
            writeVoicePackage(
                lexiconJson =
                    """
                    {
                      "words": {
                        "hello": ["h", "i"]
                      }
                    }
                    """.trimIndent(),
            )

        val result =
            LocalPiperPhonemizer()
                .encode(
                    "Sing for me",
                    espeakConfig(
                        extraPhonemeIds =
                            mapOf(
                                "l" to listOf(12L),
                                "m" to listOf(13L),
                                "n" to listOf(14L),
                                "s" to listOf(15L),
                                "t" to listOf(16L),
                                "\u0261" to listOf(17L),
                                "ɪ" to listOf(18L),
                                "f" to listOf(19L),
                                "ɔ" to listOf(20L),
                                "ɹ" to listOf(21L),
                            ),
                    ),
                    voicePackage,
                )

        assertTrue(result is PiperPhonemeEncodeResult.Success)
        assertArrayEquals(
            longArrayOf(
                1L,
                0L,
                15L,
                0L,
                18L,
                0L,
                14L,
                0L,
                17L,
                0L,
                19L,
                0L,
                20L,
                0L,
                21L,
                0L,
                13L,
                0L,
                11L,
                0L,
                2L,
            ),
            (result as PiperPhonemeEncodeResult.Success).inputIds,
        )
    }

    @Test
    fun `package lexicon fails locally when a word is missing`() {
        val voicePackage =
            writeVoicePackage(
                lexiconJson =
                    """
                    {
                      "words": {
                        "hello": ["h", "i"]
                      }
                    }
                    """.trimIndent(),
            )

        val result =
            PiperPackageLexiconPhonemizer()
                .encode("unknown", espeakConfig(), voicePackage)

        assertTrue(result is PiperPhonemeEncodeResult.Failure)
        assertEquals(
            "Piper lexicon cannot encode 'unknown' locally.",
            (result as PiperPhonemeEncodeResult.Failure).message,
        )
    }

    @Test
    fun `package lexicon reports invalid local json without network fallback`() {
        val voicePackage = writeVoicePackage(lexiconJson = "{not json")

        val readiness =
            PiperPackageLexiconPhonemizer()
                .readiness(espeakConfig(), voicePackage)

        assertTrue(readiness is PiperPhonemizerReadiness.Unavailable)
        assertEquals(
            "Piper lexicon phonemizer file is not valid JSON.",
            (readiness as PiperPhonemizerReadiness.Unavailable).message,
        )
    }

    private fun writeVoicePackage(lexiconJson: String): ResolvedCustomVoicePackage {
        val packageRoot = File(tempDir, "voice-${System.nanoTime()}")
        val modelFile = File(packageRoot, "models/voice.onnx")
        val configFile = File(packageRoot, "models/voice.json")
        val phonemizerFile = File(packageRoot, "phonemizers/en-lexicon.json")
        modelFile.parentFile?.mkdirs()
        phonemizerFile.parentFile?.mkdirs()
        modelFile.writeBytes(byteArrayOf(1, 2, 3))
        configFile.writeText("{}")
        phonemizerFile.writeText(lexiconJson)

        val manifest =
            CustomVoicePackageManifest(
                packageId = "en.lexicon.test",
                displayName = "Lexicon Test",
                localeTag = "en-US",
                sampleText = "Hello.",
                runtime =
                    CustomVoiceRuntimeManifest(
                        type = CustomVoiceRuntimeType.PIPER_V1,
                        sampleRateHz = 22_050,
                    ),
                model =
                    CustomVoiceModelFileManifest(
                        path = "models/voice.onnx",
                        sizeBytes = modelFile.length(),
                        sha256 = modelFile.readBytes().sha256(),
                        configPath = "models/voice.json",
                    ),
                phonemizer =
                    CustomVoicePhonemizerManifest(
                        type = CustomVoicePhonemizerType.PIPER_LEXICON_V1,
                        path = "phonemizers/en-lexicon.json",
                        sizeBytes = phonemizerFile.length(),
                        sha256 = phonemizerFile.readBytes().sha256(),
                    ),
            )
        val manifestFile = File(packageRoot, CUSTOM_VOICE_PACKAGE_MANIFEST_FILE)
        manifestFile.writeText("{}")

        return ResolvedCustomVoicePackage(
            manifest = manifest,
            packageRoot = packageRoot,
            manifestFile = manifestFile,
            modelFile = modelFile,
            configFile = configFile,
            phonemizerFile = phonemizerFile,
        )
    }

    private fun espeakConfig(
        extraPhonemeIds: Map<String, List<Long>> = emptyMap(),
    ): PiperVoiceConfig =
        PiperVoiceConfig(
            phonemeType = "espeak",
            espeakVoice = "en-us",
            phonemeIdMap =
                mapOf(
                    "_" to listOf(0L),
                    "^" to listOf(1L),
                    "$" to listOf(2L),
                    "h" to listOf(10L),
                    "i" to listOf(11L),
                ) + extraPhonemeIds,
            sampleRateHz = 22_050,
            noiseScale = 0.667f,
            lengthScale = 1f,
            noiseW = 0.8f,
            speakerId = null,
        )

    private fun ByteArray.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(this)
        return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
