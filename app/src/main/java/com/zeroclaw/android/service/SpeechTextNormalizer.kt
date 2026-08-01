/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import java.util.Locale

object SpeechTextNormalizer {
    fun normalize(text: String): String {
        val withoutCodeBlocks =
            CODE_BLOCK_PATTERN.replace(text) {
                " code output omitted. "
            }
        return withoutCodeBlocks
            .lineSequence()
            .map { line -> line.trim().removeListMarker() }
            .filter { line -> line.isNotBlank() }
            .joinToString(" ")
            .replace(ELLIPSIS_PATTERN, ". ")
            .replace(PERCENT_PATTERN) { match ->
                "${match.groupValues[1]} percent"
            }.replace(DOLLAR_PATTERN) { match ->
                "${match.groupValues[1]} dollars"
            }.replace(AMPERSAND_PATTERN, " and ")
            .replace(SPACED_SLASH_PATTERN, " or ")
            .replace(COMPACT_SPACING_PATTERN, " ")
            .trim()
            .expandSpeechTokens()
    }

    private fun String.removeListMarker(): String =
        replace(LIST_MARKER_PATTERN, "")

    private fun String.expandSpeechTokens(): String =
        split(' ')
            .joinToString(" ") { token ->
                val expanded =
                    TOKEN_EXPANSIONS[token.lowercase(Locale.US)]
                        ?: TOKEN_EXPANSIONS[token.trimEnd('.', ',', ';', ':', '!', '?').lowercase(Locale.US)]
                        ?: token
                expanded
            }

    private val TOKEN_EXPANSIONS =
        mapOf(
            "ai" to "A I",
            "api" to "A P I",
            "ui" to "U I",
            "url" to "U R L",
            "sms" to "S M S",
            "tts" to "T T S",
            "stt" to "S T T",
            "onnx" to "O N N X",
            "ok" to "okay",
            "vs" to "versus",
            "vs." to "versus",
            "e.g." to "for example",
            "i.e." to "that is",
        )

    private val CODE_BLOCK_PATTERN = Regex("```[\\s\\S]*?```")
    private val LIST_MARKER_PATTERN = Regex("""^([-*]|\d+[.)])\s+""")
    private val ELLIPSIS_PATTERN = Regex("""\.{3,}""")
    private val PERCENT_PATTERN = Regex("""\b(\d+(?:\.\d+)?)%""")
    private val DOLLAR_PATTERN = Regex("""\$(\d+(?:\.\d+)?)\b""")
    private val AMPERSAND_PATTERN = Regex("""\s*&\s*""")
    private val SPACED_SLASH_PATTERN = Regex("""\s+/\s+""")
    private val COMPACT_SPACING_PATTERN = Regex("""\s+""")
}
