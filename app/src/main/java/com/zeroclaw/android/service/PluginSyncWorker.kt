/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.zeroclaw.android.ZeroClawApplication
import com.zeroclaw.android.data.remote.OkHttpPluginRegistryClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Periodic [CoroutineWorker] that synchronises the local plugin database
 * with the remote plugin registry.
 *
 * Reads the registry URL from [AppSettings], fetches remote metadata via
 * [OkHttpPluginRegistryClient], and merges it into the local database.
 * Updates the `lastPluginSyncTimestamp` setting on success.
 *
 * Retries on startup warm-up and transient network failures so the registry
 * does not remain stale for a full scheduling interval.
 *
 * @param context Application context.
 * @param params Worker parameters including constraints and input data.
 */
class PluginSyncWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {
    @Suppress("TooGenericExceptionCaught")
    override suspend fun doWork(): Result {
        val app =
            applicationContext as? ZeroClawApplication
                ?: return Result.failure()

        if (!app.repositoriesReady.value) {
            val repositoriesReady =
                withTimeoutOrNull(REPOSITORY_READY_TIMEOUT_MS) {
                    app.repositoriesReady.first { it }
                } != null
            if (!repositoriesReady) {
                Log.w(TAG, "Plugin sync deferred: repositories are still warming up")
                return Result.retry()
            }
        }

        val settings = app.settingsRepository.settings.first()
        val registryUrl = settings.pluginRegistryUrl

        return try {
            val client = OkHttpPluginRegistryClient(app.sharedHttpClient)
            val remotePlugins = client.fetchPlugins(registryUrl)
            app.pluginRepository.mergeRemotePlugins(remotePlugins)
            app.settingsRepository.setLastPluginSyncTimestamp(System.currentTimeMillis())
            Log.i(TAG, "Plugin sync complete: ${remotePlugins.size} plugins")
            Result.success()
        } catch (e: java.io.IOException) {
            Log.w(TAG, "Plugin sync transient failure; scheduling retry", e)
            Result.retry()
        } catch (e: Exception) {
            Log.e(TAG, "Plugin sync permanent failure", e)
            Result.failure()
        }
    }

    /** Constants for [PluginSyncWorker]. */
    companion object {
        private const val TAG = "PluginSync"
        private const val REPOSITORY_READY_TIMEOUT_MS = 15_000L

        /** Unique work name for the periodic sync job. */
        const val WORK_NAME = "plugin_registry_sync"
    }
}
