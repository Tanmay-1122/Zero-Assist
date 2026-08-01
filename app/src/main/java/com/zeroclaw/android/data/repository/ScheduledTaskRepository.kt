/*
 * Copyright 2026 Zero-Assist Community, MIT License
 */

package com.zeroclaw.android.data.repository

import com.zeroclaw.android.data.local.dao.ScheduledTaskDao
import com.zeroclaw.android.data.local.dao.ScheduledTaskRunDao
import com.zeroclaw.android.data.local.entity.ScheduledTaskEntity
import com.zeroclaw.android.data.local.entity.ScheduledTaskRunEntity
import kotlinx.coroutines.flow.Flow

/**
 * Repository for scheduled task CRUD and run history.
 *
 * This is the single source of truth for the native scheduler, replacing
 * the Rust daemon's cron store.
 */
class ScheduledTaskRepository(
    private val taskDao: ScheduledTaskDao,
    private val runDao: ScheduledTaskRunDao,
) {
    /** Observe all tasks, ordered by next run. */
    fun observeAll(): Flow<List<ScheduledTaskEntity>> = taskDao.observeAll()

    /** List all tasks (non-reactive). */
    suspend fun listAll(): List<ScheduledTaskEntity> = taskDao.listAll()

    /** Get a single task by ID. */
    suspend fun getTask(id: String): ScheduledTaskEntity? = taskDao.getById(id)

    /** Insert or update a task. */
    suspend fun upsertTask(task: ScheduledTaskEntity) = taskDao.upsert(task)

    /** Update the next run time for a task. */
    suspend fun updateNextRun(id: String, nextRunMs: Long) = taskDao.updateNextRun(id, nextRunMs)

    /** Update the last run status. */
    suspend fun updateLastRun(id: String, lastRunMs: Long, lastStatus: String) =
        taskDao.updateLastRun(id, lastRunMs, lastStatus)

    /** Pause a task. */
    suspend fun pauseTask(id: String) = taskDao.setPaused(id, paused = true)

    /** Resume a task. */
    suspend fun resumeTask(id: String) = taskDao.setPaused(id, paused = false)

    /** Delete a task and its run history (cascade). */
    suspend fun deleteTask(id: String) = taskDao.deleteById(id)

    /** List run history for a task. */
    suspend fun listRuns(taskId: String, limit: Int = 20): List<ScheduledTaskRunEntity> =
        runDao.listByTask(taskId, limit)

    /** List due tasks (paused=false, next_run_ms <= now). */
    suspend fun listDue(): List<ScheduledTaskEntity> = taskDao.listDue()

    /** Prune completed one-shot tasks. */
    suspend fun pruneOneShots() = taskDao.pruneCompletedOneShots()
}
