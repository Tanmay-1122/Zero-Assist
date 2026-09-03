/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * Foreground service that keeps model downloads alive when the app goes into
 * the background.
 *
 * This service must be declared in `AndroidManifest.xml` with the
 * `android.permission.FOREGROUND_SERVICE` permission and
 * `android:foregroundServiceType="dataSync"`:
 *
 * ```xml
 * <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
 * <uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
 *
 * <service
 *     android:name=".service.ModelDownloadForegroundService"
 *     android:exported="false"
 *     android:foregroundServiceType="dataSync" />
 * ```
 *
 * **Lifecycle**:
 * - Started by [LiteRTInferenceEngine] once a live HTTP connection is established.
 * - Stopped by [LiteRTInferenceEngine] when the download completes, fails, or is
 *   cancelled.
 * - Notification progress is updated directly via [NotificationManager] by the
 *   engine; this service only holds the foreground slot.
 */
class ModelDownloadForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Model Download",
            NotificationManager.IMPORTANCE_LOW,
        )
        nm.createNotificationChannel(channel)

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Zero-Assist")
            .setContentText("Downloading on-device model…")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setProgress(100, 0, true)
            .build()

        startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    companion object {
        const val CHANNEL_ID = "zero_assist_model_download"
        const val NOTIFICATION_ID = 8042
    }
}
