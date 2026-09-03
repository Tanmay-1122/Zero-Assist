/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.db.greeting

import androidx.room.TypeConverter
import com.zeroclaw.android.ui.screen.dashboard.GreetingPeriod
import com.zeroclaw.android.ui.screen.dashboard.GenerationSource
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Room type converters for greeting-related types.
 */
class GreetingTypeConverters {

    companion object {
        private const val DATE_FORMAT = "yyyy-MM-dd"
        private val dateFormatter = DateTimeFormatter.ofPattern(DATE_FORMAT)
    }

    @TypeConverter
    fun greetingPeriodToString(period: GreetingPeriod?): String? {
        return period?.name
    }

    @TypeConverter
    fun stringToGreetingPeriod(value: String?): GreetingPeriod? {
        return value?.let { GreetingPeriod.valueOf(it) }
    }

    @TypeConverter
    fun generationSourceToString(source: GenerationSource?): String? {
        return source?.name
    }

    @TypeConverter
    fun stringToGenerationSource(value: String?): GenerationSource? {
        return value?.let { GenerationSource.valueOf(it) }
    }

    @TypeConverter
    fun localDateToString(date: LocalDate?): String? {
        return date?.format(dateFormatter)
    }

    @TypeConverter
    fun stringToLocalDate(value: String?): LocalDate? {
        return value?.let {
            try {
                LocalDate.parse(it, dateFormatter)
            } catch (e: DateTimeParseException) {
                null
            }
        }
    }

    @TypeConverter
    fun instantToLong(instant: Instant?): Long? {
        return instant?.toEpochMilli()
    }

    @TypeConverter
    fun longToInstant(value: Long?): Instant? {
        return value?.let { Instant.ofEpochMilli(it) }
    }
}