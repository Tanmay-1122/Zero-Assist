/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.sandbox

private const val MAX_TRANSCRIPT_LINES = 500

/**
 * A single terminal line in the sandbox transcript.
 */
sealed interface TerminalLine {
    data class Command(val text: String) : TerminalLine
    data class Output(val text: String) : TerminalLine
    data class Error(val text: String) : TerminalLine
}

/**
 * Per-session facade over [PersistentSandboxShell].
 *
 * Maintains a scrolling [transcript] of commands and their output so that
 * the Terminal tab can display both user-typed and agent-driven commands
 * in a single unified view.
 */
class SessionShell(
    val sessionId: String,
    private val inner: PersistentSandboxShell,
    initialLines: List<TerminalLine> = emptyList(),
    private val onChange: ((List<TerminalLine>) -> Unit)? = null,
) {
    private val _transcript = ArrayDeque<TerminalLine>(initialLines)
    val transcript: List<TerminalLine> get() = synchronized(_transcript) { _transcript.toList() }

    /**
     * Run a single command in the persistent shell.
     *
     * @param displayCommand what to show in the transcript. Defaults to [command];
     *   callers that wrap a user command (e.g. `cd /workdir && env=foo`) should
     *   pass the original unwrapped form so the user sees what they asked for.
     */
    suspend fun run(
        command: String,
        timeoutSeconds: Long,
        displayCommand: String = command,
        onStdout: ((String) -> Unit)? = null,
        onStderr: ((String) -> Unit)? = null,
    ): Map<String, Any> {
        appendBounded(TerminalLine.Command(displayCommand))
        try {
            return inner.run(
                command = command,
                timeoutSeconds = timeoutSeconds,
                onStdout = { line ->
                    appendBounded(TerminalLine.Output(line))
                    onStdout?.invoke(line)
                },
                onStderr = { line ->
                    appendBounded(TerminalLine.Error(line))
                    onStderr?.invoke(line)
                },
            )
        } finally {
            onChange?.invoke(transcript)
        }
    }

    fun writeInput(line: String) = inner.writeInput(line)

    fun cancelForeground() = inner.cancelForeground()

    fun reset() = inner.reset()

    private fun appendBounded(line: TerminalLine) {
        synchronized(_transcript) {
            _transcript.addLast(line)
            while (_transcript.size > MAX_TRANSCRIPT_LINES) {
                _transcript.removeFirst()
            }
        }
    }

    companion object {
        /** Fallback session key used when no conversation ID is available. */
        const val DEFAULT_SESSION_ID = "default"
    }
}
