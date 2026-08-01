/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.hardware

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.zeroclaw.android.ZeroClawApplication
import com.zeroclaw.android.startup.AppStartupTrace
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * WorkManager sweep that executes scheduled actuator commands once they are due.
 */
class HardwareScheduledCommandWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    @Suppress("TooGenericExceptionCaught")
    override suspend fun doWork(): Result {
        val app = applicationContext as? ZeroClawApplication
            ?: return Result.failure()

        if (app.isColdStartCriticalPathActive()) {
            AppStartupTrace.mark("hardware_worker_deferred", "reason=cold_start")
            Log.i(TAG, "Scheduled hardware commands deferred: cold-start critical path is still active")
            return Result.retry()
        }

        if (!app.repositoriesReady.value) {
            val repositoriesReady =
                withTimeoutOrNull(REPOSITORY_READY_TIMEOUT_MS) {
                    app.repositoriesReady.first { it }
                } != null
            if (!repositoriesReady) {
                Log.w(TAG, "Scheduled hardware commands deferred: repositories are still warming up")
                return Result.retry()
            }
        }

        return AppStartupTrace.suspendSection("hardware_scheduled_worker") {
            try {
                val summary = app.hardwareRepository.executeDueScheduledActuatorCommands()
                if (summary.hadWork) {
                    Log.i(
                        TAG,
                        "Scheduled hardware commands complete: " +
                            "attempted=${summary.attempted}, completed=${summary.completed}, " +
                            "failed=${summary.failed}, skipped=${summary.skipped}",
                    )
                }
                Result.success()
            } catch (e: Exception) {
                Log.e(TAG, "Scheduled hardware command sweep failed", e)
                Result.retry()
            }
        }
    }

    companion object {
        private const val TAG = "HardwareSchedule"
        private const val REPOSITORY_READY_TIMEOUT_MS = 15_000L
    }
}

/**
 * Schedules periodic execution sweeps for due hardware actuator commands.
 */
object HardwareScheduledCommandScheduler {
    const val WORK_NAME = "hardware_scheduled_actuator_commands"

    fun schedulePeriodic(context: Context) {
        val request =
            PeriodicWorkRequestBuilder<HardwareScheduledCommandWorker>(
                HARDWARE_COMMAND_SWEEP_INTERVAL_MINUTES,
                TimeUnit.MINUTES,
            ).setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                HARDWARE_COMMAND_BACKOFF_MINUTES,
                TimeUnit.MINUTES,
            ).setInitialDelay(
                HARDWARE_COMMAND_INITIAL_DELAY_MINUTES,
                TimeUnit.MINUTES,
            ).build()

        WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
            WORK_NAME,
            existingWorkPolicy,
            request,
        )
    }

    internal val existingWorkPolicy = ExistingPeriodicWorkPolicy.KEEP
    internal const val HARDWARE_COMMAND_INITIAL_DELAY_MINUTES = 15L
    private const val HARDWARE_COMMAND_SWEEP_INTERVAL_MINUTES = 15L
    private const val HARDWARE_COMMAND_BACKOFF_MINUTES = 10L
}
