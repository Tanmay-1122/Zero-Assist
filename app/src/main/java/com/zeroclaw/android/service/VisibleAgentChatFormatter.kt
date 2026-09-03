/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

/**
 * Normalizes daemon-visible chat text so the UI only receives brief, human-readable summaries.
 *
 * Backend orchestration can keep full prompts, context, embeddings, and raw payloads private
 * while this formatter enforces the shorter chat-facing contract.
 */
internal object VisibleAgentChatFormatter {
    private const val MAX_VISIBLE_CHAT_LENGTH = 320
    private const val MAX_STREAMING_CHUNK_LENGTH = 160

    private val whitespaceRegex = Regex("\\s+")
    private val sentenceRegex = Regex("""[^.!?]+(?:[.!?]+|$)""")

    fun taskAssignmentSummary(
        fromAgentName: String,
        toAgentName: String,
        rawSummary: String,
    ): String =
        sanitizeSummary(
            rawSummary = rawSummary,
            sentenceLimit = 2,
            fallback = "$fromAgentName assigned a task to $toAgentName.",
        )

    fun statusUpdate(
        agentName: String,
        rawStatus: String,
    ): String =
        sanitizeSummary(
            rawSummary = rawStatus,
            sentenceLimit = 1,
            fallback = "$agentName shared a status update.",
        )

    fun resultSummary(
        agentName: String,
        rawSummary: String,
    ): String =
        sanitizeSummary(
            rawSummary = rawSummary,
            sentenceLimit = 3,
            fallback = "$agentName shared a result summary.",
        )

    fun streamingChunk(rawChunk: String): String {
        val normalized =
            rawChunk
                .removeUnsafeControls()
                .replace("```", "")
                .replace('\r', ' ')
                .replace('\n', ' ')
                .take(MAX_STREAMING_CHUNK_LENGTH)
        return normalized
    }

    private fun sanitizeSummary(
        rawSummary: String,
        sentenceLimit: Int,
        fallback: String,
    ): String {
        val normalized = normalizeForVisibleChat(rawSummary)
        if (normalized.isBlank()) return fallback
        if (looksLikeStructuredPayload(normalized)) return fallback

        val limitedSentences =
            sentenceRegex
                .findAll(normalized)
                .map { match -> match.value.trim() }
                .filter { sentence -> sentence.isNotBlank() }
                .take(sentenceLimit)
                .toList()

        val sentenceBoundText =
            if (limitedSentences.isNotEmpty()) {
                limitedSentences.joinToString(" ")
            } else {
                normalized
            }

        return trimToLength(ensureTerminalPunctuation(sentenceBoundText))
    }

    private fun normalizeForVisibleChat(text: String): String =
        text
            .removeUnsafeControls()
            .replace("```", " ")
            .replace('\r', ' ')
            .replace('\n', ' ')
            .replace('\t', ' ')
            .replace(whitespaceRegex, " ")
            .trim()

    private fun looksLikeStructuredPayload(text: String): Boolean {
        val trimmed = text.trim()
        val lower = trimmed.lowercase()
        val wrappedAsObject =
            (trimmed.startsWith("{") && trimmed.endsWith("}")) ||
                (trimmed.startsWith("[") && trimmed.endsWith("]"))
        val colonCount = trimmed.count { char -> char == ':' }
        val hasStructureMarkers =
            trimmed.any { char -> char == '{' || char == '}' || char == '[' || char == ']' || char == '"' }
        val hasSensitiveMarkers =
            listOf("embedding", "embeddings", "vector", "vectors", "metadata", "raw_context")
                .any { marker -> lower.contains(marker) }

        return wrappedAsObject || (hasStructureMarkers && colonCount >= 3) || (hasSensitiveMarkers && colonCount >= 2)
    }

    private fun trimToLength(text: String): String {
        if (text.length <= MAX_VISIBLE_CHAT_LENGTH) return text
        val truncated = text.take(MAX_VISIBLE_CHAT_LENGTH).trimEnd()
        val safeCut = truncated.substringBeforeLast(' ', missingDelimiterValue = truncated).trimEnd('.', ',', ';', ':')
        return "$safeCut..."
    }

    private fun ensureTerminalPunctuation(text: String): String =
        if (text.isBlank()) {
            text
        } else if (text.last() == '.' || text.last() == '!' || text.last() == '?') {
            text
        } else {
            "$text."
        }

    private fun String.removeUnsafeControls(): String =
        filter { char ->
            !Character.isISOControl(char) || char == '\n' || char == '\r' || char == '\t'
        }
}
