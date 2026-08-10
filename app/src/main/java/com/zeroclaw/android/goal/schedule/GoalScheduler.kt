/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.goal.schedule

import com.zeroclaw.android.goal.CancellationToken
import com.zeroclaw.android.goal.Goal
import com.zeroclaw.android.goal.GoalStatus
import com.zeroclaw.android.goal.GoalType
import com.zeroclaw.android.goal.HumanClarificationRequest
import com.zeroclaw.android.goal.SecurityContext
import com.zeroclaw.android.goal.agents.AgentCoordinator
import com.zeroclaw.android.goal.agents.AgentOutput
import com.zeroclaw.android.goal.agents.AgentOutputMerger
import com.zeroclaw.android.goal.agents.SubAgent
import com.zeroclaw.android.goal.agents.ExecutionOutcome
import com.zeroclaw.android.goal.agents.TaskExecutor
import com.zeroclaw.android.goal.memory.GoalDiagnostics
import com.zeroclaw.android.goal.graph.GraphUpdate
import com.zeroclaw.android.goal.graph.MergePolicy
import com.zeroclaw.android.goal.graph.TaskGraph
import com.zeroclaw.android.goal.graph.TaskNode
import com.zeroclaw.android.goal.graph.TaskResult
import com.zeroclaw.android.goal.graph.TaskState
import com.zeroclaw.android.goal.graph.isTerminal
import com.zeroclaw.android.goal.plan.AlternativePlanProvider
import com.zeroclaw.android.goal.plan.ReplanningEngine
import com.zeroclaw.android.goal.recover.HumanClarificationAnswer
import com.zeroclaw.android.goal.recover.FallbackUsage
import com.zeroclaw.android.goal.recover.RecoveryDecision
import com.zeroclaw.android.goal.recover.RecoveryEngine
import com.zeroclaw.android.goal.GoalSecurityEnforcer
import com.zeroclaw.android.goal.verify.VerificationEngine
import com.zeroclaw.android.goal.verify.VerificationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Concurrency and resource budgets the scheduler must respect.
 */
data class ResourceLimits(
    val maxConcurrentTasks: Int = 4,
    /** Optional per-capability concurrency caps (backpressure at capability level). */
    val maxPerCapability: Map<String, Int> = emptyMap(),
)

/**
 * Immutable snapshot of goal execution state, safe to render in UI.
 */
data class GoalExecutionSnapshot(
    val goal: Goal,
    val graph: TaskGraph,
    val status: GoalStatus,
    val runningTasks: List<TaskNode>,
    val completedTasks: List<TaskNode>,
    val waitingTasks: List<TaskNode>,
    val skippedTasks: List<TaskNode>,
    val progress: Float,
    val recoveryEvents: List<String>,
    val agentAssignments: Map<String, String>,
    val startedAtEpochMs: Long?,
) {
    val totalTasks: Int get() = graph.size()
}

/**
 * Streaming execution events for diagnostics and the rich UI overlay.
 */
sealed interface ExecutionEvent {
    val goalId: String

    data class GoalStarted(override val goalId: String, val goal: Goal) : ExecutionEvent
    data class GoalProgress(
        override val goalId: String,
        val progress: Float,
        val running: Int,
        val completed: Int,
        val total: Int,
    ) : ExecutionEvent
    data class GraphUpdated(
        override val goalId: String,
        val revision: Long,
        val applied: List<GraphUpdate>,
    ) : ExecutionEvent
    data class TaskStarted(
        override val goalId: String,
        val taskId: String,
        val capabilityId: String,
        val agentId: String?,
    ) : ExecutionEvent
    data class TaskVerifying(
        override val goalId: String,
        val taskId: String,
        val attempt: Int,
    ) : ExecutionEvent
    data class TaskCompleted(
        override val goalId: String,
        val taskId: String,
        val result: TaskResult,
    ) : ExecutionEvent
    data class TaskFailed(
        override val goalId: String,
        val taskId: String,
        val result: TaskResult,
    ) : ExecutionEvent
    data class TaskCancelled(override val goalId: String, val taskId: String) : ExecutionEvent
    data class TaskSkipped(override val goalId: String, val taskId: String) : ExecutionEvent
    data class RecoveryOccurred(
        override val goalId: String,
        val taskId: String,
        val decision: String,
        val reason: String,
    ) : ExecutionEvent
    data class Replanned(
        override val goalId: String,
        val revision: Long,
        val applied: List<GraphUpdate>,
    ) : ExecutionEvent
    data class HumanClarificationRequested(
        override val goalId: String,
        val request: HumanClarificationRequest,
    ) : ExecutionEvent
    data class GoalCompleted(override val goalId: String, val goal: Goal) : ExecutionEvent
    data class GoalFailed(override val goalId: String, val goal: Goal, val reason: String) : ExecutionEvent
    data class GoalCancelled(override val goalId: String, val goal: Goal, val reason: String) : ExecutionEvent
}

