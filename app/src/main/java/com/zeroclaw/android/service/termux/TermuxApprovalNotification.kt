/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.termux

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.zeroclaw.android.R

/**
 * Creates and shows a system notification with Approve/Deny actions
 * for background Termux command approval requests.
 */
object TermuxApprovalNotification {
    const val CHANNEL_ID = "termux_approval"
    const val NOTIFICATION_ID_BASE = 9000
    const val EXTRA_REQUEST_ID = "termux_approval_request_id"
    const val EXTRA_COMMAND_PREVIEW = "termux_approval_command_preview"

    const val ACTION_APPROVE = "com.zeroclaw.android.termux.APPROVE"
    const val ACTION_DENY = "com.zeroclaw.android.termux.DENY"

    fun createChannel(context: Context) {
        val channel =
            NotificationChannel(
                CHANNEL_ID,
                "Termux Command Approval",
                NotificationManager.IMPORTANCE_HIGH,
            ).apply {
                description = "Approve or deny Termux commands when the app is in the background."
            }
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    fun show(
        context: Context,
        request: TermuxApprovalRequest,
        notificationId: Int = NOTIFICATION_ID_BASE + request.hashCode().and(0x7FFFFFFF) % 1000,
    ) {
        createChannel(context)

        val approveIntent =
            Intent(ACTION_APPROVE).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_REQUEST_ID, request.id)
            }
        val approvePending =
            PendingIntent.getBroadcast(
                context,
                request.id.hashCode(),
                approveIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val denyIntent =
            Intent(ACTION_DENY).apply {
                setPackage(context.packageName)
                putExtra(EXTRA_REQUEST_ID, request.id)
            }
        val denyPending =
            PendingIntent.getBroadcast(
                context,
                request.id.hashCode() + 1,
                denyIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

        val notification =
            NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle("Termux command needs approval")
                .setContentText(request.commandPreview)
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText("${request.commandPreview}\n\nRisk: ${request.risk.name}\n${request.reason}"),
                )
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .addAction(0, "Approve", approvePending)
                .addAction(0, "Deny", denyPending)
                .build()

        val manager = context.getSystemService(NotificationManager::class.java)
        manager.notify(notificationId, notification)
    }
}

/**
 * Handles approve/deny actions from Termux approval notifications.
 * Must be registered in AndroidManifest.xml.
 */
class TermuxApprovalActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val requestId = intent.getStringExtra(TermuxApprovalNotification.EXTRA_REQUEST_ID) ?: return
        val action = intent.action
        val result =
            when (action) {
                TermuxApprovalNotification.ACTION_APPROVE -> true
                TermuxApprovalNotification.ACTION_DENY -> false
                else -> return
            }
        // Post on main thread to avoid ANR — broadcast timeout is 10s
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            TermuxApprovalDecisionStore.setDecision(requestId, result)
        }
    }
}

/**
 * In-memory store for approval decisions from notifications.
 * The approval flow checks this after showing a notification.
 */
object TermuxApprovalDecisionStore {
    private val decisions = mutableMapOf<String, Boolean>()

    fun setDecision(requestId: String, approved: Boolean) {
        synchronized(decisions) {
            decisions[requestId] = approved
        }
    }

    fun consumeDecision(requestId: String): Boolean? =
        synchronized(decisions) {
            decisions.remove(requestId)
        }
}
