/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.zeroclaw.android.R
import com.zeroclaw.android.ZeroClawApplication
import com.zeroclaw.android.VoiceAssistantActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** Decision for whether the foreground wake-up service may start the detector. */
sealed interface VoiceWakeupForegroundStartDecision {
    data object Ready : VoiceWakeupForegroundStartDecision

    data class Blocked(
        val message: String,
    ) : VoiceWakeupForegroundStartDecision
}

/** Shared guard that keeps foreground wake-up fail-closed until local support exists. */
object VoiceWakeupForegroundStartGuard {
    suspend fun currentStatusOrUnavailable(
        detector: VoiceWakeupDetector,
        timeoutMs: Long = STATUS_TIMEOUT_MS,
    ): VoiceWakeupDetectorStatus =
        withTimeoutOrNull(timeoutMs) {
            runCatching { detector.status.first() }.getOrNull()
        } ?: VoiceWakeupDetectorStatus.Unavailable.copy(
            message = DETECTOR_STATUS_UNAVAILABLE_MESSAGE,
        )

    fun evaluate(
        status: VoiceWakeupDetectorStatus,
        hasRecordAudioPermission: Boolean,
    ): VoiceWakeupForegroundStartDecision {
        if (!status.available || !status.foregroundServiceReady) {
            return VoiceWakeupForegroundStartDecision.Blocked(status.message)
        }
        if (status.requiresRecordAudioPermission && !hasRecordAudioPermission) {
            return VoiceWakeupForegroundStartDecision.Blocked(RECORD_AUDIO_REQUIRED_MESSAGE)
        }
        return VoiceWakeupForegroundStartDecision.Ready
    }
}

/**
 * Foreground-service shell for a future on-device wake-word detector.
 *
 * This service is intentionally guarded: until [VoiceWakeupDetector.status] reports a bundled
 * local detector that is foreground-service-ready, it stops without starting microphone work.
 */
