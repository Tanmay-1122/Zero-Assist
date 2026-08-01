/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.terminal

import com.zeroclaw.android.util.CredentialPatterns

/**
 * Generic, extensible formatter that maps internal tool identifiers to
 * short, human-readable display labels for the thinking panel.
 *
 * Each tool gets a display prefix (e.g. `"shell"`, `"search"`, `"fetch"`).
 * Unmapped tools degrade gracefully to `toolName: hint`.
 *
 * Truncation and redaction are applied here, not in the Rust FFI layer.
 */
internal object ToolDisplayFormatter {

    private const val MAX_HINT_LENGTH = 80

    /** Verified prefix map — matches actual `fn name()` return values from build_tools_registry. */
    private val prefixMap = mapOf(
        // Shell / execution
        "sandbox_execute" to "shell",
        "termux_run" to "termux",

        // File / folder
        "shared_folder_list" to "files-list",
        "shared_folder_read" to "files-read",
        "shared_folder_write" to "files-write",
        "workflow_folder_list" to "wf-list",
        "workflow_folder_read" to "wf-read",
        "workflow_folder_write" to "wf-write",

        // Device


        // Memory
        "memory_store" to "remember",
        "memory_recall" to "recall",
        "memory_forget" to "forget",

        // Web / HTTP
        "web_search_tool" to "search",
        "web_fetch" to "fetch",
        "http_request" to "http",

        // Cron
        "cron_list" to "cron",
        "cron_runs" to "cron",

        // Sandbox process management
        "sandbox_manage_process" to "sandbox",

        // Composio / tool search
        "composio" to "composio",
        "tool_search" to "toolfind",

        // Termux
        "termux_get_capabilities" to "termux",
    )

    /**
     * Formats a tool call for display in the thinking panel.
     *
     * @param name  Internal tool identifier (unchanged).
     * @param hint  Short argument summary from the Rust FFI layer.
     * @return Redacted, human-readable string like `"shell: ls -la"`.
     */
    fun format(name: String, hint: String): String {
        val prefix = prefixMap[name] ?: name
        val summary = hint.trim()
        if (summary.isEmpty()) return prefix
        val redacted = CredentialPatterns.sanitizeForDisplay(summary)
        val truncated = if (redacted.length > MAX_HINT_LENGTH) {
            redacted.take(MAX_HINT_LENGTH - 1) + "…"
        } else redacted
        return "$prefix: $truncated"
    }
}
