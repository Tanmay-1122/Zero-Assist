/*
 * Copyright 2026 Zero-Assist Community, MIT License
 */

package com.zeroclaw.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.zeroclaw.android.data.local.entity.ScheduledTaskEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for [ScheduledTaskEntity] records.
 *
 * Provides reactive queries for the scheduled tasks UI and write operations
 * for the native scheduler service.
 */
@Dao
interface ScheduledTaskDao {

    @Query("SELECT * FROM scheduled_tasks ORDER BY next_run_ms ASC")
    fun observeAll(): Flow<List<ScheduledTaskEntity>>

    @Query("SELECT * FROM scheduled_tasks ORDER BY next_run_ms ASC")
    suspend fun listAll(): List<ScheduledTaskEntity>

    @Query("SELECT * FROM scheduled_tasks WHERE id = :id")
    suspend fun getById(id: String): ScheduledTaskEntity?

    @Query("SELECT * FROM scheduled_tasks WHERE paused = 0 AND one_shot = 0 ORDER BY next_run_ms ASC")
    suspend fun listActiveRecurring(): List<ScheduledTaskEntity>

    @Query("SELECT * FROM scheduled_tasks WHERE paused = 0 AND one_shot = 1 AND next_run_ms > 0 ORDER BY next_run_ms ASC")
    suspend fun listActiveOneShots(): List<ScheduledTaskEntity>

    @Query("SELECT * FROM scheduled_tasks WHERE paused = 0 AND next_run_ms <= :nowMs ORDER BY next_run_ms ASC")
    suspend fun listDue(nowMs: Long = System.currentTimeMillis()): List<ScheduledTaskEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(task: ScheduledTaskEntity)

    @Update
    suspend fun update(task: ScheduledTaskEntity)

    @Query("UPDATE scheduled_tasks SET paused = :paused WHERE id = :id")
    suspend fun setPaused(id: String, paused: Boolean)

    @Query("UPDATE scheduled_tasks SET next_run_ms = :nextRunMs WHERE id = :id")
    suspend fun updateNextRun(id: String, nextRunMs: Long)

    @Query("UPDATE scheduled_tasks SET last_run_ms = :lastRunMs, last_status = :lastStatus WHERE id = :id")
    suspend fun updateLastRun(id: String, lastRunMs: Long, lastStatus: String)

    @Query("DELETE FROM scheduled_tasks WHERE id = :id")
    suspend fun deleteById(id: String)

    @Query("DELETE FROM scheduled_tasks WHERE one_shot = 1 AND paused = 0 AND last_run_ms IS NOT NULL")
    suspend fun pruneCompletedOneShots()
}
