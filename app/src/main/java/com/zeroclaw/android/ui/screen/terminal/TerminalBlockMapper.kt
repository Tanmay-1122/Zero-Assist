package com.zeroclaw.android.ui.screen.terminal

import com.zeroclaw.android.model.TerminalEntry

internal object TerminalBlockMapper {
    private const val ENTRY_TYPE_INPUT = "input"
    private const val ENTRY_TYPE_RESPONSE = "response"
    private const val ENTRY_TYPE_ERROR = "error"

    private val ACCESSIBILITY_ERROR_PATTERN = Regex(
        """(?i)accessibility service is not enabled|settings.*accessibility.*enable""",
    )

    fun toBlock(entry: TerminalEntry): TerminalBlock =
        when (entry.entryType) {
            ENTRY_TYPE_INPUT ->
                TerminalBlock.Input(
                    id = entry.id,
                    timestamp = entry.timestamp,
                    text = entry.content,
                    imageNames =
                        entry.imageUris.map { uri ->
                            uri.substringAfterLast('/')
                        },
                )
            ENTRY_TYPE_RESPONSE -> {
                val trimmed = entry.content.trimStart()
                if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                    TerminalBlock.Structured(
                        id = entry.id,
                        timestamp = entry.timestamp,
                        json = entry.content,
                    )
                } else {
                    TerminalBlock.Response(
                        id = entry.id,
                        timestamp = entry.timestamp,
                        content = entry.content,
                    )
                }
            }
            ENTRY_TYPE_ERROR ->
                TerminalBlock.Error(
                    id = entry.id,
                    timestamp = entry.timestamp,
                    message = entry.content,
                    actionLabel = if (ACCESSIBILITY_ERROR_PATTERN.containsMatchIn(entry.content)) {
                        "Open Accessibility Settings"
                    } else {
                        null
                    },
                )
            else ->
                TerminalBlock.System(
                    id = entry.id,
                    timestamp = entry.timestamp,
                    text = entry.content,
                )
        }
}
