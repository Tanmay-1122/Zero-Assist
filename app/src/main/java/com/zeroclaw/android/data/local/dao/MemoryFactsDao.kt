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
import com.zeroclaw.android.data.local.entity.MemoryFactEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data access for read-only memory fact mirrors.
 *
 * The canonical source of truth is the Rust daemon's brain.db.
 * This DAO provides reactive query access for the Memory Browser UI
 * via [getAllFacts] and [getFactsByCategory].
 */
@Dao
interface MemoryFactsDao {
    /** Returns all facts ordered by creation time (newest first). */
    @Query("SELECT * FROM memory_facts_mirror ORDER BY created_at DESC")
    fun getAllFacts(): Flow<List<MemoryFactEntity>>

    /** Returns facts filtered by category, newest first. */
    @Query("SELECT * FROM memory_facts_mirror WHERE category = :category ORDER BY created_at DESC")
    fun getFactsByCategory(category: String): Flow<List<MemoryFactEntity>>

    /** Inserts or replaces a fact (used by mirror sync from Rust FFI events). */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFact(fact: MemoryFactEntity)

    /** Deletes a fact by ID (used when Rust daemon forgets a fact). */
    @Query("DELETE FROM memory_facts_mirror WHERE id = :id")
    suspend fun deleteFact(id: String)

    /** Returns total fact count for display in the Memory Browser header. */
    @Query("SELECT COUNT(*) FROM memory_facts_mirror")
    suspend fun factCount(): Int
}
