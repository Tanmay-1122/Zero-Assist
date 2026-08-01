/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.CalendarContract
import com.zeroclaw.android.model.VoiceAssistantCommand
import com.zeroclaw.android.model.VoiceAssistantDeviceAction

data class VoiceAssistantCommandLaunchResult(
    val success: Boolean,
    val failureMessage: String? = null,
    val finalMessage: String? = null,
    val shouldSpeak: Boolean = true,
)

object VoiceAssistantCommandLauncher {
    suspend fun launch(
        context: Context,
        command: VoiceAssistantCommand,
    ): VoiceAssistantCommandLaunchResult {
        val intent =
            when {
                command.phoneDialUri != null ->
                    Intent(Intent.ACTION_DIAL, Uri.parse(command.phoneDialUri))
                command.deviceAction != null ->
                    command.deviceAction.toIntent()
                else ->
                    return command.failedLaunchResult()
            }
        if (intent.resolveActivity(context.packageManager) == null) {
            return command.failedLaunchResult()
        }
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return if (runCatching { context.startActivity(intent) }.isSuccess) {
            VoiceAssistantCommandLaunchResult(success = true)
        } else {
            command.failedLaunchResult()
        }
    }
}

internal fun VoiceAssistantCommand.failedLaunchResult(): VoiceAssistantCommandLaunchResult =
    VoiceAssistantCommandLaunchResult(
        success = false,
        failureMessage = defaultLaunchFailureMessage(),
    )

internal fun VoiceAssistantCommand.defaultLaunchFailureMessage(): String =
    when {
        phoneDialUri != null -> "No local phone app is available on this device."
        deviceAction != null -> deviceAction.launchFailureMessage()
        else -> "Unable to open the local task pipeline."
    }

private fun VoiceAssistantDeviceAction.toIntent(): Intent =
    when (this) {
        is VoiceAssistantDeviceAction.SetAlarm ->
            Intent(AlarmClock.ACTION_SET_ALARM)
                .putExtra(AlarmClock.EXTRA_HOUR, hour)
                .putExtra(AlarmClock.EXTRA_MINUTES, minute)
                .putExtra(AlarmClock.EXTRA_MESSAGE, message)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        is VoiceAssistantDeviceAction.SetTimer ->
            Intent(AlarmClock.ACTION_SET_TIMER)
                .putExtra(AlarmClock.EXTRA_LENGTH, lengthSeconds)
                .putExtra(AlarmClock.EXTRA_MESSAGE, message)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, false)
        is VoiceAssistantDeviceAction.CreateReminder -> {
            val startAt = triggerAtEpochMillis
            Intent(Intent.ACTION_INSERT)
                .setData(CalendarContract.Events.CONTENT_URI)
                .putExtra(CalendarContract.Events.TITLE, title)
                .putExtra(CalendarContract.Events.DESCRIPTION, "Created from Zero-Assist voice assistant.")
                .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, startAt)
                .putExtra(
                    CalendarContract.EXTRA_EVENT_END_TIME,
                    startAt + durationMinutes.coerceAtLeast(1) * 60_000L,
                )
                .putExtra(CalendarContract.Events.HAS_ALARM, true)
        }
    }

private fun VoiceAssistantDeviceAction.launchFailureMessage(): String =
    when (this) {
        is VoiceAssistantDeviceAction.SetAlarm -> "No local alarm app is available on this device."
        is VoiceAssistantDeviceAction.SetTimer -> "No local timer app is available on this device."
        is VoiceAssistantDeviceAction.CreateReminder -> "No local calendar app is available on this device."
    }

private fun DeviceAction.launchFailureMessage(): String =
    when (this) {
        is DeviceAction.OpenApp,
        is DeviceAction.OpenPackage -> "No matching app is available on this device."
        is DeviceAction.TapText -> "Unable to find matching screen text to tap."
        is DeviceAction.SetText -> "Unable to enter text on this device right now."
        is DeviceAction.PressGlobal -> "Unable to run the Android navigation action."
        is DeviceAction.Flashlight -> "Unable to control the flashlight right now."
        is DeviceAction.Spotify -> "Unable to run the Spotify device action."
        is DeviceAction.MakeCall -> "Unable to initiate the call right now."
        is DeviceAction.SendSms -> "Unable to send the SMS right now."
        is DeviceAction.SetAlarm -> "Unable to set the alarm right now."
        is DeviceAction.SetTimer -> "Unable to start the timer right now."
        is DeviceAction.SetVolume -> "Unable to set the volume right now."
        is DeviceAction.SetBrightness -> "Unable to set the brightness right now."
        is DeviceAction.OpenUrl -> "Unable to open the URL right now."
        is DeviceAction.ToggleWifi -> "Unable to toggle WiFi right now."
        is DeviceAction.ToggleBluetooth -> "Unable to toggle Bluetooth right now."
        is DeviceAction.TakeScreenshot -> "Unable to take a screenshot right now."
        is DeviceAction.ReadNotifications -> "Unable to read notifications right now."
        is DeviceAction.Ignore -> "Unable to run the device action."
    }
