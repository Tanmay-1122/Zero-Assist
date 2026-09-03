package com.zeroclaw.android.ui.screen.terminal

internal object TerminalResponseSanitizer {
    private val THINKING_TAG_REGEX =
        Regex(
            "<(?:think|thinking|commentary|tool_output|analysis" +
                "|reflection|inner_monologue)>" +
                "[\\s\\S]*?" +
                "</(?:think|thinking|commentary|tool_output|analysis" +
                "|reflection|inner_monologue)>",
            RegexOption.IGNORE_CASE,
        )

    private val TOOL_CALL_TAG_REGEX =
        Regex(
            "<(?:tool_call|function_call)\\b[\\s\\S]*?/>" +
                "|<(?:tool_call|function_call)>[\\s\\S]*?</(?:tool_call|function_call)>" +
                "|<(?:tool_call|function_call)[\\s\\S]*$",
            RegexOption.IGNORE_CASE,
        )

    fun stripThinkingTags(text: String): String = text.replace(THINKING_TAG_REGEX, "").trim()

    fun stripToolCallTags(text: String): String = text.replace(TOOL_CALL_TAG_REGEX, "").trim()
}
