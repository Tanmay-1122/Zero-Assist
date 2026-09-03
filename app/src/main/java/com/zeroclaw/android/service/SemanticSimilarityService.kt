/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import android.util.Log
import kotlin.math.sqrt

private const val TAG = "SemanticSimilarityService"

/**
 * Service for computing semantic similarity between text using embeddings.
 *
 * Provides embedding generation, cosine similarity, and approximate nearest neighbor
 * search via L2 distance for fast retrieval of similar facts.
 */
class SemanticSimilarityService {
    /**
     * Computes a simple embedding vector for text via hash-based method.
     *
     * In production, this would integrate with a local embedding model (e.g., ONNX)
     * or call an embedding API (e.g., Cohere, OpenAI). For now, uses a lightweight
     * approximation based on character term frequencies.
     *
     * Returns a 384-dimensional vector normalized to unit length.
     *
     * @param text The input text to embed.
     * @return FloatArray of dimension 384.
     */
    fun getEmbedding(text: String): FloatArray {
        return try {
            generateTextEmbedding(text.lowercase())
        } catch (e: Exception) {
            Log.e(TAG, "Error generating embedding: ${e.message}")
            FloatArray(384) // Zero vector fallback
        }
    }

    /**
     * Computes cosine similarity between two embedding vectors.
     *
     * Returns a score in range [0.0, 1.0] where:
     * - 1.0 = identical vectors (perfect match)
     * - 0.5 = orthogonal vectors
     * - 0.0 = opposite vectors
     *
     * @param embedding1 First embedding vector.
     * @param embedding2 Second embedding vector.
     * @return Cosine similarity score (0.0-1.0).
     */
    fun cosineSimilarity(embedding1: FloatArray, embedding2: FloatArray): Float {
        if (embedding1.size != embedding2.size || embedding1.isEmpty()) {
            return 0f
        }

        var dotProduct = 0f
        var norm1 = 0f
        var norm2 = 0f

        for (i in embedding1.indices) {
            dotProduct += embedding1[i] * embedding2[i]
            norm1 += embedding1[i] * embedding1[i]
            norm2 += embedding2[i] * embedding2[i]
        }

        if (norm1 == 0f || norm2 == 0f) return 0f

        return (dotProduct / (sqrt(norm1) * sqrt(norm2))).coerceIn(0f, 1f)
    }

    /**
     * Computes L2 (Euclidean) distance between two embeddings.
     *
     * Used as an alternative to cosine similarity, often faster for large-scale search.
     * Returns distance which is then converted to similarity via: similarity = 1 / (1 + distance).
     *
     * @param embedding1 First embedding vector.
     * @param embedding2 Second embedding vector.
     * @return L2 distance (Euclidean distance).
     */
    fun l2Distance(embedding1: FloatArray, embedding2: FloatArray): Float {
        if (embedding1.size != embedding2.size || embedding1.isEmpty()) {
            return Float.MAX_VALUE
        }

        var sumSquaredDiff = 0f
        for (i in embedding1.indices) {
            val diff = embedding1[i] - embedding2[i]
            sumSquaredDiff += diff * diff
        }

        return sqrt(sumSquaredDiff)
    }

    /**
     * Converts L2 distance to a similarity score (0.0-1.0).
     *
     * @param distance L2 distance value.
     * @return Similarity score (0.0-1.0).
     */
    fun distanceToSimilarity(distance: Float): Float {
        return (1f / (1f + distance)).coerceIn(0f, 1f)
    }

