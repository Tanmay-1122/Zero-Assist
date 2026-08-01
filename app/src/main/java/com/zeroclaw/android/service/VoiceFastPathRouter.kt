/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.model.VoiceAssistantCommand
import com.zeroclaw.android.model.VoiceAssistantDeviceAction
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.regex.Pattern

sealed interface VoiceFastPathResult {
    data class Command(
        val command: VoiceAssistantCommand,
        val statusMessage: String,
    ) : VoiceFastPathResult

    data class ContactCall(
        val transcript: String,
        val contactName: String,
    ) : VoiceFastPathResult

    data class SpokenResponse(
        val message: String,
    ) : VoiceFastPathResult
}

object VoiceFastPathRouter {
    fun route(
        transcript: String,
        lastSpokenResponse: String? = null,
    ): VoiceFastPathResult? {
        val trimmed = transcript.trim()
        if (trimmed.isBlank()) return null

        spokenResponse(trimmed, lastSpokenResponse)?.let { return it }

        instantVoiceResponse(trimmed)?.let { return it }

        VoiceAssistantActionParser.phoneDialUri(trimmed)?.let { uri ->
            return VoiceFastPathResult.Command(
                command =
                    VoiceAssistantCommand(
                        text = trimmed,
                        phoneDialUri = uri,
                    ),
                statusMessage = "Opening phone dialer.",
            )
        }

        VoiceAssistantActionParser.phoneContactName(trimmed)?.let { contactName ->
            return VoiceFastPathResult.ContactCall(
                transcript = trimmed,
                contactName = contactName,
            )
        }

        VoiceAssistantActionParser.deviceAction(trimmed)?.let { action ->
            return VoiceFastPathResult.Command(
                command =
                    VoiceAssistantCommand(
                        text = trimmed,
                        deviceAction = action,
                    ),
                statusMessage = action.statusMessage(),
            )
        }

        return null
    }

    private fun spokenResponse(
        transcript: String,
        lastSpokenResponse: String?,
    ): VoiceFastPathResult.SpokenResponse? {
        val normalized = transcript.normalized()
        return when {
            normalized in HELP_QUESTIONS ->
                VoiceFastPathResult.SpokenResponse(
                    "I can open apps, call contacts, set timers, control the phone, and answer short questions.",
                )
            normalized in CANCEL_COMMANDS ->
                VoiceFastPathResult.SpokenResponse("Cancelled.")
            normalized in REPEAT_COMMANDS ->
                VoiceFastPathResult.SpokenResponse(
                    lastSpokenResponse
                        ?.takeIf { it.isNotBlank() }
                        ?: "I do not have a previous answer to repeat.",
                )
            else -> null
        }
    }

