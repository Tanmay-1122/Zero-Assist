/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.goal.memory

import com.zeroclaw.android.diagnostics.RichRuntimeDiagnostics
import com.zeroclaw.android.goal.Goal
import com.zeroclaw.android.goal.GoalStatus
import com.zeroclaw.android.goal.GoalType
import com.zeroclaw.android.goal.graph.TaskGraph
import com.zeroclaw.android.goal.graph.TaskState
import com.zeroclaw.android.goal.graph.isTerminal
import com.zeroclaw.android.goal.schedule.GoalScheduler
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * One persisted goal execution (goal + execution graph) at a point in time.
 */
@Serializable
data class StoredGoal(
    val goal: Goal,
    val graph: TaskGraph,
    val savedAtEpochMs: Long = System.currentTimeMillis(),
    val snapshotCount: Int = 1,
)

/**
 * Persistent store for goals and their execution graphs.
 */
interface GoalStore {
    suspend fun save(stored: StoredGoal)
    suspend fun load(goalId: String): StoredGoal?
    suspend fun listGoalIds(): List<String>
    suspend fun delete(goalId: String)
}

/**
 * In-memory store for tests and lightweight deployments.
 */
class InMemoryGoalStore : GoalStore {
    private val map = LinkedHashMap<String, StoredGoal>()
    private val mutex = Mutex()

    override suspend fun save(stored: StoredGoal) = mutex.withLock {
        val previous = map[stored.goal.id]
        map[stored.goal.id] = stored.copy(snapshotCount = (previous?.snapshotCount ?: 0) + 1)
    }

    override suspend fun load(goalId: String): StoredGoal? = mutex.withLock { map[goalId] }

    override suspend fun listGoalIds(): List<String> = mutex.withLock { map.keys.toList() }

    override suspend fun delete(goalId: String) {
        mutex.withLock { map.remove(goalId) }
    }

    suspend fun size(): Int = mutex.withLock { map.size }
}

/**
 * File-backed store: one JSON file per goal under a root directory.
 * Enables crash recovery and cross-process resume.
 */
class JsonGoalStore(
    private val rootDirectory: File,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) : GoalStore {

    init {
        rootDirectory.mkdirs()
    }

    override suspend fun save(stored: StoredGoal) {
        val file = fileFor(stored.goal.id)
        file.parentFile?.mkdirs()
        val content = json.encodeToString(stored)
        file.writeText(content)
    }

    override suspend fun load(goalId: String): StoredGoal? {
        val file = fileFor(goalId)
        if (!file.isFile) return null
        return runCatching { json.decodeFromString<StoredGoal>(file.readText()) }.getOrNull()
    }

    override suspend fun listGoalIds(): List<String> {
        return rootDirectory.listFiles { file -> file.name.endsWith(".json") }
            ?.map { it.name.removePrefix("goal_").removeSuffix(".json") }
            ?.sorted()
            ?: emptyList()
    }

    override suspend fun delete(goalId: String) {
        fileFor(goalId).delete()
    }

    private fun fileFor(goalId: String): File =
        File(rootDirectory, "goal_${sanitize(goalId)}.json")

    private fun sanitize(goalId: String): String =
        goalId.replace(Regex("[^A-Za-z0-9._-]"), "_")
}

/**
 * High-level memory integration for goals:
 *
 * - long-running / scheduled / resumable goals,
 * - interrupted-execution recovery (crash recovery via snapshots),
 * - persistent projects (goal histories).
 */
