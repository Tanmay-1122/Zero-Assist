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

@DisplayName("VoiceTurnTrace")
class VoiceTurnTraceTest {
    @Test
    fun `markOnce and complete emit each milestone once`() {
        var nowMs = 1_000L
        val messages = mutableListOf<String>()
        val trace =
            VoiceTurnTrace.forTest(
                id = "voice-test",
                source = "popup",
                startedAtMs = nowMs,
                nowMs = { nowMs },
                sink = { messages += it },
            )

        trace.mark("Mic Tapped", "line\nbreak")
        nowMs += 42
        trace.markOnce("first token")
        trace.markOnce("first token")
        trace.complete("success")
        trace.complete("cancelled")

        assertEquals(3, messages.size)
        assertTrue(messages[0].contains("event=mic_tapped"))
        assertTrue(messages[0].contains("detail=line break"))
        assertTrue(messages[1].contains("event=first_token elapsedMs=42"))
        assertTrue(messages[2].contains("event=turn_completed"))
        assertTrue(messages[2].contains("outcome=success"))
    }

    @Test
    fun `recorder keeps privacy safe waterfall snapshots`() {
        VoiceTurnTraceRecorder.clear()
        try {
            var nowMs = 2_000L
            val trace =
                VoiceTurnTrace.forTest(
                    id = "voice-recorded",
                    source = "popup",
                    startedAtMs = nowMs,
                    nowMs = { nowMs },
                    sink = {},
                    recordEvents = true,
                )

            trace.mark("Mic Tapped", "line\nbreak")
            nowMs += 175L
            trace.complete("success")

            val snapshot = VoiceTurnTraceRecorder.turns.value.single()
            assertEquals("voice-recorded", snapshot.id)
            assertEquals("popup", snapshot.source)
            assertEquals("success", snapshot.outcome)
            assertEquals(175L, snapshot.durationMs)
            assertEquals("mic_tapped", snapshot.events.first().event)
            assertEquals("line break", snapshot.events.first().detail)
            assertEquals("turn_completed", snapshot.events.last().event)
        } finally {
            VoiceTurnTraceRecorder.clear()
        }
    }

    @Test
    fun `benchmark flags slow first audio latency`() {
        val turn =
            VoiceTurnTraceSnapshot(
                id = "voice-slow",
                source = "popup",
                events =
                    listOf(
                        VoiceTurnTraceEvent("turn_started", 0L),
                        VoiceTurnTraceEvent("first_tts_synthesis_start", 1_000L),
                        VoiceTurnTraceEvent("first_tts_audio_ready", 4_100L),
                        VoiceTurnTraceEvent("turn_completed", 4_500L, "outcome=success"),
                    ),
                outcome = "success",
            )

        assertEquals(VoiceTurnBenchmarkStatus.SLOW, turn.benchmark.status)
        assertTrue(turn.benchmark.summary.contains("first audio 3100ms"))
    }

    @Test
    fun `benchmark passes fast completed turns`() {
        val turn =
            VoiceTurnTraceSnapshot(
                id = "voice-fast",
                source = "popup",
                events =
                    listOf(
                        VoiceTurnTraceEvent("turn_started", 0L),
                        VoiceTurnTraceEvent("ui_first_partial", 800L),
                        VoiceTurnTraceEvent("ui_final_transcript", 2_000L),
                        VoiceTurnTraceEvent("first_tts_synthesis_start", 2_100L),
                        VoiceTurnTraceEvent("first_tts_audio_ready", 2_600L),
                        VoiceTurnTraceEvent("turn_completed", 3_000L, "outcome=success"),
                    ),
                outcome = "success",
            )

        assertEquals(VoiceTurnBenchmarkStatus.FAST, turn.benchmark.status)
        assertEquals("On target", turn.benchmark.summary)
    }
}
