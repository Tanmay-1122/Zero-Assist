package com.zeroclaw.android.ui.screen.terminal

internal object TerminalSessionRecoveryPolicy {
    fun shouldRetryWithFreshSession(
        errorMessage: String?,
        daemonRunning: Boolean,
    ): Boolean {
        val detail = errorMessage?.lowercase().orEmpty()
        if (detail.contains("cancel")) return false
        return detail.contains("no active session") ||
            detail.contains("call session_start first") ||
            detail.contains("no session") ||
            detail.contains("session not") ||
            (
                detail.contains("daemon not running") &&
                    daemonRunning
            )
    }
}
