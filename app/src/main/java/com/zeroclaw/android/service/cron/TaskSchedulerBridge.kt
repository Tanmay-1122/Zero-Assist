/*
 * Copyright 2026 Zero-Assist Community, MIT License
 */

package com.zeroclaw.android.service.cron

/**
 * Bridge interface for scheduled task operations.
 *
 * Replaces the Rust daemon FFI bridge with native Android scheduling
 * backed by Room + AlarmManager + WorkManager.
 */
interface TaskSchedulerBridge {

    /**
     * Create a recurring task with a cron expression.
     *
     * @param cronExpression 5-field cron expression (e.g., "0 9 * * *" for 9 AM daily)
     * @param command Command to execute
     * @param name Optional task name
     * @return Task ID
     */
    suspend fun addCronJob(
        cronExpression: String,
        command: String,
        name: String = "",
    ): String

    /**
     * Create a one-shot task with delay.
     *
     * @param delay Duration like "5m", "30s", "2h", "1d"
     * @param command Command to execute
     * @param name Optional task name
     * @return Task ID
     */
    suspend fun addOneShot(
        delay: String,
        command: String,
        name: String = "",
    ): String

    /**
     * Create an interval-based task.
     *
     * @param intervalMs Interval in milliseconds
     * @param command Command to execute
     * @param name Optional task name
     * @return Task ID
     */
    suspend fun addInterval(
        intervalMs: Long,
        command: String,
        name: String = "",
    ): String

    /**
     * Create an agent-based task (LLM-powered).
     *
     * @param cronExpression Cron expression
     * @param prompt AI prompt/instruction
     * @param model Optional model selector
     * @param name Optional task name
     * @return Task ID
     */
    suspend fun addAgentJob(
        cronExpression: String,
        prompt: String,
        model: String = "",
        name: String = "",
    ): String

    /** List all scheduled tasks. */
    suspend fun listJobs(): List<TaskInfo>

    /** Create a one-shot task that fires at a specific epoch millis timestamp. */
    suspend fun addJobAt(
        timestampMs: Long,
        command: String,
        name: String = "",
    ): String

    /** Get task details. */
    suspend fun getJob(jobId: String): TaskInfo?

    /** Update task schedule or command. */
    suspend fun updateJob(
        jobId: String,
        expression: String? = null,
        command: String? = null,
        enabled: Boolean? = null,
    ): Boolean

    /** Remove task. */
    suspend fun removeJob(jobId: String): Boolean

    /** Pause task. */
    suspend fun pauseJob(jobId: String): Boolean

    /** Resume task. */
    suspend fun resumeJob(jobId: String): Boolean

    /** Get task execution history. */
    suspend fun getJobRuns(jobId: String, limit: Int = 10): List<TaskRun>
}

/**
 * Information about a scheduled task.
 */
data class TaskInfo(
    val id: String,
    val name: String,
    val expression: String,
    val jobType: String, // "shell" or "agent"
    val command: String,
    val enabled: Boolean,
    val createdAt: Long,
    val nextRun: Long,
    val lastRun: Long?,
    val lastStatus: String?,
    val lastOutput: String?,
)

/**
 * Information about a task execution.
 */
data class TaskRun(
    val taskId: String,
    val runAt: Long,
    val status: String,
    val output: String,
    val duration: Long,
)
