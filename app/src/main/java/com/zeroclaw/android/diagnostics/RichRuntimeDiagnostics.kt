/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.diagnostics

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Diagnostic trace log record.
 */
data class DiagnosticRecord(
    val timestampMs: Long = System.currentTimeMillis(),
    val category: String,
    val message: String,
    val durationMs: Long? = null,
)

/**
 * Developer tooling recorder for monitoring rich content runtime and planner metrics.
 */
object RichRuntimeDiagnostics {
    private val records = mutableListOf<DiagnosticRecord>()
    private val eventCounts = ConcurrentHashMap<String, AtomicLong>()

    fun record(category: String, message: String, durationMs: Long? = null) {
        synchronized(records) {
            if (records.size >= 1000) {
                records.removeAt(0)
            }
            records.add(DiagnosticRecord(category = category, message = message, durationMs = durationMs))
        }
        eventCounts.computeIfAbsent(category) { AtomicLong() }.incrementAndGet()
    }

    fun getRecords(category: String? = null): List<DiagnosticRecord> {
        return synchronized(records) {
            if (category.isNullOrBlank()) {
                records.toList()
            } else {
                records.filter { it.category == category }
            }
        }
    }

    fun clear() {
        synchronized(records) { records.clear() }
        eventCounts.clear()
    }

    fun dumpSummary(): String {
        val sb = StringBuilder()
        sb.appendLine("=== Rich Runtime Diagnostics Summary ===")
        eventCounts.forEach { (cat, count) ->
            sb.appendLine("  $cat: ${count.get()} events")
        }
        return sb.toString()
    }
}
