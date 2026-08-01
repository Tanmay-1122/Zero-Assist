/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import android.os.SystemClock
import android.util.Log
import com.zeroclaw.android.model.BackgroundProcessEntry
import com.zeroclaw.android.model.BackgroundProcessState
import com.zeroclaw.android.model.ProcessStatus
import com.zeroclaw.android.model.ProcessType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicReference

/**
 * Service to log and track background processes happening during user interactions.
 *
 * Used to capture:
 * - File searches and operations
 * - API calls and responses
 * - Memory operations
 * - Tool executions
 * - Analysis/logic decisions
 *
 * Emits state updates that UI components can subscribe to for real-time display.
 * Auto-shows when work starts and auto-hides [AUTO_HIDE_DELAY_MS] after all work completes.
 */
class BackgroundProcessLogger {
    private val _state = MutableStateFlow(BackgroundProcessState())
    val state: StateFlow<BackgroundProcessState> = _state.asStateFlow()

    private val activeProcesses = mutableMapOf<String, Long>()  // id -> startTime
    private val lastProcessUpdateAt = mutableMapOf<String, Long>()
    private val lastProcessDescription = mutableMapOf<String, String>()

    /** Internal scope used solely for the auto-hide delay job. */
    private val loggerScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val autoHideJob = AtomicReference<Job?>(null)

    /**
     * Log a file search operation.
     *
     * Example: logSearch("git grep", "test handlers", "src/", "Found 5 matches")
     */
    fun logSearch(
        tool: String,
        query: String,
        scope: String,
        result: String,
    ): String {
        val description = "$tool: $query in $scope"
        val details = result
        return logProcess(ProcessType.SEARCH, description, details)
    }

    /**
     * Log a file read operation.
     *
     * Example: logFileRead("MainActivity.kt", "lines 1-100")
     */
    fun logFileRead(
        filename: String,
        range: String = "",
    ): String {
        val description = "Reading: $filename" +
            if (range.isNotEmpty()) " [$range]" else ""
        return logProcess(ProcessType.FILE_READ, description)
    }

    /**
     * Log a file write/create operation.
     *
     * Example: logFileWrite("utils/helpers.ts", "created", "200 lines")
     */
    fun logFileWrite(
        filename: String,
        action: String,
        details: String = "",
    ): String {
        val description = "$action: $filename"
        return logProcess(ProcessType.FILE_WRITE, description, details)
    }

    /**
     * Log an API call.
     *
     * Example: logApiCall("OpenAI", "GPT-4", "code analysis", "response in 2.3s")
     */
    fun logApiCall(
        service: String,
        endpoint: String,
        purpose: String = "",
        details: String = "",
    ): String {
        val description = "API: $service → $endpoint" +
            if (purpose.isNotEmpty()) " ($purpose)" else ""
        return logProcess(ProcessType.API_CALL, description, details)
    }

    /**
     * Log a memory operation.
     *
     * Example: logMemory("Loaded", "/memories/android_datastore_fix.md")
     */
    fun logMemory(
        operation: String,
        path: String,
        details: String = "",
    ): String {
        val description = "$operation: $path"
        return logProcess(ProcessType.MEMORY_OP, description, details)
    }

    /**
     * Log analysis/reasoning.
     *
     * Example: logAnalysis("fragment lifecycle", "state management considerations")
     */
    fun logAnalysis(
        subject: String,
        reasoning: String,
    ): String {
        val description = "Analyzing: $subject"
        return logProcess(ProcessType.ANALYSIS, description, reasoning)
    }

    /**
     * Log a tool execution.
     *
     * Example: logToolExecution("file_search", "gradle.properties")
     */
    fun logToolExecution(
        toolName: String,
        parameters: String = "",
    ): String {
        val description = "Tool: $toolName" +
            if (parameters.isNotEmpty()) " ($parameters)" else ""
        return logProcess(ProcessType.TOOL_EXEC, description)
    }

    /**
     * Log a decision/approach selection.
     *
     * Example: logDecision("multi_replace_string_in_file", "more efficient than sequential edits")
     */
    fun logDecision(
        decision: String,
        reasoning: String,
    ): String {
        val description = "Decision: $decision"
        return logProcess(ProcessType.DECISION, description, reasoning)
    }

    /**
     * Log a generic operation.
     */
    fun logOperation(
        type: ProcessType,
        description: String,
        details: String = "",
        source: String = "",
        stage: String = "",
        progressLabel: String = "",
        expectedDurationMs: Long? = null,
        nextAction: String? = null,
        correlationId: String? = null,
    ): String {
        return logProcess(
            type = type,
            description = description,
            details = details,
            source = source,
            stage = stage,
            progressLabel = progressLabel,
            expectedDurationMs = expectedDurationMs,
            nextAction = nextAction,
            correlationId = correlationId,
        )
    }

    /**
     * Mark a process as completed.
     */
    fun completeProcess(
        processId: String,
        durationMs: Long? = null,
        details: String? = null,
    ) {
        val actualDuration = durationMs ?: activeProcesses[processId]?.let {
            System.currentTimeMillis() - it
        }

        _state.update {
            it.updateProcess(
                processId,
                ProcessStatus.COMPLETED,
                actualDuration,
                details,
                lastHeartbeatMs = System.currentTimeMillis(),
            )
        }

        activeProcesses.remove(processId)
        lastProcessUpdateAt.remove(processId)
        lastProcessDescription.remove(processId)
        Log.d(TAG, "Process completed: $processId (${actualDuration}ms)")
        scheduleAutoHideIfIdle()
    }

