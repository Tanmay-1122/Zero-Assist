/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.media.AudioManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.util.Log

/**
 * Executes native Android phone actions via intents and system APIs.
 *
 * Ported from private-agent-main's CommunicationService, AlarmService, and SystemControlService.
 *
 * These actions do NOT require the accessibility service or an AI model —
 * they use standard Android intents and are therefore available even when
 * the full UI agent infrastructure is not configured.
 *
 * Permissions used:
 *   - READ_CONTACTS  — already declared in AndroidManifest.xml for contact lookup
 *   - CALL_PHONE     — required for direct ACTION_CALL; falls back to ACTION_DIAL
 *   - SEND_SMS       — required for SmsManager; falls back to ACTION_SENDTO (opens SMS app)
 *   - WRITE_SETTINGS — required for setBrightness; falls back to opening Display Settings
 */
object NativeIntentExecutor {

    private const val TAG = "NativeIntentExecutor"

    // ─── Unified dispatch ─────────────────────────────────────────────────────

    /**
     * Execute any native [DeviceAction] that maps to an Android system intent.
     *
     * Call this from [DeviceActionRouter] or any other executor that already knows
     * the action is a native type.
     */
    fun execute(context: Context, action: DeviceAction): String =
        when (action) {
            is DeviceAction.MakeCall -> makeCall(context, action.contactNameOrNumber)
            is DeviceAction.SendSms -> sendSms(context, action.contactNameOrNumber, action.message)
            is DeviceAction.SetAlarm -> setAlarm(context, action.hour, action.minute, action.label)
            is DeviceAction.SetTimer -> setTimer(context, action.seconds, action.label)
            is DeviceAction.SetVolume -> setVolume(context, action.level)
            is DeviceAction.SetBrightness -> setBrightness(context, action.level)
            is DeviceAction.OpenUrl -> openUrl(context, action.url)
            is DeviceAction.ToggleWifi -> toggleWifi(context, action.enabled)
            is DeviceAction.ToggleBluetooth -> toggleBluetooth(context, action.enabled)
            is DeviceAction.TakeScreenshot -> "Screenshot requires accessibility service."
            is DeviceAction.ReadNotifications -> "Notification reading requires accessibility service."
            else -> "Unsupported native action: ${action::class.simpleName}"
        }

