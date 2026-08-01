/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import java.util.ArrayDeque

/**
 * In-memory tool execution health tracker for developer diagnostics.
 */
object ToolHealthMonitor {
    private const val SESSION_ERROR_KEY = "__session__"
    private const val MAX_ERRORS_PER_TOOL = 10

    private val lock = Any()
    private val activeStartsByTool = linkedMapOf<String, ArrayDeque<Long>>()
    private val statsByTool = linkedMapOf<String, MutableToolHealthStats>()

    fun onToolStart(
        name: String,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val toolName = name.ifBlank { SESSION_ERROR_KEY }
        synchronized(lock) {
            activeStartsByTool
                .getOrPut(toolName) { ArrayDeque() }
                .addLast(nowMillis)
            statsByTool.getOrPut(toolName) { MutableToolHealthStats(toolName) }
        }
    }

    fun onToolResult(
        name: String,
        success: Boolean,
        durationSecs: ULong,
        nowMillis: Long = System.currentTimeMillis(),
    ) {
        val toolName = name.ifBlank { SESSION_ERROR_KEY }
        val measuredDurationMillis =
            durationSecs
                .takeIf { it > 0uL }
                ?.toLong()
                ?.times(1_000)
                ?: elapsedDurationMillis(toolName, nowMillis)

        synchronized(lock) {
            val stats = statsByTool.getOrPut(toolName) { MutableToolHealthStats(toolName) }
            if (success) {
                stats.successCount += 1
            } else {
                stats.failureCount += 1
                stats.addError("Tool reported failure.")
            }
            stats.totalDurationMillis += measuredDurationMillis.coerceAtLeast(0)
            stats.completedCount += 1
        }
    }

    fun onError(
        toolName: String? = null,
        error: String,
    ) {
        val key = toolName?.ifBlank { SESSION_ERROR_KEY } ?: SESSION_ERROR_KEY
        synchronized(lock) {
            val stats = statsByTool.getOrPut(key) { MutableToolHealthStats(key) }
            stats.failureCount += 1
            stats.addError(error)
        }
    }

    fun snapshot(): List<ToolHealthSnapshot> =
        synchronized(lock) {
            statsByTool.values.map { stats ->
                val total = stats.successCount + stats.failureCount
                ToolHealthSnapshot(
                    name = stats.name,
                    successCount = stats.successCount,
                    failureCount = stats.failureCount,
                    successRate = if (total == 0) 0.0 else stats.successCount.toDouble() / total,
                    averageDurationMillis =
                        if (stats.completedCount == 0) {
                            0
                        } else {
                            stats.totalDurationMillis / stats.completedCount
                        },
                    lastErrors = stats.lastErrors.toList(),
                )
            }
        }

    fun reset() {
        synchronized(lock) {
            activeStartsByTool.clear()
            statsByTool.clear()
        }
    }

    private fun elapsedDurationMillis(
        toolName: String,
        nowMillis: Long,
    ): Long =
        synchronized(lock) {
            val starts = activeStartsByTool[toolName] ?: return@synchronized 0L
            val startedAt = starts.pollFirst() ?: return@synchronized 0L
            nowMillis - startedAt
        }

    private data class MutableToolHealthStats(
        val name: String,
        var successCount: Int = 0,
        var failureCount: Int = 0,
        var totalDurationMillis: Long = 0,
        var completedCount: Int = 0,
        val lastErrors: ArrayDeque<String> = ArrayDeque(),
    ) {
        fun addError(error: String) {
            val trimmed = error.trim().ifBlank { "Unknown tool error." }
            lastErrors.addFirst(trimmed)
            while (lastErrors.size > MAX_ERRORS_PER_TOOL) {
                lastErrors.removeLast()
            }
        }
    }
}

data class ToolHealthSnapshot(
    val name: String,
    val successCount: Int,
    val failureCount: Int,
    val successRate: Double,
    val averageDurationMillis: Long,
    val lastErrors: List<String>,
)
