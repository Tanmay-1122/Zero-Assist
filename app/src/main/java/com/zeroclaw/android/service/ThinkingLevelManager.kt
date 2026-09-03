/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.model.ThinkingLevel

/**
 * Parses per-message thinking directives such as `/think:high`.
 */
object ThinkingLevelManager {
    private const val PREFIX = "/think:"

    data class ParsedMessage(
        val level: ThinkingLevel?,
        val message: String,
    )

    fun parseDirective(message: String): ParsedMessage {
        val trimmed = message.trimStart()
        if (!trimmed.startsWith(PREFIX, ignoreCase = true)) {
            return ParsedMessage(level = null, message = message.trim())
        }

        val afterPrefix = trimmed.substring(PREFIX.length)
        val levelEnd = afterPrefix.indexOfFirst { char -> char.isWhitespace() }.let { index ->
            if (index == -1) afterPrefix.length else index
        }
        val level = ThinkingLevel.fromDirective(afterPrefix.substring(0, levelEnd))
            ?: return ParsedMessage(level = null, message = message.trim())
        val remaining = afterPrefix.substring(levelEnd).trimStart()
        return ParsedMessage(level = level, message = remaining.trim())
    }
}
