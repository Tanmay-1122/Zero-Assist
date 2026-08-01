/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.sandbox

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Manages detached background shell processes started with `background=true`.
 *
 * Each background process runs in its own one-shot [ProotExecutor] so it
 * does not share state with the persistent session shells.
 */
class SandboxProcessManager(private val sandboxManager: LinuxSandboxManager) {

    class Session(
        val id: String,
        val command: String,
        val startTime: Long,
        @Volatile var stdout: String = "",
        @Volatile var stderr: String = "",
        @Volatile var finished: Boolean = false,
        @Volatile var exitCode: Int? = null,
        @Volatile var timedOut: Boolean = false,
    ) {
        var executor: ProotExecutor? = null
        val cancelled = AtomicBoolean(false)
    }

    private val sessions = ConcurrentHashMap<String, Session>()
    private val nextId = AtomicInteger(1)

    fun startBackground(
        command: String,
        timeoutSeconds: Long,
        workingDir: String,
        env: Map<String, String>,
    ): Map<String, Any> {
        val sessionId = "bg-${nextId.getAndIncrement()}"
        val session = Session(
            id = sessionId,
            command = command,
            startTime = System.currentTimeMillis(),
        )
        sessions[sessionId] = session

        val executor = sandboxManager.createProotExecutor()
        CompletableFuture.runAsync {
            val result = executor.execute(command, timeoutSeconds, workingDir, env)
            if (!session.cancelled.get()) {
                session.stdout = result["stdout"] as? String ?: ""
                session.stderr = result["stderr"] as? String ?: ""
                session.exitCode = result["exit_code"] as? Int ?: -1
                session.timedOut = result["timed_out"] as? Boolean ?: false
                session.finished = true
            }
        }
        // Store the executor so kill() can forcibly terminate the process.
        // Small race: if the process hasn't started yet, the CompletableFuture
        // runner will finish before kill() runs — process will already be gone.
        session.executor = executor

        return mapOf(
            "success" to true,
            "session_id" to sessionId,
            "status" to "running",
            "message" to "Process started in background. Use sandbox_manage_process tool to check status.",
        )
    }

    fun list(): Map<String, Any> {
        val running = sessions.values.filter { !it.finished }.map { it.toInfo() }
        val finished = sessions.values.filter { it.finished }.map { it.toInfo() }
        return mapOf(
            "running" to running,
            "finished" to finished,
            "total" to sessions.size,
        )
    }

    fun log(sessionId: String, offset: Int, limit: Int): Map<String, Any> {
        val session = sessions[sessionId]
            ?: return mapOf("success" to false, "error" to "Unknown session: $sessionId")

        val stdoutLines = session.stdout.lines()
        val sliced = stdoutLines.drop(offset).take(limit).joinToString("\n")

        return mapOf(
            "success" to true,
            "session_id" to sessionId,
            "status" to if (session.finished) "finished" else "running",
            "exit_code" to (session.exitCode ?: -1),
            "stdout" to sliced,
            "stderr" to session.stderr.takeLast(2000),
            "total_stdout_lines" to stdoutLines.size,
            "offset" to offset,
            "timed_out" to session.timedOut,
        )
    }

    fun kill(sessionId: String): Map<String, Any> {
        val session = sessions[sessionId]
            ?: return mapOf("success" to false, "error" to "Unknown session: $sessionId")

        if (session.finished) {
            return mapOf("success" to true, "message" to "Process already finished", "exit_code" to (session.exitCode ?: -1))
        }

        session.cancelled.set(true)
        session.executor?.cancelCurrent()
        session.finished = true
        session.exitCode = -1
        session.timedOut = true
        return mapOf("success" to true, "message" to "Process terminated")
    }

    fun remove(sessionId: String): Map<String, Any> {
        sessions.remove(sessionId)
            ?: return mapOf("success" to false, "error" to "Unknown session: $sessionId")
        return mapOf("success" to true, "message" to "Session removed")
    }

    /**
     * Routes an action string from the daemon tool call to the correct operation.
     *
     * @param action  One of: "list", "log", "kill", "remove"
     * @param sessionId Required for log/kill/remove; ignored for list.
     * @param offset  Line offset for "log" (default 0).
     * @param limit   Max lines for "log" (default 200).
     */
    fun dispatch(
        action: String,
        sessionId: String,
        offset: Int,
        limit: Int,
    ): Map<String, Any> = when (action) {
        "list" -> list()
        "log" -> log(sessionId, offset, limit)
        "kill" -> kill(sessionId)
        "remove" -> remove(sessionId)
        else -> mapOf(
            "success" to false,
            "error" to "Unknown action '$action'. Valid actions: list, log, kill, remove.",
        )
    }

    private fun Session.toInfo(): Map<String, Any> = mapOf(
        "session_id" to id,
        "command" to command,
        "status" to if (finished) "finished" else "running",
        "exit_code" to (exitCode ?: -1),
        "duration_seconds" to ((System.currentTimeMillis() - startTime) / 1000),
        "timed_out" to timedOut,
        "stdout_length" to stdout.length,
    )
}
