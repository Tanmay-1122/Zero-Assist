/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import android.os.SystemClock
import android.util.Log
import java.util.Collections
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VoiceTurnTraceEvent(
    val event: String,
    val elapsedMs: Long,
    val detail: String? = null,
)

data class VoiceTurnTraceSnapshot(
    val id: String,
    val source: String,
    val events: List<VoiceTurnTraceEvent>,
    val outcome: String? = null,
) {
    val durationMs: Long
        get() = events.lastOrNull()?.elapsedMs ?: 0L

    val latestEvent: VoiceTurnTraceEvent?
        get() = events.lastOrNull()

    val benchmark: VoiceTurnBenchmarkResult
        get() = VoiceTurnBenchmark.evaluate(this)
}

enum class VoiceTurnBenchmarkStatus {
    FAST,
    SLOW,
    FAILED,
    INCOMPLETE,
}

data class VoiceTurnBenchmarkResult(
    val status: VoiceTurnBenchmarkStatus,
    val summary: String,
)

object VoiceTurnBenchmark {
    fun evaluate(turn: VoiceTurnTraceSnapshot): VoiceTurnBenchmarkResult {
        val outcome = turn.outcome
        if (outcome == null) {
            return VoiceTurnBenchmarkResult(
                status = VoiceTurnBenchmarkStatus.INCOMPLETE,
                summary = "Recording",
            )
        }
        if (outcome != "success" &&
            outcome != "fast_path_spoken_response" &&
            outcome != "command_completed"
        ) {
            return VoiceTurnBenchmarkResult(
                status = VoiceTurnBenchmarkStatus.FAILED,
                summary = outcome,
            )
        }

        val slowReasons = mutableListOf<String>()
        turn.elapsedFor("ui_first_partial", "recognizer_first_partial")?.let { elapsedMs ->
            if (elapsedMs > FIRST_PARTIAL_TARGET_MS) {
                slowReasons += "first partial ${elapsedMs}ms"
            }
        }
        turn.elapsedFor("ui_final_transcript", "recognizer_final")?.let { elapsedMs ->
            if (elapsedMs > FINAL_TRANSCRIPT_TARGET_MS) {
                slowReasons += "final transcript ${elapsedMs}ms"
            }
        }
        val ttsStart = turn.elapsedFor("first_tts_synthesis_start", "first_tts_speak_started")
        val ttsAudio = turn.elapsedFor("first_tts_audio_ready")
        if (ttsStart != null && ttsAudio != null) {
            val ttsLatencyMs = (ttsAudio - ttsStart).coerceAtLeast(0L)
            if (ttsLatencyMs > FIRST_AUDIO_TARGET_MS) {
                slowReasons += "first audio ${ttsLatencyMs}ms"
            }
        }
        if (turn.durationMs > TOTAL_TURN_TARGET_MS) {
            slowReasons += "turn ${turn.durationMs}ms"
        }

        return if (slowReasons.isEmpty()) {
            VoiceTurnBenchmarkResult(
                status = VoiceTurnBenchmarkStatus.FAST,
                summary = "On target",
            )
        } else {
            VoiceTurnBenchmarkResult(
                status = VoiceTurnBenchmarkStatus.SLOW,
                summary = slowReasons.joinToString(),
            )
        }
    }

    private fun VoiceTurnTraceSnapshot.elapsedFor(vararg eventNames: String): Long? {
        val candidates = eventNames.toSet()
        return events.firstOrNull { event -> event.event in candidates }?.elapsedMs
    }

    private const val FIRST_PARTIAL_TARGET_MS = 1_500L
    private const val FINAL_TRANSCRIPT_TARGET_MS = 5_000L
    private const val FIRST_AUDIO_TARGET_MS = 2_500L
    private const val TOTAL_TURN_TARGET_MS = 12_000L
}

object VoiceTurnTraceRecorder {
    private val lock = Any()
    private val traces = LinkedHashMap<String, MutableVoiceTurnTraceSnapshot>()
    private val _turns = MutableStateFlow<List<VoiceTurnTraceSnapshot>>(emptyList())

    val turns: StateFlow<List<VoiceTurnTraceSnapshot>> = _turns.asStateFlow()

    fun record(
        id: String,
        source: String,
        event: String,
        elapsedMs: Long,
        detail: String?,
    ) {
        synchronized(lock) {
            val trace =
                traces.getOrPut(id) {
                    MutableVoiceTurnTraceSnapshot(
                        id = id,
                        source = source,
                    )
                }
            trace.events +=
                VoiceTurnTraceEvent(
                    event = event,
                    elapsedMs = elapsedMs,
                    detail = detail,
                )
            if (event == TURN_COMPLETED_EVENT) {
                trace.outcome = detail?.substringAfter("outcome=", missingDelimiterValue = detail)
            }
            traces.remove(id)
            traces[id] = trace
            while (traces.size > MAX_RECORDED_TURNS) {
                val oldestId = traces.keys.firstOrNull() ?: break
                traces.remove(oldestId)
            }
            _turns.value = traces.values.reversed().map { it.toSnapshot() }
        }
    }

