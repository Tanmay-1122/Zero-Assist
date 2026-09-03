/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.dashboard

import android.content.Context
import android.util.Log
import com.google.mlkit.genai.prompt.Generation
import com.google.mlkit.genai.prompt.GenerativeModel
import com.zeroclaw.android.data.repository.GreetingHistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI-powered greeting generator using ML Kit GenAI (on-device LLM).
 * Falls back to local generator on any failure.
 */
@Singleton
class AiGreetingGenerator @Inject constructor(
    private val context: Context,
    private val localGenerator: LocalGreetingGenerator,
    private val historyRepository: GreetingHistoryRepository,
) : GreetingGenerator {

    private val model by lazy {
        Generation.getClient()
    }

    override val isAvailable: Boolean = true // ML Kit handles download automatically

    override suspend fun generate(context: GreetingContext): GreetingResult {
        // Try AI generation with timeout
        return try {
            val result = withTimeoutOrNull(GENERATION_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    generateWithAI(context)
                }
            }

            result ?: runCatching { localGenerator.generate(context) }.getOrElse {
                GreetingResult.Failure("Both AI and local failed", getFallbackGreeting(context))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "AI generation failed, using local fallback", e)
            runCatching { localGenerator.generate(context) }.getOrElse {
                GreetingResult.Failure("Both AI and local failed", getFallbackGreeting(context))
            }
        }
    }

    private suspend fun generateWithAI(context: GreetingContext): GreetingResult {
        val recentHistory = historyRepository.getRecentGreetings(
            context.userName,
            context.period,
            RECENT_HISTORY_LIMIT
        )
        
        val prompt = buildPrompt(context, recentHistory)
        Log.d(TAG, "Generating greeting with prompt: $prompt")

        val response = model.generateContent(prompt)
        val text = response.candidates.firstOrNull()?.text?.trim()

        if (text.isNullOrBlank()) {
            throw IllegalStateException("Empty AI response")
        }

        // Validate and sanitize
        val sanitized = sanitizeResponse(text, context)
        if (sanitized.isNullOrBlank()) {
            throw IllegalStateException("Sanitized response empty")
        }

        // Check against recent history to avoid immediate repeats
        if (sanitized in recentHistory) {
            Log.d(TAG, "AI generated duplicate, using local fallback")
            return localGenerator.generate(context)
        }

        return GreetingResult.Success(sanitized, GenerationSource.AI)
    }

    private fun buildPrompt(context: GreetingContext, recentHistory: List<String>): String {
        val historyText = if (recentHistory.isEmpty()) {
            "none"
        } else {
            recentHistory.joinToString(", ") { "\"$it\"" }
        }

        return """
            Write a brief, warm greeting for ${context.userName}.
            Time: ${context.hour}:00 (${context.period.name.lowercase()})
            Context: ${GreetingPeriod.contextDescription(context.period)}
            Recent greetings to avoid: $historyText
            Rules: One sentence. Under ${MAX_LENGTH} chars. Include name naturally. No emojis. Be different from previous.
            """.trimIndent()
    }

    private fun sanitizeResponse(response: String, context: GreetingContext): String {
        var cleaned = response.trim()

        // Remove quotes if the model wrapped it
        cleaned = cleaned.trim('"', '\'', '`')

        // Ensure single sentence (take first sentence)
        val sentenceEnd = cleaned.indexOfFirst { it in ".!?" }
        if (sentenceEnd > 0) {
            cleaned = cleaned.substring(0, sentenceEnd + 1)
        }

        // Ensure name is included naturally
        if (context.userName !in cleaned) {
            val prefix = when (context.period) {
                GreetingPeriod.MORNING -> "Good morning"
                GreetingPeriod.AFTERNOON -> "Good afternoon"
                GreetingPeriod.EVENING -> "Good evening"
            }
            cleaned = "$prefix, ${context.userName}! $cleaned"
        }

        // Truncate if too long
        if (cleaned.length > MAX_LENGTH) {
            cleaned = cleaned.take(MAX_LENGTH - 1).trimEnd() + "…"
        }

        // Basic profanity filter
        cleaned = cleaned.replace(Regex("(?i)\\b(damn|hell|crap)\\b"), "")

        return cleaned.trim()
    }

    private fun getFallbackGreeting(context: GreetingContext): String {
        val prefix = when (context.period) {
            GreetingPeriod.MORNING -> "Good morning"
            GreetingPeriod.AFTERNOON -> "Good afternoon"
            GreetingPeriod.EVENING -> "Good evening"
        }
        return "$prefix, ${context.userName}!"
    }

    companion object {
        private const val TAG = "AiGreetingGenerator"
        private const val MODEL_NAME = "gemma-2b-it"
        private const val GENERATION_TIMEOUT_MS = 2000L
        private const val MAX_LENGTH = 60
        private const val RECENT_HISTORY_LIMIT = 10
    }
}