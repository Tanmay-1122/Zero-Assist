/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.db.memory

import androidx.room.TypeConverter
import org.json.JSONArray

/**
 * Room type converters for memory system.
 *
 * Handles conversion between Kotlin types (List, etc.) and database-storable strings.
 */
class MemoryTypeConverters {

    /**
     * Converts List<String> to JSON string for storage.
     */
    @TypeConverter
    fun stringListToJson(list: List<String>?): String? {
        if (list == null || list.isEmpty()) return null
        return JSONArray(list).toString()
    }

    /**
     * Converts JSON string to List<String>.
     */
    @TypeConverter
    fun jsonToStringList(json: String?): List<String> {
        if (json == null || json.isEmpty()) return emptyList()
        return try {
            val array = JSONArray(json)
            val list = mutableListOf<String>()
            for (i in 0 until array.length()) {
                list.add(array.getString(i))
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }
}
