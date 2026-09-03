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
import java.io.File
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

@DisplayName("PiperTextPhonemeEncoder")
class PiperTextPhonemeEncoderTest {
    private val encoder = PiperTextPhonemeEncoder()

    @Test
    fun `encode creates piper text ids with separators`() {
        val result = encoder.encode("Hi", textConfig())

        assertTrue(result is PiperPhonemeEncodeResult.Success)
        assertArrayEquals(
            longArrayOf(1L, 0L, 10L, 0L, 11L, 0L, 2L),
            (result as PiperPhonemeEncodeResult.Success).inputIds,
        )
    }

    @Test
    fun `encode fails for piper espeak config until local phonemizer exists`() {
        val result = encoder.encode("hi", textConfig(phonemeType = "espeak"))

        assertTrue(result is PiperPhonemeEncodeResult.Failure)
        assertEquals(
            "This Piper voice needs an offline eSpeak phonemizer or a local package lexicon before it can speak locally.",
            (result as PiperPhonemeEncodeResult.Failure).message,
        )
    }

    @Test
    fun `readiness reports missing offline espeak phonemizer`() {
        val readiness = encoder.readiness(textConfig(phonemeType = "espeak"))

        assertTrue(readiness is PiperPhonemizerReadiness.Unavailable)
        assertEquals(
            "This Piper voice needs an offline eSpeak phonemizer or a local package lexicon before it can speak locally.",
            (readiness as PiperPhonemizerReadiness.Unavailable).message,
        )
    }

    @Test
    fun `local phonemizer uses rule fallback for English espeak voices`() {
        val result =
            LocalPiperPhonemizer()
                .encode("Hello what's your name", englishRuleConfig())

        assertTrue(result is PiperPhonemeEncodeResult.Success)
        assertArrayEquals(
            longArrayOf(
                1L,
                0L,
                10L,
                0L,
                12L,
                0L,
                13L,
                0L,
                14L,
                0L,
                15L,
                0L,
                16L,
                0L,
                17L,
                0L,
                18L,
                0L,
                19L,
                0L,
                20L,
                0L,
                21L,
                0L,
                22L,
                0L,
                23L,
                0L,
                24L,
                0L,
                25L,
                0L,
                26L,
                0L,
                2L,
            ),
            (result as PiperPhonemeEncodeResult.Success).inputIds,
        )
    }

    @Test
    fun `local phonemizer maps hard g to Piper script g ids`() {
        val result = LocalPiperPhonemizer().encode("go", englishRuleConfig())

        assertTrue(result is PiperPhonemeEncodeResult.Success)
        assertArrayEquals(
            longArrayOf(1L, 0L, 27L, 0L, 14L, 0L, 15L, 0L, 2L),
            (result as PiperPhonemeEncodeResult.Success).inputIds,
        )
    }

    @Test
    fun `local phonemizer decomposes ng using Piper script g fallback`() {
        val result = LocalPiperPhonemizer().encode("sing", englishRuleConfig())

        assertTrue(result is PiperPhonemeEncodeResult.Success)
        assertArrayEquals(
            longArrayOf(1L, 0L, 19L, 0L, 25L, 0L, 23L, 0L, 27L, 0L, 2L),
            (result as PiperPhonemeEncodeResult.Success).inputIds,
        )
    }

    @Test
    fun `native espeak phonemizer requires espeak voice metadata`() {
        val readiness =
            NativePiperEspeakPhonemizer(FakeEspeakBridge(longArrayOf(1L)))
                .readiness(textConfig(phonemeType = "espeak"))

        assertTrue(readiness is PiperPhonemizerReadiness.Unavailable)
        assertEquals(
            "Piper eSpeak voice config is missing its eSpeak voice.",
            (readiness as PiperPhonemizerReadiness.Unavailable).message,
        )
    }

    @Test
    fun `local phonemizer can use injected native espeak bridge`() {
        val bridge = FakeEspeakBridge(longArrayOf(1L, 99L, 2L))
        val phonemizer =
            LocalPiperPhonemizer(
                espeakPhonemizer = NativePiperEspeakPhonemizer(bridge),
            )

        val result =
            phonemizer.encode(
                "  Hello  ",
                textConfig(phonemeType = "espeak", espeakVoice = "en-us"),
            )

        assertTrue(result is PiperPhonemeEncodeResult.Success)
        assertArrayEquals(
            longArrayOf(1L, 99L, 2L),
            (result as PiperPhonemeEncodeResult.Success).inputIds,
        )
        assertEquals("Hello", bridge.lastText)
        assertEquals("en-us", bridge.lastEspeakVoice)
    }

    @Test
    fun `local phonemizer uses package lexicon for espeak package`(
        @TempDir tempDir: File,
    ) {
        val lexiconFile =
            tempDir.resolve("lexicon.json").apply {
                writeText(
                    """
                    {
                      "words": {
                        "hello": ["HH", "AH", "L", "OW"]
                      }
                    }
                    """.trimIndent(),
                )
            }
        val voicePackage = voicePackage(tempDir, lexiconFile)
        val config =
            textConfig(
                phonemeType = "espeak",
                espeakVoice = "en-us",
                extraPhonemeIds =
                    mapOf(
                        "HH" to listOf(12L),
                        "AH" to listOf(13L),
                        "L" to listOf(14L),
                        "OW" to listOf(15L),
                    ),
            )

        val result = LocalPiperPhonemizer().encode("Hello", config, voicePackage)

        assertTrue(result is PiperPhonemeEncodeResult.Success)
        assertArrayEquals(
            longArrayOf(1L, 0L, 12L, 0L, 13L, 0L, 14L, 0L, 15L, 0L, 2L),
            (result as PiperPhonemeEncodeResult.Success).inputIds,
        )
    }