    fun clear() {
        synchronized(lock) {
            traces.clear()
            _turns.value = emptyList()
        }
    }

    private data class MutableVoiceTurnTraceSnapshot(
        val id: String,
        val source: String,
        val events: MutableList<VoiceTurnTraceEvent> = mutableListOf(),
        var outcome: String? = null,
    ) {
        fun toSnapshot(): VoiceTurnTraceSnapshot =
            VoiceTurnTraceSnapshot(
                id = id,
                source = source,
                events = events.toList(),
                outcome = outcome,
            )
    }
}

/**
 * Lightweight per-turn telemetry for voice assistant latency waterfalls.
 *
 * The trace intentionally logs event names and coarse metadata only; transcripts
 * and spoken response text should stay out of these diagnostics.
 */
class VoiceTurnTrace private constructor(
    val id: String,
    private val source: String,
    private val startedAtMs: Long,
    private val nowMs: () -> Long,
    private val sink: (String) -> Unit,
    private val enabled: Boolean,
    private val recordEvents: Boolean,
) {
    private val onceEvents = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())
    private val completed = AtomicBoolean(false)

    fun mark(
        event: String,
        detail: String? = null,
    ) {
        if (!enabled) return
        val eventKey = event.sanitizeKey()
        val elapsedMs = (nowMs() - startedAtMs).coerceAtLeast(0L)
        val sanitizedDetail = detail?.sanitizeDetail()?.takeIf { it.isNotBlank() }
        val suffix =
            sanitizedDetail
                ?.let { " detail=$it" }
                .orEmpty()
        sink("id=$id source=$source event=$eventKey elapsedMs=$elapsedMs$suffix")
        if (recordEvents) {
            VoiceTurnTraceRecorder.record(
                id = id,
                source = source,
                event = eventKey,
                elapsedMs = elapsedMs,
                detail = sanitizedDetail,
            )
        }
    }

    fun markOnce(
        event: String,
        detail: String? = null,
    ) {
        if (onceEvents.add(event.sanitizeKey())) {
            mark(event, detail)
        }
    }

    fun complete(outcome: String) {
        if (completed.compareAndSet(false, true)) {
            mark(TURN_COMPLETED_EVENT, "outcome=${outcome.sanitizeKey()}")
        }
    }

    companion object {
        private const val TAG = "VoiceTurnTrace"
        private val nextId = AtomicLong(0L)
        private val noop =
            VoiceTurnTrace(
                id = "noop",
                source = "noop",
                startedAtMs = 0L,
                nowMs = { 0L },
                sink = {},
                enabled = false,
                recordEvents = false,
            )

        fun start(source: String): VoiceTurnTrace {
            val now = SystemClock.elapsedRealtime()
            val id = "voice-${now.toString(36)}-${nextId.incrementAndGet().toString(36)}"
            return VoiceTurnTrace(
                id = id,
                source = source.sanitizeKey(),
                startedAtMs = now,
                nowMs = { SystemClock.elapsedRealtime() },
                sink = { message -> Log.d(TAG, message) },
                enabled = true,
                recordEvents = true,
            ).also { trace ->
                trace.mark("turn_started")
            }
        }

        fun noop(): VoiceTurnTrace = noop

        internal fun forTest(
            id: String,
            source: String,
            startedAtMs: Long,
            nowMs: () -> Long,
            sink: (String) -> Unit,
            recordEvents: Boolean = false,
        ): VoiceTurnTrace =
            VoiceTurnTrace(
                id = id,
                source = source,
                startedAtMs = startedAtMs,
                nowMs = nowMs,
                sink = sink,
                enabled = true,
                recordEvents = recordEvents,
            )
    }
}

private fun String.sanitizeKey(): String =
    lowercase(Locale.US)
        .replace(Regex("[^a-z0-9_:-]+"), "_")
        .trim('_')
        .ifBlank { "unknown" }

private fun String.sanitizeDetail(): String =
    replace(Regex("[\\r\\n]+"), " ")
        .replace(Regex("\\s+"), " ")
        .trim()
        .take(MAX_DETAIL_CHARS)

private const val MAX_DETAIL_CHARS = 180
private const val MAX_RECORDED_TURNS = 10
private const val TURN_COMPLETED_EVENT = "turn_completed"
