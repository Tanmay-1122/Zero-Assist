package com.zeroclaw.android.service.devicecontrol

import android.util.Log

/**
 * Structured performance trace for one complete device-control request.
 *
 * Collects timestamps at every pipeline boundary and emits a single
 * summary log line at completion for easy logcat filtering:
 *
 *   DEVICE_CONTROL_PERF request_id=... total_ms=... planner_calls=...
 */
class DeviceControlPerformanceTrace(
    val requestId: String,
    val goalHash: Int,
) {
    private val totalStart = System.currentTimeMillis()

    private var callbackEntryMs = 0L
    private var executorStartMs = 0L
    private var executorEndMs = 0L
    private var callbackExitMs = 0L

    var plannerCalls = 0
        private set
    private var plannerTotalMs = 0L
    private var plannerMaxMs = 0L
    private var lastPlannerLatencyMs = 0L

    var snapshots = 0
        private set
    private var snapshotTotalMs = 0L

    var actions = 0
        private set
    private var actionTotalMs = 0L

    var waitTotalMs = 0L
        private set

    var retries = 0
        private set
    var recoveries = 0
        private set

    var result: String = "UNKNOWN"
        private set

    // ── Callback-level timestamps ────────────────────────────────────

    fun markCallbackEntry() { callbackEntryMs = System.currentTimeMillis() }
    fun markExecutorStart() { executorStartMs = System.currentTimeMillis() }
    fun markExecutorEnd() { executorEndMs = System.currentTimeMillis() }
    fun markCallbackExit() { callbackExitMs = System.currentTimeMillis() }

    // ── Planner metrics ──────────────────────────────────────────────

    fun beginPlannerCall(): Long = System.currentTimeMillis()

    fun endPlannerCall(startMs: Long) {
        val elapsed = System.currentTimeMillis() - startMs
        plannerCalls++
        plannerTotalMs += elapsed
        lastPlannerLatencyMs = elapsed
        if (elapsed > plannerMaxMs) plannerMaxMs = elapsed
    }

    fun addRetry() { retries++ }
    fun addRecovery() { recoveries++ }

    // ── Snapshot metrics ─────────────────────────────────────────────

    fun beginSnapshot(): Long = System.currentTimeMillis()

    fun endSnapshot(startMs: Long) {
        val elapsed = System.currentTimeMillis() - startMs
        snapshots++
        snapshotTotalMs += elapsed
    }

    // ── Action metrics ───────────────────────────────────────────────

    fun beginAction(): Long = System.currentTimeMillis()

    fun endAction(startMs: Long) {
        val elapsed = System.currentTimeMillis() - startMs
        actions++
        actionTotalMs += elapsed
    }

    fun addWait(ms: Long) { waitTotalMs += ms }

    // ── Result ───────────────────────────────────────────────────────

    fun markSuccess() { result = "SUCCESS" }
    fun markFailure() { result = "FAILURE" }
    fun markCancelled() { result = "CANCELLED" }

    // ── Emit ─────────────────────────────────────────────────────────

    fun emit() {
        val totalMs = System.currentTimeMillis() - totalStart
        val avgPlannerMs = if (plannerCalls > 0) plannerTotalMs / plannerCalls else 0L

        val summary = buildString {
            append("DEVICE_CONTROL_PERF")
            append(" request_id=$requestId")
            append(" goal_hash=$goalHash")
            append(" total_ms=$totalMs")
            append(" planner_calls=$plannerCalls")
            append(" planner_total_ms=$plannerTotalMs")
            append(" planner_avg_ms=$avgPlannerMs")
            append(" planner_max_ms=$plannerMaxMs")
            append(" snapshots=$snapshots")
            append(" snapshot_total_ms=$snapshotTotalMs")
            append(" actions=$actions")
            append(" action_total_ms=$actionTotalMs")
            append(" wait_total_ms=$waitTotalMs")
            append(" retries=$retries")
            append(" recoveries=$recoveries")
            append(" result=$result")
        }
        Log.i(TAG, summary)
    }

    companion object {
        private const val TAG = "DeviceControlPerf"
    }
}
