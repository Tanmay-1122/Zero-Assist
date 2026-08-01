/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.cron

import android.util.Log

class CronTranslator {
    companion object {
        private const val TAG = "CronTranslator"
    }

    fun translateSchedule(naturalSchedule: String): Pair<String, String> {
        Log.d(TAG, "Translating: $naturalSchedule")
        return when {
            naturalSchedule.contains("every", ignoreCase = true) &&
                naturalSchedule.contains("minute", ignoreCase = true) -> {
                val minutes = extractNumber(naturalSchedule) ?: 5
                Pair("*/$minutes * * * *", "recurring")
            }

            naturalSchedule.contains("every", ignoreCase = true) &&
                naturalSchedule.contains("hour", ignoreCase = true) -> {
                val hours = extractNumber(naturalSchedule) ?: 1
                Pair("0 */$hours * * *", "recurring")
            }

            naturalSchedule.contains("every", ignoreCase = true) &&
                naturalSchedule.contains("day", ignoreCase = true) -> {
                val days = extractNumber(naturalSchedule) ?: 1
                val time = extractTime(naturalSchedule)
                if (time != null) {
                    Pair("${time.minute} ${time.hour} */$days * *", "recurring")
                } else {
                    Pair("0 0 */$days * *", "recurring")
                }
            }

            naturalSchedule.contains("daily", ignoreCase = true) ||
                naturalSchedule.contains("every day", ignoreCase = true) -> {
                val time = extractTime(naturalSchedule)
                if (time != null) {
                    Pair("${time.minute} ${time.hour} * * *", "recurring")
                } else {
                    Pair("0 0 * * *", "recurring")
                }
            }

            naturalSchedule.contains("weekly", ignoreCase = true) -> {
                val time = extractTime(naturalSchedule)
                val dayOfWeek = extractDayOfWeek(naturalSchedule) ?: 1
                if (time != null) {
                    Pair("${time.minute} ${time.hour} * * $dayOfWeek", "recurring")
                } else {
                    Pair("0 0 * * $dayOfWeek", "recurring")
                }
            }

            naturalSchedule.contains("hourly", ignoreCase = true) -> {
                Pair("0 * * * *", "recurring")
            }

            Regex("\\d+ minute").containsMatchIn(naturalSchedule) -> {
                val minutes = extractNumber(naturalSchedule) ?: 5
                Pair("*/$minutes * * * *", "recurring")
            }

            naturalSchedule.contains("in", ignoreCase = true) -> {
                val delay = extractDelay(naturalSchedule)
                Pair(delay, "oneshot")
            }

            naturalSchedule.contains("at", ignoreCase = true) && hasTime(naturalSchedule) -> {
                val time = extractTime(naturalSchedule)
                if (time != null) {
                    Pair("${time.minute} ${time.hour} * * *", "recurring")
                } else {
                    Pair("0 9 * * *", "recurring")
                }
            }

            else -> {
                Log.w(TAG, "Could not parse schedule: $naturalSchedule, using default")
                Pair("*/5 * * * *", "recurring")
            }
        }
    }

    private fun extractNumber(text: String): Int? {
        val regex = """(\d+)""".toRegex()
        return regex.find(text)?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractTime(text: String): Time? {
        val regex12 = """(\d{1,2}):(\d{2})\s*(am|pm)?""".toRegex(RegexOption.IGNORE_CASE)
        val match = regex12.find(text)
        return match?.let {
            val hour = it.groupValues[1].toInt()
            val minute = it.groupValues[2].toInt()
            val period = it.groupValues[3].lowercase()
            val adjustedHour =
                when {
                    period.contains("pm") && hour != 12 -> hour + 12
                    period.contains("am") && hour == 12 -> 0
                    else -> hour
                }
            Time(adjustedHour, minute)
        } ?: run {
            val regex24 = """(\d{1,2}):(\d{2})""".toRegex()
            regex24.find(text)?.let {
                val hour = it.groupValues[1].toInt()
                val minute = it.groupValues[2].toInt()
                Time(hour, minute)
            }
        }
    }

    private fun extractDayOfWeek(text: String): Int? =
        when {
            text.contains("monday", ignoreCase = true) -> 1
            text.contains("tuesday", ignoreCase = true) -> 2
            text.contains("wednesday", ignoreCase = true) -> 3
            text.contains("thursday", ignoreCase = true) -> 4
            text.contains("friday", ignoreCase = true) -> 5
            text.contains("saturday", ignoreCase = true) -> 6
            text.contains("sunday", ignoreCase = true) -> 0
            else -> null
        }

    private fun extractDelay(text: String): String {
        val regex = """(\d+)\s*([smhd])""".toRegex(RegexOption.IGNORE_CASE)
        val match = regex.find(text)
        return match?.let {
            val number = it.groupValues[1]
            val unit = it.groupValues[2].lowercase()
            "$number$unit"
        } ?: "5m"
    }

    private fun hasTime(text: String): Boolean {
        val timeRegex = """\d{1,2}:\d{2}|am|pm|\d{1,2}\s*o.?clock""".toRegex(RegexOption.IGNORE_CASE)
        return timeRegex.containsMatchIn(text)
    }

    data class Time(
        val hour: Int,
        val minute: Int,
    )
}
