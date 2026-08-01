/*
 * Copyright 2026 Zero-Assist Community, MIT License
 */

package com.zeroclaw.android.service.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.zeroclaw.android.ZeroClawApplication
import com.zeroclaw.android.data.local.dao.ScheduledTaskDao
import com.zeroclaw.android.data.local.dao.ScheduledTaskRunDao
import com.zeroclaw.android.data.local.entity.ScheduledTaskEntity
import com.zeroclaw.android.data.local.entity.ScheduledTaskRunEntity
import com.zeroclaw.android.startup.AppStartupTrace
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Native Android scheduler that replaces the Rust daemon's cron system.
 *
 * Uses [AlarmManager] for exact timing and [WorkManager] for reliable execution.
 * Tasks survive app kills and device reboots (via [BootReceiver]).
 */
object NativeSchedulerService {
    private const val TAG = "NativeScheduler"
    const val ACTION_TASK_DUE = "com.zeroclaw.android.SCHEDULED_TASK_DUE"
    const val EXTRA_TASK_ID = "task_id"
    private const val WORK_NAME_PREFIX = "scheduled_task_"

    /**
     * Schedules the next alarm for a task using AlarmManager.
     */
    fun scheduleAlarm(context: Context, task: ScheduledTaskEntity) {
        if (task.paused || task.nextRunMs <= 0) return

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ScheduledTaskReceiver::class.java).apply {
            action = ACTION_TASK_DUE
            putExtra(EXTRA_TASK_ID, task.id)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            task.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        task.nextRunMs,
                        pendingIntent,
                    )
                } else {
                    // Fallback to inexact alarm if exact alarm permission not granted
                    alarmManager.setAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP,
                        task.nextRunMs,
                        pendingIntent,
                    )
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP,
                    task.nextRunMs,
                    pendingIntent,
                )
            }
            Log.d(TAG, "Alarm scheduled for task ${task.id} at ${task.nextRunMs}")
        } catch (e: SecurityException) {
            Log.e(TAG, "Failed to schedule alarm for task ${task.id}: ${e.message}")
        }
    }

    /**
     * Cancels a pending alarm for a task.
     */
    fun cancelAlarm(context: Context, taskId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ScheduledTaskReceiver::class.java).apply {
            action = ACTION_TASK_DUE
            putExtra(EXTRA_TASK_ID, taskId)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            taskId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        alarmManager.cancel(pendingIntent)
    }

    /**
     * Enqueues a one-time WorkManager task for immediate execution.
     */
    fun enqueueTaskExecution(context: Context, taskId: String) {
        val workRequest = OneTimeWorkRequestBuilder<ScheduledTaskWorker>()
            .setInputData(
                androidx.work.workDataOf("task_id" to taskId),
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build()
            )
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                5,
                TimeUnit.SECONDS,
            )
            .addTag("scheduled_task")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            "$WORK_NAME_PREFIX$taskId",
            ExistingWorkPolicy.REPLACE,
            workRequest,
        )
    }

    /**
     * Re-arms all active recurring alarms. Called after boot and on app startup.
     */
    suspend fun rearmAllAlarms(context: Context, taskDao: ScheduledTaskDao) {
        val tasks = taskDao.listActiveRecurring()
        tasks.forEach { task ->
            scheduleAlarm(context, task)
        }
        Log.i(TAG, "Re-armed ${tasks.size} recurring task alarms")
    }

    /**
     * Computes the next run time for a task and updates the database.
     */
    suspend fun reschedule(task: ScheduledTaskEntity, taskDao: ScheduledTaskDao, context: Context) {
        val nextRun = computeNextRun(task) ?: return
        val updated = task.copy(nextRunMs = nextRun)
        taskDao.upsert(updated)
        scheduleAlarm(context, updated)
    }

    /**
     * Computes the next run epoch millis for a task based on its schedule type.
     */
    fun computeNextRun(task: ScheduledTaskEntity): Long? {
        val now = System.currentTimeMillis()
        return when (task.scheduleType) {
            "cron" -> {
                if (task.cronExpression.isBlank()) return null
                CronParser.nextRun(task.cronExpression, now)
            }
            "at" -> {
                if (task.atMs > now) task.atMs else null
            }
            "every" -> {
                if (task.intervalMs <= 0) return null
                if (task.lastRunMs != null) {
                    task.lastRunMs + task.intervalMs
                } else {
                    now + task.intervalMs
                }
            }
            else -> null
        }
    }
}