    private fun String.normalized(): String =
        lowercase()
            .replace(Regex("""[?.!,]+"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun VoiceAssistantDeviceAction.statusMessage(): String =
        when (this) {
            is VoiceAssistantDeviceAction.SetAlarm -> "Opening alarm."
            is VoiceAssistantDeviceAction.SetTimer -> "Opening timer."
            is VoiceAssistantDeviceAction.CreateReminder -> "Opening reminder."
        }

    private fun DeviceAction.statusMessage(): String =
        when (this) {
            is DeviceAction.OpenApp -> "Opening $query."
            is DeviceAction.OpenPackage -> "Opening app."
            is DeviceAction.PressGlobal -> "Running navigation action."
            is DeviceAction.Flashlight -> if (enabled) "Turning flashlight on." else "Turning flashlight off."
            is DeviceAction.Spotify -> "Starting Spotify task."
            is DeviceAction.TapText,
            is DeviceAction.SetText -> "Starting phone task."
            // Native intent actions
            is DeviceAction.MakeCall -> "Calling ${contactNameOrNumber}."
            is DeviceAction.SendSms -> "Sending SMS to ${contactNameOrNumber}."
            is DeviceAction.SetAlarm -> "Setting alarm."
            is DeviceAction.SetTimer -> "Starting timer."
            is DeviceAction.SetVolume -> "Setting volume."
            is DeviceAction.SetBrightness -> "Setting brightness."
            is DeviceAction.OpenUrl -> "Opening URL."
            is DeviceAction.ToggleWifi -> if (enabled) "Turning WiFi on." else "Turning WiFi off."
            is DeviceAction.ToggleBluetooth -> if (enabled) "Turning Bluetooth on." else "Turning Bluetooth off."
            is DeviceAction.TakeScreenshot -> "Taking screenshot."
            is DeviceAction.ReadNotifications -> "Reading notifications."
            is DeviceAction.Ignore -> "Ignoring device action."
        }

    private fun instantVoiceResponse(transcript: String): VoiceFastPathResult.SpokenResponse? {
        val normalized = transcript.normalized()

        timeResponse(normalized)?.let { return VoiceFastPathResult.SpokenResponse(it) }
        dateResponse(normalized)?.let { return VoiceFastPathResult.SpokenResponse(it) }
        mathResponse(transcript).let { response ->
            response?.let { return VoiceFastPathResult.SpokenResponse(it) }
        }
        identityResponse(normalized)?.let { return VoiceFastPathResult.SpokenResponse(it) }

        return null
    }

    private fun timeResponse(normalized: String): String? {
        if (normalized !in TIME_QUESTIONS && !TIME_PATTERN.matcher(normalized).matches()) return null
        val now = Calendar.getInstance()
        val format = SimpleDateFormat("h:mm a", Locale.getDefault())
        return "It's ${format.format(now.time)}."
    }

    private fun dateResponse(normalized: String): String? {
        if (normalized !in DATE_QUESTIONS && !DATE_PATTERN.matcher(normalized).matches()) return null
        val now = Calendar.getInstance()
        val dayFormat = SimpleDateFormat("EEEE", Locale.getDefault())
        val dateFormat = SimpleDateFormat("MMMM d", Locale.getDefault())
        return "Today is ${dayFormat.format(now.time)}, ${dateFormat.format(now.time)}."
    }

    private fun mathResponse(originalTranscript: String): String? {
        val normalized = originalTranscript.normalized()
        val matcher = MATH_PATTERN.matcher(normalized)
        if (!matcher.matches()) return null
        val num1 = matcher.group(1)?.toDoubleOrNull() ?: return null
        val op = matcher.group(2) ?: return null
        val num2 = matcher.group(3)?.toDoubleOrNull() ?: return null

        val result =
            when (op) {
                "plus", "+" -> num1 + num2
                "minus", "-" -> num1 - num2
                "times", "multiplied by", "*" -> num1 * num2
                "divided by", "/" -> if (num2 != 0.0) num1 / num2 else return null
                else -> return null
            }

        val formatted =
            if (result == result.toLong().toDouble()) {
                result.toLong().toString()
            } else {
                "%.2f".format(result).trimEnd('0').trimEnd('.')
            }
        return "The answer is $formatted."
    }

    private fun identityResponse(normalized: String): String? =
        when (normalized) {
            in IDENTITY_QUESTIONS -> "I'm Zero Assist, your voice assistant on this phone."
            else -> null
        }

    private val TIME_QUESTIONS =
        setOf(
            "what time is it",
            "whats the time",
            "whats the time now",
            "what is the time",
            "current time",
            "tell me the time",
            "give me the time",
            "what time",
        )

    private val DATE_QUESTIONS =
        setOf(
            "what day is it",
            "what day is today",
            "what is today",
            "whats today",
            "what is the date",
            "whats the date",
            "what date is it",
            "current date",
            "tell me the date",
            "what day of the week is it",
            "what day of the week",
        )

    private val IDENTITY_QUESTIONS =
        setOf(
            "who are you",
            "what are you",
            "what is your name",
            "whats your name",
            "your name",
            "what are you called",
            "what model are you",
            "which model are you",
            "introduce yourself",
        )

    private val TIME_PATTERN: Pattern =
        Pattern.compile("what(?:'s| is) (?:the )?time(?: now)?")

    private val DATE_PATTERN: Pattern =
        Pattern.compile("what(?:'s| is) (?:the )?(?:date|today|day)")

    private val MATH_PATTERN: Pattern =
        Pattern.compile(
            "(?:whats|what(?:'| i)s|calculate|compute|solve)\\s+" +
                "(\\d+(?:\\.\\d+)?)\\s+" +
                "(plus|minus|times|multiplied by|divided by|\\+|\\-|\\*|\\/)\\s+" +
                "(\\d+(?:\\.\\d+)?)",
        )

    private val HELP_QUESTIONS =
        setOf(
            "what can you do",
            "what can i say",
            "help",
            "voice help",
        )
    private val CANCEL_COMMANDS =
        setOf(
            "cancel",
            "stop",
            "never mind",
            "nevermind",
        )
    private val REPEAT_COMMANDS =
        setOf(
            "repeat",
            "repeat that",
            "say that again",
        )
}
