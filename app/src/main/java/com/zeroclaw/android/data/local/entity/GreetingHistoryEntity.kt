/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.zeroclaw.android.ui.screen.dashboard.GreetingPeriod
import com.zeroclaw.android.ui.screen.dashboard.GenerationSource
import java.time.Instant
import java.time.LocalDate

/**
 * Entity storing greeting history to prevent repetition.
 * One entry per user per period per day.
 */
@Entity(
    tableName = "greeting_history",
    indices = [
        Index(value = ["userId", "period", "date"], unique = true),
        Index(value = ["date"]),
        Index(value = ["userId"]),
    ]
)
data class GreetingHistoryEntity(
    /** Composite key: userId_period_date (e.g., "user123_MORNING_2026-01-15") */
    @PrimaryKey
    val id: String,

    /** User identifier */
    val userId: String,

    /** Greeting period (MORNING, AFTERNOON, EVENING) */
    val period: GreetingPeriod,

    /** Calendar date of the greeting */
    val date: LocalDate,

    /** The generated greeting text */
    val greeting: String,

    /** When the greeting was generated */
    val generatedAt: Instant,

    /** Source of generation: AI or LOCAL */
    val source: GenerationSource,
) {
    companion object {
        fun createId(userId: String, period: GreetingPeriod, date: LocalDate): String {
            return "${userId}_${period.name}_${date}"
        }
    }
}