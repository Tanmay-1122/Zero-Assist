/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.startup

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.zeroclaw.android.backup.SyncRepository
import com.zeroclaw.android.backup.WorkManagerScheduler
import com.zeroclaw.android.data.local.ZeroClawDatabase
import com.zeroclaw.android.data.repository.SettingsRepository
import com.zeroclaw.android.service.PluginSyncWorker
import com.zeroclaw.android.service.hardware.HardwareScheduledCommandScheduler
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

internal object AppStartupTasks {
    fun warmDatabase(database: ZeroClawDatabase) {
        AppStartupTrace.section("database_warmup_query") {
            database.openHelper.readableDatabase.query("SELECT 1").use { cursor ->
                cursor.moveToFirst()
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    fun scheduleDeferredDatabaseWarmup(
        database: ZeroClawDatabase,
        scope: CoroutineScope,
    ) {
        scope.launch {
            AppStartupTrace.mark(
                "database_warmup_deferred",
                "delayMs=$DEFERRED_DATABASE_WARMUP_DELAY_MS",
            )
            delay(DEFERRED_DATABASE_WARMUP_DELAY_MS)
            try {
                warmDatabase(database)
            } catch (e: Exception) {
                Log.w(TAG, "Deferred database warmup failed", e)
            }
        }
    }

    fun startDeferredWorkManagerTasks(
        context: Context,
        scope: CoroutineScope,
        repositoriesReady: StateFlow<Boolean>,
        settingsRepository: SettingsRepository,
        syncRepository: SyncRepository,
    ) {
        scope.launch {
            AppStartupTrace.suspendSection("deferred_workmanager_wait_repositories") {
                repositoriesReady.first { it }
            }
            AppStartupTrace.section("deferred_plugin_sync_registration") {
                schedulePluginSyncIfEnabled(
                    context = context,
                    scope = this,
                    settingsRepository = settingsRepository,
                )
            }
            AppStartupTrace.section("deferred_hardware_scheduler_registration") {
                HardwareScheduledCommandScheduler.schedulePeriodic(context)
            }
            AppStartupTrace.suspendSection("deferred_backup_sync_registration") {
                if (syncRepository.hasSignedInAccount()) {
                    WorkManagerScheduler.schedulePeriodic(context)
                } else {
                    WorkManagerScheduler.cancelPeriodic(context)
                }
            }
        }
    }

    private fun schedulePluginSyncIfEnabled(
        context: Context,
        scope: CoroutineScope,
        settingsRepository: SettingsRepository,
    ) {
        scope.launch {
            settingsRepository.settings
                .map { settings ->
                    settings.pluginSyncEnabled to settings.pluginSyncIntervalHours
                }.distinctUntilChanged()
                .collect { (pluginSyncEnabled, pluginSyncIntervalHours) ->
                    val workManager = WorkManager.getInstance(context)
                    if (pluginSyncEnabled) {
                        val constraints =
                            Constraints
                                .Builder()
                                .setRequiredNetworkType(NetworkType.CONNECTED)
                                .build()
                        val request =
                            PeriodicWorkRequestBuilder<PluginSyncWorker>(
                                pluginSyncIntervalHours
                                    .coerceAtLeast(MIN_PLUGIN_SYNC_INTERVAL_HOURS)
                                    .toLong(),
                                TimeUnit.HOURS,
                            ).setConstraints(constraints)
                                .setBackoffCriteria(
                                    BackoffPolicy.EXPONENTIAL,
                                    PLUGIN_SYNC_BACKOFF_MINUTES,
                                    TimeUnit.MINUTES,
                                ).build()
                        workManager.enqueueUniquePeriodicWork(
                            PluginSyncWorker.WORK_NAME,
                            ExistingPeriodicWorkPolicy.UPDATE,
                            request,
                        )
                        AppStartupTrace.mark(
                            "plugin_sync_scheduled",
                            "intervalHours=$pluginSyncIntervalHours",
                        )
                    } else {
                        workManager.cancelUniqueWork(PluginSyncWorker.WORK_NAME)
                        AppStartupTrace.mark("plugin_sync_cancelled")
                    }
                }
        }
    }

    private const val TAG = "AppStartupTasks"
    internal const val DEFERRED_DATABASE_WARMUP_DELAY_MS = 30_000L
    private const val MIN_PLUGIN_SYNC_INTERVAL_HOURS = 1
    private const val PLUGIN_SYNC_BACKOFF_MINUTES = 30L
}
