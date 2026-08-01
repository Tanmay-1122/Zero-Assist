/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("PiperVoiceConfigParser")
class PiperVoiceConfigParserTest {
    private val parser = PiperVoiceConfigParser()

    @Test
    fun `parse reads piper text config`() {
        val result = parser.parse(textConfig())

        assertTrue(result is PiperVoiceConfigParseResult.Success)
        val config = (result as PiperVoiceConfigParseResult.Success).config
        assertEquals("text", config.phonemeType)
        assertEquals(16_000, config.sampleRateHz)
        assertEquals(listOf(10L), config.phonemeIdMap["h"])
        assertEquals(0.7f, config.noiseScale)
        assertEquals(1.1f, config.lengthScale)
        assertEquals(0.9f, config.noiseW)
        assertEquals(2L, config.speakerId)
    }

    @Test
    fun `parse fails when phoneme map is missing`() {
        val result = parser.parse("""{"phoneme_type":"text"}""")

        assertTrue(result is PiperVoiceConfigParseResult.Failure)
        assertEquals(
            "Piper voice config is missing phoneme_id_map.",
            (result as PiperVoiceConfigParseResult.Failure).message,
        )
    }

    @Test
    fun `parse reads piper inference block scales`() {
        val result =
            parser.parse(
                textConfig(
                    topLevelScales = false,
                    inferenceBlock =
                        """
                        "inference": {
                          "noise_scale": 0.5,
                          "length_scale": 1.4,
                          "noise_w": 0.6
                        }
                        """.trimIndent(),
                ),
            )

        assertTrue(result is PiperVoiceConfigParseResult.Success)
        val config = (result as PiperVoiceConfigParseResult.Success).config
        assertEquals(0.5f, config.noiseScale)
        assertEquals(1.4f, config.lengthScale)
        assertEquals(0.6f, config.noiseW)
    }

    @Test
    fun `parse reads espeak voice metadata`() {
        val result =
            parser.parse(
                textConfig(
                    phonemeType = "espeak",
                    espeakBlock =
                        """
                        "espeak": {
                          "voice": "en-us"
                        }
                        """.trimIndent(),
                ),
            )

        assertTrue(result is PiperVoiceConfigParseResult.Success)
        val config = (result as PiperVoiceConfigParseResult.Success).config
        assertEquals("espeak", config.phonemeType)
        assertEquals("en-us", config.espeakVoice)
    }

    private fun textConfig(
        phonemeType: String = "text",
        topLevelScales: Boolean = true,
        inferenceBlock: String? = null,
        espeakBlock: String? = null,
    ): String {
        val topLevelScaleFields =
            if (topLevelScales) {
                """
                  "noise_scale": 0.7,
                  "length_scale": 1.1,
                  "noise_w": 0.9,
                """.trimIndent()
            } else {
                ""
            }
        val inferenceSection =
            inferenceBlock
                ?.let { "$it," }
                .orEmpty()
        val espeakSection =
            espeakBlock
                ?.let { "$it," }
                .orEmpty()
        return (
        """
        {
          "phoneme_type": "$phonemeType",
          "phoneme_id_map": {
            "_": [0],
            "^": [1],
            "$": [2],
            "h": [10],
            "i": [11]
          },
          "audio": {
            "sample_rate": 16000
          },
          $topLevelScaleFields
          $inferenceSection
          $espeakSection
          "speaker_id": 2
        }
        """.trimIndent()
            )
    }
}