    @Test
    fun `native espeak phonemizer rejects empty native id output`() {
        val result =
            NativePiperEspeakPhonemizer(FakeEspeakBridge(longArrayOf()))
                .encode("hi", textConfig(phonemeType = "espeak", espeakVoice = "en-us"))

        assertTrue(result is PiperPhonemeEncodeResult.Failure)
        assertEquals(
            "Piper eSpeak phonemizer returned no phoneme ids.",
            (result as PiperPhonemeEncodeResult.Failure).message,
        )
    }

    @Test
    fun `native espeak phonemizer converts bridge exceptions to local failures`() {
        val result =
            NativePiperEspeakPhonemizer(ThrowingEspeakBridge())
                .encode("hi", textConfig(phonemeType = "espeak", espeakVoice = "en-us"))

        assertTrue(result is PiperPhonemeEncodeResult.Failure)
        assertEquals(
            "Piper eSpeak phonemizer is unavailable locally: native library missing",
            (result as PiperPhonemeEncodeResult.Failure).message,
        )
    }

    @Test
    fun `local phonemizer rejects unsupported phoneme type`() {
        val readiness = LocalPiperPhonemizer().readiness(textConfig(phonemeType = "ipa"))

        assertTrue(readiness is PiperPhonemizerReadiness.Unavailable)
        assertEquals(
            "Unsupported Piper phoneme type 'ipa' for local playback.",
            (readiness as PiperPhonemizerReadiness.Unavailable).message,
        )
    }

    @Test
    fun `encode fails when text has no mapped symbols`() {
        val result = encoder.encode("???", textConfig())

        assertTrue(result is PiperPhonemeEncodeResult.Failure)
        assertEquals(
            "Piper voice config cannot encode this text locally.",
            (result as PiperPhonemeEncodeResult.Failure).message,
        )
    }

    private fun textConfig(
        phonemeType: String = "text",
        espeakVoice: String? = null,
        extraPhonemeIds: Map<String, List<Long>> = emptyMap(),
    ): PiperVoiceConfig =
        PiperVoiceConfig(
            phonemeType = phonemeType,
            espeakVoice = espeakVoice,
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

    private fun englishRuleConfig(
        extraPhonemeIds: Map<String, List<Long>> = emptyMap(),
    ): PiperVoiceConfig =
        textConfig(
            phonemeType = "espeak",
            espeakVoice = "en-us",
            extraPhonemeIds =
                mapOf(
                    "ɛ" to listOf(12L),
                    "l" to listOf(13L),
                    "o" to listOf(14L),
                    "ʊ" to listOf(15L),
                    "w" to listOf(16L),
                    "ʌ" to listOf(17L),
                    "t" to listOf(18L),
                    "s" to listOf(19L),
                    "j" to listOf(20L),
                    "ɔ" to listOf(21L),
                    "ɹ" to listOf(22L),
                    "n" to listOf(23L),
                    "e" to listOf(24L),
                    "ɪ" to listOf(25L),
                    "m" to listOf(26L),
                    "\u0261" to listOf(27L),
                ) + extraPhonemeIds,
        )

    private fun voicePackage(
        packageRoot: File,
        lexiconFile: File,
    ): ResolvedCustomVoicePackage {
        val modelFile = packageRoot.resolve("model.onnx").apply { writeBytes(byteArrayOf()) }
        val manifest =
            CustomVoicePackageManifest(
                packageId = "test.voice",
                displayName = "Test Voice",
                localeTag = "en-US",
                sampleText = "Hello",
                runtime =
                    CustomVoiceRuntimeManifest(
                        type = CustomVoiceRuntimeType.PIPER_V1,
                        sampleRateHz = 22_050,
                    ),
                model =
                    CustomVoiceModelFileManifest(
                        path = modelFile.name,
                        sizeBytes = 0,
                        sha256 = "unused",
                    ),
                phonemizer =
                    CustomVoicePhonemizerManifest(
                        type = CustomVoicePhonemizerType.PIPER_LEXICON_V1,
                        path = lexiconFile.name,
                        sizeBytes = lexiconFile.length(),
                        sha256 = "unused",
                    ),
            )
        return ResolvedCustomVoicePackage(
            manifest = manifest,
            packageRoot = packageRoot,
            manifestFile = packageRoot.resolve("voice-package.json"),
            modelFile = modelFile,
            configFile = null,
            phonemizerFile = lexiconFile,
        )
    }

    private class FakeEspeakBridge(
        private val inputIds: LongArray,
    ) : PiperEspeakPhonemizerBridge {
        var lastText: String? = null
            private set
        var lastEspeakVoice: String? = null
            private set

        override fun readiness(config: PiperVoiceConfig): PiperPhonemizerReadiness =
            PiperPhonemizerReadiness.Ready

        override fun phonemize(
            text: String,
            config: PiperVoiceConfig,
        ): PiperPhonemeEncodeResult {
            lastText = text
            lastEspeakVoice = config.espeakVoice
            return PiperPhonemeEncodeResult.Success(inputIds)
        }
    }

    private class ThrowingEspeakBridge : PiperEspeakPhonemizerBridge {
        override fun readiness(config: PiperVoiceConfig): PiperPhonemizerReadiness =
            throw IllegalStateException("native library missing")

        override fun phonemize(
            text: String,
            config: PiperVoiceConfig,
        ): PiperPhonemeEncodeResult =
            throw IllegalStateException("native call should not run")
    }
}
