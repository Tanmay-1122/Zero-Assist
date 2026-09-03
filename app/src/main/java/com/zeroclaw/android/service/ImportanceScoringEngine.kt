/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.model.MemoryFact
import java.time.Instant
import kotlin.math.log

/**
 * Engine for computing importance scores for memory facts.
 *
 * Importance combines multiple factors:
 * - **Recency**: Recent facts are more relevant than old ones (exponential decay)
 * - **Access Frequency**: Facts accessed more often are more useful
 * - **Source Quality**: Facts from structured sources (API, logs) are more reliable
 * - **Length**: Moderate-length facts (50-500 chars) are better than very short/long
 * - **Connections**: Facts with more relationships in the knowledge graph are more central
 *
 * Final score: normalized to [0.0, 1.0] range
 */
class ImportanceScoringEngine {
    /**
     * Compute importance score for a fact based on multiple factors.
     *
     * @param fact The memory fact to score.
     * @param currentTime Current timestamp (ISO-8601).
     * @param avgAccessCount Average access count across all facts (for normalization).
     * @param graphDensity Average connections per fact (for normalization).
     * @return Importance score (0.0-1.0).
     */
    fun computeImportance(
        fact: MemoryFact,
        currentTime: String = Instant.now().toString(),
        avgAccessCount: Float = 1f,
        graphDensity: Float = 1f,
    ): Float {
        var score = 0.5f // Base score

        // Factor 1: Recency (exponential decay, half-life = 30 days)
        val recencyScore = computeRecencyScore(fact.createdAt, currentTime)
        score += recencyScore * 0.25f

        // Factor 2: Access frequency (normalized by average)
        val accessScore = computeAccessScore(fact.accessCount, avgAccessCount)
        score += accessScore * 0.25f

        // Factor 3: Source quality (some sources are more reliable)
        val sourceScore = computeSourceScore(fact.source)
        score += sourceScore * 0.15f

        // Factor 4: Content length (reward moderate-length facts)
        val lengthScore = computeLengthScore(fact.content)
        score += lengthScore * 0.15f

        // Factor 5: Graph connectivity (central facts are valuable)
        val connectivityScore = computeConnectivityScore(fact.relatedIds.size, graphDensity)
        score += connectivityScore * 0.20f

        return score.coerceIn(0f, 1f)
    }

    /**
     * Score based on age of fact using exponential decay.
     *
     * Half-life = 30 days. A fact 30 days old gets score 0.5, 60 days = 0.25, etc.
     *
     * @param createdAt ISO-8601 creation timestamp.
     * @param currentTime ISO-8601 current timestamp.
     * @return Recency score (0.0-1.0).
     */
    private fun computeRecencyScore(createdAt: String, currentTime: String): Float {
        return try {
            val created = Instant.parse(createdAt)
            val current = Instant.parse(currentTime)
            val ageSeconds = (current.epochSecond - created.epochSecond).toFloat()
            val halfLifeSeconds = 30 * 24 * 3600f // 30 days in seconds

            // Exponential decay: score = 2^(-age / halfLife)
            val score = 2f.pow(-ageSeconds / halfLifeSeconds)
            score.coerceIn(0f, 1f)
        } catch (e: Exception) {
            0.5f // Default if parsing fails
        }
    }

    /**
     * Score based on how frequently fact has been accessed.
     *
     * Uses logarithmic scaling: log(accessCount + 1) / log(avgAccessCount + 10)
     *
     * @param accessCount Number of times fact was accessed.
     * @param avgAccessCount Average access count for normalization.
     * @return Access score (0.0-1.0).
     */
    private fun computeAccessScore(accessCount: Int, avgAccessCount: Float): Float {
        val numerator = log((accessCount + 1).toFloat(), 2f)
        val denominator = log((avgAccessCount + 10).toFloat(), 2f)
        return (numerator / denominator).coerceIn(0f, 1f)
    }

    /**
     * Score based on source reliability.
     *
     * Ranking: api/log (trusted sources) > conversation > user > system
     *
     * @param source Source type.
     * @return Source quality score (0.0-1.0).
     */
    private fun computeSourceScore(source: String): Float {
        return when (source.lowercase()) {
            "api", "log" -> 1.0f           // Highly reliable
            "conversation" -> 0.7f         // Good reliability
            "user" -> 0.6f                 // Manual, verified by user
            "system" -> 0.4f               // Auto-generated, lower confidence
            else -> 0.5f                   // Unknown source
        }
    }

    /**
     * Score based on optimal content length.
     *
     * Reward range: 50-500 characters. Very short or very long facts score lower.
     * Quadratic curve peaking at 200 chars.
     *
     * @param content The memory content text.
     * @return Length score (0.0-1.0).
     */
    private fun computeLengthScore(content: String): Float {
        val length = content.length
        return when {
            length < 20 -> 0.1f              // Too short, likely incomplete
            length in 50..500 -> {
                // Peak at 200, decay towards edges
                val distFromOptimal = kotlin.math.abs(length - 200)
                (1f - (distFromOptimal / 450f)).coerceIn(0.4f, 1f)
            }
            length > 2000 -> 0.3f            // Too long, likely bloated
            else -> 0.7f                     // Reasonable length
        }
    }

    /**
     * Score based on how connected the fact is in the knowledge graph.
     *
     * More connections = more central/relevant to the knowledge base.
     * Uses logarithmic scaling to avoid over-rewarding highly connected facts.
     *
     * @param connectionCount Number of related facts.
     * @param graphDensity Average connections per fact.
     * @return Connectivity score (0.0-1.0).
     */
    private fun computeConnectivityScore(connectionCount: Int, graphDensity: Float): Float {
        if (graphDensity <= 0f) return 0.5f

        val normalizedConnections = connectionCount / graphDensity
        return (log(normalizedConnections + 1f, 2f) / log(10f, 2f)).coerceIn(0f, 1f)
    }

    /**
     * Decay importance over time for facts that haven't been accessed.
     *
     * Called during consolidation to gradually reduce scores of stale facts.
     * Linear decay: -0.1 per 10 days of no access.
     *
     * @param fact The fact to decay.
     * @param currentTime Current timestamp.
     * @return New importance score after decay.
     */
    fun applyDecayForStaleness(fact: MemoryFact, currentTime: String): Float {
        return try {
            val lastAccess = Instant.parse(fact.lastAccessedAt)
            val current = Instant.parse(currentTime)
            val staleDays = (current.epochSecond - lastAccess.epochSecond) / (24 * 3600)

            val decayPerTenDays = 0.1f
            val decayFactor = 1f - (staleDays / 100f) * decayPerTenDays
            (fact.importance * decayFactor).coerceIn(0f, 1f)
        } catch (e: Exception) {
            fact.importance // No decay if time parsing fails
        }
    }
}

private fun Float.pow(exponent: Float): Float {
    return kotlin.math.exp(exponent * kotlin.math.ln(this))
}

private fun log(value: Float, base: Float): Float {
    return kotlin.math.log(value.toDouble(), base.toDouble()).toFloat()
}