class GoalMemoryService(
    private val store: GoalStore,
) {
    private val mutex = Mutex()

    /** Persists a snapshot. Scheduler hooks this via [com.zeroclaw.android.goal.schedule.GoalPersistenceListener]. */
    suspend fun snapshot(goal: Goal, graph: TaskGraph) {
        store.save(StoredGoal(goal = goal, graph = graph))
    }

    /** Loads a stored goal execution for inspection or resume. */
    suspend fun recall(goalId: String): StoredGoal? = store.load(goalId)

    /** Deletes a goal (e.g. after completion or explicit discard). */
    suspend fun forget(goalId: String) = store.delete(goalId)

    /** Lists all persisted goals. */
    suspend fun listGoals(): List<StoredGoal> {
        return store.listGoalIds().mapNotNull { store.load(it) }
    }

    /**
     * Stores a goal to run at [atEpochMs]. The scheduler can poll
     * [dueGoals] and execute them when ready.
     */
    suspend fun schedule(goal: Goal, atEpochMs: Long) {
        val scheduled = goal.copy(
            type = GoalType.SCHEDULED,
            runtime = goal.runtime.copy(scheduledForEpochMs = atEpochMs),
        )
        store.save(StoredGoal(goal = scheduled, graph = TaskGraph.empty(goal.id)))
    }

    /** Goals whose scheduled time has passed and which are not yet running/completed. */
    suspend fun dueGoals(nowEpochMs: Long = System.currentTimeMillis()): List<StoredGoal> {
        return mutex.withLock {
            listGoals().filter { stored ->
                val scheduledFor = stored.goal.runtime.scheduledForEpochMs
                scheduledFor != null &&
                    scheduledFor <= nowEpochMs &&
                    stored.goal.runtime.status == GoalStatus.PLANNED &&
                    stored.graph.size() == 0
            }
        }
    }

    /**
     * Resumes an interrupted (crashed) execution from its last snapshot.
     * Non-terminal nodes are re-admitted by the scheduler on [GoalScheduler.execute].
     */
    suspend fun resume(
        goalId: String,
        scheduler: GoalScheduler,
    ): Goal? {
        val stored = store.load(goalId) ?: return null
        val resumedGoal = stored.goal.copy(
            type = if (stored.goal.type == GoalType.RECOVERABLE || stored.goal.type == GoalType.LONG_RUNNING) {
                stored.goal.type
            } else {
                GoalType.RECOVERABLE
            },
            runtime = stored.goal.runtime.copy(
                status = GoalStatus.PLANNED,
                attempt = stored.goal.runtime.attempt + 1,
            ),
        )
        scheduler.execute(resumedGoal, stored.graph)
        return resumedGoal
    }
}

/**
 * Structured telemetry for goal execution. Records scheduler events, timeline,
 * resource usage, recovery events, and per-goal summaries. Mirrors everything
 * into [RichRuntimeDiagnostics] under the "GOAL" category so the existing
 * developer dashboard keeps working.
 */
object GoalDiagnostics {
    private val timeline = CopyOnWriteArrayList<GoalTimelineEvent>()
    private val recoveryEvents = CopyOnWriteArrayList<String>()
    private val resourceUsage = ConcurrentHashMap<String, AtomicLong>()
    private val stateCounters = ConcurrentHashMap<TaskState, AtomicLong>()

    /** A point-in-time scheduler event. */
    data class GoalTimelineEvent(
        val timestampMs: Long = System.currentTimeMillis(),
        val goalId: String,
        val type: String,
        val detail: String,
        val durationMs: Long? = null,
    )

    /** Aggregated counters for the inspector dashboard. */
    data class GoalTelemetry(
        val goalsStarted: Long,
        val goalsCompleted: Long,
        val goalsFailed: Long,
        val goalsCancelled: Long,
        val tasksTotal: Long,
        val tasksSucceeded: Long,
        val tasksFailed: Long,
        val recoveryEvents: Int,
        val activeGoals: Int,
        val resourceUsage: Map<String, Long>,
        val lastTimeline: List<GoalTimelineEvent>,
    )

    @Volatile
    private var activeGoals = 0
    private val goalsStarted = AtomicLong()
    private val goalsCompleted = AtomicLong()
    private val goalsFailed = AtomicLong()
    private val goalsCancelled = AtomicLong()
    private val tasksTotal = AtomicLong()
    private val tasksSucceeded = AtomicLong()
    private val tasksFailed = AtomicLong()

    fun record(goalId: String, type: String, detail: String, durationMs: Long? = null) {
        timeline.add(GoalTimelineEvent(goalId = goalId, type = type, detail = detail, durationMs = durationMs))
        if (timeline.size > 2000) {
            timeline.removeAt(0)
        }
        RichRuntimeDiagnostics.record("GOAL", "[$goalId] $type: $detail", durationMs)
    }

