/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

/** Builds the deliberately minimal system instruction for a local-model turn. */
internal fun buildOfflineModePrompt(
    agentInstructions: String,
    memoryContext: String?,
): String = buildString {
    appendLine("You are running in Offline Mode on a downloaded local model.")
    appendLine("Do not use tools, MCP servers, channels, web access, or cloud services.")
    appendLine("Answer using only the conversation, your instructions, and the memory below when present.")
    appendLine()
    appendLine("## Identity")
    appendLine(agentInstructions)
    if (!memoryContext.isNullOrBlank()) {
        appendLine()
        appendLine("## Memory")
        appendLine(memoryContext)
    }
}
