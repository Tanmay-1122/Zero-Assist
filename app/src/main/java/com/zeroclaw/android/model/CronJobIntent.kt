/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.model

/**
 * Represents parsed user intent for cron job creation.
 *
 * Extracted from natural language user input by CronIntentParser.
 */
data class CronJobIntent(
    /** What to do (the task/action). */
    val task: String,

    /** When to do it (human readable). */
    val schedule: String,

    /** Type of schedule: recurring, oneshot, or interval. */
    val scheduleType: String,

    /** Actual cron expression or schedule specification. */
    val cronExpression: String,

    /** How to deliver results (telegram, discord, slack, email, none). */
    val deliveryMethod: String,

    /** Whether AI needs more information from user. */
    val requiresClarification: Boolean,

    /** Questions to ask user if clarification needed. */
    val clarificationQuestions: List<String> = emptyList()
) {
    fun toSummary(): String {
        return "Task: $task\nSchedule: $schedule\nDelivery: $deliveryMethod"
    }
}
