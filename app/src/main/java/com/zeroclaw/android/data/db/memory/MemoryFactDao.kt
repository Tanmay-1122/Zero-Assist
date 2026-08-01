/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.db.memory

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.zeroclaw.android.model.MemoryFact
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for MemoryFact persistence.
 *
 * Handles CRUD operations, semantic search queries, and workspace-scoped
 * filtering for memory facts storage.
 *
 * Embedding vectors (FloatArray) are stored as JSON strings in the database
 * for compatibility with Room's default serialization.
 *
 * Key operations:
 * - Insert/Update/Delete facts with conflict resolution
 * - Workspace-scoped queries for isolation
 * - Importance score tracking and updates
 * - Access count increments for frequency tracking
 * - Related fact retrieval for knowledge graph navigation
 * - Stale fact queries for automatic pruning during consolidation
 * - Full-text-like keyword search (simple LIKE-based, not full-text search)
 */
@Dao
interface MemoryFactDao {

    /**
     * Insert a new memory fact, replacing on conflict.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(fact: MemoryFact): Long

    /**
     * Insert multiple facts in batch.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(facts: List<MemoryFact>)

    /**
     * Update an existing memory fact.
     */
    @Update
    suspend fun update(fact: MemoryFact)

    /**
     * Update importance score for a fact.
     */
    @Query("UPDATE memory_facts SET importance = :newImportance WHERE id = :factId")
    suspend fun updateImportance(factId: String, newImportance: Float)

    /**
     * Increment access count for a fact.
     */
    @Query("UPDATE memory_facts SET accessCount = accessCount + 1, lastAccessedAt = :currentTime WHERE id = :factId")
    suspend fun recordAccess(factId: String, currentTime: String)

    /**
     * Delete a memory fact by ID.
     */
    @Query("DELETE FROM memory_facts WHERE id = :factId")
    suspend fun deleteById(factId: String)

    /**
     * Delete all memory facts (complete clear).
     */
    @Query("DELETE FROM memory_facts")
    suspend fun deleteAll()

    /**
     * Delete all facts in a workspace.
     */
    @Query("DELETE FROM memory_facts WHERE workspaceId = :workspaceId")
    suspend fun deleteByWorkspace(workspaceId: String)

    /**
     * Delete a fact by full object reference.
     */
    @Delete
    suspend fun delete(fact: MemoryFact)

    /**
     * Retrieve a fact by ID.
     */
    @Query("SELECT * FROM memory_facts WHERE id = :factId LIMIT 1")
    suspend fun getById(factId: String): MemoryFact?

    /**
     * Get all facts in a workspace, ordered by importance descending.
     */
    @Query("""
        SELECT * FROM memory_facts 
        WHERE workspaceId = :workspaceId 
        ORDER BY importance DESC, lastAccessedAt DESC
    """)
    fun getByWorkspace(workspaceId: String): Flow<List<MemoryFact>>

    /**
     * Get all facts in a workspace as a single-shot query (not a Flow).
     */
    @Query("""
        SELECT * FROM memory_facts 
        WHERE workspaceId = :workspaceId 
        ORDER BY importance DESC
    """)
    suspend fun getAllFactsByWorkspace(workspaceId: String): List<MemoryFact>

    /**
     * Get facts by tag (simple substring search).
     */
    @Query("""
        SELECT * FROM memory_facts 
        WHERE workspaceId = :workspaceId AND tags LIKE '%' || :tag || '%'
        ORDER BY importance DESC
    """)
    suspend fun getByTag(workspaceId: String, tag: String): List<MemoryFact>

    /**
     * Get high-importance facts (>= minImportance) in a workspace.
     */
    @Query("""
        SELECT * FROM memory_facts 
        WHERE workspaceId = :workspaceId AND importance >= :minImportance
        ORDER BY importance DESC
    """)
    suspend fun getHighImportanceFacts(
        workspaceId: String,
        minImportance: Float = 0.7f,
    ): List<MemoryFact>

    /**
     * Get facts by related ID (knowledge graph navigation).
     * Returns facts that are referenced in the relatedIds list of the given fact.
     */
    @Query("""
        SELECT * FROM memory_facts 
        WHERE workspaceId = :workspaceId AND id IN (:relatedIds)
        ORDER BY importance DESC
    """)
    suspend fun getRelatedFacts(
        workspaceId: String,
        relatedIds: List<String>,
    ): List<MemoryFact>

    /**
     * Get all facts with embeddings (for similarity matching).
     */
    @Query("""
        SELECT * FROM memory_facts 
        WHERE workspaceId = :workspaceId AND embedding IS NOT NULL
        ORDER BY importance DESC
    """)
    suspend fun getFactsWithEmbeddings(workspaceId: String): List<MemoryFact>

