/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.dashboard

import java.util.Locale

/**
 * Context provided to greeting generators for personalized output.
 */
data class GreetingContext(
    /** User's display name */
    val userName: String,
    /** Current greeting period */
    val period: GreetingPeriod,
    /** Current hour (0-23) */
    val hour: Int,
    /** Recent greetings to avoid (most recent first) */
    val recentHistory: List<String>,
    /** User's locale for localization */
    val locale: Locale,
)

/**
 * Result of greeting generation.
 */
sealed interface GreetingResult {
    data class Success(val greeting: String, val source: GenerationSource) : GreetingResult
    data class Failure(val error: String, val fallbackGreeting: String?) : GreetingResult
}

/**
 * Source of greeting generation.
 */
enum class GenerationSource {
    AI,
    LOCAL
}

/**
 * Interface for generating personalized greeting messages.
 * Implementations can use AI (on-device LLM) or local templates.
 */
interface GreetingGenerator {

    /**
     * Generates a greeting for the given context.
     * Must be called on IO dispatcher.
     */
    suspend fun generate(context: GreetingContext): GreetingResult

    /**
     * Whether this generator is available/ready to use.
     */
    val isAvailable: Boolean
}