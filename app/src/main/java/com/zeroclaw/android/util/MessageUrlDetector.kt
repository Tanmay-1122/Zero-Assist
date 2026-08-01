/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.util

/**
 * URL span detected in user-visible assistant output.
 *
 * @property start Inclusive start offset in the source text.
 * @property end Exclusive end offset in the source text.
 * @property displayText Exact text shown as the link.
 * @property openUrl Normalized HTTP(S) URL used for launch.
 */
data class DetectedUrl(
    val start: Int,
    val end: Int,
    val displayText: String,
    val openUrl: String,
)

/**
 * Finds safe web URLs in plain AI text without changing persistence.
 */
object MessageUrlDetector {
    private val urlPattern = Regex("""(?i)(?:https?://|www\.)[^\s<>"'`]+""")
    private val sentenceTrailingPunctuation = setOf('.', ',', '!', '?', ';', ':')
    private val closingToOpening = mapOf(')' to '(', ']' to '[', '}' to '{')

    /**
     * Detects HTTP(S) and `www.` URLs, trimming punctuation that belongs to the sentence.
     */
    fun detect(text: String): List<DetectedUrl> =
        urlPattern
            .findAll(text)
            .mapNotNull { match -> match.toDetectedUrl() }
            .toList()

    private fun MatchResult.toDetectedUrl(): DetectedUrl? {
        val trimmedEnd = trimEnd(value)
        if (trimmedEnd <= 0) {
            return null
        }

        val displayText = value.substring(0, trimmedEnd)
        val openUrl = normalizedOpenUrl(displayText) ?: return null

        return DetectedUrl(
            start = range.first,
            end = range.first + trimmedEnd,
            displayText = displayText,
            openUrl = openUrl,
        )
    }

    private fun normalizedOpenUrl(displayText: String): String? {
        val lower = displayText.lowercase()
        return when {
            lower.startsWith("http://") || lower.startsWith("https://") -> displayText
            lower.startsWith("www.") -> "https://$displayText"
            else -> null
        }
    }

    private fun trimEnd(candidate: String): Int {
        var end = candidate.length
        var changed: Boolean
        do {
            changed = false
            while (end > 0 && candidate[end - 1] in sentenceTrailingPunctuation) {
                end--
                changed = true
            }
            while (end > 0 && hasUnmatchedClosing(candidate, end)) {
                end--
                changed = true
            }
        } while (changed)
        return end
    }

    private fun hasUnmatchedClosing(
        candidate: String,
        end: Int,
    ): Boolean {
        val closing = candidate[end - 1]
        val opening = closingToOpening[closing] ?: return false
        var depth = 0
        for (index in 0 until end) {
            when (candidate[index]) {
                opening -> depth++
                closing -> depth--
            }
        }
        return depth < 0
    }
}
