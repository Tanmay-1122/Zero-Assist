/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.dashboard

import java.time.LocalDate
import java.time.LocalTime

/**
 * Time period for greeting generation.
 * Changes 3x per day: Morning (5-11), Afternoon (12-17), Evening (18-4).
 */
enum class GreetingPeriod {
    MORNING,
    AFTERNOON,
    EVENING;

    companion object {
        private const val MORNING_START = 5
        private const val AFTERNOON_START = 12
        private const val EVENING_START = 18
        private const val EVENING_END = 4 // Exclusive, wraps to next day

        /**
         * Determines the greeting period for a given time.
         */
        fun fromTime(time: LocalTime): GreetingPeriod {
            return when (time.hour) {
                in MORNING_START..(AFTERNOON_START - 1) -> MORNING
                in AFTERNOON_START..(EVENING_START - 1) -> AFTERNOON
                else -> EVENING
            }
        }

        /**
         * Determines the greeting period for a given hour (0-23).
         */
        fun fromHour(hour: Int): GreetingPeriod {
            return when (hour) {
                in MORNING_START..(AFTERNOON_START - 1) -> MORNING
                in AFTERNOON_START..(EVENING_START - 1) -> AFTERNOON
                else -> EVENING
            }
        }

        /**
         * Gets the period for the current time.
         */
        fun current(): GreetingPeriod {
            return fromTime(LocalTime.now())
        }

        /**
         * Gets the period for a specific date and hour.
         */
        fun forDateAndHour(date: LocalDate, hour: Int): GreetingPeriod {
            return fromHour(hour)
        }

        /**
         * Returns a human-readable context description for the period.
         */
        fun contextDescription(period: GreetingPeriod): String {
            return when (period) {
                MORNING -> "Start of day, fresh energy, planning ahead"
                AFTERNOON -> "Mid-day momentum, productivity, progress"
                EVENING -> "Winding down, reflection, relaxation"
            }
        }
    }
}