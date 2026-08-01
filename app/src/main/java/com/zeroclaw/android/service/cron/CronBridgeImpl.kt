/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.cron

import com.zeroclaw.android.model.CronJob
import com.zeroclaw.android.service.cron.ffi.CronBridge as FfiCronBridge

/**
 * Default implementation of CronBridge.
 *
 * This implementation delegates to the app-owned FFI bridge instead of
 * returning placeholder job IDs.
 */
class CronBridgeImpl(
    private val daemonClient: CronDaemonClient = FfiCronDaemonClient(),
) : CronBridge {

    override suspend fun addCronJob(
        cronExpression: String,
        command: String,
        name: String,
    ): String {
        return daemonClient.addJob(cronExpression, command).id
    }

    override suspend fun addOneShot(
        delay: String,
        command: String,
        name: String,
    ): String {
        return daemonClient.addOneShot(delay, command).id
    }

    override suspend fun addInterval(
        intervalMs: Long,
        command: String,
        name: String,
    ): String {
        require(intervalMs > 0) { "Interval must be positive." }
        return daemonClient.addJobEvery(intervalMs.toULong(), command).id
    }

    override suspend fun addAgentJob(
        cronExpression: String,
        prompt: String,
        model: String,
        name: String,
    ): String {
        return daemonClient.addJob(cronExpression, prompt).id
    }

    override suspend fun listJobs(): List<CronJobInfo> {
        return daemonClient.listJobs().map { job -> job.toInfo() }
    }

    override suspend fun getJob(jobId: String): CronJobInfo? {
        return daemonClient.getJob(jobId)?.toInfo()
    }

    override suspend fun updateJob(
        jobId: String,
        expression: String?,
        command: String?,
        enabled: Boolean?,
    ): Boolean {
        if (expression != null || command != null) {
            return false
        }
        return when (enabled) {
            true -> daemonClient.resumeJob(jobId)
            false -> daemonClient.pauseJob(jobId)
            null -> false
        }
    }

    override suspend fun removeJob(jobId: String): Boolean {
        return daemonClient.removeJob(jobId)
    }

    override suspend fun pauseJob(jobId: String): Boolean {
        return daemonClient.pauseJob(jobId)
    }

    override suspend fun resumeJob(jobId: String): Boolean {
        return daemonClient.resumeJob(jobId)
    }

    override suspend fun getJobRuns(jobId: String, limit: Int): List<JobRun> {
        // The current FFI surface exposes latest status on CronJob, but not run history.
        return emptyList()
    }
}

interface CronDaemonClient {
    suspend fun listJobs(): List<CronJob>

    suspend fun getJob(id: String): CronJob?

    suspend fun addJob(
        expression: String,
        command: String,
    ): CronJob

    suspend fun addOneShot(
        delay: String,
        command: String,
    ): CronJob

    suspend fun addJobEvery(
        intervalMs: ULong,
        command: String,
    ): CronJob

    suspend fun removeJob(id: String): Boolean

    suspend fun pauseJob(id: String): Boolean

    suspend fun resumeJob(id: String): Boolean
}

private class FfiCronDaemonClient(
    private val bridge: FfiCronBridge = FfiCronBridge(),
) : CronDaemonClient {
    override suspend fun listJobs(): List<CronJob> = bridge.listJobs()

    override suspend fun getJob(id: String): CronJob? = bridge.getJob(id)

    override suspend fun addJob(
        expression: String,
        command: String,
    ): CronJob = bridge.addJob(expression, command)

    override suspend fun addOneShot(
        delay: String,
        command: String,
    ): CronJob = bridge.addOneShot(delay, command)

    override suspend fun addJobEvery(
        intervalMs: ULong,
        command: String,
    ): CronJob = bridge.addJobEvery(intervalMs, command)

    override suspend fun removeJob(id: String): Boolean {
        bridge.removeJob(id)
        return true
    }

    override suspend fun pauseJob(id: String): Boolean {
        bridge.pauseJob(id)
        return true
    }

    override suspend fun resumeJob(id: String): Boolean {
        bridge.resumeJob(id)
        return true
    }
}

private fun CronJob.toInfo(): CronJobInfo =
    CronJobInfo(
        id = id,
        name = id,
        expression = expression,
        jobType = if (oneShot) "oneshot" else "shell",
        command = command,
        enabled = !paused,
        createdAt = 0L,
        nextRun = nextRunMs,
        lastRun = lastRunMs,
        lastStatus = lastStatus,
        lastOutput = null,
    )
