/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.model.VoiceAssistantDeviceAction
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.Calendar

@DisplayName("VoiceAssistantActionParser")
class VoiceAssistantActionParserTest {
    @Test
    fun `phoneDialUri parses numeric call commands`() {
        val uri = VoiceAssistantActionParser.phoneDialUri("Call +1 (555) 123-4567")

        assertEquals("tel:+15551234567", uri)
    }

    @Test
    fun `phoneDialUri ignores contact names for main task pipeline`() {
        val uri = VoiceAssistantActionParser.phoneDialUri("call mom")

        assertNull(uri)
        assertEquals("mom", VoiceAssistantActionParser.phoneContactName("call mom"))
    }

    @Test
    fun `phoneDialUri rejects too-short numbers`() {
        val uri = VoiceAssistantActionParser.phoneDialUri("dial 12")

        assertNull(uri)
    }

    @Test
    fun `phoneContactName ignores numeric call commands`() {
        val contactName = VoiceAssistantActionParser.phoneContactName("dial 555 1212")

        assertNull(contactName)
    }

    @Test
    fun `deviceAction parses alarm command`() {
        val action = VoiceAssistantActionParser.deviceAction("Set an alarm for 7:30 am")

        assertEquals(
            VoiceAssistantDeviceAction.SetAlarm(
                hour = 7,
                minute = 30,
                message = "Zero-Assist alarm",
            ),
            action,
        )
    }

    @Test
    fun `deviceAction parses wake up command at midnight`() {
        val action = VoiceAssistantActionParser.deviceAction("wake me up at 12:05 am")

        assertEquals(
            VoiceAssistantDeviceAction.SetAlarm(
                hour = 0,
                minute = 5,
                message = "Zero-Assist alarm",
            ),
            action,
        )
    }

    @Test
    fun `deviceAction parses timer duration`() {
        val action = VoiceAssistantActionParser.deviceAction("start a timer for 1 hour 5 minutes")

        assertEquals(
            VoiceAssistantDeviceAction.SetTimer(
                lengthSeconds = 3_900,
                message = "Zero-Assist timer",
            ),
            action,
        )
    }

    @Test
    fun `deviceAction parses reminder command`() {
        val nowMillis = millisFor(2026, Calendar.MAY, 1, 10, 0)
        val action =
            VoiceAssistantActionParser.deviceAction(
                transcript = "Remind me to take medicine at 7 pm",
                nowMillis = nowMillis,
            )

        assertEquals(
            VoiceAssistantDeviceAction.CreateReminder(
                title = "take medicine",
                triggerAtEpochMillis = millisFor(2026, Calendar.MAY, 1, 19, 0),
            ),
            action,
        )
    }

    @Test
    fun `deviceAction rolls reminder to tomorrow when time already passed`() {
        val nowMillis = millisFor(2026, Calendar.MAY, 1, 20, 0)
        val action =
            VoiceAssistantActionParser.deviceAction(
                transcript = "set reminder to stretch at 7 pm",
                nowMillis = nowMillis,
            )

        assertEquals(
            VoiceAssistantDeviceAction.CreateReminder(
                title = "stretch",
                triggerAtEpochMillis = millisFor(2026, Calendar.MAY, 2, 19, 0),
            ),
            action,
        )
    }

    @Test
    fun `deviceAction rejects invalid alarm time`() {
        val action = VoiceAssistantActionParser.deviceAction("set alarm for 25:99")

        assertNull(action)
    }

    @Test
    fun `deviceAction rejects timer without numeric duration`() {
        val action = VoiceAssistantActionParser.deviceAction("set a timer for a few minutes")

        assertNull(action)
    }

    private fun millisFor(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
    ): Long =
        Calendar.getInstance().apply {
            set(Calendar.YEAR, year)
            set(Calendar.MONTH, month)
            set(Calendar.DAY_OF_MONTH, day)
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
}
