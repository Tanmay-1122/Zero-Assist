/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.util

/**
 * Regex-based redaction of secrets from log messages before sharing.
 *
 * Applied in [com.zeroclaw.android.ui.screen.settings.logs.LogViewerScreen]
 * when the user exports logs via the share intent. Patterns cover common
 * API key formats, bearer tokens, bot tokens, and authorization headers.
 *
 * All pattern logic is delegated to [CredentialPatterns].
 */
object LogSanitizer {
    /**
     * Applies all redaction patterns to a log message.
     *
     * @param message Raw log message text.
     * @return The message with secrets replaced by redaction placeholders.
     */
    fun sanitizeLogMessage(message: String): String = CredentialPatterns.sanitize(message)
}