/**
 * Snapshot hook invoked by the scheduler whenever goal state advances, enabling
 * the memory layer to persist goals for crash recovery / resume (Phase K).
 */
fun interface GoalPersistenceListener {
    suspend fun onSnapshot(goal: Goal, graph: TaskGraph)
}

/**
 * Internal mutable state of one scheduled goal execution.
 */
private class ExecutionRuntime(initialGraph: TaskGraph) {
    var graph: TaskGraph = initialGraph
    val running = mutableSetOf<String>()
    val jobs = mutableMapOf<String, Job>()
    val results = mutableMapOf<String, TaskResult>()
    val readinessEpoch = mutableMapOf<String, Long>()
    var status = GoalStatus.PLANNED
    var cancelled = false
    var failureReason: String? = null
    var startedAt: Long = 0
    var epochCounter = 0L

    init {
        initialGraph.nodes.values.forEach { node ->
            node.result?.let { results[node.taskId] = it }
        }
    }
}

/**
 * Goal-oriented scheduler. Executes a [TaskGraph] as a DAG of capability tasks:
 *
 * - dependency resolution (only tasks with all-successful deps become ready),
 * - parallel execution bounded by [ResourceLimits],
 * - priority ordering + fairness within a priority class,
 * - cancellation, timeouts, backpressure,
 * - dynamic graph updates while execution is running,
 * - per-task verification and automatic recovery.
 *
 * Tasks are never executed inline — everything is scheduled through the loop.
 */
