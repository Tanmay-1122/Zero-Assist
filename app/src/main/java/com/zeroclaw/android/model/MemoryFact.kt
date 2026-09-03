/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Represents a single memory fact stored in the advanced memory system.
 *
 * Each fact has semantic embeddings for similarity search, importance scoring,
 * and workspace isolation. Facts are periodically consolidated to remove
 * duplicates and low-importance entries.
 *
 * @property id UUID identifier for the fact.
 * @property content The actual memory text (up to 4000 chars).
 * @property embedding Vector of floats for semantic similarity (384-dim).
 * @property importance Score 0.0-1.0 indicating how important this fact is.
 * @property source Where fact originated ("conversation", "log", "user", etc).
 * @property workspaceId Which workspace owns this fact (for isolation).
 * @property tags Keywords for quick filtering (#entity, #topic, etc).
 * @property relatedIds UUIDs of related facts for knowledge graph.
 * @property accessCount How many times this fact was retrieved/used.
 * @property createdAt ISO-8601 timestamp.
 * @property lastAccessedAt ISO-8601 timestamp of last retrieval.
 * @property consolidatedAt Timestamp of last consolidation pass (null if pending).
 */
@Entity(tableName = "memory_facts")
@Serializable
data class MemoryFact(
    @PrimaryKey
    val id: String,

    val content: String,

    @SerialName("embedding")
    val embedding: String? = null, // JSON-serialized 384-dim vector

    val importance: Float = 0.5f,

    val source: String = "conversation", // "conversation", "log", "user", "system"

    val workspaceId: String = "default",

    val tags: List<String> = emptyList(),

    @SerialName("related_ids")
    val relatedIds: List<String> = emptyList(), // Knowledge graph edges

    val accessCount: Int = 0,

    @SerialName("created_at")
    val createdAt: String = "",

    @SerialName("last_accessed_at")
    val lastAccessedAt: String = "",

    @SerialName("consolidated_at")
    val consolidatedAt: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MemoryFact) return false
        return id == other.id
    }

    override fun hashCode(): Int {
        return id.hashCode()
    }
}

/**
 * Result of a memory retrieval query with relevance scoring.
 *
 * @property fact The memory fact that was retrieved.
 * @property relevanceScore 0.0-1.0 indicating how well it matches the query.
 * @property relationshipStrength For knowledge graph matches: 0.0-1.0.
 * @property retrievalMethod How it was found: "semantic", "keyword", "graph", "recency".
 */
@Serializable
data class MemoryRetrievalResult(
    val fact: MemoryFact,
    val relevanceScore: Float,
    val relationshipStrength: Float = 0f,
    val retrievalMethod: String = "semantic",
)

/**
 * Statistics about memory health and utilization.
 *
 * @property totalFacts Total number of facts stored.
 * @property averageImportance Average importance score across all facts.
 * @property highValueFacts Count of facts with importance > 0.7.
 * @property lowValueFacts Count of facts with importance < 0.3.
 * @property lastConsolidatedAt Timestamp of last consolidation pass.
 * @property nextConsolidationDue ISO-8601 when next consolidation should run.
 * @property memoryEfficiency Percentage of high-value facts (should be > 60%).
 * @property graphDensity Average connections per fact (lower = sparse, higher = dense).
 */
@Serializable
data class MemoryHealthStats(
    val totalFacts: Int = 0,
    val averageImportance: Float = 0.5f,
    val highValueFacts: Int = 0,
    val lowValueFacts: Int = 0,
    val lastConsolidatedAt: String? = null,
    val nextConsolidationDue: String? = null,
    val memoryEfficiency: Float = 0.5f, // percentage 0-100
    val graphDensity: Float = 1.0f,
)

/**
 * Memory consolidation result summary.
 *
 * Consolidation periodically cleans up the memory:
 * - Removes duplicate/low-importance facts
 * - Merges similar facts
 * - Prunes stale entries
 * - Rebuilds knowledge graph
 *
 * @property factsRemoved Count of facts deleted.
 * @property factsMerged Count of facts merged.
 * @property graphRebuilt Whether knowledge graph was rebuilt.
 * @property totalDurationMs Time taken in milliseconds.
 * @property timestamp When consolidation completed.
 */
@Serializable
data class MemoryConsolidationResult(
    val factsRemoved: Int = 0,
    val factsMerged: Int = 0,
    val graphRebuilt: Boolean = false,
    val totalDurationMs: Long = 0L,
    val timestamp: String = "",
)

/**
 * Configuration for memory consolidation behavior.
 *
 * @property runAutomatically If true, consolidation runs on a schedule.
 * @property consolidationIntervalDays How often to run (default 7 days).
 * @property importanceThreshold Remove facts below this score (default 0.2).
 * @property maxRetentionDays Delete facts older than this (default 180 days).
 * @property similarityThreshold Merge facts with similarity above this (0.0-1.0).
 */
@Serializable
data class MemoryConsolidationConfig(
    val runAutomatically: Boolean = true,
    val consolidationIntervalDays: Int = 7,
    val importanceThreshold: Float = 0.2f,
    val maxRetentionDays: Int = 180,
    val similarityThreshold: Float = 0.85f,
)

/**
 * Memory system configuration.
 *
 * @property semanticSearchEnabled Whether to use embeddings for retrieval.
 * @property maxFactsPerWorkspace Limit facts per workspace (0 = unlimited).
 * @property autoGenerateEmbeddings If true, embeddings computed on store.
 * @property enableGraphAnalysis If true, knowledge graph relationships tracked.
 * @property consolidationConfig Settings for periodic cleanup.
 */
@Serializable
data class MemorySystemConfig(
    val semanticSearchEnabled: Boolean = true,
    val maxFactsPerWorkspace: Int = 5000,
    val autoGenerateEmbeddings: Boolean = true,
    val enableGraphAnalysis: Boolean = true,
    val consolidationConfig: MemoryConsolidationConfig = MemoryConsolidationConfig(),
)
