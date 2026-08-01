/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.zeroclaw.android.data.local.entity.InteractionOutcomeEntity

/**
 * Data access for interaction outcome statistics.
 *
 * Feeds the provider leaderboard in Settings > Providers, showing which
 * providers and models produce the most successful responses.
 */
@Dao
interface InteractionOutcomeDao {
    /** Inserts a new interaction outcome record. */
    @Insert
    suspend fun insert(outcome: InteractionOutcomeEntity)

    /** Returns the last [limit] outcomes, newest first. */
    @Query("SELECT * FROM interaction_outcomes ORDER BY created_at DESC LIMIT :limit")
    suspend fun recentOutcomes(limit: Int): List<InteractionOutcomeEntity>

    /** Returns total stored outcome count. */
    @Query("SELECT COUNT(*) FROM interaction_outcomes")
    suspend fun outcomeCount(): Int

    /**
     * Aggregates interaction outcomes into a provider leaderboard.
     *
     * Groups by provider and model, computing success rate and average
     * latency for the last 30 days. Results are sorted by success rate
     * descending so the best provider appears first in the UI.
     *
     * @param thirtyDaysAgoMillis Epoch millis cutoff (older outcomes are excluded).
     * @return Rows ordered by success rate descending.
     */
    @Query(
        """
        SELECT provider, model,
               COUNT(*) as total,
               SUM(CASE WHEN outcome = 'SUCCESS' THEN 1 ELSE 0 END) * 100.0 / COUNT(*) as successRate,
               AVG(latency_ms) as avgLatency
        FROM interaction_outcomes
        WHERE created_at > :thirtyDaysAgoMillis
        GROUP BY provider, model
        ORDER BY successRate DESC
        """,
    )
    suspend fun providerLeaderboard(thirtyDaysAgoMillis: Long): List<LeaderboardRow>
}

/**
 * Aggregated provider leaderboard row returned by [InteractionOutcomeDao.providerLeaderboard].
 *
 * @property provider Provider name (e.g. "anthropic").
 * @property model Model name (e.g. "claude-sonnet-4-20250514").
 * @property total Total interactions in the measured period.
 * @property successRate Percentage of SUCCESS outcomes (0.0–100.0).
 * @property avgLatency Average response latency in milliseconds.
 */
data class LeaderboardRow(
    val provider: String,
    val model: String,
    val total: Int,
    val successRate: Double,
    val avgLatency: Double,
)
