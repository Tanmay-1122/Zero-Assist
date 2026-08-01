/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.sandbox

import android.util.Log
import com.zeroclaw.android.google.GoogleWorkspaceAuditLogger
import com.zeroclaw.android.google.GoogleWorkspaceValidator
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.json.JSONObject

/**
 * Lightweight NanoHTTPD server that bridges the Rust daemon's app-tool callback
 * mechanism to the in-process [LinuxSandboxManager].
 *
 * The Rust daemon executes `sandbox_execute` and `sandbox_manage_process` tool
 * calls by making HTTP POST requests to this server on localhost. The server
 * validates the bearer token, delegates to the sandbox manager, and returns
 * a JSON result.
 *
 * Uses [NanoHTTPD] (already in the app's dependency set) to avoid the
 * `com.sun.net.httpserver` JDK class that is unavailable on Android.
 *
 * @param sandboxManager In-process sandbox runtime.
 * @param authToken      Bearer token registered with the Rust daemon via FFI.
 * @param port           Localhost port to bind (default [DEFAULT_PORT]).
 * @param maxTimeoutSecs Maximum timeout in seconds for execute commands (default 10800 = 3 hours).
 */
class SandboxBridgeServer(
    private val sandboxManager: LinuxSandboxManager,
    private val authToken: String,
    port: Int = DEFAULT_PORT,
    private val maxTimeoutSecs: Long = DEFAULT_MAX_TIMEOUT_SECS,
) : NanoHTTPD("127.0.0.1", port) {

    // Cap concurrent sandbox operations to avoid unbounded proot process spawning.
    private val execSemaphore = Semaphore(4)
    
    // Track in-flight long-running commands to prevent duplicate concurrent executions
    // Map: command -> Deferred<Map<String, Any>>
    private val inflightCommands = mutableMapOf<String, Deferred<Map<String, Any>>>()
    private val inflightLock = Any()

    override fun serve(session: IHTTPSession): Response {
        // ── Auth ────────────────────────────────────────────────────────────
        val authHeader = session.headers["authorization"] ?: ""
        if (authHeader != "Bearer $authToken") {
            return errorJson(Response.Status.UNAUTHORIZED, "Unauthorized")
        }

        if (session.method != Method.POST) {
            return errorJson(Response.Status.METHOD_NOT_ALLOWED, "Method Not Allowed")
        }

        return when (session.uri) {
            "/health" -> handleHealth()
            "/execute" -> handleExecute(session)
            "/execute/gws" -> handleGwsExecute(session)
            "/manage_process" -> handleManageProcess(session)
            else -> errorJson(Response.Status.NOT_FOUND, "Unknown path: ${session.uri}")
        }
    }

    // ── Handlers ────────────────────────────────────────────────────────────

    private fun handleHealth(): Response {
        val ready = sandboxManager.state.value is SandboxState.Ready
        Log.d(TAG, "Health check: sandbox_ready=$ready")
        return okJson(JSONObject().apply {
            put("status", "ok")
            put("sandbox_ready", ready)
        })
    }

    /**
     * POST /execute
     *
     * JSON body fields:
     *  - command (required): shell command string
     *  - timeout: seconds (1–60, default 30)
     *  - working_dir: optional directory; cd persists in the session shell
     *  - env: object of key/value pairs scoped to this call only
     *  - background: boolean – run detached, returns session_id
     *  - fresh: boolean – one-shot isolated shell
     *  - session_id: string – conversation session key (default "default")
     */
    private fun handleExecute(session: IHTTPSession): Response {
        val json = readJson(session) ?: return errorJson(Response.Status.BAD_REQUEST, "Invalid JSON body")

        val command = json.optString("command", "").trim()
        Log.d(TAG, "Received execute request: $command")
        if (command.isEmpty()) {
            // Provide detailed diagnostic feedback to help the AI understand what went wrong
            val diagnosticMessage = buildString {
                append("Missing or empty 'command' field. ")
                append("The sandbox_execute tool requires a non-empty command parameter. ")
                append("Received JSON keys: ${json.keys().asSequence().joinToString(", ")}. ")
                if (json.length() == 0) {
                    append("The request body was empty or contained no fields. ")
                }
                append("Example: {\"command\": \"pwd\"}")
            }
            return errorJson(Response.Status.BAD_REQUEST, diagnosticMessage)
        }

        if (sandboxManager.state.value !is SandboxState.Ready) {
            return okJson(JSONObject().apply {
                put("success", false)
                put("error", "Linux Sandbox is not installed. Enable it in Plugins -> Installed.")
            }, status = Response.Status.SERVICE_UNAVAILABLE)
        }

        val background = json.optBoolean("background", false)

        // Detect long-running commands and suggest appropriate timeout
        val isLongRunningCommand = detectLongRunningCommand(command)
        val suggestedTimeout = when {
            background -> maxTimeoutSecs  // Use configured max for background tasks
            isLongRunningCommand -> maxTimeoutSecs  // Use configured max for package installs, builds, etc.
            else -> 30L
        }

        val timeoutSeconds = json.optLong("timeout", suggestedTimeout).coerceIn(1L, maxTimeoutSecs)

        // Log diagnostic info when we auto-extend timeout
        if (isLongRunningCommand && !json.has("timeout")) {
            Log.d(TAG, "Detected long-running command pattern, auto-extending timeout to ${timeoutSeconds}s (max: ${maxTimeoutSecs}s): $command")
        }

        // Check if this exact command is already running (deduplication for long-running commands)
        if (isLongRunningCommand && !background) {
            synchronized(inflightLock) {
                val deferred = inflightCommands[command]
                if (deferred != null && deferred.isActive) {
                    Log.d(TAG, "Duplicate long-running command detected, returning wait message: $command")
                    return okJson(JSONObject().apply {
                        put("success", false)
                        put("stdout", "")
                        put("stderr", "This command is already running in another request. Please wait for it to complete.")
                        put("exit_code", -2)
                        put("duplicate_request", true)
                    })
                }
            }
        }

        val workingDir = json.optString("working_dir", "").ifEmpty { null }
        val fresh = json.optBoolean("fresh", false)
        val sessionId = json.optString("session_id", SessionShell.DEFAULT_SESSION_ID)
        val envMap: Map<String, String> = json.optJSONObject("env")
            ?.let { obj -> obj.keys().asSequence().associateWith { obj.optString(it) } }
            ?: emptyMap()

        // Create async job for long-running commands
        val resultDeferred = if (isLongRunningCommand && !background) {
            val deferred = kotlinx.coroutines.GlobalScope.async(Dispatchers.IO) {
                executeCommand(command, background, fresh, sessionId, timeoutSeconds, workingDir, envMap)
            }
            synchronized(inflightLock) {
                inflightCommands[command] = deferred
            }
            deferred
        } else {
            null
        }

        return try {
            val result = if (resultDeferred != null) {
                runBlocking(Dispatchers.IO) {
                    try {
                        resultDeferred.await()
                    } finally {
                        synchronized(inflightLock) {
                            inflightCommands.remove(command)
                        }
                    }
                }
            } else {
                runBlocking(Dispatchers.IO) {
                    executeCommand(command, background, fresh, sessionId, timeoutSeconds, workingDir, envMap)
                }
            }

            // Enhance error messages with diagnostic hints for the AI
            val enhancedResult = if (result["success"] != true) {
                enhanceErrorResult(result, command, timeoutSeconds, isLongRunningCommand)
            } else {
                result
            }

            Log.d(TAG, "Execute result: $enhancedResult")
            okJson(resultToJson(enhancedResult))
        } catch (e: Exception) {
            synchronized(inflightLock) {
                inflightCommands.remove(command)
            }
            Log.e(TAG, "Execute failed with exception: ${e.message}", e)
            return errorJson(Response.Status.INTERNAL_ERROR, "Execution failed: ${e.message}")
        }
    }

    private suspend fun executeCommand(
        command: String,
        background: Boolean,
        fresh: Boolean,
        sessionId: String,
        timeoutSeconds: Long,
        workingDir: String?,
        envMap: Map<String, String>
    ): Map<String, Any> {
        return when {
            background -> sandboxManager.processManager.startBackground(
                command = command,
                timeoutSeconds = timeoutSeconds,
                workingDir = workingDir ?: "/root",
                env = envMap,
            )
            fresh -> sandboxManager.createProotExecutor()
                .execute(command, timeoutSeconds, workingDir ?: "/root", envMap)
            else -> {
                val prefix = buildString {
                    if (workingDir != null) {
                        append("cd ").append(shellQuote(workingDir)).append(" && ")
                    }
                    envMap.forEach { (k, v) ->
                        append(shellQuote(k)).append('=').append(shellQuote(v)).append(' ')
                    }
                }
                val wrapped = if (prefix.isEmpty()) command else "$prefix$command"
                sandboxManager.shellFor(sessionId).run(
                    command = wrapped,
                    timeoutSeconds = timeoutSeconds,
                    displayCommand = command,
                )
            }
        }
    }

    /**
     * POST /manage_process
     *
     * JSON body fields:
     *  - action (required): "list" | "log" | "kill" | "remove"
     *  - session_id: process session ID (required for log/kill/remove)
     *  - offset: log line offset (default 0)
     *  - limit: max log lines (default 200)
     */
    private fun handleManageProcess(session: IHTTPSession): Response {
        val json = readJson(session) ?: return errorJson(Response.Status.BAD_REQUEST, "Invalid JSON body")

        val action = json.optString("action", "").trim()
        val sessionId = json.optString("session_id", "")
        val offset = json.optInt("offset", 0)
        val limit = json.optInt("limit", 200)

        Log.d(TAG, "manage_process action=$action session_id=$sessionId offset=$offset limit=$limit")

        val result = runBlocking(Dispatchers.IO) {
            execSemaphore.withPermit {
                sandboxManager.processManager.dispatch(action, sessionId, offset, limit)
            }
        }
        Log.d(TAG, "manage_process result=$result")
        return okJson(resultToJson(result))
    }

    /**
     * POST /execute/gws
     *
     * JSON body fields:
     *  - service (required): Google Workspace service (e.g. drive, gmail, calendar)
     *  - resource (required): Service resource (e.g. files, messages, events)
     *  - method (required): Method to call (e.g. list, get, create)
     *  - sub_resource: optional sub-resource for nested operations
     *  - params: object of URL/query parameters
     *  - body: object for POST/PATCH/PUT request body
     *  - format: output format (json, table, yaml, csv)
     *  - page_all: boolean, auto-paginate results
     *  - page_limit: integer, max pages when using page_all
     */
    private fun handleGwsExecute(session: IHTTPSession): Response {
        val json = readJson(session) ?: return errorJson(Response.Status.BAD_REQUEST, "Invalid JSON body")

        val service = json.optString("service", "").trim()
        val resource = json.optString("resource", "").trim()
        val method = json.optString("method", "").trim()
        val subResource = json.optString("sub_resource", "").trim().ifEmpty { null }
        val format = json.optString("format", "").trim().ifEmpty { null }

        val validationError = GoogleWorkspaceValidator.validate(service, resource, method, subResource, format)
        if (validationError != null) {
            return errorJson(Response.Status.BAD_REQUEST, validationError)
        }

        val params: Map<String, Any>? = json.optJSONObject("params")
            ?.let { obj -> obj.keys().asSequence().associateWith { obj.get(it) } }
        val body: Map<String, Any>? = json.optJSONObject("body")
            ?.let { obj -> obj.keys().asSequence().associateWith { obj.get(it) } }
        val pageAll = json.optBoolean("page_all", false)
        val pageLimit = json.optInt("page_limit", 10).takeIf { json.has("page_limit") }

        val cmdArgs = GoogleWorkspaceValidator.buildCommandArgs(
            service = service,
            resource = resource,
            method = method,
            subResource = subResource,
            params = params,
            body = body,
            format = format,
            pageAll = pageAll,
            pageLimit = pageLimit,
        )

        val command = cmdArgs.joinToString(" ")

        if (sandboxManager.state.value !is SandboxState.Ready) {
            return okJson(JSONObject().apply {
                put("success", false)
                put("error", "Linux Sandbox is not installed")
            }, status = Response.Status.SERVICE_UNAVAILABLE)
        }

        val startTime = System.currentTimeMillis()
        val result = runBlocking(Dispatchers.IO) {
            sandboxManager.shellFor("gws").run(
                command = command,
                timeoutSeconds = 30,
                displayCommand = command,
            )
        }
        val durationMs = System.currentTimeMillis() - startTime

        val success = result is Map<*, *> && result["exit_code"] == 0
        val error = if (!success) (result as? Map<*, *>)?.get("error")?.toString()
            ?: (result as? Map<*, *>)?.get("stderr")?.toString()?.take(200)
        else null
        GoogleWorkspaceAuditLogger.logExecution(service, resource, method, subResource, success, durationMs, error)

        return okJson(resultToJson(result))
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    /** Reads and parses the POST body as JSON, returning null on parse failure. */
    private fun readJson(session: IHTTPSession): JSONObject? {
        return try {
            val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
            val buffer = ByteArray(contentLength)
            session.inputStream.read(buffer, 0, contentLength)
            JSONObject(String(buffer, Charsets.UTF_8))
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read JSON body: ${e.message}")
            null
        }
    }

    private fun okJson(body: JSONObject, status: Response.Status = Response.Status.OK): Response =
        newFixedLengthResponse(status, "application/json", body.toString())

    private fun errorJson(status: Response.Status, message: String): Response =
        newFixedLengthResponse(
            status, "application/json",
            JSONObject().apply { put("success", false); put("error", message) }.toString(),
        )

    @Suppress("UNCHECKED_CAST")
    private fun resultToJson(result: Any?): JSONObject = when (result) {
        is Map<*, *> -> JSONObject().apply {
            (result as Map<String, Any?>).forEach { (k, v) ->
                when (v) {
                    null -> putOpt(k, null)
                    is Int -> put(k, v)
                    is Long -> put(k, v)
                    is Boolean -> put(k, v)
                    is String -> put(k, v)
                    else -> put(k, v.toString())
                }
            }
        }
        is String -> JSONObject().apply { put("output", result) }
        null -> JSONObject().apply { put("success", false); put("error", "null result") }
        else -> JSONObject().apply { put("output", result.toString()) }
    }

    private fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

    /**
     * Detects commands that typically take longer than 30 seconds to complete.
     * Used to automatically extend timeout for package installations, builds, etc.
     */
    /**
     * Enhances error results with diagnostic hints to help the AI understand
     * what went wrong and how to fix it.
     */
    private fun enhanceErrorResult(
        result: Map<String, Any>,
        command: String,
        timeoutSeconds: Long,
        isLongRunning: Boolean
    ): Map<String, Any> {
        val diagnostics = mutableListOf<String>()

        // Timeout diagnostics
        if (result["timed_out"] == true) {
            diagnostics.add("[TIMEOUT after ${timeoutSeconds}s]")
            if (isLongRunning) {
                diagnostics.add("This operation was detected as long-running and timeout was auto-extended to ${timeoutSeconds}s (max: ${maxTimeoutSecs}s).")
                diagnostics.add("If the operation needs more time, consider using background=true.")
            } else {
                diagnostics.add("Consider increasing the timeout parameter or using background=true.")
            }

            // Specific hints for common timeout scenarios
            when {
                command.contains("pip install") || command.contains("pip3 install") -> {
                    diagnostics.add("Tip: Python package installations can be slow. Try: 1) Use --no-cache-dir flag, 2) Install specific versions, 3) Check if package name is correct.")
                }
                command.contains("apk add") -> {
                    diagnostics.add("Tip: Alpine package installation timed out. The package manager may be fetching indexes or large packages. Retry or use background=true.")
                }
                command.contains("docker") -> {
                    diagnostics.add("Note: Docker is not available in this sandbox environment. Use native Alpine Linux tools instead.")
                }
            }
        }

        // Process killed diagnostics (exit code 137 = SIGKILL)
        if (result["exit_code"] == 137) {
            diagnostics.add("[PROCESS KILLED - exit code 137]")
            diagnostics.add("The process was forcibly terminated, likely due to:")
            when {
                command.contains("pip install") || command.contains("pip3 install") -> {
                    diagnostics.add("1. The package name may be incorrect or doesn't exist on PyPI")
                    diagnostics.add("2. Memory/resource limits were exceeded during installation")
                    diagnostics.add("3. Consider: Search PyPI first, use --no-cache-dir flag, or install lighter alternatives")
                }
                else -> {
                    diagnostics.add("1. Memory/resource constraints (OOM killer)")
                    diagnostics.add("2. System resource limits exceeded")
                    diagnostics.add("3. Consider: breaking the operation into smaller steps or using background=true")
                }
            }
        }

        // Exit code diagnostics
        if (result["exit_code"] == 1 && result["timed_out"] != true) {
            when {
                command.contains("which") -> {
                    diagnostics.add("Command not found. Check if the tool is installed. Use 'apk search <package>' to find packages.")
                }
                command.contains("pip") && (result["stderr"] as? String)?.contains("externally-managed-environment") == true -> {
                    diagnostics.add("Python externally-managed-environment error detected. Use --break-system-packages flag or create a virtual environment.")
                }
            }
        }

        // Network/connectivity issues
        val stderr = result["stderr"] as? String ?: ""
        val stdout = result["stdout"] as? String ?: ""
        if (stderr.contains("Could not resolve host") ||
            stderr.contains("Network is unreachable") ||
            stdout.contains("Could not resolve host")) {
            diagnostics.add("Network connectivity issue detected. Check internet connection or try again later.")
        }

        // Build enhanced error message
        val enhancedStdout = if (diagnostics.isNotEmpty()) {
            buildString {
                append(stdout)
                if (stdout.isNotEmpty()) append("\n\n")
                append("=== DIAGNOSTICS ===\n")
                diagnostics.forEach { append("• $it\n") }
            }
        } else {
            stdout
        }

        return result.toMutableMap().apply {
            put("stdout", enhancedStdout)
        }
    }

    private fun detectLongRunningCommand(command: String): Boolean {
        val lowercaseCmd = command.lowercase().trim()

        // Package managers
        if (lowercaseCmd.contains("pip install") ||
            lowercaseCmd.contains("pip3 install") ||
            lowercaseCmd.contains("apk add") ||
            lowercaseCmd.contains("npm install") ||
            lowercaseCmd.contains("yarn install") ||
            lowercaseCmd.contains("cargo install") ||
            lowercaseCmd.contains("gem install") ||
            lowercaseCmd.contains("apt-get install") ||
            lowercaseCmd.contains("apt install")) {
            return true
        }

        // Build commands
        if (lowercaseCmd.contains("cargo build") ||
            lowercaseCmd.contains("npm run build") ||
            lowercaseCmd.contains("yarn build") ||
            lowercaseCmd.contains("mvn package") ||
            lowercaseCmd.contains("gradle build") ||
            lowercaseCmd.contains("make install")) {
            return true
        }

        // Large file operations
        if (lowercaseCmd.contains("wget") ||
            lowercaseCmd.contains("curl") && (lowercaseCmd.contains("-o") || lowercaseCmd.contains("--output"))) {
            return true
        }

        return false
    }

    companion object {
        private const val TAG = "SandboxBridgeServer"

        /** Default localhost port for the sandbox bridge. */
        const val DEFAULT_PORT = 49481

        /** Default maximum timeout in seconds for execute commands (3 hours). */
        const val DEFAULT_MAX_TIMEOUT_SECS = 10800L
    }
}