    /**
     * Finds approximate nearest neighbors using linear search.
     *
     * For small datasets (< 10K embeddings), this is faster than more complex algorithms.
     * Returns sorted results by similarity (highest first).
     *
     * @param queryEmbedding The query vector.
     * @param candidateEmbeddings List of candidate vectors with IDs.
     * @param k Number of nearest neighbors to return.
     * @param minSimilarity Minimum similarity threshold (0.0-1.0).
     * @return List of (id, similarity) pairs sorted by similarity descending.
     */
    fun findNearestNeighbors(
        queryEmbedding: FloatArray,
        candidateEmbeddings: List<Pair<String, FloatArray>>,
        k: Int = 10,
        minSimilarity: Float = 0.3f,
    ): List<Pair<String, Float>> {
        return candidateEmbeddings
            .map { (id, embedding) ->
                id to cosineSimilarity(queryEmbedding, embedding)
            }
            .filter { (_, similarity) -> similarity >= minSimilarity }
            .sortedByDescending { (_, similarity) -> similarity }
            .take(k)
    }

    /**
     * Generates a simple hash-based text embedding.
     *
     * Creates a 384-dim vector by hashing character bigrams and distributing
     * term frequencies across buckets. Normalized to unit length.
     *
     * This is a lightweight approximation. Production systems would use:
     * - SentenceBERT / MiniLM (via ONNX Runtime)
     * - FastText (pre-trained embeddings)
     * - Local LLM embeddings (Ollama, LM Studio)
     * - Remote API (Cohere, OpenAI)
     *
     * @param text Lowercase input text.
     * @return Normalized 384-dimensional embedding.
     */
    private fun generateTextEmbedding(text: String): FloatArray {
        val embedding = FloatArray(384)
        val words = text.split(Regex("\\s+")).filter { it.isNotEmpty() }

        if (words.isEmpty()) {
            return embedding // Zero vector
        }

        // Distribute word hashes and bigrams across embedding buckets
        for (word in words) {
            if (word.length >= 2) {
                for (i in 0 until word.length - 1) {
                    val bigram = word.substring(i, i + 2)
                    val hash = bigram.hashCode().toLong() and 0xFFFFFFFFL
                    val bucket = (hash % embedding.size).toInt()
                    embedding[bucket] += 1f / words.size
                }
            }
        }

        // Also add single character frequencies
        for (char in text) {
            if (char.isLetterOrDigit()) {
                val hash = char.code.toLong() and 0xFFFFFFFFL
                val bucket = (hash % embedding.size).toInt()
                embedding[bucket] += 0.1f / text.length
            }
        }

        // Normalize to unit length
        var norm = 0f
        for (value in embedding) {
            norm += value * value
        }

        if (norm > 0f) {
            norm = sqrt(norm)
            for (i in embedding.indices) {
                embedding[i] /= norm
            }
        }

        return embedding
    }

    /**
     * Serializes a FloatArray embedding to JSON string for database storage.
     *
     * @param embedding The embedding vector to serialize.
     * @return JSON string representation or null if input is null.
     */
    fun serializeEmbedding(embedding: FloatArray?): String? {
        if (embedding == null) return null
        return "[" + embedding.joinToString(",") + "]"
    }

    /**
     * Deserializes a JSON string embedding back to FloatArray.
     *
     * @param embeddingJson The JSON string representation.
     * @return FloatArray or null if input is null or invalid.
     */
    fun deserializeEmbedding(embeddingJson: String?): FloatArray? {
        if (embeddingJson == null || embeddingJson.isEmpty()) return null
        return try {
            val trimmed = embeddingJson.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                val arrayStr = trimmed.substring(1, trimmed.length - 1)
                arrayStr.split(",").map { it.trim().toFloat() }.toFloatArray()
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error deserializing embedding: ${e.message}")
            null
        }
    }

    /**
     * Computes cosine similarity between a FloatArray and a JSON string embedding.
     *
     * @param embedding1 First embedding as FloatArray.
     * @param embedding2Json Second embedding as JSON string.
     * @return Cosine similarity score (0.0-1.0).
     */
    fun cosineSimilarityWithJson(embedding1: FloatArray, embedding2Json: String?): Float {
        val embedding2 = deserializeEmbedding(embedding2Json) ?: return 0f
        return cosineSimilarity(embedding1, embedding2)
    }
}

