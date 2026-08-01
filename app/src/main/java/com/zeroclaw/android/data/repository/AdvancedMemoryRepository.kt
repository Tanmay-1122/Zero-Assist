/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.repository

import com.zeroclaw.android.model.MemoryConsolidationConfig
import com.zeroclaw.android.model.MemoryConsolidationResult
import com.zeroclaw.android.model.MemoryFact
import com.zeroclaw.android.model.MemoryHealthStats
import com.zeroclaw.android.model.MemoryRetrievalResult
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for advanced memory management with semantic search,
 * importance scoring, and knowledge graph support.
 *
 * Provides storage, retrieval, and consolidation of memories with optional
 * semantic embeddings and relationship tracking.
 */
interface AdvancedMemoryRepository {
    /**
     * Store a new memory fact.
     *
     * @param fact The memory to store.
     * @param generateEmbedding If true, optionally compute embeddings.
     */
    suspend fun storeFact(fact: MemoryFact, generateEmbedding: Boolean = true)

    /**
     * Retrieve memories similar to a query using semantic search.
     *
     * @param query Text query to search for.
     * @param workspaceId Workspace to query within.
     * @param limit Maximum results to return.
     * @param minRelevance Minimum relevance score (0.0-1.0).
     * @return List of retrieved facts with relevance scores.
     */
    suspend fun semanticSearch(
        query: String,
        workspaceId: String = "default",
        limit: Int = 10,
        minRelevance: Float = 0.3f,
    ): List<MemoryRetrievalResult>

    /**
     * Keyword search for facts containing specific tags/terms.
     *
     * @param keywords Search terms.
     * @param workspaceId Workspace to query within.
     * @param limit Maximum results.
     * @return List of matching facts.
     */
    suspend fun keywordSearch(
        keywords: List<String>,
        workspaceId: String = "default",
        limit: Int = 20,
    ): List<MemoryFact>

    /**
     * Retrieve related facts via knowledge graph traversal.
     *
     * @param factId The fact to find relationships for.
     * @param depth How many hops to traverse (1-3).
     * @return Related facts with relationship strengths.
     */
    suspend fun getRelatedFacts(
        factId: String,
        depth: Int = 1,
    ): List<MemoryRetrievalResult>

    /**
     * Update importance score for a fact.
     *
     * Called when a fact is accessed or when consolidation runs.
     *
     * @param factId The fact to update.
     * @param newImportance New importance score (0.0-1.0).
     */
    suspend fun updateImportance(factId: String, newImportance: Float)

    /**
     * Mark a fact as accessed, updating accessCount and lastAccessedAt.
     *
     * @param factId The fact that was accessed.
     */
    suspend fun recordAccess(factId: String)

    /**
     * Delete a specific fact.
     *
     * @param factId The fact to delete.
     */
    suspend fun deleteFact(factId: String)

    /**
     * Get all facts in a workspace.
     *
     * Observable stream for reactive UI updates.
     *
     * @param workspaceId Workspace to query.
     * @return Flow of all facts in workspace.
     */
    fun observeFactsByWorkspace(workspaceId: String): Flow<List<MemoryFact>>

    /**
     * Get memory health statistics.
     *
     * @param workspaceId Workspace to analyze.
     * @return Memory health metrics.
     */
    suspend fun getHealthStats(workspaceId: String = "default"): MemoryHealthStats

    /**
     * Run consolidation to clean up and optimize memory.
     *
     * Removes duplicates, merges similar facts, prunes stale entries,
     * and rebuilds the knowledge graph.
     *
     * @param workspaceId Workspace to consolidate.
     * @param config Consolidation settings.
     * @return Consolidation summary with stats.
     */
    suspend fun consolidateMemory(
        workspaceId: String = "default",
        config: MemoryConsolidationConfig = MemoryConsolidationConfig(),
    ): MemoryConsolidationResult

    /**
     * Update consolidation configuration.
     *
     * @param config New configuration.
     */
    suspend fun updateConsolidationConfig(config: MemoryConsolidationConfig)

    /**
     * Delete all facts in a workspace.
     *
     * @param workspaceId Workspace to clear.
     */
    suspend fun clearWorkspace(workspaceId: String)

    /**
     * Export memory facts as JSON for backup.
     *
     * @param workspaceId Workspace to export.
     * @return JSON string of all facts.
     */
    suspend fun exportFactsAsJson(workspaceId: String): String

    /**
     * Import facts from JSON.
     *
     * @param json JSON string containing facts.
     * @param workspaceId Workspace to import into.
     * @param mergeStrategy "replace" or "merge".
     */
    suspend fun importFactsFromJson(
        json: String,
        workspaceId: String,
        mergeStrategy: String = "merge",
    )
}
