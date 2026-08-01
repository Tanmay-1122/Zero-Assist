/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.SystemClock
import android.util.Log
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext

interface CustomVoiceAudioPlayer {
    suspend fun play(audio: CustomVoicePcmAudio): SpeechSynthesisResult

    suspend fun playStream(
        chunks: Flow<CustomVoicePcmAudio>,
        trace: VoiceTurnTrace = VoiceTurnTrace.noop(),
    ): SpeechSynthesisResult {
        var played = false
        var failure: SpeechSynthesisResult? = null
        chunks.collect { chunk ->
            if (failure == null) {
                played = true
                when (val result = play(chunk)) {
                    SpeechSynthesisResult.Completed -> Unit
                    SpeechSynthesisResult.Cancelled,
                    is SpeechSynthesisResult.Failed -> failure = result
                }
            }
        }
        return failure
            ?: if (played) {
                SpeechSynthesisResult.Completed
            } else {
                SpeechSynthesisResult.Failed("Custom voice runtime produced no audio.")
            }
    }

    fun stop()
}

class AndroidPcmAudioPlayer : CustomVoiceAudioPlayer {
    @Volatile
    private var activeTrack: AudioTrack? = null

    override suspend fun play(audio: CustomVoicePcmAudio): SpeechSynthesisResult =
        playStream(flowOf(audio), VoiceTurnTrace.noop())

    override suspend fun playStream(
        chunks: Flow<CustomVoicePcmAudio>,
        trace: VoiceTurnTrace,
    ): SpeechSynthesisResult =
        withContext(Dispatchers.IO) {
            var track: AudioTrack? = null
            var sampleRateHz = 0
            var framesWritten = 0
            var chunksWritten = 0
            try {
                chunks.collect { audio ->
                    if (audio.sampleRateHz <= 0 || audio.pcm16Mono.isEmpty()) {
                        throw PcmPlaybackException("Custom voice runtime produced empty audio.")
                    }

                    if (sampleRateHz != 0 && audio.sampleRateHz != sampleRateHz) {
                        throw PcmPlaybackException(
                            "Custom voice stream changed sample rate during playback.",
                        )
                    }
                    sampleRateHz = audio.sampleRateHz

                    val normalized = PcmSpeechLoudnessNormalizer.normalize(audio.pcm16Mono)
                    val playbackPcm = normalized.pcm16Mono
                    val pcmStats = normalized.after
                    val active =
                        track
                            ?: createStreamingTrack(
                                sampleRateHz = sampleRateHz,
                                pcmSizeBytes = playbackPcm.size,
                            ).also { created ->
                                track = created
                                activeTrack = created
                                created.setVolume(1f)
                                created.play()
                                trace.markOnce("playback_started", "driver=custom_pcm_stream")
                            }
                    chunksWritten += 1
                    Log.d(
                        TAG,
                        "Streaming PCM chunk index=$chunksWritten sampleRate=$sampleRateHz " +
                            "bytes=${playbackPcm.size} frames=${pcmStats.frames} " +
                            "durationMs=${pcmStats.durationMs(sampleRateHz)} " +
                            "peak=${pcmStats.peak} rms=${pcmStats.rms} " +
                            "gain=${"%.2f".format(normalized.gain)} " +
                            "peakBefore=${normalized.before.peak} rmsBefore=${normalized.before.rms}",
                    )
                    framesWritten += active.writeAll(playbackPcm)
                }
                val active = track
                if (active == null || chunksWritten == 0) {
                    return@withContext SpeechSynthesisResult.Failed(
                        "Custom voice runtime produced no audio.",
                    )
                }
                waitForPlaybackDrain(active, framesWritten, sampleRateHz)
                SpeechSynthesisResult.Completed
            } catch (error: PcmPlaybackException) {
                SpeechSynthesisResult.Failed(error.message ?: "Custom voice playback failed.")
            } finally {
                activeTrack = null
                track?.let { active ->
                    runCatching { active.stop() }
                    active.release()
                }
            }
        }

    override fun stop() {
        activeTrack?.stop()
    }

    private fun createStreamingTrack(
        sampleRateHz: Int,
        pcmSizeBytes: Int,
    ): AudioTrack {
        val minBufferSize =
            AudioTrack.getMinBufferSize(
                sampleRateHz,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        if (minBufferSize <= 0) {
            throw PcmPlaybackException(
                "Android audio output does not support this voice sample rate.",
            )
        }
        val bufferSize =
            playbackBufferSize(
                minBufferSize = minBufferSize,
                sampleRateHz = sampleRateHz,
                pcmSizeBytes = pcmSizeBytes,
            )
        Log.d(
            TAG,
            "Starting streaming PCM playback sampleRate=$sampleRateHz " +
                "bufferSize=$bufferSize minBufferSize=$minBufferSize",
        )
        return AudioTrack
            .Builder()
            .setAudioAttributes(
                AudioAttributes
                    .Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build(),
            ).setAudioFormat(
                AudioFormat
                    .Builder()
                    .setSampleRate(sampleRateHz)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            ).setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
    }

    private fun AudioTrack.writeAll(pcm16Mono: ByteArray): Int {
        var written = 0
        while (written < pcm16Mono.size) {
            val count =
                write(
                    pcm16Mono,
                    written,
                    pcm16Mono.size - written,
                )
            if (count < 0) {
                throw PcmPlaybackException("Android audio output rejected custom voice audio.")
            }
            written += count
        }
        return written / PCM_16_MONO_BYTES_PER_FRAME
    }

    private suspend fun waitForPlaybackDrain(
        track: AudioTrack,
        framesWritten: Int,
        sampleRateHz: Int,
    ) {
        val expectedDurationMs =
            ((framesWritten.toLong() * 1_000L) / sampleRateHz).coerceAtLeast(1L)
        val timeoutAt = SystemClock.elapsedRealtime() + expectedDurationMs + PLAYBACK_DRAIN_GRACE_MS
        var playbackHead = track.playbackHeadPosition
        while (
            track.playState == AudioTrack.PLAYSTATE_PLAYING &&
            playbackHead < framesWritten &&
            SystemClock.elapsedRealtime() < timeoutAt
        ) {
            delay(PLAYBACK_DRAIN_POLL_MS)
            playbackHead = track.playbackHeadPosition
        }
        Log.d(
            TAG,
            "PCM playback drain finished head=$playbackHead target=$framesWritten " +
                "state=${track.playState} timeout=${SystemClock.elapsedRealtime() >= timeoutAt}",
        )
    }

    private fun playbackBufferSize(
        minBufferSize: Int,
        sampleRateHz: Int,
        pcmSizeBytes: Int,
    ): Int {
        val targetBytes =
            (sampleRateHz * PCM_16_MONO_BYTES_PER_FRAME * STREAM_BUFFER_TARGET_MS) / 1_000
        return max(
            minBufferSize,
            min(
                pcmSizeBytes,
                max(minBufferSize * 2, targetBytes),
            ),
        )
    }

    private fun PcmLoudnessStats.durationMs(sampleRateHz: Int): Long =
        if (sampleRateHz <= 0) {
            0L
        } else {
            (frames.toLong() * 1_000L) / sampleRateHz
        }

    private class PcmPlaybackException(message: String) : RuntimeException(message)

    private companion object {
        private const val TAG = "AndroidPcmAudioPlayer"
        private const val PCM_16_MONO_BYTES_PER_FRAME = 2
        private const val STREAM_BUFFER_TARGET_MS = 200
        private const val PLAYBACK_DRAIN_GRACE_MS = 1_500L
        private const val PLAYBACK_DRAIN_POLL_MS = 25L
    }
}
