/*
 * Copyright 2026 Zero-Assist Community, MIT License
 */

package com.zeroclaw.android.service.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.zeroclaw.android.ZeroClawApplication

/**
 * Receives AlarmManager intents for due scheduled tasks and enqueues
 * WorkManager execution. Returns from [onReceive] immediately to avoid ANR.
 */
class ScheduledTaskReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != NativeSchedulerService.ACTION_TASK_DUE) return
        val taskId = intent.getStringExtra(NativeSchedulerService.EXTRA_TASK_ID) ?: return

        Log.d(TAG, "Task $taskId alarm fired")

        // Post to handler to return from onReceive immediately (ANR prevention)
        Handler(Looper.getMainLooper()).post {
            NativeSchedulerService.enqueueTaskExecution(context, taskId)
        }
    }

    companion object {
        private const val TAG = "ScheduledTaskReceiver"
    }
}
