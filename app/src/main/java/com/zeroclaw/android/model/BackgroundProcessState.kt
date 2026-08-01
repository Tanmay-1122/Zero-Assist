/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.model

import kotlinx.serialization.Serializable

/**
 * Represents a single background operation/process entry.
 */
@Serializable
data class BackgroundProcessEntry(
    val id: String = java.util.UUID.randomUUID().toString(),
    val type: ProcessType,
    val description: String,
    val details: String = "",
    val timestamp: Long = System.currentTimeMillis(),
    val status: ProcessStatus = ProcessStatus.ACTIVE,
    val durationMs: Long? = null,
    val source: String = "",
    val stage: String = "",
    val progressLabel: String = "",
    val startedAtMs: Long = timestamp,
    val lastHeartbeatMs: Long = timestamp,
    val expectedDurationMs: Long? = null,
    val stuckReason: String? = null,
    val nextAction: String? = null,
    val correlationId: String? = null,
)

/**
 * Types of background processes to track.
 */
enum class ProcessType {
    SEARCH,           // 🔍 File/semantic/grep searches
    FILE_READ,        // 📄 Reading files
    FILE_WRITE,       // 💾 Writing/creating files
    API_CALL,         // 📡 External API calls
    MEMORY_OP,        // 🧠 Memory operations (save/load)
    ANALYSIS,         // 🔬 Data analysis/logic
    TOOL_EXEC,        // 🔧 Tool execution
    DECISION,         // ✅ Decision making
    OTHER,            // ℹ️ Other operations
}

/**
 * Status of a background process.
 */
enum class ProcessStatus {
    ACTIVE,           // Currently running
    COMPLETED,        // Finished successfully
    FAILED,           // Finished with error
    CANCELLED,        // Cancelled by user
}

/**
 * Container for all background processes in the current session.
 */
data class BackgroundProcessState(
    val processes: List<BackgroundProcessEntry> = emptyList(),
    val isVisible: Boolean = false,
    val isExpanded: Boolean = false,
    val maxEntries: Int = 50,  // Keep only recent entries
) {
    /** True when at least one process has been logged this session. */
    fun hasAnyProcess(): Boolean = processes.isNotEmpty()

    /** True when at least one process is currently running. */
    fun shouldAutoShow(): Boolean = processes.any { it.status == ProcessStatus.ACTIVE }

    /**
     * Add a new process entry, removing oldest if exceeding maxEntries.
     */
    fun addProcess(entry: BackgroundProcessEntry): BackgroundProcessState {
        val updated = (processes.filterNot { it.id == entry.id } + entry)
            .takeLast(maxEntries)
        return copy(processes = updated)
    }

    /**
     * Update an existing process (mark as complete, etc).
     */
    fun updateProcess(
        id: String,
        status: ProcessStatus,
        durationMs: Long? = null,
        details: String? = null,
        progressLabel: String? = null,
        lastHeartbeatMs: Long? = null,
        stuckReason: String? = null,
        nextAction: String? = null,
        stage: String? = null,
    ): BackgroundProcessState {
        val updated = processes.map { proc ->
            if (proc.id == id) {
                proc.copy(
                    status = status,
                    durationMs = durationMs,
                    details = details ?: proc.details,
                    stage = stage ?: status.toProcessStage(proc.stage),
                    progressLabel = progressLabel ?: proc.progressLabel,
                    lastHeartbeatMs = lastHeartbeatMs ?: System.currentTimeMillis(),
                    stuckReason = stuckReason ?: proc.stuckReason,
                    nextAction = nextAction ?: proc.nextAction,
                )
            } else {
                proc
            }
        }
        return copy(processes = updated.deduplicatedByIdKeepingLast())
    }

    /**
     * Update runtime metadata for an active process without changing status.
     */
    fun updateProcessRuntime(
        id: String,
        stage: String? = null,
        progressLabel: String? = null,
        expectedDurationMs: Long? = null,
        stuckReason: String? = null,
        nextAction: String? = null,
        lastHeartbeatMs: Long = System.currentTimeMillis(),
    ): BackgroundProcessState {
        val updated = processes.map { proc ->
            if (proc.id == id) {
                proc.copy(
                    stage = stage ?: proc.stage,
                    progressLabel = progressLabel ?: proc.progressLabel,
                    expectedDurationMs = expectedDurationMs ?: proc.expectedDurationMs,
                    stuckReason = stuckReason ?: proc.stuckReason,
                    nextAction = nextAction ?: proc.nextAction,
                    lastHeartbeatMs = lastHeartbeatMs,
                )
            } else {
                proc
            }
        }
        return copy(processes = updated.deduplicatedByIdKeepingLast())
    }

    /**
     * Get only active/running processes.
     */
    fun getActiveProcesses(): List<BackgroundProcessEntry> =
        processes.filter { it.status == ProcessStatus.ACTIVE }

    /**
     * Get only recently completed processes.
     */
    fun getRecentCompletedProcesses(limitSeconds: Int = 10): List<BackgroundProcessEntry> {
        val cutoff = System.currentTimeMillis() - (limitSeconds * 1000)
        return processes.filter {
            it.status == ProcessStatus.COMPLETED &&
                (it.timestamp + (it.durationMs ?: 0)) > cutoff
        }
    }

    /**
     * Clear all processes.
     */
    fun clear(): BackgroundProcessState = copy(processes = emptyList())
}

private fun List<BackgroundProcessEntry>.deduplicatedByIdKeepingLast(): List<BackgroundProcessEntry> {
    val seen = HashSet<String>()
    return asReversed()
        .filter { entry -> seen.add(entry.id) }
        .asReversed()
}

private fun ProcessStatus.toProcessStage(currentStage: String): String =
    when (this) {
        ProcessStatus.ACTIVE -> currentStage
        ProcessStatus.COMPLETED -> "finished"
        ProcessStatus.FAILED -> "stuck"
        ProcessStatus.CANCELLED -> "blocked"
    }

/**
 * Get human-readable type label.
 */
fun ProcessType.label(): String = when (this) {
    ProcessType.SEARCH -> "Search"
    ProcessType.FILE_READ -> "Read"
    ProcessType.FILE_WRITE -> "Write"
    ProcessType.API_CALL -> "API"
    ProcessType.MEMORY_OP -> "Memory"
    ProcessType.ANALYSIS -> "Analysis"
    ProcessType.TOOL_EXEC -> "Tool"
    ProcessType.DECISION -> "Decision"
    ProcessType.OTHER -> "Process"
}

/**
 * Get status label.
 */
fun ProcessStatus.label(): String = when (this) {
    ProcessStatus.ACTIVE -> "Running"
    ProcessStatus.COMPLETED -> "Done"
    ProcessStatus.FAILED -> "Failed"
    ProcessStatus.CANCELLED -> "Cancelled"
}
