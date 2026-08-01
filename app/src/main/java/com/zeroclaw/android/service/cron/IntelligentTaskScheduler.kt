/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.cron

import android.util.Log
import com.zeroclaw.android.model.CronJobIntent
import com.zeroclaw.android.service.ChannelDetector

/**
 * Orchestrates intelligent task scheduling from natural language input.
 *
 * Sequence:
 * 1. User sends natural language message
 * 2. AI parses intent (task, schedule, delivery)
 * 3. System clarifies if needed
 * 4. Automatically creates cron job
 * 5. Confirms with user
 *
 * Supports both terminal chat and agent-based requests.
 */
class IntelligentTaskScheduler(
    private val intentParser: CronIntentParser,
    private val cronTranslator: CronTranslator,
    private val channelDetector: ChannelDetector,
    private val cronBridge: CronBridge,
) {
    companion object {
        private const val TAG = "IntelligentTaskScheduler"
        private const val DEFAULT_INTERVAL_MINUTES = 5L
        private const val MILLIS_PER_SECOND = 1_000L
        private const val MILLIS_PER_MINUTE = 60L * MILLIS_PER_SECOND
        private const val MILLIS_PER_HOUR = 60L * MILLIS_PER_MINUTE
        private const val MILLIS_PER_DAY = 24L * MILLIS_PER_HOUR
        private const val DEFAULT_INTERVAL_MS = DEFAULT_INTERVAL_MINUTES * MILLIS_PER_MINUTE
    }

    /**
     * Process user's natural language task request.
     *
     * @param userMessage Natural language task description
     * @return ScheduleResult with outcome
     */
    suspend fun scheduleFromNaturalLanguage(userMessage: String): ScheduleResult {
        Log.d(TAG, "Processing natural language: $userMessage")

        if (!intentParser.isSchedulingRequest(userMessage)) {
            Log.d(TAG, "Not a scheduling request")
            return ScheduleResult.NotSchedulingRequest
        }

        val intent =
            intentParser.parseUserInput(userMessage)
                ?: return ScheduleResult.ParseError("Could not understand your request. Please be more specific.")

        Log.d(TAG, "Intent parsed: $intent")

        if (intent.requiresClarification && intent.clarificationQuestions.isNotEmpty()) {
            Log.d(TAG, "Clarification needed: ${intent.clarificationQuestions}")
            return ScheduleResult.NeedsClarification(
                intent = intent,
                questions = intent.clarificationQuestions,
            )
        }

        val channels =
            if (intent.deliveryMethod != "none") {
                channelDetector.detectChannels(intent.deliveryMethod)
            } else {
                emptyList()
            }

        if (channels.isEmpty() && intent.deliveryMethod != "none") {
            Log.w(TAG, "No channels found for: ${intent.deliveryMethod}")
            return ScheduleResult.NoChannelsFound(intent.deliveryMethod)
        }

        val (cronExpression, scheduleType) = cronTranslator.translateSchedule(intent.schedule)
        Log.d(TAG, "Translated to cron: $cronExpression (type: $scheduleType)")

        return try {
            val jobId =
                when (scheduleType) {
                    "oneshot" -> cronBridge.addOneShot(cronExpression, intent.task)
                    "recurring" -> cronBridge.addCronJob(cronExpression, intent.task)
                    "interval" -> cronBridge.addInterval(extractIntervalMs(intent.schedule), intent.task)
                    else -> cronBridge.addCronJob(cronExpression, intent.task)
                }

            Log.d(TAG, "Job created successfully: $jobId")

            ScheduleResult.Success(
                jobId = jobId,
                task = intent.task,
                schedule = intent.schedule,
                deliveryChannels = channels.map { it.toString() },
                scheduleType = scheduleType,
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create cron job", e)
            ScheduleResult.CreationError(e.message ?: "Unknown error creating job")
        }
    }

    /**
     * Handle user's clarification response.
     */
    suspend fun scheduleWithClarifications(
        originalIntent: CronJobIntent,
        clarifications: Map<String, String>,
    ): ScheduleResult {
        Log.d(TAG, "Processing with clarifications: $clarifications")

        val task = clarifications["task"] ?: originalIntent.task
        val schedule = clarifications["schedule"] ?: originalIntent.schedule
        val delivery = clarifications["delivery"] ?: originalIntent.deliveryMethod

        val updatedMessage = buildMessage(task, schedule, delivery)
        return scheduleFromNaturalLanguage(updatedMessage)
    }

    private fun buildMessage(
        task: String,
        schedule: String,
        delivery: String,
    ): String {
        return "$task $schedule and send to $delivery"
    }

    private fun extractIntervalMs(schedule: String): Long {
        val regex = """(\d+)\s*([smhd])""".toRegex(RegexOption.IGNORE_CASE)
        val match = regex.find(schedule)

        return match?.let {
            val number = it.groupValues[1].toLongOrNull() ?: DEFAULT_INTERVAL_MINUTES
            val unit = it.groupValues[2].lowercase()

            when (unit) {
                "s" -> number * MILLIS_PER_SECOND
                "m" -> number * MILLIS_PER_MINUTE
                "h" -> number * MILLIS_PER_HOUR
                "d" -> number * MILLIS_PER_DAY
                else -> DEFAULT_INTERVAL_MS
            }
        } ?: DEFAULT_INTERVAL_MS
    }

}

/**
 * Result of scheduling attempt.
 */
sealed class ScheduleResult {
    data class Success(
        val jobId: String,
        val task: String,
        val schedule: String,
        val deliveryChannels: List<String>,
        val scheduleType: String,
    ) : ScheduleResult()

    data class NeedsClarification(
        val intent: CronJobIntent,
        val questions: List<String>,
    ) : ScheduleResult()

    data class ParseError(val message: String) : ScheduleResult()

    data class NoChannelsFound(val requestedChannel: String) : ScheduleResult()

    data class CreationError(val message: String) : ScheduleResult()

    object NotSchedulingRequest : ScheduleResult()
}
