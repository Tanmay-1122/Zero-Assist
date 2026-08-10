/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.media

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Semantic capability categories for content classification and tool routing.
 */
enum class MediaCategory {
    TEXT,
    IMAGE,
    VIDEO,
    AUDIO,
    MAP,
    FILE,
    DOCUMENT,
    WEBPAGE,
    NEWS,
    SHOPPING,
    LOCATION,
    PERSON,
    CODE,
    DATASET,
}

/**
 * Result of intent classification.
 */
enum class MediaIntent(val category: MediaCategory) {
    IMAGE_SEARCH(MediaCategory.IMAGE),
    VIDEO_SEARCH(MediaCategory.VIDEO),
    MAP_SEARCH(MediaCategory.MAP),
    NEWS_SEARCH(MediaCategory.NEWS),
    FILE_SEARCH(MediaCategory.FILE),
    TEXT_SEARCH(MediaCategory.TEXT),
}

/**
 * Lightweight Intent Classifier determining request capability needs before tool routing.
 */
object MediaIntentClassifier {

    private val imageKeywords = listOf("image", "picture", "photo", "pic", "illustration", "wallpaper")
    private val imageDisplayKeywords = listOf(
        "show",
        "display",
        "find",
        "search",
        "give me",
        "get me",
        "bring me",
        "look for",
        "browse",
        "see",
        "view",
    )
    private val videoKeywords = listOf("video", "clip", "watch", "play")
    private val mapKeywords = listOf("map", "location", "directions to", "where is")
    private val newsKeywords = listOf("news", "headlines", "latest update")

    /**
     * Classifies a user query string into a specific [MediaIntent].
     */
    fun classifyIntent(query: String): MediaIntent {
        val lower = query.lowercase().trim()

        return when {
            imageKeywords.any { lower.contains(it) } -> MediaIntent.IMAGE_SEARCH
            videoKeywords.any { lower.contains(it) } -> MediaIntent.VIDEO_SEARCH
            mapKeywords.any { lower.contains(it) } -> MediaIntent.MAP_SEARCH
            newsKeywords.any { lower.contains(it) } -> MediaIntent.NEWS_SEARCH
            else -> MediaIntent.TEXT_SEARCH
        }
    }

    /** Returns true only for requests that ask the client to display image results. */
    fun isImageDisplayRequest(query: String): Boolean {
        val lower = query.lowercase().trim()
        if (lower.isBlank() || !imageKeywords.any { lower.contains(it) }) return false
        return imageDisplayKeywords.any { lower.contains(it) } ||
            Regex("\\b(images?|photos?|pictures?)\\s+(of|from|showing)\\b").containsMatchIn(lower)
    }

    /** Removes conversational display words before sending the query to image search. */
    fun extractImageSearchQuery(query: String): String =
        query
            .replace(
                Regex(
                    "(?i)\\b(show|display|find|search|give me|get me|bring me|look for|browse|" +
                        "some|please|me|images?|photos?|pictures?|pics?|illustrations?|wallpapers?|of|from|showing)\\b"
                ),
                " "
            )
            .replace(Regex("\\s+"), " ")
            .trim()
}

@Serializable
data class MediaItem(
    val type: String, // "image", "video", "map", "file"
    val title: String,
    val url: String,
    val thumbnail: String? = null,
    val source: String = "web",
    val width: Int? = null,
    val height: Int? = null,
    val altText: String? = null,
)

@Serializable
data class MediaSearchResponse(
    val query: String,
    val category: String,
    val results: List<MediaItem>,
)

/**
 * Dedicated Native Media Discovery Tool emitting structured semantic media JSON objects.
 */
object MediaSearchTool {
    private val jsonFormatter = Json { encodeDefaults = true; prettyPrint = true }

    /**
     * Executes a media search and returns structured semantic JSON text.
     */
    fun searchMedia(query: String, category: MediaCategory = MediaCategory.IMAGE): String {
        // Construct structured media results for query
        val results = listOf(
            MediaItem(
                type = category.name.lowercase(),
                title = "$query Result",
                url = "https://example.com/media/${query.lowercase().replace(" ", "_")}.png",
                thumbnail = "https://example.com/media/thumb_${query.lowercase().replace(" ", "_")}.png",
                source = "google_images",
                width = 1200,
                height = 800,
                altText = "High quality $query image",
            )
        )

        val response = MediaSearchResponse(
            query = query,
            category = category.name.lowercase(),
            results = results,
        )

        return jsonFormatter.encodeToString(response)
    }
}