class VoiceWakeupForegroundService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var detector: VoiceWakeupDetector
    private lateinit var launchRequests: VoiceAssistantLaunchRequests
    private lateinit var notifications: VoiceWakeupNotificationManager
    private var wakeEventJob: kotlinx.coroutines.Job? = null

    override fun onCreate() {
        super.onCreate()
        val app = application as ZeroClawApplication
        detector = app.voiceWakeupDetector
        launchRequests = app.voiceAssistantLaunchRequests
        notifications = VoiceWakeupNotificationManager(this)
        notifications.createChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        when (intent?.action) {
            ACTION_START -> handleStart(startId)
            ACTION_STOP -> handleStop(startId)
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // Do not block the main thread during teardown: the detector shutdown
        // may wait on recognizer cleanup. Runs on a background dispatcher,
        // still bounded by STOP_DETECTOR_TIMEOUT_MS.
        runBlocking(Dispatchers.Default) {
            wakeEventJob?.cancel()
            wakeEventJob = null
            withTimeoutOrNull(STOP_DETECTOR_TIMEOUT_MS) {
                detector.stopForegroundWakeup()
            }
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun handleStart(startId: Int) {
        if (
            !tryStartForeground(
                notification = notifications.buildNotification(
                    contentText = "Checking local wake-up readiness.",
                ),
                microphoneActive = false,
            )
        ) {
            stopSelf(startId)
            return
        }
        serviceScope.launch {
            val status = VoiceWakeupForegroundStartGuard.currentStatusOrUnavailable(detector)
            when (
                val decision =
                    VoiceWakeupForegroundStartGuard.evaluate(
                        status = status,
                        hasRecordAudioPermission = hasRecordAudioPermission(),
                    )
            ) {
                VoiceWakeupForegroundStartDecision.Ready -> startDetectorOrStop(startId)
                is VoiceWakeupForegroundStartDecision.Blocked -> stopWithoutListening(startId, decision.message)
            }
        }
    }

    private suspend fun startDetectorOrStop(startId: Int) {
        if (
            !tryStartForeground(
                notification = notifications.buildNotification(
                    contentText = "Listening locally for the wake phrase.",
                ),
                microphoneActive = true,
            )
        ) {
            stopWithoutListening(
                startId = startId,
                message = MICROPHONE_FOREGROUND_UNAVAILABLE_MESSAGE,
            )
            return
        }
        when (val result = detector.startForegroundWakeup()) {
            VoiceWakeupStartResult.Started -> collectWakeEvents(startId)
            is VoiceWakeupStartResult.Unavailable -> stopWithoutListening(startId, result.message)
            is VoiceWakeupStartResult.Failed -> stopWithoutListening(startId, result.message)
        }
    }

    private fun collectWakeEvents(startId: Int) {
        wakeEventJob?.cancel()
        wakeEventJob =
            serviceScope.launch {
                detector.wakeEvents.collect {
                    launchRequests.requestOpen()
                    startActivity(
                        Intent(this@VoiceWakeupForegroundService, VoiceAssistantActivity::class.java)
                            .apply {
                                action = VoiceAssistantIntentRouter.ACTION_OPEN_VOICE_ASSISTANT
                                addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or
                                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                                )
                            },
                    )
                    stopWithoutListening(
                        startId = startId,
                        message = "Wake phrase heard.",
                    )
                }
            }
    }

    private suspend fun stopWithoutListening(
        startId: Int,
        message: String,
    ) {
        tryStartForeground(
            notification = notifications.buildNotification(contentText = message),
            microphoneActive = false,
        )
        detector.stopForegroundWakeup()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf(startId)
    }

    private fun handleStop(startId: Int) {
        serviceScope.launch {
            wakeEventJob?.cancel()
            wakeEventJob = null
            detector.stopForegroundWakeup()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
        }
    }

    private fun tryStartForeground(
        notification: Notification,
        microphoneActive: Boolean,
    ): Boolean =
        runCatching {
            startForegroundCompat(
                notification = notification,
                microphoneActive = microphoneActive,
            )
        }.isSuccess

    private fun startForegroundCompat(
        notification: Notification,
        microphoneActive: Boolean,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                foregroundServiceType(microphoneActive),
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun foregroundServiceType(microphoneActive: Boolean): Int =
        if (microphoneActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }

    private fun hasRecordAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    companion object {
        const val ACTION_START = "com.zeroclaw.android.action.START_VOICE_WAKEUP"
        const val ACTION_STOP = "com.zeroclaw.android.action.STOP_VOICE_WAKEUP"

        private const val NOTIFICATION_ID = 2
    }
}

private class VoiceWakeupNotificationManager(
    private val context: Context,
) {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun createChannel() {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "Voice wake-up",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Local wake-word foreground service"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
            }
        notificationManager.createNotificationChannel(channel)
    }

    fun buildNotification(contentText: String): Notification {
        val contentIntent =
            PendingIntent.getActivity(
                context,
                REQUEST_CODE_OPEN,
                Intent(context, VoiceAssistantActivity::class.java).apply {
                    action = VoiceAssistantIntentRouter.ACTION_OPEN_VOICE_ASSISTANT
                },
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        val stopIntent =
            PendingIntent.getService(
                context,
                REQUEST_CODE_STOP,
                Intent(context, VoiceWakeupForegroundService::class.java).apply {
                    action = VoiceWakeupForegroundService.ACTION_STOP
                },
                PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Zero-Assist wake-up")
            .setContentText(contentText)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(R.drawable.ic_stop, "Stop", stopIntent)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "zeroclaw_voice_wakeup"
        const val REQUEST_CODE_OPEN = 200
        const val REQUEST_CODE_STOP = 201
    }
}

private const val RECORD_AUDIO_REQUIRED_MESSAGE =
    "Microphone permission is required for wake-up mode."
private const val MICROPHONE_FOREGROUND_UNAVAILABLE_MESSAGE =
    "Android blocked microphone foreground wake-up startup."
private const val DETECTOR_STATUS_UNAVAILABLE_MESSAGE =
    "Wake-up detector status is unavailable."
private const val STATUS_TIMEOUT_MS = 1_000L
private const val STOP_DETECTOR_TIMEOUT_MS = 1_000L
