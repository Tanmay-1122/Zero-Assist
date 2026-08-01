/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.zeroclaw.android.data.local.entity.GreetingHistoryEntity
import com.zeroclaw.android.ui.screen.dashboard.GreetingPeriod
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

/**
 * Data access object for greeting history.
 */
@Dao
interface GreetingHistoryDao {

    /**
     * Inserts a new greeting history record.
     * Uses REPLACE to handle duplicate key (same user/period/date).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: GreetingHistoryEntity)

    /**
     * Gets the greeting for a specific user, period, and date.
     */
    @Query("SELECT * FROM greeting_history WHERE userId = :userId AND period = :period AND date = :date")
    suspend fun getGreeting(
        userId: String,
        period: GreetingPeriod,
        date: LocalDate
    ): GreetingHistoryEntity?

    /**
     * Gets recent greeting history for a user and period (for AI prompt context).
     * Returns most recent first, limited by [limit].
     */
    @Query("SELECT greeting FROM greeting_history WHERE userId = :userId AND period = :period ORDER BY date DESC LIMIT :limit")
    suspend fun getRecentGreetings(
        userId: String,
        period: GreetingPeriod,
        limit: Int
    ): List<String>

    /**
     * Gets all greeting history for a user and period.
     */
    @Query("SELECT * FROM greeting_history WHERE userId = :userId AND period = :period ORDER BY date DESC")
    fun getAllHistoryForPeriod(
        userId: String,
        period: GreetingPeriod
    ): Flow<List<GreetingHistoryEntity>>

    /**
     * Deletes greeting history older than [cutoffDate] for a specific user and period.
     * Used for 90-day retention.
     */
    @Query("DELETE FROM greeting_history WHERE userId = :userId AND period = :period AND date < :cutoffDate")
    suspend fun deleteOldHistory(
        userId: String,
        period: GreetingPeriod,
        cutoffDate: LocalDate
    )

    /**
     * Deletes ALL greeting history older than [cutoffDate] (across all users/periods).
     * Used for maintenance cleanup.
     */
    @Query("DELETE FROM greeting_history WHERE date < :cutoffDate")
    suspend fun deleteAllOldHistory(cutoffDate: LocalDate)

    /**
     * Counts total history entries for a user.
     */
    @Query("SELECT COUNT(*) FROM greeting_history WHERE userId = :userId")
    suspend fun countForUser(userId: String): Int
}