/**
 * WorkManager worker that executes a scheduled task.
 *
 * Supports both shell commands and agent (LLM) prompts.
 */
class ScheduledTaskWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    @Suppress("TooGenericExceptionCaught")
    override suspend fun doWork(): Result {
        val app = applicationContext as? ZeroClawApplication ?: return Result.failure()
        val taskId = inputData.getString("task_id") ?: return Result.failure()

        if (app.isColdStartCriticalPathActive()) {
            return Result.retry()
        }

        if (!app.repositoriesReady.value) {
            val ready = withTimeoutOrNull(15_000L) {
                app.repositoriesReady.first { it }
            } != null
            if (!ready) return Result.retry()
        }

        return AppStartupTrace.suspendSection("scheduled_task_worker") {
            try {
                val taskDao = app.database.scheduledTaskDao()
                val runDao = app.database.scheduledTaskRunDao()
                val task = taskDao.getById(taskId) ?: return@suspendSection Result.failure()

                if (task.paused) return@suspendSection Result.success()

                val startTime = System.currentTimeMillis()

                val result = executeTask(app, task)
                val duration = System.currentTimeMillis() - startTime
                val status = if (result.isSuccess) "ok" else "error: ${result.error}"

                runDao.insert(
                    ScheduledTaskRunEntity(
                        taskId = taskId,
                        status = if (result.isSuccess) "ok" else "error",
                        output = result.output.take(2000),
                        errorMessage = result.error,
                        runAtMs = startTime,
                        durationMs = duration,
                    ),
                )

                taskDao.updateLastRun(
                    id = taskId,
                    lastRunMs = System.currentTimeMillis(),
                    lastStatus = status,
                )

                // For one-shot tasks, mark as completed (no reschedule)
                // For recurring, reschedule
                if (task.oneShot) {
                    taskDao.deleteById(taskId)
                } else {
                    NativeSchedulerService.reschedule(task, taskDao, applicationContext)
                }

                Log.i(TAG, "Task $taskId executed: $status (${duration}ms)")
                if (result.isSuccess) Result.success() else Result.failure()
            } catch (e: Exception) {
                Log.e(TAG, "Task $taskId execution failed", e)
                Result.retry()
            }
        }
    }

    private suspend fun executeTask(
        app: ZeroClawApplication,
        task: ScheduledTaskEntity,
    ): TaskResult {
        return when (task.jobType) {
            "shell" -> executeShellCommand(task.command)
            "agent" -> executeAgentPrompt(app, task.command)
            else -> TaskResult(false, "", "Unknown job type: ${task.jobType}")
        }
    }

    private fun executeShellCommand(command: String): TaskResult {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val finished = process.waitFor(120, TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                return TaskResult(false, stdout, "Command timed out after 120s")
            }
            val exitCode = process.exitValue()
            if (exitCode == 0) {
                TaskResult(true, stdout, null)
            } else {
                TaskResult(false, stdout, "Exit code $exitCode: $stderr")
            }
        } catch (e: Exception) {
            TaskResult(false, "", e.message ?: "Unknown error")
        }
    }

    private suspend fun executeAgentPrompt(
        app: ZeroClawApplication,
        prompt: String,
    ): TaskResult {
        // Agent jobs require the daemon to be running — they route through
        // the Rust daemon's LLM pipeline. If daemon is not running, fail gracefully.
        return try {
            val daemonBridge = app.daemonBridge
            if (daemonBridge.serviceState.value != com.zeroclaw.android.model.ServiceState.RUNNING) {
                return TaskResult(false, "", "Daemon not running — agent jobs require the daemon")
            }
            // TODO: Wire up actual agent execution via daemon bridge when available
            TaskResult(false, "", "Agent job execution not yet wired to daemon bridge")
        } catch (e: Exception) {
            TaskResult(false, "", e.message ?: "Agent execution failed")
        }
    }

    companion object {
        private const val TAG = "ScheduledTaskWorker"
    }
}

/** Result of a task execution. */
data class TaskResult(
    val isSuccess: Boolean,
    val output: String,
    val error: String?,
)
