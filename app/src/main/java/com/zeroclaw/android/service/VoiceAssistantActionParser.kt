/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.model.VoiceAssistantDeviceAction
import java.util.Calendar

object VoiceAssistantActionParser {
    fun phoneDialUri(transcript: String): String? {
        val match = PHONE_COMMAND_PATTERN.matchEntire(transcript.trim()) ?: return null
        val rawNumber = match.groupValues[1]
        return phoneDialUriForNumber(rawNumber)
    }

    fun phoneContactName(transcript: String): String? {
        if (phoneDialUri(transcript) != null) return null

        val match = PHONE_TARGET_PATTERN.matchEntire(transcript.trim()) ?: return null
        val target = match.groupValues[1].trim()
        if (target.isBlank() || !target.any { it.isLetter() }) return null
        return target
    }

    fun phoneDialUriForNumber(rawNumber: String): String? {
        val phoneNumber = normalizePhoneNumber(rawNumber) ?: return null
        return "tel:$phoneNumber"
    }

    fun deviceAction(
        transcript: String,
        nowMillis: Long = System.currentTimeMillis(),
    ): VoiceAssistantDeviceAction? {
        val trimmed = transcript.trim()
        val normalized = trimmed.lowercase()
        return alarmAction(normalized) ?: timerAction(normalized) ?: reminderAction(trimmed, nowMillis)
    }

    private fun alarmAction(normalized: String): VoiceAssistantDeviceAction.SetAlarm? {
        val match = ALARM_COMMAND_PATTERN.matchEntire(normalized) ?: return null
        val rawHour = match.groupValues[1].toIntOrNull() ?: return null
        val minute = match.groupValues[2].takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
        val meridiem = match.groupValues[3].takeIf { it.isNotBlank() }
        val hour = normalizeAlarmHour(rawHour, meridiem) ?: return null
        if (minute !in 0..59) return null
        return VoiceAssistantDeviceAction.SetAlarm(
            hour = hour,
            minute = minute,
            message = DEFAULT_ALARM_MESSAGE,
        )
    }

    private fun timerAction(normalized: String): VoiceAssistantDeviceAction.SetTimer? {
        val match = TIMER_COMMAND_PATTERN.matchEntire(normalized) ?: return null
        val durationText = match.groupValues[1]
        val durationParts = DURATION_PART_PATTERN.findAll(durationText).toList()
        if (durationParts.isEmpty()) return null
        val leftover = DURATION_PART_PATTERN.replace(durationText, "").trim()
        if (leftover.isNotBlank()) return null
        val totalSeconds =
            durationParts.sumOf { part ->
                val value = part.groupValues[1].toIntOrNull() ?: return null
                val unit = part.groupValues[2]
                value * unit.secondsMultiplier()
            }
        if (totalSeconds !in MIN_TIMER_SECONDS..MAX_TIMER_SECONDS) return null
        return VoiceAssistantDeviceAction.SetTimer(
            lengthSeconds = totalSeconds,
            message = DEFAULT_TIMER_MESSAGE,
        )
    }

    private fun reminderAction(
        transcript: String,
        nowMillis: Long,
    ): VoiceAssistantDeviceAction.CreateReminder? {
        val match = REMINDER_COMMAND_PATTERN.matchEntire(transcript) ?: return null
        val title = match.groupValues[1].trim()
        if (title.isBlank()) return null
        val tomorrow = match.groupValues[2].isNotBlank()
        val rawHour = match.groupValues[3].toIntOrNull() ?: return null
        val minute = match.groupValues[4].takeIf { it.isNotBlank() }?.toIntOrNull() ?: 0
        val meridiem = match.groupValues[5].takeIf { it.isNotBlank() }?.lowercase()
        val hour = normalizeAlarmHour(rawHour, meridiem) ?: return null
        if (minute !in 0..59) return null
        return VoiceAssistantDeviceAction.CreateReminder(
            title = title,
            triggerAtEpochMillis = reminderAtMillis(
                nowMillis = nowMillis,
                hour = hour,
                minute = minute,
                tomorrow = tomorrow,
            ),
        )
    }

    private fun normalizePhoneNumber(raw: String): String? {
        val trimmed = raw.trim()
        if (trimmed.isBlank()) return null

        val hasLeadingPlus = trimmed.startsWith("+")
        val digits = trimmed.filter { it.isDigit() }
        if (digits.length !in MIN_PHONE_DIGITS..MAX_PHONE_DIGITS) return null

        return if (hasLeadingPlus) {
            "+$digits"
        } else {
            digits
        }
    }

    private fun normalizeAlarmHour(
        hour: Int,
        meridiem: String?,
    ): Int? {
        if (meridiem == null) {
            return hour.takeIf { it in 0..23 }
        }
        if (hour !in 1..12) return null
        return when (meridiem) {
            "am" -> if (hour == 12) 0 else hour
            "pm" -> if (hour == 12) 12 else hour + 12
            else -> null
        }
    }

    private fun reminderAtMillis(
        nowMillis: Long,
        hour: Int,
        minute: Int,
        tomorrow: Boolean,
    ): Long {
        val calendar =
            Calendar.getInstance().apply {
                timeInMillis = nowMillis
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
                if (tomorrow || timeInMillis <= nowMillis) {
                    add(Calendar.DAY_OF_YEAR, 1)
                }
            }
        return calendar.timeInMillis
    }

    private fun String.secondsMultiplier(): Int =
        when (this) {
            "hour", "hours", "hr", "hrs", "h" -> 3_600
            "minute", "minutes", "min", "mins", "m" -> 60
            "second", "seconds", "sec", "secs", "s" -> 1
            else -> 0
        }

    private const val MIN_PHONE_DIGITS = 3
    private const val MAX_PHONE_DIGITS = 15
    private const val MIN_TIMER_SECONDS = 1
    private const val MAX_TIMER_SECONDS = 86_400
    private const val DEFAULT_ALARM_MESSAGE = "Zero-Assist alarm"
    private const val DEFAULT_TIMER_MESSAGE = "Zero-Assist timer"
    private val PHONE_COMMAND_PATTERN =
        Regex("""(?:call|dial|phone)\s+(\+?[0-9][0-9\s().-]*)""", RegexOption.IGNORE_CASE)
    private val PHONE_TARGET_PATTERN =
        Regex("""(?:call|dial|phone)\s+(.+)""", RegexOption.IGNORE_CASE)
    private val ALARM_COMMAND_PATTERN =
        Regex("""(?:set\s+(?:an?\s+)?alarm\s+for|wake\s+me\s+up\s+at)\s+([0-9]{1,2})(?::([0-9]{2}))?\s*(am|pm)?""")
    private val TIMER_COMMAND_PATTERN =
        Regex("""(?:set|start)\s+(?:a\s+)?timer\s+for\s+(.+)""")
    private val REMINDER_COMMAND_PATTERN =
        Regex(
            """(?:remind\s+me\s+to|set\s+(?:a\s+)?reminder\s+(?:to|for))\s+(.+?)\s+(?:(tomorrow)\s+)?at\s+([0-9]{1,2})(?::([0-9]{2}))?\s*(am|pm)?""",
            RegexOption.IGNORE_CASE,
        )
    private val DURATION_PART_PATTERN =
        Regex("""([0-9]+)\s*(hours?|hrs?|h|minutes?|mins?|m|seconds?|secs?|s)\b""")
}
