/*
 * Copyright 2026 Zero-Assist Community, MIT License
 */

package com.zeroclaw.android.service.cron

import android.content.Context
import com.zeroclaw.android.data.local.dao.ScheduledTaskDao
import com.zeroclaw.android.data.local.entity.ScheduledTaskEntity
import com.zeroclaw.android.data.repository.ScheduledTaskRepository
import com.zeroclaw.android.service.scheduler.CronParser
import com.zeroclaw.android.service.scheduler.NativeSchedulerService
import java.util.UUID

/**
 * Native implementation of [TaskSchedulerBridge] backed by Room + AlarmManager.
 *
 * Replaces [CronBridgeImpl] which delegated to the Rust daemon via FFI.
 */
class NativeTaskSchedulerBridge(
    private val repository: ScheduledTaskRepository,
    private val taskDao: ScheduledTaskDao,
    private val context: Context,
) : TaskSchedulerBridge {

    override suspend fun addCronJob(
        cronExpression: String,
        command: String,
        name: String,
    ): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val nextRun = CronParser.nextRun(cronExpression, now)
            ?: throw IllegalArgumentException("Invalid cron expression: $cronExpression")

        val task = ScheduledTaskEntity(
            id = id,
            name = name.ifBlank { id },
            scheduleType = "cron",
            cronExpression = cronExpression,
            command = command,
            nextRunMs = nextRun,
            createdAtMs = now,
        )
        repository.upsertTask(task)
        NativeSchedulerService.scheduleAlarm(context, task)
        return id
    }

    override suspend fun addOneShot(
        delay: String,
        command: String,
        name: String,
    ): String {
        val delayMs = parseDelay(delay)
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val task = ScheduledTaskEntity(
            id = id,
            name = name.ifBlank { id },
            scheduleType = "every",
            intervalMs = delayMs,
            command = command,
            nextRunMs = now + delayMs,
            oneShot = true,
            createdAtMs = now,
        )
        repository.upsertTask(task)
        NativeSchedulerService.scheduleAlarm(context, task)
        return id
    }

    override suspend fun addInterval(
        intervalMs: Long,
        command: String,
        name: String,
    ): String {
        require(intervalMs > 0) { "Interval must be positive" }
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val task = ScheduledTaskEntity(
            id = id,
            name = name.ifBlank { id },
            scheduleType = "every",
            intervalMs = intervalMs,
            command = command,
            nextRunMs = now + intervalMs,
            createdAtMs = now,
        )
        repository.upsertTask(task)
        NativeSchedulerService.scheduleAlarm(context, task)
        return id
    }

    override suspend fun addAgentJob(
        cronExpression: String,
        prompt: String,
        model: String,
        name: String,
    ): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val nextRun = CronParser.nextRun(cronExpression, now)
            ?: throw IllegalArgumentException("Invalid cron expression: $cronExpression")

        val task = ScheduledTaskEntity(
            id = id,
            name = name.ifBlank { id },
            scheduleType = "cron",
            cronExpression = cronExpression,
            command = prompt,
            jobType = "agent",
            nextRunMs = nextRun,
            createdAtMs = now,
        )
        repository.upsertTask(task)
        NativeSchedulerService.scheduleAlarm(context, task)
        return id
    }

    override suspend fun listJobs(): List<TaskInfo> {
        return repository.listAll().map { it.toInfo() }
    }

    override suspend fun addJobAt(
        timestampMs: Long,
        command: String,
        name: String,
    ): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        require(timestampMs > now) { "Timestamp must be in the future" }
        val task = ScheduledTaskEntity(
            id = id,
            name = name.ifBlank { id },
            scheduleType = "at",
            atMs = timestampMs,
            command = command,
            nextRunMs = timestampMs,
            oneShot = true,
            createdAtMs = now,
        )
        repository.upsertTask(task)
        NativeSchedulerService.scheduleAlarm(context, task)
        return id
    }

    override suspend fun getJob(jobId: String): TaskInfo? {
        return repository.getTask(jobId)?.toInfo()
    }

    override suspend fun updateJob(
        jobId: String,
        expression: String?,
        command: String?,
        enabled: Boolean?,
    ): Boolean {
        val task = repository.getTask(jobId) ?: return false
        val updated = task.copy(
            cronExpression = expression ?: task.cronExpression,
            command = command ?: task.command,
        )
        repository.upsertTask(updated)
        if (enabled != null) {
            if (enabled) {
                repository.resumeTask(jobId)
                NativeSchedulerService.reschedule(updated.copy(paused = false), taskDao, context)
            } else {
                repository.pauseTask(jobId)
                NativeSchedulerService.cancelAlarm(context, jobId)
            }
        }
        return true
    }

    override suspend fun removeJob(jobId: String): Boolean {
        NativeSchedulerService.cancelAlarm(context, jobId)
        repository.deleteTask(jobId)
        return true
    }

    override suspend fun pauseJob(jobId: String): Boolean {
        repository.pauseTask(jobId)
        NativeSchedulerService.cancelAlarm(context, jobId)
        return true
    }

    override suspend fun resumeJob(jobId: String): Boolean {
        repository.resumeTask(jobId)
        val task = repository.getTask(jobId) ?: return false
        NativeSchedulerService.reschedule(task, taskDao, context)
        return true
    }

    override suspend fun getJobRuns(jobId: String, limit: Int): List<TaskRun> {
        return repository.listRuns(jobId, limit).map { run ->
            TaskRun(
                taskId = run.taskId,
                runAt = run.runAtMs,
                status = run.status,
                output = run.output,
                duration = run.durationMs,
            )
        }
    }

    private fun parseDelay(delay: String): Long {
        val regex = """(\d+)\s*([smhd])""".toRegex(RegexOption.IGNORE_CASE)
        val match = regex.find(delay) ?: throw IllegalArgumentException("Invalid delay: $delay")
        val number = match.groupValues[1].toLongOrNull()
            ?: throw IllegalArgumentException("Invalid delay number: $delay")
        val unit = match.groupValues[2].lowercase()
        return when (unit) {
            "s" -> number * 1_000L
            "m" -> number * 60_000L
            "h" -> number * 3_600_000L
            "d" -> number * 86_400_000L
            else -> throw IllegalArgumentException("Unknown delay unit: $unit")
        }
    }
}

private fun ScheduledTaskEntity.toInfo(): TaskInfo =
    TaskInfo(
        id = id,
        name = name,
        expression = when (scheduleType) {
            "cron" -> cronExpression
            "at" -> "@at"
            "every" -> "@every ${intervalMs}ms"
            else -> scheduleType
        },
        jobType = jobType,
        command = command,
        enabled = !paused,
        createdAt = createdAtMs,
        nextRun = nextRunMs,
        lastRun = lastRunMs,
        lastStatus = lastStatus,
        lastOutput = null,
    )
