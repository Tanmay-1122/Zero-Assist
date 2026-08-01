/*
 * Copyright 2026 Zero-Assist Community, MIT License
 */

package com.zeroclaw.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A scheduled task managed by the native Android scheduler.
 *
 * Supports three schedule types:
 * - **cron**: recurring based on a 5-field cron expression
 * - **at**: one-shot at a specific epoch millis timestamp
 * - **every**: fixed-interval repeating in milliseconds
 *
 * Execution is handled by [com.zeroclaw.android.service.scheduler.NativeSchedulerService]
 * using AlarmManager for exact timing and WorkManager for reliable execution.
 */
@Entity(
    tableName = "scheduled_tasks",
    indices = [
        Index(value = ["next_run_ms"]),
        Index(value = ["paused"]),
        Index(value = ["one_shot"]),
    ],
)
data class ScheduledTaskEntity(
    @PrimaryKey
    val id: String,
    val name: String = "",
    /** Schedule type: "cron", "at", or "every". */
    @ColumnInfo(name = "schedule_type")
    val scheduleType: String,
    /** Cron expression (5-field) for "cron" type, empty for others. */
    @ColumnInfo(name = "cron_expression")
    val cronExpression: String = "",
    /** Target epoch millis for "at" type, 0 for others. */
    @ColumnInfo(name = "at_ms")
    val atMs: Long = 0L,
    /** Interval millis for "every" type, 0 for others. */
    @ColumnInfo(name = "interval_ms")
    val intervalMs: Long = 0L,
    /** Command string or agent prompt to execute. */
    val command: String,
    /** Job type: "shell" or "agent". */
    @ColumnInfo(name = "job_type")
    val jobType: String = "shell",
    /** Epoch millis of the next scheduled run. */
    @ColumnInfo(name = "next_run_ms")
    val nextRunMs: Long,
    /** Epoch millis of the last completed run, or null. */
    @ColumnInfo(name = "last_run_ms")
    val lastRunMs: Long? = null,
    /** Status string from the last run (e.g. "ok", "error: ..."), or null. */
    @ColumnInfo(name = "last_status")
    val lastStatus: String? = null,
    /** Whether this job is currently paused. */
    val paused: Boolean = false,
    /** Whether this is a one-shot job that fires once then self-removes. */
    @ColumnInfo(name = "one_shot")
    val oneShot: Boolean = false,
    /** Epoch millis when this task was created. */
    @ColumnInfo(name = "created_at_ms")
    val createdAtMs: Long = System.currentTimeMillis(),
)