    fun recordRecovery(goalId: String, detail: String) {
        recoveryEvents.add("[${System.currentTimeMillis()}] [$goalId] $detail")
        record(goalId, "RECOVERY", detail)
    }

    fun markGoalStarted() {
        activeGoals += 1
        goalsStarted.incrementAndGet()
    }

    fun markGoalFinished(status: GoalStatus) {
        activeGoals = (activeGoals - 1).coerceAtLeast(0)
        when (status) {
            GoalStatus.COMPLETED -> goalsCompleted.incrementAndGet()
            GoalStatus.FAILED -> goalsFailed.incrementAndGet()
            GoalStatus.CANCELLED -> goalsCancelled.incrementAndGet()
            else -> {}
        }
    }

    fun markTaskTerminal(taskState: TaskState, total: Long) {
        tasksTotal.incrementAndGet()
        when (taskState) {
            TaskState.SUCCEEDED -> tasksSucceeded.incrementAndGet()
            TaskState.FAILED -> tasksFailed.incrementAndGet()
            else -> {}
        }
        stateCounters.computeIfAbsent(taskState) { AtomicLong() }.incrementAndGet()
        if (total > 0 && tasksTotal.get() % 10 == 0L) {
            // Throttled mirror for existing dashboard.
            RichRuntimeDiagnostics.record(
                "GOAL_TAIL",
                "task terminal; succeeded=${tasksSucceeded.get()} failed=${tasksFailed.get()}",
            )
        }
    }

    fun noteResource(kind: String, count: Long) {
        resourceUsage.computeIfAbsent(kind) { AtomicLong() }.addAndGet(count)
    }

    fun noteActiveTasks(active: Int) {
        noteResource("active_tasks_peak", 0)
        resourceUsage.computeIfAbsent("active_tasks_last") { AtomicLong() }.set(active.toLong())
    }

    fun getTimeline(goalId: String? = null): List<GoalTimelineEvent> {
        return timeline.filter { goalId == null || it.goalId == goalId }
    }

    fun getRecoveryEvents(goalId: String? = null): List<String> {
        return if (goalId == null) recoveryEvents.toList()
        else recoveryEvents.filter { it.contains("[$goalId]") }
    }

    fun getResourceUsage(): Map<String, Long> {
        return resourceUsage.mapValues { it.value.get() }
    }

    fun telemetry(): GoalTelemetry {
        return GoalTelemetry(
            goalsStarted = goalsStarted.get(),
            goalsCompleted = goalsCompleted.get(),
            goalsFailed = goalsFailed.get(),
            goalsCancelled = goalsCancelled.get(),
            tasksTotal = tasksTotal.get(),
            tasksSucceeded = tasksSucceeded.get(),
            tasksFailed = tasksFailed.get(),
            recoveryEvents = recoveryEvents.size,
            activeGoals = activeGoals,
            resourceUsage = getResourceUsage(),
            lastTimeline = timeline.takeLast(40),
        )
    }

    fun clear() {
        timeline.clear()
        recoveryEvents.clear()
        resourceUsage.clear()
        stateCounters.clear()
    }

    /**
     * Human-readable graph inspection used by the developer dashboard.
     */
    fun describeGraph(graph: TaskGraph): String {
        val states = graph.nodes.values.groupingBy { it.state }.eachCount()
        val sb = StringBuilder()
        sb.appendLine(graph.describeShape())
        states.forEach { (state, count) -> sb.appendLine("  $state: $count") }
        graph.nodes.values.filter { it.state.isTerminal || it.state == TaskState.RUNNING }
            .sortedBy { it.taskId }
            .forEach { node ->
                val result = node.result
                val suffix = result?.let { r ->
                    val provider = r.providerId?.let { "@$it" } ?: ""
                    val verified = if (r.verified) "verified" else "unverified"
                    val error = r.error?.let { " err=${it.take(60)}" } ?: ""
                    " $provider $verified$error"
                } ?: ""
                sb.appendLine("  [${node.state}] ${node.taskId} (${node.capabilityId})$suffix")
            }
        return sb.toString().trimEnd()
    }
}
