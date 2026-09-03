/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.repository

import com.zeroclaw.android.data.local.dao.GreetingHistoryDao
import com.zeroclaw.android.data.local.entity.GreetingHistoryEntity
import com.zeroclaw.android.ui.screen.dashboard.GenerationSource
import com.zeroclaw.android.ui.screen.dashboard.GreetingPeriod
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository for managing greeting history.
 * Tracks greetings per user per period per day to prevent repetition.
 */
@Singleton
class GreetingHistoryRepository @Inject constructor(
    private val dao: GreetingHistoryDao,
) {

    companion object {
        /** Maximum history retention in days (90 days = ~3 months) */
        private const val RETENTION_DAYS = 90L

        /** Maximum recent greetings to fetch for AI context */
        private const val RECENT_LIMIT = 10
    }

    /**
     * Saves a generated greeting to history.
     */
    suspend fun saveGreeting(
        userId: String,
        period: GreetingPeriod,
        date: LocalDate,
        greeting: String,
        source: GenerationSource,
    ) {
        val entity = GreetingHistoryEntity(
            id = GreetingHistoryEntity.createId(userId, period, date),
            userId = userId,
            period = period,
            date = date,
            greeting = greeting,
            generatedAt = java.time.Instant.now(),
            source = source,
        )
        withContext(Dispatchers.IO) {
            dao.insert(entity)
        }
    }

    /**
     * Gets the greeting for a specific user, period, and date (if already generated today).
     */
    suspend fun getGreetingForToday(
        userId: String,
        period: GreetingPeriod,
        date: LocalDate,
    ): String? {
        return withContext(Dispatchers.IO) {
            dao.getGreeting(userId, period, date)?.greeting
        }
    }

    /**
     * Gets recent greetings for a user and period to avoid repetition in AI prompts.
     * Returns most recent first.
     */
    suspend fun getRecentGreetings(
        userId: String,
        period: GreetingPeriod,
        limit: Int = RECENT_LIMIT,
    ): List<String> {
        return withContext(Dispatchers.IO) {
            dao.getRecentGreetings(userId, period, limit)
        }
    }

    /**
     * Observes all history for a user and period.
     */
    fun observeHistoryForPeriod(
        userId: String,
        period: GreetingPeriod,
    ): Flow<List<GreetingHistoryEntity>> {
        return dao.getAllHistoryForPeriod(userId, period)
    }

    /**
     * Cleans up history older than RETENTION_DAYS for a specific user and period.
     * Should be called periodically (e.g., on app start).
     */
    suspend fun cleanupOldHistory(userId: String, period: GreetingPeriod) {
        val cutoffDate = LocalDate.now().minusDays(RETENTION_DAYS)
        withContext(Dispatchers.IO) {
            dao.deleteOldHistory(userId, period, cutoffDate)
        }
    }

    /**
     * Cleans up ALL history older than RETENTION_DAYS (maintenance).
     */
    suspend fun cleanupAllOldHistory() {
        val cutoffDate = LocalDate.now().minusDays(RETENTION_DAYS)
        withContext(Dispatchers.IO) {
            dao.deleteAllOldHistory(cutoffDate)
        }
    }

    /**
     * Gets total history count for a user (for debugging/monitoring).
     */
    suspend fun getHistoryCount(userId: String): Int {
        return withContext(Dispatchers.IO) {
            dao.countForUser(userId)
        }
    }
}