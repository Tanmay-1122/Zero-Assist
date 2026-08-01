/*
 * Copyright 2026 Zero-Assist Community, MIT License
 */

package com.zeroclaw.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Records a single execution of a [ScheduledTaskEntity].
 *
 * Used for run history display and debugging.
 */
@Entity(
    tableName = "scheduled_task_runs",
    foreignKeys = [
        ForeignKey(
            entity = ScheduledTaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["task_id"]),
        Index(value = ["run_at_ms"]),
    ],
)
data class ScheduledTaskRunEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "task_id")
    val taskId: String,
    /** "success", "error", or "timeout". */
    val status: String,
    /** First 2000 chars of output. */
    val output: String = "",
    /** Error message if status is "error". */
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,
    /** Epoch millis when the run started. */
    @ColumnInfo(name = "run_at_ms")
    val runAtMs: Long = System.currentTimeMillis(),
    /** Wall-clock duration in milliseconds. */
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long = 0L,
)
