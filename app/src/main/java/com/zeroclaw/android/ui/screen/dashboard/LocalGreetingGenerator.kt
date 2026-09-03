/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.dashboard

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.Random
import kotlin.math.absoluteValue

/**
 * Local template-based greeting generator with deterministic daily variation.
 * Uses seeded randomization based on userId + period + date to ensure:
 * - Same greeting for same user/period/date (cached)
 * - Different greeting each day
 * - No repeats within 90 days (handled by history repository)
 */
class LocalGreetingGenerator : GreetingGenerator {

    override val isAvailable = true

    override suspend fun generate(context: GreetingContext): GreetingResult {
        return withContext(Dispatchers.IO) {
            try {
                val greeting = generateGreeting(context)
                GreetingResult.Success(greeting, GenerationSource.LOCAL)
            } catch (e: Exception) {
                GreetingResult.Failure("Local generation failed", getFallbackGreeting(context))
            }
        }
    }

    private fun generateGreeting(context: GreetingContext): String {
        val templates = getTemplatesForPeriod(context.period)
        val timeContexts = getTimeContextsForPeriod(context.period)

        // Deterministic seed: userId hash + period + date
        val seed = createSeed(context.userName, context.period)
        val random = Random(seed)

        // Shuffle templates deterministically
        val shuffledTemplates = templates.shuffled(random)
        val shuffledContexts = timeContexts.shuffled(random)

        // Find first template not in recent history
        val historySet = context.recentHistory.toSet()
        for (i in shuffledTemplates.indices) {
            val template = shuffledTemplates[i]
            val timeContext = shuffledContexts[i % shuffledContexts.size]

            val greeting = template
                .replace("{name}", context.userName)
                .replace("{timeContext}", timeContext)

            if (greeting !in historySet && greeting.length <= MAX_LENGTH) {
                return greeting
            }
        }

        // Fallback: use first template even if in history (should rarely happen)
        val fallback = shuffledTemplates.first()
            .replace("{name}", context.userName)
            .replace("{timeContext}", shuffledContexts.first())

        return if (fallback.length <= MAX_LENGTH) {
            fallback
        } else {
            getFallbackGreeting(context)
        }
    }

    private fun createSeed(userName: String, period: GreetingPeriod): Long {
        // Combine user name hash, period, and current date for daily variation
        val datePart = java.time.LocalDate.now().toEpochDay()
        return (userName.hashCode().toLong() + period.ordinal * 10000 + datePart * 1000).absoluteValue
    }

    private fun getTemplatesForPeriod(period: GreetingPeriod): List<String> = when (period) {
        GreetingPeriod.MORNING -> MORNING_TEMPLATES
        GreetingPeriod.AFTERNOON -> AFTERNOON_TEMPLATES
        GreetingPeriod.EVENING -> EVENING_TEMPLATES
    }

    private fun getTimeContextsForPeriod(period: GreetingPeriod): List<String> = when (period) {
        GreetingPeriod.MORNING -> MORNING_CONTEXTS
        GreetingPeriod.AFTERNOON -> AFTERNOON_CONTEXTS
        GreetingPeriod.EVENING -> EVENING_CONTEXTS
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
        private const val MAX_LENGTH = 60

        // 20+ templates per period for variety
        private val MORNING_TEMPLATES = listOf(
            "Good morning, {name}! {timeContext}",
            "Rise and shine, {name}! {timeContext}",
            "Morning, {name}! {timeContext}",
            "Top of the morning to you, {name}! {timeContext}",
            "Hey {name}, {timeContext}",
            "Good to see you, {name}! {timeContext}",
            "Welcome back, {name}! {timeContext}",
            "{name}, {timeContext}",
            "Hey there, {name}! {timeContext}",
            "Ready to conquer the day, {name}? {timeContext}",
            "Another beautiful morning, {name}! {timeContext}",
            "Time to shine, {name}! {timeContext}",
            "Hello sunshine, {name}! {timeContext}",
            "What a great morning, {name}! {timeContext}",
            "Morning glory, {name}! {timeContext}",
            "Fresh start awaits, {name}! {timeContext}",
            "Let's make today count, {name}! {timeContext}",
            "Good day, {name}! {timeContext}",
            "Up and at 'em, {name}! {timeContext}",
            "New day, new possibilities, {name}! {timeContext}",
            "Rise and thrive, {name}! {timeContext}",
        )

        private val AFTERNOON_TEMPLATES = listOf(
            "Good afternoon, {name}! {timeContext}",
            "Hey {name}, {timeContext}",
            "Afternoon, {name}! {timeContext}",
            "Good to see you, {name}! {timeContext}",
            "How's it going, {name}? {timeContext}",
            "Welcome back, {name}! {timeContext}",
            "Hey there, {name}! {timeContext}",
            "Hope your day's going well, {name}! {timeContext}",
            "Keep up the great work, {name}! {timeContext}",
            "Afternoon check-in, {name}! {timeContext}",
            "How's the day treating you, {name}? {timeContext}",
            "Still going strong, {name}! {timeContext}",
            "You're doing great, {name}! {timeContext}",
            "Halfway through, keep it up, {name}! {timeContext}",
            "Nice to see you, {name}! {timeContext}",
            "Good afternoon there, {name}! {timeContext}",
            "Productive afternoon, {name}! {timeContext}",
            "Mid-day momentum, {name}! {timeContext}",
            "Afternoon vibes, {name}! {timeContext}",
            "Steady progress, {name}! {timeContext}",
        )

        private val EVENING_TEMPLATES = listOf(
            "Good evening, {name}! {timeContext}",
            "Evening, {name}! {timeContext}",
            "Hey there, {name}! {timeContext}",
            "Good to see you, {name}! {timeContext}",
            "Welcome back, {name}! {timeContext}",
            "Night owl, {name}! {timeContext}",
            "How was your day, {name}? {timeContext}",
            "Time to unwind, {name}! {timeContext}",
            "Hey, {name}! {timeContext}",
            "Hope you had a great day, {name}! {timeContext}",
            "Winding down, {name}! {timeContext}",
            "Evening check-in, {name}! {timeContext}",
            "Good to see you again, {name}! {timeContext}",
            "Still at it, {name}! {timeContext}",
            "What a day, {name}! {timeContext}",
            "Evening breeze, {name}! {timeContext}",
            "Relax and recharge, {name}! {timeContext}",
            "Day's done, {name}! {timeContext}",
            "Evening calm, {name}! {timeContext}",
            "Peaceful evening, {name}! {timeContext}",
        )

        // Contextual additions for variety
        private val MORNING_CONTEXTS = listOf(
            "ready to tackle the day?",
            "fresh start awaits!",
            "let's make today count!",
            "another beautiful morning!",
            "time to shine!",
            "what will you achieve today?",
            "seize the day!",
            "new opportunities ahead!",
            "morning energy activated!",
            "let's do this!",
        )

        private val AFTERNOON_CONTEXTS = listOf(
            "how's the momentum?",
            "making progress?",
            "afternoon productivity!",
            "mid-day check-in!",
            "keep the pace!",
            "halfway there!",
            "steady as she goes!",
            "crushing it today!",
            "nice work so far!",
            "afternoon focus mode!",
        )

        private val EVENING_CONTEXTS = listOf(
            "time to relax!",
            "well deserved break!",
            "reflect on today's wins!",
            "unwind and recharge!",
            "peaceful evening ahead!",
            "you made it through!",
            "evening vibes activated!",
            "slow down, you earned it!",
            "tomorrow's another day!",
            "rest well!",
        )
    }
}