class GoalScheduler(
    private val executor: TaskExecutor,
    private val verificationEngine: VerificationEngine,
    private val recoveryEngine: RecoveryEngine,
    private val replanningEngine: ReplanningEngine = ReplanningEngine(),
    private val replanProvider: AlternativePlanProvider? = null,
    private val securityEnforcer: GoalSecurityEnforcer = GoalSecurityEnforcer(),
    private val agentCoordinator: AgentCoordinator? = null,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob()),
    private val resourceLimits: ResourceLimits = ResourceLimits(),
    private val identityGrant: SecurityContext = GoalSecurityEnforcer.READ_ONLY,
    private val persistenceListener: GoalPersistenceListener? = null,
    private val defaultTaskTimeoutMs: Long = 30_000,
) {
    private val mutex = Mutex()
    private val pendingClarifications = mutableMapOf<String, CompletableDeferred<HumanClarificationAnswer>>()
    @Volatile
    private var tickRef: CompletableDeferred<Unit> = CompletableDeferred()

    private val _snapshot = MutableStateFlow<GoalExecutionSnapshot?>(null)
    val snapshot: StateFlow<GoalExecutionSnapshot?> = _snapshot.asStateFlow()

    private val _events = MutableSharedFlow<ExecutionEvent>(extraBufferCapacity = 512)
    val events: SharedFlow<ExecutionEvent> = _events.asSharedFlow()

    private val runtimeState = ExecutionRuntime(TaskGraph.empty(""))
    private var currentGoal: Goal? = null

    init {
        // Internal drainer guarantees `emit` never blocks when observers are
        // slow or absent, while still delivering every event to subscribers.
        scope.launch {
            _events.collect { /* drain */ }
        }
    }

    /**
     * Executes [graph] for [goal]. Returns when the goal reaches a terminal state.
     */
    suspend fun execute(goal: Goal, graph: TaskGraph): GoalExecutionSnapshot {
        mutex.withLock {
            runtimeState.graph = graph
            runtimeState.status = GoalStatus.RUNNING
            runtimeState.startedAt = System.currentTimeMillis()
            runtimeState.cancelled = false
            runtimeState.failureReason = null
            runtimeState.running.clear()
            runtimeState.jobs.clear()
            runtimeState.readinessEpoch.clear()
            runtimeState.results.clear()
            graph.nodes.values.forEach { node ->
                node.result?.let { runtimeState.results[node.taskId] = it }
            }
            currentGoal = goal.copy(
                runtime = goal.runtime.copy(
                    status = GoalStatus.RUNNING,
                    startedAtEpochMs = System.currentTimeMillis(),
                ),
            )
            // Re-admit interrupted nodes after a resume.
            if (goal.type == GoalType.RECOVERABLE || goal.type == GoalType.LONG_RUNNING) {
                runtimeState.graph = runtimeState.graph.copy(
                    nodes = runtimeState.graph.nodes.mapValues { (_, node) ->
                        when (node.state) {
                            TaskState.RUNNING, TaskState.SCHEDULED, TaskState.RETRYING,
                            TaskState.WAITING_VERIFICATION, TaskState.WAITING_HUMAN,
                            -> node.copy(state = TaskState.PENDING)
                            else -> node
                        }
                    },
                )
            }
        }

        GoalDiagnostics.markGoalStarted()
        emitSafely(ExecutionEvent.GoalStarted(goal.id, goal))

        val schedulingJob = scope.launch {
            scheduleLoop(goal)
        }
        schedulingJob.join()

        val terminal = snapshotValue()
        persistSnapshot(goal)
        return terminal
    }

    /**
     * Applies [update] to the running graph. The scheduler re-evaluates the ready
     * set on the next loop pass — dynamic replanning without restart.
     */
    suspend fun submitUpdate(update: GraphUpdate): TaskGraph = submitUpdates(listOf(update))

    /**
     * Applies a batch of graph mutations. Replaced/removed running nodes are cancelled.
     */
    suspend fun submitUpdates(updates: List<GraphUpdate>): TaskGraph {
        if (updates.isEmpty()) return mutex.withLock { runtimeState.graph }
        val (applied, removedIds) = mutex.withLock {
            var valid = emptyList<GraphUpdate>()
            var g = runtimeState.graph
            for (update in updates) {
                if (g.validateUpdate(update)) {
                    g = g.apply(update)
                    valid = valid + update
                }
            }
            val removed = valid.mapNotNull {
                when (it) {
                    is GraphUpdate.RemoveNode -> it.taskId
                    is GraphUpdate.ReplaceNode -> it.node.taskId
                    else -> null
                }
            }
            runtimeState.running.removeAll(removed.toSet())
            runtimeState.results.keys.removeAll(removed.toSet())
            removed.forEach { id -> runtimeState.jobs.remove(id)?.cancel() }
            runtimeState.graph = g
            Pair(valid, removed)
        }
        if (applied.isNotEmpty()) {
            val revision = mutex.withLock { runtimeState.graph.revision }
            emitSafely(ExecutionEvent.GraphUpdated(currentGoal?.id.orEmpty(), revision, applied))
            GoalDiagnostics.record(currentGoal?.id.orEmpty(), "GRAPH_UPDATE", "revision=$revision updates=${applied.size}")
            completeTick()
        }
        return mutex.withLock { runtimeState.graph }
    }

    /**
     * Requests cancellation of the running goal.
     */
    suspend fun cancel(reason: String = "User requested cancellation") {
        mutex.withLock {
            runtimeState.cancelled = true
            runtimeState.failureReason = reason
            runtimeState.jobs.values.forEach { job -> job.cancel() }
            runtimeState.jobs.clear()
        }
        completeTick()
    }

    /**
     * Cancels a single task, leaving the rest of the goal running.
     */
    suspend fun cancelTask(taskId: String, reason: String = "Task cancelled") {
        mutex.withLock {
            runtimeState.jobs[taskId]?.cancel()
            runtimeState.jobs.remove(taskId)
        }
        completeTick()
    }

    /**
     * Resolves a pending human clarification.
     */
    suspend fun answerClarification(requestId: String, answer: HumanClarificationAnswer) {
        pendingClarifications.remove(requestId)?.complete(answer)
        completeTick()
    }

    suspend fun snapshotGraph(): TaskGraph = mutex.withLock { runtimeState.graph }

    fun isRunning(): Boolean = snapshot.value?.status == GoalStatus.RUNNING

    private suspend fun scheduleLoop(goal: Goal) {
        while (true) {
            // Fresh tick so the wait below actually blocks until new work arrives.
            val localTick = CompletableDeferred<Unit>()
            tickRef = localTick
            var terminal = false
            var shouldWait = false
            val skippedNodes = mutableListOf<TaskNode>()

            mutex.withLock {
                val state = runtimeState
                val now = System.currentTimeMillis()

                val deadline = goal.deadlineEpochMs
                if (deadline != null && now > deadline) {
                    state.cancelled = true
                    state.failureReason = "Deadline exceeded"
                    state.jobs.values.forEach { it.cancel() }
                    state.jobs.clear()
                    state.running.clear()
                    state.graph = state.graph.copy(
                        nodes = state.graph.nodes.mapValues { (_, node) ->
                            if (node.state != TaskState.SUCCEEDED && node.state != TaskState.FAILED) {
                                node.copy(state = TaskState.CANCELLED)
                            } else {
                                node
                            }
                        },
                    )
                    state.status = GoalStatus.FAILED
                    terminal = true
                } else if (state.cancelled) {
                    state.graph = state.graph.copy(
                        nodes = state.graph.nodes.mapValues { (_, node) ->
                            if (!node.state.isTerminal) node.copy(state = TaskState.CANCELLED) else node
                        },
                    )
                    state.running.clear()
                    state.status = GoalStatus.CANCELLED
                    terminal = true
                } else {
                    val freeSlots = (resourceLimits.maxConcurrentTasks - state.running.size).coerceAtLeast(0)
                    val eligible = state.graph.eligibleNodes(state.running).filter { node ->
                        node.state == TaskState.PENDING || node.state == TaskState.READY
                    }

                    eligible.forEach { node ->
                        if (node.taskId !in state.readinessEpoch) {
                            state.readinessEpoch[node.taskId] = ++state.epochCounter
                        }
                    }

                    val candidates = eligible.sortedWith(
                        compareBy<TaskNode> { -it.priority.ordinal }
                            .thenBy { state.readinessEpoch[it.taskId] ?: Long.MAX_VALUE }
                            .thenBy { it.taskId },
                    )

                    // Select greedily in priority order, honoring BOTH the global
                    // concurrency budget and per-capability backpressure against the
                    // tasks already committed in this batch.
                    val selected = mutableListOf<TaskNode>()
                    val tentativeRunning = state.running.toMutableSet()
                    var slotsLeft = freeSlots
                    for (candidate in candidates) {
                        if (slotsLeft <= 0) break
                        if (capabilitySlotsFree(tentativeRunning, state, candidate)) {
                            selected += candidate
                            tentativeRunning += candidate.taskId
                            slotsLeft -= 1
                        }
                    }

                    selected.forEach { task ->
                        state.running += task.taskId
                        state.readinessEpoch.remove(task.taskId)
                        state.graph = state.graph.apply(
                            GraphUpdate.ReplaceNode(task.copy(state = TaskState.SCHEDULED)),
                        )
                        val job = scope.launch {
                            executeTask(goal, task)
                        }
                        state.jobs[task.taskId] = job
                    }

                    // Stranded nodes: dependencies failed, they can never run.
                    if (selected.isEmpty() && state.running.isEmpty()) {
                        val stranded = state.graph.blockedNodes()
                        stranded.forEach { node ->
                            state.graph = state.graph.apply(
                                GraphUpdate.ReplaceNode(node.copy(state = TaskState.SKIPPED)),
                            )
                            skippedNodes += node
                            GoalDiagnostics.record(goal.id, "TASK_SKIPPED", node.taskId)
                        }
                    }

                    if (state.running.isEmpty()) {
                        val unreachable = state.graph.nodes.values.any {
                            !it.state.isTerminal &&
                                it.dependencies.any { dep -> state.graph.node(dep)?.state == TaskState.FAILED }
                        }
                        if (!unreachable) {
                            terminal = true
                            state.status = if (state.graph.nodes.values.any { it.state == TaskState.FAILED }) {
                                GoalStatus.FAILED
                            } else {
                                GoalStatus.COMPLETED
                            }
                        }
                    }
                    shouldWait = !terminal
                }
            }

            if (skippedNodes.isNotEmpty()) {
                skippedNodes.forEach { node ->
                    emitSafely(ExecutionEvent.TaskSkipped(goal.id, node.taskId))
                }
            }

            if (terminal) {
                finalizeGoal(goal)
                return
            }
            if (shouldWait) {
                localTick.await()
            }
        }
    }

    private fun capabilitySlotsFree(running: Set<String>, state: ExecutionRuntime, node: TaskNode): Boolean {
        val cap = resourceLimits.maxPerCapability[node.capabilityId] ?: return true
        val activeForCapability = running.count { id ->
            state.graph.node(id)?.capabilityId == node.capabilityId
        }
        return activeForCapability < cap
    }

    private suspend fun executeTask(goal: Goal, initialTask: TaskNode) {
        var task = initialTask
        var excludedProviders = emptySet<String>()
        var fallbackUsed = FallbackUsage()
        var retryRounds = 0

        val agent = task.agentId?.let { _ -> agentCoordinator?.resolveAgent(task) }

        try {
            while (true) {
                val state = mutex.withLock { runtimeState }
                if (state.cancelled) {
                    withContext(NonCancellable) {
                        markTerminal(goal, task, cancelledResult(task, "goal cancelled", agent), TaskState.CANCELLED)
                    }
                    return
                }

                // Security gate (Phase L).
                val grant = securityEnforcer.effectiveGrant(goal, identityGrant)
                if (!securityEnforcer.authorize(task, grant)) {
                    val reasons = securityEnforcer.deniedReasons(task, grant).joinToString(",")
                    GoalDiagnostics.record(goal.id, "SECURITY_DENIED", "${task.taskId}: $reasons")
                    withContext(NonCancellable) {
                        markTerminal(
                            goal, task,
                            TaskResult(task.taskId, task.capabilityId, success = false, error = "SECURITY_DENIED: missing $reasons"),
                            TaskState.FAILED,
                        )
                    }
                    return
                }

                markNodeState(goal, task.copy(state = TaskState.RUNNING))
                emitSafely(ExecutionEvent.TaskStarted(goal.id, task.taskId, task.capabilityId, agent?.agentId))
                GoalDiagnostics.record(goal.id, "TASK_START", "${task.taskId} (${task.capabilityId})")

                val outcome = runOutcome(task, agent, excludedProviders)
                if (outcome !is ExecutionOutcome.Denied) {
                    emitSafely(ExecutionEvent.TaskVerifying(goal.id, task.taskId, retryRounds))
                }

                val verification: VerificationResult = when (task.mergePolicy) {
                    MergePolicy.SINGLE -> verificationEngine.verify(task, outcome)
                    else -> VerificationResult.Verified(evidence = "merged outputs")
                }

                when (outcome) {
                    is ExecutionOutcome.Output -> {
                        if (verification is VerificationResult.Verified) {
                            withContext(NonCancellable) {
                                markTerminal(
                                    goal, task,
                                    TaskResult(
                                        taskId = task.taskId,
                                        capabilityId = task.capabilityId,
                                        providerId = outcome.providerId,
                                        outputJson = outcome.outputJson,
                                        success = true,
                                        retriesConsumed = retryRounds,
                                        verified = true,
                                        verificationEvidence = verification.evidence,
                                        verificationAttempts = verification.attempts,
                                        agentId = agent?.agentId,
                                    ),
                                    TaskState.SUCCEEDED,
                                )
                            }
                            return
                        }
                        val decision = recoveryEngine.decide(
                            goal, task, outcome, retryRounds, fallbackUsed,
                            excludedProviders = excludedProviders,
                            failedProviderId = outcome.providerId,
                        )
                        val continuation = handleRecovery(goal, task, decision, fallbackUsed)
                        if (continuation == null) return
                        task = continuation.task
                        excludedProviders = continuation.excludedProviders
                        fallbackUsed = continuation.fallbackUsed
                        retryRounds = continuation.retryRounds
                        if (continuation.returned) return
                    }

                    is ExecutionOutcome.Failure -> {
                        val decision = recoveryEngine.decide(
                            goal, task, outcome, retryRounds, fallbackUsed,
                            excludedProviders = excludedProviders,
                            failedProviderId = outcome.providerId,
                        )
                        val continuation = handleRecovery(goal, task, decision, fallbackUsed)
                        if (continuation == null) return
                        task = continuation.task
                        excludedProviders = continuation.excludedProviders
                        fallbackUsed = continuation.fallbackUsed
                        retryRounds = continuation.retryRounds
                        if (continuation.returned) return
                    }

                    is ExecutionOutcome.Denied -> {
                        withContext(NonCancellable) {
                            markTerminal(
                                goal, task,
                                TaskResult(task.taskId, task.capabilityId, success = false, error = outcome.reason),
                                TaskState.FAILED,
                            )
                        }
                        return
                    }
                }
            }
        } catch (e: CancellationException) {
            val isGoalCancelled = mutex.withLock { runtimeState.cancelled }
            if (isGoalCancelled) {
                withContext(NonCancellable) {
                    markTerminal(goal, task, cancelledResult(task, "goal cancelled", agent), TaskState.CANCELLED)
                }
            } else {
                // Task-level cancellation.
                withContext(NonCancellable) {
                    markTerminal(goal, task, cancelledResult(task, "task cancelled", agent), TaskState.CANCELLED)
                }
            }
        } catch (e: Exception) {
            GoalDiagnostics.record(goal.id, "TASK_ERROR", "${task.taskId}: ${e.message}")
            withContext(NonCancellable) {
                markTerminal(
                    goal, task,
                    TaskResult(task.taskId, task.capabilityId, success = false, error = e.message ?: "unknown error"),
                    TaskState.FAILED,
                )
            }
        }
    }

    private fun cancelledResult(task: TaskNode, reason: String, agent: SubAgent?): TaskResult =
        TaskResult(
            taskId = task.taskId,
            capabilityId = task.capabilityId,
            success = false,
            error = reason,
            agentId = agent?.agentId,
        )

    /**
     * Executes one attempt of a task (merge-point → sub-agent → capability executor).
     */
    private suspend fun runOutcome(
        task: TaskNode,
        agent: SubAgent?,
        excludedProviders: Set<String>,
    ): ExecutionOutcome {
        if (task.mergePolicy != MergePolicy.SINGLE) {
            val depResults = mutex.withLock {
                task.dependencies.mapNotNull { runtimeState.results[it] }
            }
            val outputs = depResults.map { it.outputJson }
            val agentOutputs = outputs.mapIndexed { index, output ->
                AgentOutput("dep_$index", task.taskId, success = true, outputJson = output)
            }
            val merged = AgentOutputMerger.merge(agentOutputs, task.mergePolicy)
            return if (merged.success) {
                ExecutionOutcome.Output(providerId = "merge:${task.mergePolicy}", outputJson = merged.outputJson)
            } else {
                ExecutionOutcome.Failure(error = merged.error ?: "merge failed")
            }
        }

        if (agent != null) {
            val agentOutput = agent.run(task, CancellationToken())
            return if (agentOutput.success) {
                ExecutionOutcome.Output(providerId = agentOutput.agentId, outputJson = agentOutput.outputJson)
            } else {
                ExecutionOutcome.Failure(error = agentOutput.error ?: "agent failed", providerId = agentOutput.agentId)
            }
        }

        val timeoutMs = task.timeoutMs ?: defaultTaskTimeoutMs
        return withTimeoutOrNull(timeoutMs) {
            executor.execute(task, excludedProviders = excludedProviders)
        } ?: ExecutionOutcome.Failure("timeout after ${timeoutMs}ms")
    }

    /**
     * Applies a recovery decision. Returns null when the task reached a permanent
     * terminal state; otherwise returns updated execution parameters to continue.
     */
    private suspend fun handleRecovery(
        goal: Goal,
        task: TaskNode,
        decision: RecoveryDecision,
        fallbackUsed: FallbackUsage,
    ): RecoveryContinuation? {
        return when (decision) {
            is RecoveryDecision.Retry -> {
                GoalDiagnostics.recordRecovery(goal.id, "${task.taskId}: retry ${decision.attempt} (${decision.reason})")
                emitSafely(ExecutionEvent.RecoveryOccurred(goal.id, task.taskId, "RETRY", decision.reason))
                if (decision.backoffMs > 0) delay(decision.backoffMs)
                markNodeState(goal, task.copy(state = TaskState.RETRYING))
                RecoveryContinuation(
                    task = task.copy(retryCount = task.retryCount + 1),
                    excludedProviders = emptySet(),
                    fallbackUsed = fallbackUsed,
                    retryRounds = task.retryCount + 1,
                    returned = false,
                )
            }

            is RecoveryDecision.UseFallbackProvider -> {
                GoalDiagnostics.recordRecovery(goal.id, "${task.taskId}: fallback provider (${decision.reason})")
                emitSafely(ExecutionEvent.RecoveryOccurred(goal.id, task.taskId, "FALLBACK_PROVIDER", decision.reason))
                markNodeState(goal, task.copy(state = TaskState.RETRYING))
                RecoveryContinuation(
                    task = task,
                    excludedProviders = decision.excludedProviders,
                    fallbackUsed = fallbackUsed.withProviderFallback(),
                    retryRounds = task.retryCount + 1,
                    returned = false,
                )
            }

            is RecoveryDecision.UseAlternativeCapability -> {
                GoalDiagnostics.recordRecovery(goal.id, "${task.taskId}: alternative capability ${decision.capabilityId}")
                emitSafely(ExecutionEvent.RecoveryOccurred(goal.id, task.taskId, "ALTERNATIVE_CAPABILITY", decision.reason))
                markNodeState(goal, task.copy(state = TaskState.RETRYING))
                RecoveryContinuation(
                    task = task.copy(
                        capabilityId = decision.capabilityId,
                        retryCount = task.retryCount + 1,
                    ),
                    excludedProviders = emptySet(),
                    fallbackUsed = fallbackUsed.withAlternative(),
                    retryRounds = task.retryCount + 1,
                    returned = false,
                )
            }

            is RecoveryDecision.Replan -> {
                GoalDiagnostics.recordRecovery(goal.id, "${task.taskId}: replan (${decision.reason})")
                emitSafely(ExecutionEvent.RecoveryOccurred(goal.id, task.taskId, "REPLAN", decision.reason))
                val graph = mutex.withLock { runtimeState.graph }
                val updates = replanningEngine.buildUpdates(
                    graph = graph,
                    failedTaskId = task.taskId,
                    alternativeSteps = emptyList(),
                    alternatives = replanProvider,
                    replacementReason = decision.reason,
                )
                if (updates.isEmpty()) {
                    // No alternatives available — the goal is genuinely stuck here.
                    withContext(NonCancellable) {
                        markTerminal(
                            goal, task,
                            TaskResult(task.taskId, task.capabilityId, success = false, error = "replan produced no alternatives: ${decision.reason}"),
                            TaskState.FAILED,
                        )
                    }
                    null
                } else {
                    val newGraph = submitUpdates(updates)
                    emitSafely(ExecutionEvent.Replanned(goal.id, newGraph.revision, updates))
                    // This worker's node was removed from the graph; bookkeeping is done.
                    RecoveryContinuation(
                        task = task,
                        excludedProviders = emptySet(),
                        fallbackUsed = fallbackUsed,
                        retryRounds = task.retryCount,
                        returned = true,
                    )
                }
            }

            is RecoveryDecision.AskHuman -> {
                val deferred = CompletableDeferred<HumanClarificationAnswer>()
                pendingClarifications[decision.request.requestId] = deferred
                markNodeState(goal, task.copy(state = TaskState.WAITING_HUMAN))
                emitSafely(ExecutionEvent.HumanClarificationRequested(goal.id, decision.request))
                val answer = try {
                    deferred.await()
                } catch (e: CancellationException) {
                    return null
                }
                when (answer) {
                    is HumanClarificationAnswer.Proceed -> RecoveryContinuation(
                        task = task.copy(retryCount = task.retryCount + 1),
                        excludedProviders = emptySet(),
                        fallbackUsed = fallbackUsed,
                        retryRounds = task.retryCount + 1,
                        returned = false,
                    )
                    is HumanClarificationAnswer.UseCapability -> RecoveryContinuation(
                        task = task.copy(
                            capabilityId = answer.capabilityId,
                            retryCount = task.retryCount + 1,
                        ),
                        excludedProviders = emptySet(),
                        fallbackUsed = fallbackUsed,
                        retryRounds = task.retryCount + 1,
                        returned = false,
                    )
                    is HumanClarificationAnswer.Abort -> {
                        withContext(NonCancellable) {
                            markTerminal(
                                goal, task,
                                TaskResult(task.taskId, task.capabilityId, success = false, error = answer.reason),
                                TaskState.FAILED,
                            )
                        }
                        null
                    }
                }
            }

            is RecoveryDecision.Abort -> {
                GoalDiagnostics.recordRecovery(goal.id, "${task.taskId}: abort (${decision.reason})")
                emitSafely(ExecutionEvent.RecoveryOccurred(goal.id, task.taskId, "ABORT", decision.reason))
                withContext(NonCancellable) {
                    markTerminal(
                        goal, task,
                        TaskResult(task.taskId, task.capabilityId, success = false, error = decision.reason),
                        TaskState.FAILED,
                    )
                }
                null
            }
        }
    }

    private data class RecoveryContinuation(
        val task: TaskNode,
        val excludedProviders: Set<String>,
        val fallbackUsed: FallbackUsage,
        val retryRounds: Int,
        val returned: Boolean,
    )

    /**
     * Replaces a node's state/progress in the live graph (non-terminal transitions).
     */
    private suspend fun markNodeState(goal: Goal, node: TaskNode) {
        mutex.withLock {
            runtimeState.graph = runtimeState.graph.apply(GraphUpdate.ReplaceNode(node))
        }
        refreshProgress(goal)
    }

    /**
     * Records a terminal task state and unblocks the loop.
     */
    private suspend fun markTerminal(goal: Goal, task: TaskNode, result: TaskResult, state: TaskState) {
        val graphSize = mutex.withLock {
            runtimeState.results[task.taskId] = result
            runtimeState.running.remove(task.taskId)
            runtimeState.jobs.remove(task.taskId)
            runtimeState.readinessEpoch.remove(task.taskId)
            runtimeState.graph = runtimeState.graph.apply(
                GraphUpdate.ReplaceNode(
                    task.copy(
                        state = state,
                        result = result,
                        progress = 1f,
                    ),
                ),
            )
            recomputeNodeProgressLocked()
            runtimeState.graph.size()
        }
        when (state) {
            TaskState.SUCCEEDED -> {
                GoalDiagnostics.markTaskTerminal(state, graphSize.toLong())
                emitSafely(ExecutionEvent.TaskCompleted(goal.id, task.taskId, result))
            }
            TaskState.FAILED -> {
                GoalDiagnostics.markTaskTerminal(state, graphSize.toLong())
                emitSafely(ExecutionEvent.TaskFailed(goal.id, task.taskId, result))
            }
            TaskState.CANCELLED -> emitSafely(ExecutionEvent.TaskCancelled(goal.id, task.taskId))
            else -> {}
        }
        GoalDiagnostics.record(goal.id, "TASK_TERMINAL", "${task.taskId} -> $state")
        refreshProgress(goal)
        completeTick()
    }

    private fun recomputeNodeProgressLocked() {
        val state = runtimeState
        val total = state.graph.size()
        if (total == 0) return
        val terminal = state.graph.nodes.values.count { it.state.isTerminal }
        val progress = terminal.toFloat() / total
        state.graph = state.graph.copy(
            nodes = state.graph.nodes.mapValues { (_, node) ->
                if (node.state.isTerminal) node.copy(progress = 1f) else node
            },
        )
    }

    private suspend fun refreshProgress(goal: Goal) {
        val snap = snapshotValue()
        emitSafely(
            ExecutionEvent.GoalProgress(
                goalId = goal.id,
                progress = snap.progress,
                running = snap.runningTasks.size,
                completed = snap.completedTasks.size,
                total = snap.totalTasks,
            ),
        )
    }

    private fun completeTick() {
        tickRef.complete(Unit)
    }

    private suspend fun finalizeGoal(goal: Goal) {
        val status = runtimeState.status
        val finishedGoal = goal.copy(
            runtime = goal.runtime.copy(
                status = status,
                completedAtEpochMs = System.currentTimeMillis(),
                lastError = runtimeState.failureReason,
            ),
            progress = snapshotValue().progress,
        )
        when (status) {
            GoalStatus.COMPLETED -> {
                GoalDiagnostics.markGoalFinished(GoalStatus.COMPLETED)
                GoalDiagnostics.record(goal.id, "GOAL_COMPLETED", "progress=${finishedGoal.progress}")
                emitSafely(ExecutionEvent.GoalCompleted(goal.id, finishedGoal))
            }
            GoalStatus.FAILED -> {
                GoalDiagnostics.markGoalFinished(GoalStatus.FAILED)
                GoalDiagnostics.record(goal.id, "GOAL_FAILED", runtimeState.failureReason ?: "no reason")
                emitSafely(ExecutionEvent.GoalFailed(goal.id, finishedGoal, runtimeState.failureReason ?: "unknown"))
            }
            GoalStatus.CANCELLED -> {
                GoalDiagnostics.markGoalFinished(GoalStatus.CANCELLED)
                GoalDiagnostics.record(goal.id, "GOAL_CANCELLED", runtimeState.failureReason ?: "no reason")
                emitSafely(ExecutionEvent.GoalCancelled(goal.id, finishedGoal, runtimeState.failureReason ?: "unknown"))
            }
            else -> {}
        }
        snapshotValue()
        persistSnapshot(finishedGoal)
    }

    private suspend fun persistSnapshot(goal: Goal) {
        persistenceListener?.let { listener ->
            runCatching { listener.onSnapshot(goal, snapshotGraph()) }
        }
    }

    private fun snapshotValue(): GoalExecutionSnapshot {
        val state = runtimeState
        val nodes = state.graph.nodes.values
        val total = state.graph.size().toFloat()
        val progress = if (total > 0f) nodes.count { it.state.isTerminal } / total else 0f
        return GoalExecutionSnapshot(
            goal = currentGoal ?: Goal(id = state.graph.goalId, description = ""),
            graph = state.graph,
            status = runtimeState.status,
            runningTasks = nodes.filter { it.state == TaskState.RUNNING || it.state == TaskState.SCHEDULED },
            completedTasks = nodes.filter { it.state == TaskState.SUCCEEDED || it.state == TaskState.VERIFIED },
            waitingTasks = nodes.filter { it.state == TaskState.PENDING || it.state == TaskState.READY },
            skippedTasks = nodes.filter { it.state == TaskState.SKIPPED || it.state == TaskState.CANCELLED },
            progress = progress,
            recoveryEvents = GoalDiagnostics.getRecoveryEvents(state.graph.goalId),
            agentAssignments = nodes.filter { it.agentId != null }.associate { it.taskId to it.agentId!! },
            startedAtEpochMs = runtimeState.startedAt.takeIf { it > 0 },
        ).also { snap -> _snapshot.value = snap }
    }

    /**
     * Emits safely from any coroutine (including cancelled workers).
     */
    private suspend fun emitSafely(event: ExecutionEvent) {
        withContext(NonCancellable) {
            _events.emit(event)
        }
    }
}
