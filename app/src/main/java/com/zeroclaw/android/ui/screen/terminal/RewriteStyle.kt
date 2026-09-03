package com.zeroclaw.android.ui.screen.terminal

/**
 * Rewriting styles mapped to ML Kit rewriting output types.
 */
enum class RewriteStyle(
    val displayName: String,
) {
    ELABORATE("elaborate"),
    EMOJIFY("emojify"),
    SHORTEN("shorten"),
    FRIENDLY("friendly"),
    PROFESSIONAL("professional"),
    REPHRASE("rephrase"),
}
