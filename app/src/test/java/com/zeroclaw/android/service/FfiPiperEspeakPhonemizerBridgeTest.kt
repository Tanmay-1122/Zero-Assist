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

@DisplayName("FfiPiperEspeakPhonemizerBridge")
class FfiPiperEspeakPhonemizerBridgeTest {
    @Test
    fun `readiness returns native unavailable detail`() {
        val bridge =
            FfiPiperEspeakPhonemizerBridge(
                statusProvider = {
                    NativePiperPhonemizerStatus(
                        available = false,
                        engine = "none",
                        detail = "Native phonemizer is not bundled.",
                    )
                },
            )

        val readiness = bridge.readiness(espeakConfig())

        assertTrue(readiness is PiperPhonemizerReadiness.Unavailable)
        assertEquals(
            "Native phonemizer is not bundled.",
            (readiness as PiperPhonemizerReadiness.Unavailable).message,
        )
    }

    @Test
    fun `phonemize fails closed when native status is unavailable`() {
        val bridge =
            FfiPiperEspeakPhonemizerBridge(
                statusProvider = {
                    NativePiperPhonemizerStatus(
                        available = false,
                        engine = "none",
                        detail = "Native phonemizer is not bundled.",
                    )
                },
            )

        val result = bridge.phonemize("hello", espeakConfig())

        assertTrue(result is PiperPhonemeEncodeResult.Failure)
        assertEquals(
            "Native phonemizer is not bundled.",
            (result as PiperPhonemeEncodeResult.Failure).message,
        )
    }

    @Test
    fun `phonemize still fails until native id binding exists`() {
        val bridge =
            FfiPiperEspeakPhonemizerBridge(
                statusProvider = {
                    NativePiperPhonemizerStatus(
                        available = true,
                        engine = "piper-phonemize",
                        detail = "ready",
                    )
                },
            )

        val result = bridge.phonemize("hello", espeakConfig())

        assertTrue(result is PiperPhonemeEncodeResult.Failure)
        assertEquals(
            "Native Piper/eSpeak phonemizer 'piper-phonemize' is available, but phoneme ID binding is not implemented yet.",
            (result as PiperPhonemeEncodeResult.Failure).message,
        )
    }

    private fun espeakConfig(): PiperVoiceConfig =
        PiperVoiceConfig(
            phonemeType = "espeak",
            espeakVoice = "en-us",
            phonemeIdMap =
                mapOf(
                    "_" to listOf(0L),
                    "^" to listOf(1L),
                    "$" to listOf(2L),
                    "h" to listOf(10L),
                ),
            sampleRateHz = 22_050,
            noiseScale = 0.667f,
            lengthScale = 1f,
            noiseW = 0.8f,
            speakerId = null,
        )
}
