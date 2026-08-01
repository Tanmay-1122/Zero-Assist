package com.zeroclaw.android.service.devicecontrol

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import java.util.Locale

/**
 * Deterministic Android intents for high-confidence device-control goals.
 *
 * This keeps common navigation/search tasks off the slow LLM loop while still
 * falling back to the planner when the remaining goal needs live UI judgment.
 */
class DeviceControlQuickIntents(
    private val context: Context,
) {
    data class Result(
        val success: Boolean,
        val packageName: String? = null,
        val message: String? = null,
    )

    fun extractUrl(goal: String): String? {
        val match = URL_PATTERN.find(goal) ?: return null
        val raw = match.value.trimEnd('.', ',', ';', ')', ']')
        return when {
            raw.startsWith("http://", ignoreCase = true) -> raw
            raw.startsWith("https://", ignoreCase = true) -> raw
            raw.startsWith("www.", ignoreCase = true) -> "https://$raw"
            else -> null
        }
    }

    fun launchUrl(url: String, goal: String): Result {
        val normalizedUrl = if (url.startsWith("http", ignoreCase = true)) url else "https://$url"
        val packageName = preferredBrowserPackage(goal)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(normalizedUrl)).apply {
            addCategory(Intent.CATEGORY_BROWSABLE)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (packageName != null) setPackage(packageName)
        }
        return start(intent, packageName, "URL $normalizedUrl")
    }

    fun extractYouTubeQuery(goal: String): String? {
        val lower = goal.lowercase(Locale.US)
        if (!lower.contains("youtube")) return null
        if (!YOUTUBE_SEARCH_OR_PLAY.containsMatchIn(lower)) return null

        var query = goal
            .replace(Regex("\\b(?:please|just|can you)\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\b(?:open|launch|start)\\s+(?:the\\s+)?youtube\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\b(?:on|in)\\s+(?:the\\s+)?youtube(?:\\s+app)?\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\bsearch\\s+(?:for\\s+)?", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\bplay\\s+(?:the\\s+first\\s+)?", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\b(?:first\\s+)?video\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("\\band\\b", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("[^a-zA-Z0-9\\s'._-]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        if (query.equals("youtube", ignoreCase = true)) query = ""
        return query.takeIf { it.length >= 2 }
    }

    fun launchYouTubeSearch(query: String): Result {
        val youtubePackage = launchablePackage(YOUTUBE_PACKAGES)
        if (youtubePackage != null) {
            val searchIntent = Intent(Intent.ACTION_SEARCH).apply {
                setPackage(youtubePackage)
                putExtra(SearchManager.QUERY, query)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val searchResult = start(searchIntent, youtubePackage, "YouTube search $query")
            if (searchResult.success) return searchResult
        }

        val url = "https://www.youtube.com/results?search_query=${Uri.encode(query)}"
        return launchUrl(url, "youtube")
    }

    fun requiresPlannerAfterYouTubeSearch(goal: String): Boolean =
        Regex("\\b(play|tap|open|select)\\b.*\\b(first|video)\\b", RegexOption.IGNORE_CASE)
            .containsMatchIn(goal) ||
            Regex("\\bplay\\b", RegexOption.IGNORE_CASE).containsMatchIn(goal)

    fun requiresPlannerAfterUrlOpen(goal: String): Boolean =
        URL_REMAINDER_PATTERN.containsMatchIn(goal)

    private fun preferredBrowserPackage(goal: String): String? {
        val lower = goal.lowercase(Locale.US)
        val preferred = when {
            lower.contains("brave") -> listOf("com.brave.browser")
            lower.contains("chrome") -> listOf("com.android.chrome", "com.brave.browser")
            lower.contains("browser") -> BROWSER_PACKAGES
            else -> emptyList()
        }
        return launchablePackage(preferred)
    }

    private fun launchablePackage(packages: List<String>): String? =
        packages.firstOrNull { packageName ->
            runCatching {
                context.packageManager.getLaunchIntentForPackage(packageName) != null
            }.getOrDefault(false)
        }

    private fun start(intent: Intent, packageName: String?, label: String): Result =
        try {
            context.startActivity(intent)
            Result(success = true, packageName = packageName, message = "Started $label")
        } catch (error: Exception) {
            Log.w(TAG, "Failed quick intent for $label: ${error.message}")
            Result(success = false, packageName = packageName, message = error.message)
        }

    private companion object {
        private const val TAG = "DeviceControlQuick"
        private val URL_PATTERN = Regex("""https?://[^\s,]+|www\.[^\s,]+""", RegexOption.IGNORE_CASE)
        private val YOUTUBE_SEARCH_OR_PLAY = Regex("\\b(search|play|find|look up)\\b", RegexOption.IGNORE_CASE)
        private val URL_REMAINDER_PATTERN =
            Regex("\\b(sign\\s*in|login|log\\s*in|message|send|click|tap|type|fill|submit|continue)\\b", RegexOption.IGNORE_CASE)
        private val YOUTUBE_PACKAGES = listOf("com.google.android.youtube")
        private val BROWSER_PACKAGES = listOf(
            "com.android.chrome",
            "com.brave.browser",
            "com.microsoft.emmx",
            "org.mozilla.firefox",
            "com.sec.android.app.sbrowser",
            "com.opera.browser",
        )
    }
}
