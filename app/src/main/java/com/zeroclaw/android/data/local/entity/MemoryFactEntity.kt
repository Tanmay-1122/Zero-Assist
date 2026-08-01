/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Read-only Android-side mirror of memory facts for UI display.
 *
 * The canonical source of truth is the Rust daemon's `brain.db`.
 * This table is populated by the [MemoryBridge] whenever the daemon
 * writes a new fact via FFI, providing instant reactive UI updates
 * without polling the Rust layer.
 *
 * @property id Unique fact identifier (mirrors Rust UUID).
 * @property key Fact key (e.g. "user_name", "preference_a1b2c3").
 * @property contentPreview First 200 chars of content (privacy-safe).
 * @property category Memory category: "core", "daily", or "custom".
 * @property tags Comma-separated tags for filtering.
 * @property confidence Extraction confidence in range [0.0, 1.0].
 * @property source Extraction source: "heuristic", "llm", "agent", or "user".
 * @property accessCount Number of times this fact has been recalled.
 * @property createdAt Epoch millis when the fact was first stored.
 * @property lastAccessedAt Epoch millis of last recall, null if never recalled.
 * @property decayHalfLifeDays Ebbinghaus forgetting curve half-life in days.
 */
@Entity(
    tableName = "memory_facts_mirror",
    indices = [
        Index(value = ["category"]),
        Index(value = ["last_accessed_at"]),
    ],
)
data class MemoryFactEntity(
    @PrimaryKey val id: String,
    val key: String,
    @ColumnInfo(name = "content_preview") val contentPreview: String,
    val category: String,
    val tags: String,
    val confidence: Double,
    val source: String,
    @ColumnInfo(name = "access_count") val accessCount: Int,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "last_accessed_at") val lastAccessedAt: Long?,
    @ColumnInfo(name = "decay_half_life_days") val decayHalfLifeDays: Int,
)
