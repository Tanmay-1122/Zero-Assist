/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.repository

import com.zeroclaw.android.data.db.memory.MemoryFactDao
import com.zeroclaw.android.data.repository.AdvancedMemoryRepository
import com.zeroclaw.android.model.MemoryConsolidationConfig
import com.zeroclaw.android.model.MemoryConsolidationResult
import com.zeroclaw.android.model.MemoryFact
import com.zeroclaw.android.model.MemoryHealthStats
import com.zeroclaw.android.model.MemoryRetrievalResult
import com.zeroclaw.android.service.ImportanceScoringEngine
import com.zeroclaw.android.service.SemanticSimilarityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/**
 * Room-backed implementation of AdvancedMemoryRepository.
 *
 * Provides persistent memory storage with semantic search, importance scoring,
 * and knowledge graph consolidation. Uses Room database for durability and
 * workspace-scoped isolation.
 *
 * @param dao MemoryFactDao for database access.
 * @param semanticService SemanticSimilarityService for embeddings/similarity.
 * @param scoringEngine ImportanceScoringEngine for multi-factor importance.
 */
class RoomAdvancedMemoryRepository(
    private val dao: MemoryFactDao,
    private val semanticService: SemanticSimilarityService,
    private val scoringEngine: ImportanceScoringEngine,
) : AdvancedMemoryRepository {

    override suspend fun storeFact(fact: MemoryFact, generateEmbedding: Boolean) {
        withContext(Dispatchers.IO) {
            if (generateEmbedding) {
                val embedding = semanticService.getEmbedding(fact.content)
                val embeddingJson = semanticService.serializeEmbedding(embedding)
                dao.insert(fact.copy(embedding = embeddingJson))
            } else {
                dao.insert(fact)
            }
        }
    }

    override suspend fun deleteFact(factId: String) {
        withContext(Dispatchers.IO) {
            dao.deleteById(factId)
        }
    }

    override suspend fun semanticSearch(
        query: String,
        workspaceId: String,
        limit: Int,
        minRelevance: Float,
    ): List<MemoryRetrievalResult> {
        return withContext(Dispatchers.IO) {
            val queryEmbedding = semanticService.getEmbedding(query)
            val allFacts = dao.getAllFactsByWorkspace(workspaceId)

            allFacts
                .mapNotNull { fact ->
                    if (fact.embedding.isNullOrEmpty()) return@mapNotNull null

                    val similarity = semanticService.cosineSimilarityWithJson(
                        queryEmbedding,
                        fact.embedding,
                    )

                    if (similarity >= minRelevance) {
                        MemoryRetrievalResult(
                            fact = fact,
                            relevanceScore = similarity,
                            retrievalMethod = "semantic_search",
                        )
                    } else {
                        null
                    }
                }
                .sortedByDescending { it.relevanceScore }
                .take(limit)
        }
    }

    override suspend fun keywordSearch(
        keywords: List<String>,
        workspaceId: String,
        limit: Int,
    ): List<MemoryFact> {
        return withContext(Dispatchers.IO) {
            keywords
                .flatMap { keyword ->
                    dao.keywordSearch(workspaceId, keyword)
                }
                .distinctBy { it.id }
                .take(limit)
        }
    }

    override suspend fun getRelatedFacts(
        factId: String,
        depth: Int,
    ): List<MemoryRetrievalResult> {
        return withContext(Dispatchers.IO) {
            val mainFact = dao.getById(factId) ?: return@withContext emptyList()
            val workspaceId = mainFact.workspaceId
            val relatedIds = mainFact.relatedIds.take(depth * 5)

            dao.getRelatedFacts(workspaceId, relatedIds)
                .map { fact ->
                    MemoryRetrievalResult(
                        fact = fact,
                        relevanceScore = 0.7f,
                        retrievalMethod = "graph_traversal",
                    )
                }
        }
    }

    override suspend fun updateImportance(factId: String, newImportance: Float) {
        withContext(Dispatchers.IO) {
            dao.updateImportance(factId, newImportance.coerceIn(0f, 1f))
        }
    }

    override suspend fun recordAccess(factId: String) {
        withContext(Dispatchers.IO) {
            val now = Instant.now().toString()
            dao.recordAccess(factId, now)
        }
    }

    override fun observeFactsByWorkspace(workspaceId: String): Flow<List<MemoryFact>> {
        return dao.getByWorkspace(workspaceId)
    }

    override suspend fun getHealthStats(workspaceId: String): MemoryHealthStats {
        return withContext(Dispatchers.IO) {
            val totalFacts = dao.getFactCount(workspaceId)
            val highValueFacts = dao.getHighImportanceFacts(workspaceId, 0.7f).size
            val lowValueFacts = dao.getHighImportanceFacts(workspaceId, 0f)
                .count { it.importance < 0.3f }
            val avgImportance = dao.getAverageImportance(workspaceId) ?: 0.5f
            val graphDensity = dao.getGraphDensity(workspaceId)

            MemoryHealthStats(
                totalFacts = totalFacts,
                averageImportance = avgImportance,
                highValueFacts = highValueFacts,
                lowValueFacts = lowValueFacts,
                memoryEfficiency = if (totalFacts > 0) {
                    (highValueFacts.toFloat() / totalFacts) * 100f
                } else {
                    0f
                },
                graphDensity = graphDensity.toFloat() / maxOf(totalFacts, 1),
            )
        }
    }

    override suspend fun consolidateMemory(
        workspaceId: String,
        config: MemoryConsolidationConfig,
    ): MemoryConsolidationResult {
        return withContext(Dispatchers.IO) {
            val startTime = Instant.now()
            val allFacts = dao.getAllFactsByWorkspace(workspaceId)

            var mergedCount = 0
            var prunedCount = 0
            val processedIds = mutableSetOf<String>()

            // Phase 1: Merge near-duplicates based on semantic similarity
            allFacts.forEach { fact ->
                if (fact.id in processedIds) return@forEach

                val candidates = allFacts.filter { it.id !in processedIds && it.id != fact.id }
                candidates.forEach { candidate ->
                    if (!fact.embedding.isNullOrEmpty() && !candidate.embedding.isNullOrEmpty()) {
                        val similarity = semanticService.cosineSimilarityWithJson(
                            semanticService.deserializeEmbedding(fact.embedding)
                                ?: FloatArray(384),
                            candidate.embedding,
                        )

                        if (similarity > config.similarityThreshold) {
                            val merged = fact.copy(
                                relatedIds = (fact.relatedIds + candidate.relatedIds).distinct(),
                                importance = maxOf(fact.importance, candidate.importance),
                            )
                            dao.update(merged)
                            dao.deleteById(candidate.id)

                            processedIds.add(candidate.id)
                            mergedCount++
                        }
                    }
                }

                processedIds.add(fact.id)
            }

            // Phase 2: Prune stale/low-importance facts
            val cutoffTime = startTime
                .minusSeconds(config.maxRetentionDays.toLong() * 86400)
                .toString()

            val staleFacts = dao.getStaleFacts(
                workspaceId,
                config.importanceThreshold,
                cutoffTime,
            )

            staleFacts.forEach { fact ->
                dao.deleteById(fact.id)
                prunedCount++
            }

            // Phase 3: Recompute importance scores
            val finalFacts = dao.getAllFactsByWorkspace(workspaceId)
            val avgAccessCount = dao.getAverageAccessCount(workspaceId) ?: 1f

            finalFacts.forEach { fact ->
                val newImportance = scoringEngine.computeImportance(
                    fact,
                    Instant.now().toString(),
                    avgAccessCount,
                )
                dao.updateImportance(fact.id, newImportance)
            }

            val endTime = Instant.now()

            MemoryConsolidationResult(
                factsRemoved = prunedCount,
                factsMerged = mergedCount,
                graphRebuilt = true,
                totalDurationMs = (endTime.toEpochMilli() - startTime.toEpochMilli()),
                timestamp = endTime.toString(),
            )
        }
    }

    /** No-op: consolidation config is passed directly to [consolidateMemory]; no persistent store. */
    override suspend fun updateConsolidationConfig(config: MemoryConsolidationConfig) {
        // Config is stateless in this implementation — callers pass it directly
        // to consolidateMemory(). No action required.
    }

    override suspend fun clearWorkspace(workspaceId: String) {
        withContext(Dispatchers.IO) {
            dao.deleteByWorkspace(workspaceId)
        }
    }

    override suspend fun exportFactsAsJson(workspaceId: String): String {
        return withContext(Dispatchers.IO) {
            val facts = dao.getAllFactsByWorkspace(workspaceId)
            val jsonArray = JSONArray()

            facts.forEach { fact ->
                val jsonObj = JSONObject()
                jsonObj.put("id", fact.id)
                jsonObj.put("content", fact.content)
                jsonObj.put("importance", fact.importance)
                jsonObj.put("tags", JSONArray(fact.tags))
                jsonObj.put("accessCount", fact.accessCount)
                jsonObj.put("createdAt", fact.createdAt)
                jsonObj.put("lastAccessedAt", fact.lastAccessedAt)
                jsonObj.put("source", fact.source)
                jsonArray.put(jsonObj)
            }

            jsonArray.toString(2) // Pretty-printed
        }
    }

    override suspend fun importFactsFromJson(
        json: String,
        workspaceId: String,
        mergeStrategy: String,
    ) {
        withContext(Dispatchers.IO) {
            if (mergeStrategy == "replace") {
                dao.deleteByWorkspace(workspaceId)
            }

            val jsonArray = JSONArray(json)
            val facts = mutableListOf<MemoryFact>()

            for (i in 0 until jsonArray.length()) {
                val jsonObj = jsonArray.getJSONObject(i)
                val tags = mutableListOf<String>()
                jsonObj.optJSONArray("tags")?.let { tagArray ->
                    for (j in 0 until tagArray.length()) {
                        tags.add(tagArray.getString(j))
                    }
                }

                val fact = MemoryFact(
                    id = jsonObj.getString("id"),
                    content = jsonObj.getString("content"),
                    importance = jsonObj.optDouble("importance", 0.5).toFloat(),
                    tags = tags,
                    accessCount = jsonObj.optInt("accessCount", 0),
                    createdAt = jsonObj.optString("createdAt", Instant.now().toString()),
                    lastAccessedAt = jsonObj.optString("lastAccessedAt", Instant.now().toString()),
                    source = jsonObj.optString("source", "import"),
                    workspaceId = workspaceId,
                )
                facts.add(fact)
            }

            dao.insertAll(facts)
        }
    }
}
