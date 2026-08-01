/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Records a single skill tool execution for history tracking.
 *
 * Stored in the `skill_execution_history` table. UI components can observe
 * this table to show recent skill activity and diagnose failures.
 *
 * @property id Auto-generated primary key.
 * @property skillName The skill that owns the executed tool.
 * @property toolName The specific tool within the skill.
 * @property status Execution status: `"running"`, `"success"`, `"failed"`, `"timeout"`.
 * @property inputSummary URL and method (not full body, for privacy).
 * @property outputSummary First 500 characters of the response.
 * @property errorMessage Error details when [status] is `"failed"`.
 * @property startedAt Epoch milliseconds when execution began.
 * @property completedAt Epoch milliseconds when execution finished.
 * @property durationMs Wall-clock duration in milliseconds.
 */
@Entity(
    tableName = "skill_execution_history",
    indices = [
        Index(value = ["skill_name", "started_at"]),
        Index(value = ["started_at"]),
        Index(value = ["status"]),
    ],
)
data class SkillExecutionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    @ColumnInfo(name = "skill_name")
    val skillName: String,
    @ColumnInfo(name = "tool_name")
    val toolName: String,
    val status: String,
    @ColumnInfo(name = "input_summary")
    val inputSummary: String? = null,
    @ColumnInfo(name = "output_summary")
    val outputSummary: String? = null,
    @ColumnInfo(name = "error_message")
    val errorMessage: String? = null,
    @ColumnInfo(name = "started_at")
    val startedAt: Long,
    @ColumnInfo(name = "completed_at")
    val completedAt: Long? = null,
    @ColumnInfo(name = "duration_ms")
    val durationMs: Long? = null,
)
