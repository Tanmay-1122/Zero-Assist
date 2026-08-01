/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.dashboard

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Factory for selecting the best available greeting generator.
 * Prefers AI when available, falls back to local.
 */
@Singleton
class GreetingGeneratorFactory @Inject constructor(
    private val aiGenerator: AiGreetingGenerator,
    private val localGenerator: LocalGreetingGenerator,
) {

    /**
     * Returns the best available generator.
     * Tries AI first, falls back to local.
     */
    fun getGenerator(): GreetingGenerator {
        return if (aiGenerator.isAvailable) {
            aiGenerator
        } else {
            localGenerator
        }
    }

    /**
     * Forces use of local generator (for testing/debug).
     */
    fun getLocalGenerator(): GreetingGenerator {
        return localGenerator
    }

    /**
     * Forces use of AI generator (for testing/debug).
     */
    fun getAiGenerator(): GreetingGenerator {
        return aiGenerator
    }
}