    /**
     * Mark a process as failed.
     */
    fun failProcess(
        processId: String,
        error: String,
        durationMs: Long? = null,
    ) {
        val actualDuration = durationMs ?: activeProcesses[processId]?.let {
            System.currentTimeMillis() - it
        }

        _state.update {
            val entry = it.processes.find { p -> p.id == processId }
            if (entry != null) {
                it.updateProcess(
                    processId,
                    ProcessStatus.FAILED,
                    actualDuration,
                    error,
                    lastHeartbeatMs = System.currentTimeMillis(),
                    stuckReason = error,
                    nextAction = "Review failure details",
                )
            } else {
                it
            }
        }

        activeProcesses.remove(processId)
        lastProcessUpdateAt.remove(processId)
        lastProcessDescription.remove(processId)
        Log.e(TAG, "Process failed: $processId - $error")
        scheduleAutoHideIfIdle()
    }

    /**
     * Update description of an ongoing process.
     */
    fun updateProcess(
        processId: String,
        description: String,
        progressLabel: String = description,
        stuckReason: String? = null,
        nextAction: String? = null,
    ) {
        val now = SystemClock.elapsedRealtime()
        val lastAt = lastProcessUpdateAt[processId] ?: 0L
        val lastDescription = lastProcessDescription[processId]
        if (description == lastDescription) return
        if (now - lastAt < MIN_UPDATE_INTERVAL_MS) return

        _state.update {
            val updated = it.processes.map { entry ->
                if (entry.id == processId) {
                    entry.copy(
                        description = description,
                        progressLabel = progressLabel,
                        lastHeartbeatMs = System.currentTimeMillis(),
                        stuckReason = stuckReason ?: entry.stuckReason,
                        nextAction = nextAction ?: entry.nextAction,
                    )
                } else {
                    entry
                }
            }
            it.copy(processes = updated)
        }
        lastProcessUpdateAt[processId] = now
        lastProcessDescription[processId] = description
    }

    /**
     * Toggle visibility of the process card.
     */
    fun setVisible(visible: Boolean) {
        autoHideJob.getAndSet(null)?.cancel()
        _state.update { it.copy(isVisible = visible) }
    }

    /**
     * Toggle expansion of the process card.
     */
    fun setExpanded(expanded: Boolean) {
        _state.update { it.copy(isExpanded = expanded) }
    }

    /**
     * Clear all processes and reset to hidden state. Call on new session start.
     */
    fun resetForNewSession() {
        autoHideJob.getAndSet(null)?.cancel()
        _state.update { BackgroundProcessState() }
        activeProcesses.clear()
        lastProcessUpdateAt.clear()
        lastProcessDescription.clear()
        Log.d(TAG, "Logger reset for new session")
    }

    /**
     * Clear all processes.
     */
    fun clear() {
        autoHideJob.getAndSet(null)?.cancel()
        _state.update { it.clear() }
        activeProcesses.clear()
        lastProcessUpdateAt.clear()
        lastProcessDescription.clear()
    }

    /**
     * Get current state.
     */
    fun getCurrentState(): BackgroundProcessState = _state.value

    /**
     * Get process by ID.
     */
    fun getProcess(id: String): BackgroundProcessEntry? =
        _state.value.processes.find { it.id == id }

    /**
     * Private: Core logging logic. Auto-shows the card when the first process arrives.
     */
    private fun logProcess(
        type: ProcessType,
        description: String,
        details: String = "",
        source: String = "",
        stage: String = "",
        progressLabel: String = "",
        expectedDurationMs: Long? = null,
        nextAction: String? = null,
        correlationId: String? = null,
    ): String {
        // Cancel any pending auto-hide — new work is starting
        autoHideJob.getAndSet(null)?.cancel()

        val now = System.currentTimeMillis()
        val entry = BackgroundProcessEntry(
            type = type,
            description = description,
            details = details,
            timestamp = now,
            source = source,
            stage = stage,
            progressLabel = progressLabel,
            startedAtMs = now,
            lastHeartbeatMs = now,
            expectedDurationMs = expectedDurationMs,
            nextAction = nextAction,
            correlationId = correlationId,
        )

        _state.update { it.addProcess(entry).copy(isVisible = true) }
        activeProcesses[entry.id] = now
        lastProcessDescription[entry.id] = description

        Log.d(TAG, "Process logged: ${entry.description}")

        return entry.id
    }

    /**
     * Schedule auto-hide [AUTO_HIDE_DELAY_MS] after the last active process ends.
     * Cancelled immediately if new work arrives.
     */
    private fun scheduleAutoHideIfIdle() {
        if (_state.value.getActiveProcesses().isNotEmpty()) return
        autoHideJob.getAndSet(null)?.cancel()
        val job = loggerScope.launch {
            delay(AUTO_HIDE_DELAY_MS)
            if (_state.value.getActiveProcesses().isEmpty()) {
                _state.update { it.copy(isVisible = false) }
                Log.d(TAG, "Auto-hiding background process card")
            }
        }
        autoHideJob.set(job)
    }

    companion object {
        private const val TAG = "BackgroundProcessLogger"
        private const val MIN_UPDATE_INTERVAL_MS = 200L

        /** Delay after last process completes before the card auto-hides. */
        private const val AUTO_HIDE_DELAY_MS = 3_000L
    }
}

/**
 * Global singleton instance.
 * Can be injected via DI in production, but singleton is fine for most cases.
 */
private var loggerInstance: BackgroundProcessLogger? = null

fun getBackgroundProcessLogger(): BackgroundProcessLogger {
    return loggerInstance ?: BackgroundProcessLogger().also { loggerInstance = it }
}

fun resetBackgroundProcessLogger() {
    loggerInstance = null
}
