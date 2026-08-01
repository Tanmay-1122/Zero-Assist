/*
 * Copyright 2026 Zero-Assist Community, MIT License
 */

package com.zeroclaw.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.zeroclaw.android.data.local.entity.ScheduledTaskRunEntity

/**
 * Data access object for [ScheduledTaskRunEntity] records.
 */
@Dao
interface ScheduledTaskRunDao {

    @Query(
        "SELECT * FROM scheduled_task_runs WHERE task_id = :taskId ORDER BY run_at_ms DESC LIMIT :limit",
    )
    suspend fun listByTask(taskId: String, limit: Int = 20): List<ScheduledTaskRunEntity>

    @Insert
    suspend fun insert(run: ScheduledTaskRunEntity): Long

    @Query("DELETE FROM scheduled_task_runs WHERE task_id = :taskId")
    suspend fun deleteByTask(taskId: String)

    @Query(
        "DELETE FROM scheduled_task_runs WHERE id NOT IN (" +
            "SELECT id FROM scheduled_task_runs ORDER BY run_at_ms DESC LIMIT :retainCount)",
    )
    suspend fun pruneOld(retainCount: Int = 500)
}
