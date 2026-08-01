/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.cron

/**
 * Bridge to communicate with Rust ZeroClaw daemon for cron job operations.
 *
 * Translates Kotlin calls to FFI boundary calls for job creation, listing, and management.
 */
interface CronBridge {

    /**
     * Create a recurring cron job.
     *
     * @param cronExpression Cron expression (e.g., "0 9 * * *" for 9 AM daily)
     * @param command Shell command to execute
     * @param name Optional job name
     * @return Job ID
     */
    suspend fun addCronJob(
        cronExpression: String,
        command: String,
        name: String = ""
    ): String

    /**
     * Create a one-shot job with delay.
     *
     * @param delay Duration like "5m", "30s", "2h", "1d"
     * @param command Shell command to execute
     * @param name Optional job name
     * @return Job ID
     */
    suspend fun addOneShot(
        delay: String,
        command: String,
        name: String = ""
    ): String

    /**
     * Create an interval-based job.
     *
     * @param intervalMs Interval in milliseconds
     * @param command Shell command to execute
     * @param name Optional job name
     * @return Job ID
     */
    suspend fun addInterval(
        intervalMs: Long,
        command: String,
        name: String = ""
    ): String

    /**
     * Create an agent-based cron job (LLM-powered).
     *
     * @param cronExpression Cron expression
     * @param prompt AI prompt/instruction
     * @param model Optional model selector
     * @param name Optional job name
     * @return Job ID
     */
    suspend fun addAgentJob(
        cronExpression: String,
        prompt: String,
        model: String = "",
        name: String = ""
    ): String

    /**
     * List all cron jobs.
     */
    suspend fun listJobs(): List<CronJobInfo>

    /**
     * Get job details.
     */
    suspend fun getJob(jobId: String): CronJobInfo?

    /**
     * Update job.
     */
    suspend fun updateJob(
        jobId: String,
        expression: String? = null,
        command: String? = null,
        enabled: Boolean? = null
    ): Boolean

    /**
     * Remove job.
     */
    suspend fun removeJob(jobId: String): Boolean

    /**
     * Pause job.
     */
    suspend fun pauseJob(jobId: String): Boolean

    /**
     * Resume job.
     */
    suspend fun resumeJob(jobId: String): Boolean

    /**
     * Get job execution history.
     */
    suspend fun getJobRuns(jobId: String, limit: Int = 10): List<JobRun>
}

/**
 * Information about a cron job.
 */
data class CronJobInfo(
    val id: String,
    val name: String,
    val expression: String,
    val jobType: String, // "shell" or "agent"
    val command: String,
    val enabled: Boolean,
    val createdAt: Long,
    val nextRun: Long,
    val lastRun: Long?,
    val lastStatus: String?, // "success", "error", "running", etc.
    val lastOutput: String?
)

/**
 * Information about a job execution.
 */
data class JobRun(
    val jobId: String,
    val runAt: Long,
    val status: String, // "success", "error", "timeout"
    val output: String,
    val duration: Long
)
