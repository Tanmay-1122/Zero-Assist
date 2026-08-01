/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class PcmLoudnessStats(
    val frames: Int,
    val peak: Int,
    val rms: Int,
)

internal data class PcmLoudnessNormalizationResult(
    val pcm16Mono: ByteArray,
    val gain: Float,
    val before: PcmLoudnessStats,
    val after: PcmLoudnessStats,
)

internal object PcmSpeechLoudnessNormalizer {
    private const val PCM_16_MONO_BYTES_PER_FRAME = 2
    private const val TARGET_SPEECH_PEAK = 18_000
    private const val MAX_SPEECH_GAIN = 3.5f
    private const val MIN_GAIN_CHANGE = 1.05f

    fun normalize(pcm16Mono: ByteArray): PcmLoudnessNormalizationResult {
        val before = pcm16Mono.pcm16LoudnessStats()
        val gain = speechGain(before.peak)
        if (gain < MIN_GAIN_CHANGE) {
            return PcmLoudnessNormalizationResult(
                pcm16Mono = pcm16Mono,
                gain = 1f,
                before = before,
                after = before,
            )
        }

        val normalized = pcm16Mono.copyOf()
        var index = 0
        while (index + 1 < normalized.size) {
            val sample = normalized.readPcm16Le(index)
            normalized.writePcm16Le(
                index,
                (sample * gain)
                    .roundToInt()
                    .coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()),
            )
            index += PCM_16_MONO_BYTES_PER_FRAME
        }
        return PcmLoudnessNormalizationResult(
            pcm16Mono = normalized,
            gain = gain,
            before = before,
            after = normalized.pcm16LoudnessStats(),
        )
    }

    private fun speechGain(peak: Int): Float {
        if (peak <= 0 || peak >= TARGET_SPEECH_PEAK) {
            return 1f
        }
        return (TARGET_SPEECH_PEAK.toFloat() / peak.toFloat())
            .coerceAtMost(MAX_SPEECH_GAIN)
    }
}

internal fun ByteArray.pcm16LoudnessStats(): PcmLoudnessStats {
    var peak = 0
    var sumSquares = 0.0
    var frames = 0
    var index = 0
    while (index + 1 < size) {
        val sample = readPcm16Le(index)
        val magnitude = abs(sample)
        if (magnitude > peak) {
            peak = magnitude
        }
        sumSquares += sample.toDouble() * sample.toDouble()
        frames += 1
        index += 2
    }
    val rms =
        if (frames == 0) {
            0
        } else {
            sqrt(sumSquares / frames.toDouble()).toInt()
        }
    return PcmLoudnessStats(
        frames = frames,
        peak = peak,
        rms = rms,
    )
}

private fun ByteArray.readPcm16Le(index: Int): Int {
    val low = this[index].toInt() and 0xff
    val high = this[index + 1].toInt()
    return (high shl 8) or low
}

private fun ByteArray.writePcm16Le(
    index: Int,
    sample: Int,
) {
    this[index] = (sample and 0xff).toByte()
    this[index + 1] = ((sample shr 8) and 0xff).toByte()
}