    /**
     * Initiate a phone call.
     *
     * Resolves [contactNameOrNumber] against the device contacts. If it already looks
     * like a raw number it is used directly. Falls back to ACTION_DIAL when the
     * CALL_PHONE permission has not been granted.
     */
    fun makeCall(context: Context, contactNameOrNumber: String): String {
        val number = resolvePhoneNumber(context, contactNameOrNumber) ?: contactNameOrNumber.trim()
        return try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            "Calling ${contactNameOrNumber.trim()}"
        } catch (secEx: SecurityException) {
            // CALL_PHONE not granted — open dialer pre-filled instead
            Log.w(TAG, "CALL_PHONE not granted, falling back to ACTION_DIAL", secEx)
            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(dialIntent)
            "Opening dialer for ${contactNameOrNumber.trim()} (grant CALL_PHONE permission for auto-dial)"
        } catch (e: Exception) {
            Log.e(TAG, "makeCall failed", e)
            "Could not initiate call: ${e.message}"
        }
    }

    // ─── SMS ─────────────────────────────────────────────────────────────────

    /**
     * Send an SMS to [contactNameOrNumber] with body [message].
     *
     * Tries SmsManager for direct sending; falls back to opening the SMS app
     * pre-filled when the SEND_SMS permission is absent.
     */
    fun sendSms(context: Context, contactNameOrNumber: String, message: String): String {
        val number = resolvePhoneNumber(context, contactNameOrNumber) ?: contactNameOrNumber.trim()
        return try {
            val smsManager = context.getSystemService(android.telephony.SmsManager::class.java)
                ?: throw IllegalStateException("SmsManager unavailable")
            smsManager.sendTextMessage(number, null, message, null, null)
            "SMS sent to ${contactNameOrNumber.trim()}"
        } catch (secEx: SecurityException) {
            Log.w(TAG, "SEND_SMS not granted, falling back to ACTION_SENDTO", secEx)
            openSmsApp(context, number, message)
        } catch (e: Exception) {
            Log.w(TAG, "SmsManager send failed, falling back to ACTION_SENDTO", e)
            openSmsApp(context, number, message)
        }
    }

    private fun openSmsApp(context: Context, number: String, message: String): String {
        return try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:$number")
                putExtra("sms_body", message)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            "Opening SMS app pre-filled (grant SEND_SMS permission for direct sending)"
        } catch (e: Exception) {
            Log.e(TAG, "openSmsApp failed", e)
            "Could not open SMS app: ${e.message}"
        }
    }

    // ─── Alarm ───────────────────────────────────────────────────────────────

    /** Set an alarm using the AlarmClock intent. No special permission needed. */
    fun setAlarm(context: Context, hour: Int, minute: Int, label: String?): String {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour.coerceIn(0, 23))
                putExtra(AlarmClock.EXTRA_MINUTES, minute.coerceIn(0, 59))
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            val h = hour.toString().padStart(2, '0')
            val m = minute.toString().padStart(2, '0')
            "Alarm set for $h:$m" + if (!label.isNullOrBlank()) " ($label)" else ""
        } catch (e: Exception) {
            Log.e(TAG, "setAlarm failed", e)
            "Could not set alarm: ${e.message}"
        }
    }

    // ─── Timer ───────────────────────────────────────────────────────────────

    /** Start a countdown timer using the AlarmClock intent. */
    fun setTimer(context: Context, seconds: Int, label: String?): String {
        return try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, seconds.coerceAtLeast(1))
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                if (!label.isNullOrBlank()) putExtra(AlarmClock.EXTRA_MESSAGE, label)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            val minutes = seconds / 60
            val secs = seconds % 60
            val desc = when {
                minutes > 0 && secs > 0 -> "${minutes}m ${secs}s"
                minutes > 0 -> "${minutes}m"
                else -> "${secs}s"
            }
            "Timer set for $desc" + if (!label.isNullOrBlank()) " ($label)" else ""
        } catch (e: Exception) {
            Log.e(TAG, "setTimer failed", e)
            "Could not set timer: ${e.message}"
        }
    }

    // ─── Volume ──────────────────────────────────────────────────────────────

    /**
     * Set media volume (STREAM_MUSIC) to [levelPercent] (0–100).
     * No special permission required for STREAM_MUSIC.
     */
    fun setVolume(context: Context, levelPercent: Int): String {
        return try {
            val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            val maxVol = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val targetVol = (levelPercent.coerceIn(0, 100) * maxVol / 100.0).toInt()
            audio.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, AudioManager.FLAG_SHOW_UI)
            "Media volume set to $levelPercent%"
        } catch (e: Exception) {
            Log.e(TAG, "setVolume failed", e)
            "Could not set volume: ${e.message}"
        }
    }

    // ─── Brightness ───────────────────────────────────────────────────────────

    /**
     * Set screen brightness to [levelPercent] (0–100).
     * Requires WRITE_SETTINGS on Android 6+; opens the settings grant screen when absent.
     */
    fun setBrightness(context: Context, levelPercent: Int): String {
        return try {
            if (!Settings.System.canWrite(context)) {
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                return "Please grant 'Modify system settings' permission in the opened screen, then retry."
            }
            val brightness = (levelPercent.coerceIn(0, 100) * 255 / 100.0).toInt()
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL,
            )
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                brightness,
            )
            "Screen brightness set to $levelPercent%"
        } catch (e: Exception) {
            Log.e(TAG, "setBrightness failed", e)
            "Could not set brightness: ${e.message}"
        }
    }

    // ─── URL ─────────────────────────────────────────────────────────────────

    /** Open a URL in the default browser or the most appropriate system handler. */
    fun openUrl(context: Context, url: String): String {
        return try {
            val sanitized = url.trim().let {
                if (!it.startsWith("http://") && !it.startsWith("https://")) "https://$it" else it
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(sanitized)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            "Opened $sanitized"
        } catch (e: Exception) {
            Log.e(TAG, "openUrl failed", e)
            "Could not open URL: ${e.message}"
        }
    }

    // ─── WiFi ────────────────────────────────────────────────────────────────

    /**
     * Toggle WiFi on or off.
     * Opens the WiFi settings panel since direct toggle requires WRITE_SECURE_SETTINGS.
     */
    fun toggleWifi(context: Context, enabled: Boolean): String {
        return try {
            val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            if (enabled) "Opening WiFi settings to enable WiFi" else "Opening WiFi settings to disable WiFi"
        } catch (e: Exception) {
            Log.e(TAG, "toggleWifi failed", e)
            "Could not open WiFi settings: ${e.message}"
        }
    }

    // ─── Bluetooth ───────────────────────────────────────────────────────────

    /**
     * Toggle Bluetooth on or off.
     * Opens the Bluetooth settings panel since direct toggle requires BLUETOOTH_ADMIN permission.
     */
    fun toggleBluetooth(context: Context, enabled: Boolean): String {
        return try {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            if (enabled) "Opening Bluetooth settings to enable Bluetooth" else "Opening Bluetooth settings to disable Bluetooth"
        } catch (e: Exception) {
            Log.e(TAG, "toggleBluetooth failed", e)
            "Could not open Bluetooth settings: ${e.message}"
        }
    }

    // ─── Contact resolution ───────────────────────────────────────────────────

    /**
     * Looks up a phone number by contact name using [READ_CONTACTS] (already declared).
     * Returns null if no contact matched (the caller should fall back to using [query] directly).
     */
    private fun resolvePhoneNumber(context: Context, query: String): String? {
        val trimmed = query.trim()
        // Already looks like a phone number — use it directly
        if (trimmed.matches(Regex("""[+\d\s\-().]{6,}"""))) return trimmed

        return try {
            val resolver: ContentResolver = context.contentResolver
            val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
            val projection = arrayOf(
                ContactsContract.CommonDataKinds.Phone.NUMBER,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            )
            val selection = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?"
            val selectionArgs = arrayOf("%$trimmed%")
            val cursor: Cursor? = resolver.query(uri, projection, selection, selectionArgs, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    it.getString(it.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER))
                } else {
                    null
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Contact lookup failed for '$trimmed'", e)
            null
        }
    }
}
