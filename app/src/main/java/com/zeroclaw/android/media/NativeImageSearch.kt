/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.media

import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Small, keyless image search client used for explicit image-display requests.
 * Wikimedia Commons exposes a public API and returns direct thumbnail URLs,
 * which the chat Markdown renderer can load natively with Coil.
 */
object NativeImageSearch {
    private const val DEFAULT_RESULT_COUNT = 4
    private const val USER_AGENT = "Zero-Assist/1.0 (Android; image search)"

    private val client = OkHttpClient.Builder().build()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(
        request: String,
        maxResults: Int = DEFAULT_RESULT_COUNT,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val query = MediaIntentClassifier.extractImageSearchQuery(request)
            require(query.isNotBlank()) { "Image search query is empty" }

            val url = "https://commons.wikimedia.org/w/api.php".toHttpUrl().newBuilder()
                .addQueryParameter("action", "query")
                .addQueryParameter("generator", "search")
                .addQueryParameter("gsrsearch", query)
                .addQueryParameter("gsrnamespace", "6")
                .addQueryParameter("gsrlimit", maxResults.coerceIn(1, 8).toString())
                .addQueryParameter("prop", "imageinfo")
                .addQueryParameter("iiprop", "url|mime|size")
                .addQueryParameter("iiurlwidth", "900")
                .addQueryParameter("format", "json")
                .addQueryParameter("origin", "*")
                .build()

            val requestCall = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .build()

            client.newCall(requestCall).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("Image search failed with HTTP ${response.code}")
                }
                val body = response.body?.string() ?: throw IOException("Image search returned an empty response")
                formatMarkdown(query, body, maxResults)
                    ?: throw IOException("Image search returned no usable images")
            }
        }
    }

    internal fun formatMarkdown(
        query: String,
        responseJson: String,
        maxResults: Int = DEFAULT_RESULT_COUNT,
    ): String? {
        val root = runCatching { json.parseToJsonElement(responseJson).jsonObject }.getOrNull() ?: return null
        val pages = root["query"]?.jsonObject?.get("pages")?.jsonObject ?: return null
        val images = pages.values
            .asSequence()
            .mapNotNull { page ->
                val pageObject = page as? JsonObject ?: return@mapNotNull null
                val info = pageObject["imageinfo"]?.jsonArray?.firstOrNull()?.jsonObject ?: return@mapNotNull null
                val imageUrl = info["thumburl"]?.jsonPrimitive?.contentOrNull
                    ?: info["url"]?.jsonPrimitive?.contentOrNull
                if (imageUrl.isNullOrBlank() || !imageUrl.startsWith("http", ignoreCase = true)) {
                    return@mapNotNull null
                }
                val title = pageObject["title"]?.jsonPrimitive?.contentOrNull
                    ?.removePrefix("File:")
                    ?.replace('_', ' ')
                    ?.trim()
                    ?.takeIf { it.isNotBlank() }
                    ?: "Image of $query"
                title to imageUrl
            }
            .distinctBy { it.second }
            .take(maxResults.coerceIn(1, 8))
            .toList()

        if (images.isEmpty()) return null

        return buildString {
            appendLine("Here are some images of **${escapeMarkdown(query)}**:")
            appendLine()
            images.forEach { (title, imageUrl) ->
                appendLine("![${escapeMarkdown(title)}]($imageUrl)")
                appendLine()
            }
        }.trimEnd()
    }

    private fun escapeMarkdown(value: String): String =
        value.replace("[", "\\[").replace("]", "\\]").replace("(", "\\(").replace(")", "\\)")
}
