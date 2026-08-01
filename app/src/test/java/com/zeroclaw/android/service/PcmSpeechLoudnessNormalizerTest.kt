/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("PcmSpeechLoudnessNormalizer")
class PcmSpeechLoudnessNormalizerTest {
    @Test
    fun `boosts quiet speech without changing duration`() {
        val pcm = pcm16Mono(1_000, -2_000, 4_000, -6_000)

        val result = PcmSpeechLoudnessNormalizer.normalize(pcm)

        assertEquals(4, result.before.frames)
        assertEquals(6_000, result.before.peak)
        assertEquals(4, result.after.frames)
        assertEquals(18_000, result.after.peak)
        assertTrue(result.after.rms > result.before.rms)
        assertEquals(3f, result.gain)
    }

    @Test
    fun `does not boost audio that is already loud enough`() {
        val pcm = pcm16Mono(18_000, -12_000)

        val result = PcmSpeechLoudnessNormalizer.normalize(pcm)

        assertSame(pcm, result.pcm16Mono)
        assertEquals(1f, result.gain)
        assertEquals(result.before, result.after)
    }

    @Test
    fun `caps extreme gain for very quiet audio`() {
        val pcm = pcm16Mono(100, -200)

        val result = PcmSpeechLoudnessNormalizer.normalize(pcm)

        assertEquals(3.5f, result.gain)
        assertEquals(700, result.after.peak)
    }

    private fun pcm16Mono(vararg samples: Int): ByteArray {
        val bytes = ByteArray(samples.size * 2)
        samples.forEachIndexed { index, sample ->
            val byteIndex = index * 2
            bytes[byteIndex] = (sample and 0xff).toByte()
            bytes[byteIndex + 1] = ((sample shr 8) and 0xff).toByte()
        }
        return bytes
    }
}
