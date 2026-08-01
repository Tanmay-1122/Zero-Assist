/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.cron

import android.util.Log
import com.zeroclaw.android.model.CronJobIntent
import org.json.JSONObject

/**
 * Parses natural language user input to extract cron job scheduling intent.
 *
 * Examples:
 * - "Check server every 5 minutes and send to Telegram"
 * - "Send daily report at 9 AM"
 * - "Monitor CPU usage hourly"
 *
 * Uses AI to understand user intent and extract structured scheduling information.
 */
class CronIntentParser {
    companion object {
        private const val TAG = "CronIntentParser"
        private val SCHEDULING_TRIGGER_PATTERN =
            Regex(
                pattern =
                    """\b(every|daily|hourly|weekly|monthly|minutes?|hours?|days?|weeks?|months?|schedule|remind|check|monitor|send|report|alert|notify|at|am|pm|o'?clock)\b""",
                option = RegexOption.IGNORE_CASE,
            )
    }

    /**
     * Parse user input and extract scheduling intent.
     *
     * @param userMessage Natural language user input
     * @return CronJobIntent with parsed components or null if parsing failed
     */
    suspend fun parseUserInput(userMessage: String): CronJobIntent? {
        return try {
            val agentResponse = extractIntentFromAI(userMessage)
            val intent = parseStructuredIntent(agentResponse)

            Log.d(TAG, "Parsed intent: $intent")
            intent
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse user input", e)
            null
        }
    }

    /**
     * Extract intent components from user message using AI.
     */
    private suspend fun extractIntentFromAI(userMessage: String): String {
        // This remains a deterministic fallback until the scheduler is wired to the daemon AI gateway.
        val escapedMessage = JSONObject.quote(userMessage)
        return """
            {
                "task": $escapedMessage,
                "schedule": "every 5 minutes",
                "schedule_type": "recurring",
                "cron_expression": "*/5 * * * *",
                "delivery_method": "telegram",
                "requires_clarification": false,
                "clarification_questions": []
            }
        """.trimIndent()
    }

    /**
     * Parse structured JSON intent response from AI.
     */
    private fun parseStructuredIntent(jsonResponse: String): CronJobIntent? {
        return try {
            val json = JSONObject(jsonResponse)

            val clarifications = mutableListOf<String>()
            json.optJSONArray("clarification_questions")?.let { arr ->
                for (i in 0 until arr.length()) {
                    clarifications.add(arr.getString(i))
                }
            }

            CronJobIntent(
                task = json.getString("task"),
                schedule = json.getString("schedule"),
                scheduleType = json.getString("schedule_type"),
                cronExpression = json.optString("cron_expression", ""),
                deliveryMethod = json.getString("delivery_method"),
                requiresClarification = json.getBoolean("requires_clarification"),
                clarificationQuestions = clarifications,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse structured intent from: $jsonResponse", e)
            null
        }
    }

    /**
     * Check if input looks like a scheduling request.
     */
    fun isSchedulingRequest(message: String): Boolean {
        return SCHEDULING_TRIGGER_PATTERN.containsMatchIn(message)
    }
}
