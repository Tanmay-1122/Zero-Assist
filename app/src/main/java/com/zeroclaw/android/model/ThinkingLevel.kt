/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.model

/**
 * User-facing thinking depth for agent conversations.
 *
 * Android intentionally exposes only the three useful reasoning modes even
 * though the native runtime also supports faster internal levels.
 */
enum class ThinkingLevel(
    val label: String,
    val directiveName: String,
    val systemPromptPrefix: String?,
) {
    MEDIUM(
        label = "Medium",
        directiveName = "medium",
        systemPromptPrefix = null,
    ),
    VOICE_FAST(
        label = "Fast",
        directiveName = "off",
        systemPromptPrefix = null,
    ),
    HIGH(
        label = "High",
        directiveName = "high",
        systemPromptPrefix = "Think step by step. Provide thorough analysis and consider edge cases before answering.",
    ),
    MAX(
        label = "Max",
        directiveName = "max",
        systemPromptPrefix =
            "Think very carefully and exhaustively. Break down the problem into sub-problems, " +
                "consider all angles, verify your reasoning, and provide the most thorough analysis possible.",
    );

    companion object {
        val DEFAULT: ThinkingLevel = HIGH

        fun fromStorage(value: String?): ThinkingLevel =
            entries.firstOrNull { level -> level.name.equals(value, ignoreCase = true) } ?: DEFAULT

        fun fromDirective(value: String): ThinkingLevel? =
            when (value.trim().lowercase()) {
                "medium", "med" -> MEDIUM
                "fast", "voice_fast" -> VOICE_FAST
                "high" -> HIGH
                "max", "maximum" -> MAX
                else -> null
            }
    }
}
