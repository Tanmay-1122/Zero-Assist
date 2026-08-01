/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.util

/**
 * Shared regex-based redaction of secrets and sensitive data.
 *
 * Two entry points:
 * - [sanitize] — full redaction for log export (all patterns + LONG_URL + API_KEY_ASSIGNMENT).
 * - [sanitizeForDisplay] — display-only redaction for the thinking panel (credentials + URL/flag stripping, no LONG_URL nuke).
 */
object CredentialPatterns {

    // ── Credential patterns (used by both sanitize and sanitizeForDisplay) ──

    private val API_KEY_PATTERN = Regex("""sk-[A-Za-z0-9_-]{8,}""")
    private val ANTHROPIC_KEY_PATTERN = Regex("""sk-ant-[A-Za-z0-9_-]{8,}""")
    private val COMPOSIO_KEY_PATTERN = Regex("""\b(?:[ac]k|uak)_[A-Za-z0-9_-]{8,}\b""")
    private val GOOGLE_KEY_PATTERN = Regex("""AIza[A-Za-z0-9_-]{30,}""")
    private val BEARER_PATTERN = Regex("""Bearer\s+[A-Za-z0-9\-_.~+/]+=*""")
    private val BOT_TOKEN_PATTERN = Regex("""xoxb-[0-9]+-[A-Za-z0-9]+""")
    private val NGROK_TOKEN_PATTERN = Regex("""ngrok[_-]?[A-Za-z0-9]{20,}""")
    private val AUTH_HEADER_PATTERN = Regex("""Authorization:\s*\S+""")
    private val X_API_KEY_PATTERN = Regex("""x-api-key:\s*\S+""", RegexOption.IGNORE_CASE)
    private val X_CONSUMER_API_KEY_PATTERN =
        Regex("""x-consumer-api-key:\s*\S+""", RegexOption.IGNORE_CASE)
    private val API_KEY_ASSIGNMENT_PATTERN =
        Regex("""(?i)\b([A-Za-z0-9_.-]*api[_-]?key[A-Za-z0-9_.-]*\s*[:=]\s*)["']?[^"'\s,}]+["']?""")
    private val AWS_KEY_PATTERN = Regex("""AKIA[A-Z0-9]{16}""")
    private val GITHUB_TOKEN_PATTERN = Regex("""gh[pos]_[A-Za-z0-9_]{36,}""")
    private val DISCORD_TOKEN_PATTERN =
        Regex("""[A-Za-z0-9_-]{24}\.[A-Za-z0-9_-]{6}\.[A-Za-z0-9_-]{27,}""")
    private val JWT_PATTERN = Regex("""eyJ[A-Za-z0-9_-]+\.eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+""")
    private val LONG_URL_PATTERN = Regex("""https?://[^\s]{50,}""")

    // ── Display-only patterns (sanitizeForDisplay only) ──

    /** Bearer token with capture group to preserve the label. */
    private val DISPLAY_BEARER_PATTERN = Regex("""(Bearer\s+)[^\s"']+""")

    /** Authorization header (non-Bearer) via negative lookahead. */
    private val DISPLAY_AUTH_HEADER_PATTERN = Regex("""Authorization:\s*(?!Bearer\s)[^"'\n]+""")

    /** curl -u user:pass, --user, --password flags. */
    private val DISPLAY_CURL_USER_PATTERN =
        Regex("""(?:-u\s+|--user\s+|--password\s+)\S+""")

    /** URL query string and fragment stripping. */
    private val DISPLAY_URL_QUERY_PATTERN = Regex("""[?#][^\s]*""")

    /** URL userinfo stripping (user@host). */
    private val DISPLAY_URL_USERINFO_PATTERN = Regex("""://[^@\s]+@""")

    private const val REDACTION_PLACEHOLDER = "[REDACTED]"
    private const val REDACTED_URL = "[REDACTED_URL]"