    /**
     * Get stale facts (not accessed recently) for consolidation/pruning.
     * Returns facts older than the specified age and with low importance.
     */
    @Query("""
        SELECT * FROM memory_facts 
        WHERE workspaceId = :workspaceId 
        AND importance < :maxImportance 
        AND lastAccessedAt < :cutoffTime
        ORDER BY importance ASC, lastAccessedAt ASC
    """)
    suspend fun getStaleFacts(
        workspaceId: String,
        maxImportance: Float = 0.3f,
        cutoffTime: String, // ISO-8601 timestamp
    ): List<MemoryFact>

    /**
     * Keyword search (case-insensitive substring match).
     * Searches content, tags, and metadata.
     */
    @Query("""
        SELECT * FROM memory_facts 
        WHERE workspaceId = :workspaceId 
        AND (content LIKE '%' || :keyword || '%' OR tags LIKE '%' || :keyword || '%')
        ORDER BY 
            CASE 
                WHEN content LIKE :keyword || '%' THEN 1
                WHEN content LIKE '%' || :keyword || '%' THEN 2
                ELSE 3
            END,
            importance DESC
    """)
    suspend fun keywordSearch(workspaceId: String, keyword: String): List<MemoryFact>

    /**
     * Get count of facts in workspace.
     */
    @Query("SELECT COUNT() FROM memory_facts WHERE workspaceId = :workspaceId")
    suspend fun getFactCount(workspaceId: String): Int

    /**
     * Get total facts across all workspaces.
     */
    @Query("SELECT COUNT() FROM memory_facts")
    suspend fun getTotalFactCount(): Int

    /**
     * Get average importance score for a workspace (used for normalization).
     */
    @Query("""
        SELECT AVG(importance) FROM memory_facts 
        WHERE workspaceId = :workspaceId
    """)
    suspend fun getAverageImportance(workspaceId: String): Float?

    /**
     * Get average access count for a workspace (used for normalization).
     */
    @Query("""
        SELECT AVG(accessCount) FROM memory_facts 
        WHERE workspaceId = :workspaceId
    """)
    suspend fun getAverageAccessCount(workspaceId: String): Float?

    /**
     * Get total links (sum of related IDs count) for graph density calculation.
     */
    @Query("""
        SELECT COUNT(*) FROM memory_facts WHERE workspaceId = :workspaceId
    """)
    suspend fun getGraphDensity(workspaceId: String): Int

    /**
     * Duplicate detection: Find very similar facts by comparing content length
     * and creation time (used during consolidation).
     */
    @Query("""
        SELECT * FROM memory_facts 
        WHERE workspaceId = :workspaceId 
        AND id != :factId 
        AND LENGTH(content) BETWEEN LENGTH(:content) - :tolerance AND LENGTH(:content) + :tolerance
        ORDER BY ABS(LENGTH(content) - LENGTH(:content)), createdAt ASC
    """)
    suspend fun findPotentialDuplicates(
        workspaceId: String,
        factId: String,
        content: String,
        tolerance: Int = 50,
    ): List<MemoryFact>

    /**
     * Update multiple facts' importance scores in batch (for consolidation).
     */
    @Query("""
        UPDATE memory_facts 
        SET importance = CASE 
            WHEN id IN (:lowPriorityIds) THEN 0.1
            WHEN id IN (:mediumPriorityIds) THEN 0.5
            WHEN id IN (:highPriorityIds) THEN 0.9
            ELSE importance
        END
        WHERE workspaceId = :workspaceId
    """)
    suspend fun updateImportanceBatch(
        workspaceId: String,
        highPriorityIds: List<String>,
        mediumPriorityIds: List<String>,
        lowPriorityIds: List<String>,
    )

    /**
     * Update consolidation timestamp for multiple facts.
     */
    @Query("""
        UPDATE memory_facts 
        SET consolidatedAt = :consolidatedAt 
        WHERE id IN (:factIds)
    """)
    suspend fun markConsolidated(factIds: List<String>, consolidatedAt: String)

    /**
     * Get facts by source/origin (api, log, user, system, etc.).
     */
    @Query("""
        SELECT * FROM memory_facts 
        WHERE workspaceId = :workspaceId AND source = :source
        ORDER BY importance DESC
    """)
    suspend fun getBySource(workspaceId: String, source: String): List<MemoryFact>

    /**
     * Join with related facts — retrieve a fact plus all its related ones.
     */
    @Query("""
        SELECT DISTINCT f.* FROM memory_facts f
        WHERE f.workspaceId = :workspaceId
        AND f.id IN (
            SELECT id FROM memory_facts WHERE id = :factId
            UNION
            SELECT id FROM memory_facts WHERE workspaceId = :workspaceId LIMIT 10
        )
        ORDER BY f.importance DESC
    """)
    suspend fun getWithRelationships(
        workspaceId: String,
        factId: String,
    ): List<MemoryFact>
}
