/*
 * Copyright 2026 Zero-Assist Community, MIT License
 */

package com.zeroclaw.android.service.scheduler

import java.util.Calendar

/**
 * Minimal 5-field cron expression parser for computing next run times.
 *
 * Fields: minute hour day-of-month month day-of-week
 * Supports: wildcard, ranges (1-5), steps, lists (1,3,5), and specific values.
 * Does NOT support: seconds, year, L, W, # (not needed for this use case).
 */
object CronParser {

    /** Maximum iterations to prevent infinite loops when computing next run. */
    private const val MAX_FUTURE_SEARCH = 366 * 24 * 60L

    /**
     * Computes the next run time after [afterMs] for the given cron expression.
     *
     * @param expression 5-field cron expression (e.g. "0 9 * * *")
     * @param afterMs Epoch millis to compute from
     * @return Epoch millis of the next run, or null if parsing fails
     */
    fun nextRun(expression: String, afterMs: Long): Long? {
        val fields = parseExpression(expression) ?: return null
        val cal = Calendar.getInstance().apply { timeInMillis = afterMs }
        cal.add(Calendar.MINUTE, 1)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)

        var iterations = 0
        while (iterations < MAX_FUTURE_SEARCH) {
            if (matchesAllFields(cal, fields)) {
                return cal.timeInMillis
            }
            cal.add(Calendar.MINUTE, 1)
            iterations++
        }
        return null
    }

    /**
     * Parses a 5-field cron expression into parsed field matchers.
     */
    private fun parseExpression(expression: String): List<CronField>? {
        val parts = expression.trim().split("\\s+".toRegex())
        if (parts.size !in 5..6) return null
        // If 6 fields, skip the first (seconds)
        val fields = if (parts.size == 6) parts.drop(1) else parts
        return fields.mapIndexed { index, raw ->
            val field = parseField(raw, FIELD_RANGES[index]) ?: return null
            field
        }
    }

    private data class CronField(
        val type: Type,
        val values: Set<Int> = emptySet(),
        val step: Int = 0,
        val rangeStart: Int = 0,
        val rangeEnd: Int = 0,
    ) {
        enum class Type { EVERY, SPECIFIC, STEP, RANGE, LIST }
    }

    private val FIELD_RANGES = arrayOf(
        0..59,   // minute
        0..23,   // hour
        1..31,   // day of month
        1..12,   // month
        0..6,    // day of week (0=Sun)
    )

    private fun parseField(raw: String, range: IntRange): CronField? {
        if (raw == "*") {
            return CronField(CronField.Type.EVERY)
        }

        // Step: */5 or 1/5
        if (raw.contains("/")) {
            val stepParts = raw.split("/")
            val start = if (stepParts[0] == "*") range.first else stepParts[0].toIntOrNull() ?: return null
            val step = stepParts[1].toIntOrNull() ?: return null
            if (step <= 0) return null
            return CronField(CronField.Type.STEP, step = step, rangeStart = start, rangeEnd = range.last)
        }

        // Range: 1-5
        if (raw.contains("-")) {
            val rangeParts = raw.split("-")
            val start = rangeParts[0].toIntOrNull() ?: return null
            val end = rangeParts[1].toIntOrNull() ?: return null
            return CronField(CronField.Type.RANGE, rangeStart = start, rangeEnd = end)
        }

        // List: 1,3,5
        if (raw.contains(",")) {
            val values = raw.split(",").mapNotNull { it.toIntOrNull() }.toSet()
            return CronField(CronField.Type.LIST, values = values)
        }

        // Single value
        val value = raw.toIntOrNull() ?: return null
        return CronField(CronField.Type.SPECIFIC, values = setOf(value))
    }

    private fun matchesAllFields(cal: Calendar, fields: List<CronField>): Boolean {
        if (fields.size != 5) return false
        val calValues = intArrayOf(
            cal.get(Calendar.MINUTE),
            cal.get(Calendar.HOUR_OF_DAY),
            cal.get(Calendar.DAY_OF_MONTH),
            cal.get(Calendar.MONTH) + 1,
            cal.get(Calendar.DAY_OF_WEEK) - 1, // Convert to 0=Sun
        )
        return fields.withIndex().all { (index, field) ->
            matchesField(calValues[index], field, FIELD_RANGES[index])
        }
    }

    private fun matchesField(value: Int, field: CronField, range: IntRange): Boolean {
        return when (field.type) {
            CronField.Type.EVERY -> value in range
            CronField.Type.SPECIFIC -> value in field.values
            CronField.Type.RANGE -> value in field.rangeStart..field.rangeEnd
            CronField.Type.STEP -> {
                val start = field.rangeStart
                value >= start && (value - start) % field.step == 0 && value in range
            }
            CronField.Type.LIST -> value in field.values
        }
    }
}