    /**
     * Full redaction for log export. Covers all credential patterns plus
     * long URL nuking and API key assignment redaction.
     */
    fun sanitize(text: String): String =
        text
            .replace(JWT_PATTERN, REDACTION_PLACEHOLDER)
            .replace(BEARER_PATTERN, "Bearer $REDACTION_PLACEHOLDER")
            .replace(ANTHROPIC_KEY_PATTERN, REDACTION_PLACEHOLDER)
            .replace(COMPOSIO_KEY_PATTERN, REDACTION_PLACEHOLDER)
            .replace(API_KEY_PATTERN, REDACTION_PLACEHOLDER)
            .replace(GOOGLE_KEY_PATTERN, REDACTION_PLACEHOLDER)
            .replace(AWS_KEY_PATTERN, REDACTION_PLACEHOLDER)
            .replace(GITHUB_TOKEN_PATTERN, REDACTION_PLACEHOLDER)
            .replace(DISCORD_TOKEN_PATTERN, REDACTION_PLACEHOLDER)
            .replace(BOT_TOKEN_PATTERN, REDACTION_PLACEHOLDER)
            .replace(NGROK_TOKEN_PATTERN, REDACTION_PLACEHOLDER)
            .replace(API_KEY_ASSIGNMENT_PATTERN, "$1\"$REDACTION_PLACEHOLDER\"")
            .replace(AUTH_HEADER_PATTERN, "Authorization: $REDACTION_PLACEHOLDER")
            .replace(X_CONSUMER_API_KEY_PATTERN, "x-consumer-api-key: $REDACTION_PLACEHOLDER")
            .replace(X_API_KEY_PATTERN, "x-api-key: $REDACTION_PLACEHOLDER")
            .replace(LONG_URL_PATTERN, REDACTED_URL)

    /**
     * Display-only redaction for the thinking panel.
     *
     * Redacts credential patterns and strips URL noise (query params, userinfo, flags)
     * without nuking entire URLs via LONG_URL_PATTERN.
     *
     * Order: redact credentials first, then strip URL noise — prevents leaking
     * credential fragments through URL stripping.
     */
    fun sanitizeForDisplay(text: String): String {
        val redacted = text
            .replace(JWT_PATTERN, REDACTION_PLACEHOLDER)
            .replace(DISPLAY_BEARER_PATTERN, "$1$REDACTION_PLACEHOLDER")
            .replace(ANTHROPIC_KEY_PATTERN, REDACTION_PLACEHOLDER)
            .replace(COMPOSIO_KEY_PATTERN, REDACTION_PLACEHOLDER)
            .replace(API_KEY_PATTERN, REDACTION_PLACEHOLDER)
            .replace(GOOGLE_KEY_PATTERN, REDACTION_PLACEHOLDER)
            .replace(AWS_KEY_PATTERN, REDACTION_PLACEHOLDER)
            .replace(GITHUB_TOKEN_PATTERN, REDACTION_PLACEHOLDER)
            .replace(DISCORD_TOKEN_PATTERN, REDACTION_PLACEHOLDER)
            .replace(BOT_TOKEN_PATTERN, REDACTION_PLACEHOLDER)
            .replace(NGROK_TOKEN_PATTERN, REDACTION_PLACEHOLDER)
            .replace(DISPLAY_AUTH_HEADER_PATTERN, "Authorization: $REDACTION_PLACEHOLDER")
            .replace(X_CONSUMER_API_KEY_PATTERN, "x-consumer-api-key: $REDACTION_PLACEHOLDER")
            .replace(X_API_KEY_PATTERN, "x-api-key: $REDACTION_PLACEHOLDER")
            .replace(DISPLAY_CURL_USER_PATTERN, "[REDACTED_CREDENTIALS]")

        return redacted
            .replace(DISPLAY_URL_QUERY_PATTERN, "")
            .replace(DISPLAY_URL_USERINFO_PATTERN, "://")
    }
}
