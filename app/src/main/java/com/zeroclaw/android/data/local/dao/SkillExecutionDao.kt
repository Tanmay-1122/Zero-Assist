/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.zeroclaw.android.data.local.entity.SkillExecutionEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for [SkillExecutionEntity] records.
 *
 * Provides reactive queries for the Skills execution history UI
 * and write operations for the [SkillExecutionTracker].
 */
@Dao
interface SkillExecutionDao {
    /**
     * Observes execution history for a specific skill, newest first.
     *
     * @param skillName The skill to filter by.
     * @param limit Maximum number of records to return.
     * @return A [Flow] emitting the list whenever the table changes.
     */
    @Query(
        "SELECT * FROM skill_execution_history " +
            "WHERE skill_name = :skillName " +
            "ORDER BY started_at DESC LIMIT :limit",
    )
    fun observeBySkill(
        skillName: String,
        limit: Int = 100,
    ): Flow<List<SkillExecutionEntity>>

    /**
     * Observes all recent executions across all skills, newest first.
     *
     * @param limit Maximum number of records to return.
     * @return A [Flow] emitting the list whenever the table changes.
     */
    @Query("SELECT * FROM skill_execution_history ORDER BY started_at DESC LIMIT :limit")
    fun observeRecent(limit: Int = 100): Flow<List<SkillExecutionEntity>>

    /**
     * Inserts a new execution record and returns its generated row ID.
     *
     * @param entity The record to insert.
     * @return The auto-generated row ID.
     */
    @Insert
    suspend fun insert(entity: SkillExecutionEntity): Long

    /**
     * Updates a running execution with completion data.
     *
     * @param id Row ID of the record to update.
     * @param status Final status: `"success"`, `"failed"`, or `"timeout"`.
     * @param outputSummary First 500 characters of the response, or null.
     * @param errorMessage Error details when status is `"failed"`, or null.
     * @param completedAt Epoch milliseconds when execution finished.
     * @param durationMs Wall-clock duration in milliseconds.
     */
    @Query(
        "UPDATE skill_execution_history SET " +
            "status = :status, output_summary = :outputSummary, " +
            "error_message = :errorMessage, completed_at = :completedAt, " +
            "duration_ms = :durationMs WHERE id = :id",
    )
    @Suppress("LongParameterList")
    suspend fun updateCompletion(
        id: Long,
        status: String,
        outputSummary: String?,
        errorMessage: String?,
        completedAt: Long,
        durationMs: Long,
    )

    /**
     * Deletes oldest records for a skill, keeping only the most recent [retainCount].
     *
     * Call this after each write to prevent unbounded table growth.
     *
     * @param skillName The skill whose old records should be pruned.
     * @param retainCount Number of most-recent records to keep.
     */
    @Query(
        "DELETE FROM skill_execution_history " +
            "WHERE skill_name = :skillName AND id NOT IN (" +
            "  SELECT id FROM skill_execution_history " +
            "  WHERE skill_name = :skillName " +
            "  ORDER BY started_at DESC LIMIT :retainCount" +
            ")",
    )
    suspend fun pruneOldest(
        skillName: String,
        retainCount: Int = 500,
    )
}
