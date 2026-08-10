/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.goal.graph

import com.zeroclaw.android.goal.GoalPriority
import com.zeroclaw.android.goal.SecurityContext
import com.zeroclaw.android.goal.VerificationMode
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * A mutation applied to a running execution graph. Updates may be applied while
 * tasks are executing — the scheduler re-evaluates the ready set afterwards.
 *
 * All updates are serializable so replanning decisions can be persisted.
 */
@Serializable
sealed interface GraphUpdate {

    /** Insert a single node into the graph. */
    @Serializable
    @SerialName("add_node")
    data class AddNode(val node: TaskNode) : GraphUpdate

    /** Insert many nodes atomically. */
    @Serializable
    @SerialName("add_nodes")
    data class AddNodes(val nodes: List<TaskNode>) : GraphUpdate

    /** Remove a node and any edges pointing to or from it. */
    @Serializable
    @SerialName("remove_node")
    data class RemoveNode(val taskId: String) : GraphUpdate

    /** Replace a node in place (replanning swaps a failed plan segment). */
    @Serializable
    @SerialName("replace_node")
    data class ReplaceNode(val node: TaskNode) : GraphUpdate

    /** Connect `to` after `from`. */
    @Serializable
    @SerialName("add_edge")
    data class AddEdge(val from: String, val to: String) : GraphUpdate

    /** Remove the dependency edge between `from` and `to`. */
    @Serializable
    @SerialName("remove_edge")
    data class RemoveEdge(val from: String, val to: String) : GraphUpdate

    /** Reset one node back to PENDING so it executes again (re-execution). */
    @Serializable
    @SerialName("reset_node")
    data class ResetNode(val taskId: String) : GraphUpdate
}

/**
 * A copy-on-write DAG of [TaskNode]s for one goal.
 *
 * The graph is immutable by convention; mutations produce a new graph with an
 * incremented [revision]. A scheduler owns the latest revision, so dynamic
 * replanning is safe while execution is running.
 */
@Serializable
data class TaskGraph(
    val goalId: String,
    val revision: Long = 0,
    val nodes: Map<String, TaskNode> = emptyMap(),
) {
    init {
        require(nodes.values.none { node -> node.dependencies.any { it !in nodes } }) {
            "Graph references missing dependency node(s) for goal $goalId"
        }
    }

    val taskIds: Set<String> get() = nodes.keys

    fun node(taskId: String): TaskNode? = nodes[taskId]

    fun size(): Int = nodes.size

    /** Nodes with no pending dependencies. */
    fun roots(): List<TaskNode> =
        nodes.values.filter { node -> node.dependencies.isEmpty() }

    /**
     * Nodes whose dependencies have all reached a success state and which are not
     * yet terminal. Optionally excludes nodes that are already [running].
     */
    fun eligibleNodes(running: Set<String> = emptySet()): List<TaskNode> {
        return nodes.values.filter { node ->
            node.taskId !in running &&
                !node.state.isTerminal &&
                node.dependencies.all { dep ->
                    nodes[dep]?.state?.isSuccessState == true
                }
        }
    }

    /** Nodes whose dependencies failed — they can never run and should be skipped. */
    fun blockedNodes(): List<TaskNode> {
        return nodes.values.filter { node ->
            !node.state.isTerminal &&
                node.dependencies.any { dep ->
                    nodes[dep]?.state == TaskState.FAILED
                }
        }
    }

    fun successors(taskId: String): List<TaskNode> =
        nodes.values.filter { node -> taskId in node.dependencies }

    fun ancestors(taskId: String): Set<String> {
        val visited = mutableSetOf<String>()
        fun visit(id: String) {
            if (!visited.add(id)) return
            nodes[id]?.dependencies?.forEach { dep ->
                visit(dep)
                visited.add(dep)
            }
        }
        visit(taskId)
        return visited
    }

    /** True when applying [update] produces a valid graph (used before committing). */
    fun validateUpdate(update: GraphUpdate): Boolean {
        return when (update) {
            is GraphUpdate.AddNode ->
                update.node.taskId !in nodes && update.node.dependencies.all { it in nodes }
            is GraphUpdate.AddNodes ->
                update.nodes.all { it.taskId !in nodes } &&
                    update.nodes.all { node -> node.dependencies.all { dep -> dep in nodes || update.nodes.any { it.taskId == dep } } }
            is GraphUpdate.RemoveNode -> update.taskId in nodes
            is GraphUpdate.ReplaceNode -> update.node.taskId in nodes
            is GraphUpdate.AddEdge -> update.from in nodes && update.to in nodes && update.to != update.from
            is GraphUpdate.RemoveEdge -> update.from in nodes && update.to in nodes
            is GraphUpdate.ResetNode -> update.taskId in nodes
        }
    }

    /** Applies [update], returning a new graph revision. */
    fun apply(update: GraphUpdate): TaskGraph {
        return when (update) {
            is GraphUpdate.AddNode ->
                copy(
                    revision = revision + 1,
                    nodes = nodes + (update.node.taskId to update.node),
                )
            is GraphUpdate.AddNodes ->
                copy(
                    revision = revision + 1,
                    nodes = nodes + update.nodes.associateBy { it.taskId },
                )
            is GraphUpdate.RemoveNode -> {
                val removed = update.taskId
                copy(
                    revision = revision + 1,
                    nodes = nodes
                        .filterKeys { it != removed }
                        .mapValues { (_, node) ->
                            if (removed in node.dependencies) {
                                node.copy(dependencies = node.dependencies - removed)
                            } else {
                                node
                            }
                        },
                )
            }
            is GraphUpdate.ReplaceNode -> {
                val replacement = update.node
                val old = nodes[replacement.taskId]
                if (old == null) {
                    copy(revision = revision + 1, nodes = nodes + (replacement.taskId to replacement))
                } else {
                    // Preserve original dependencies unless the replacement re-declares them,
                    // so downstream edges stay intact. The replacement's own state, result, and
                    // retry bookkeeping are applied as-is — the scheduler relies on ReplaceNode
                    // for live state transitions (e.g. SCHEDULED -> RUNNING -> SUCCEEDED).
                    // Replanning-engine replacements declare TaskState.PENDING explicitly, so a
                    // re-executed segment still starts fresh without resetting here.
                    val merged = replacement.copy(
                        dependencies = replacement.dependencies.ifEmpty { old.dependencies },
                    )
                    copy(
                        revision = revision + 1,
                        nodes = nodes + (replacement.taskId to merged),
                    )
                }
            }
            is GraphUpdate.AddEdge -> copy(
                revision = revision + 1,
                nodes = nodes.mapValues { (_, node) ->
                    if (node.taskId == update.to && update.from !in node.dependencies) {
                        node.copy(dependencies = node.dependencies + update.from)
                    } else {
                        node
                    }
                },
            )
            is GraphUpdate.RemoveEdge -> copy(
                revision = revision + 1,
                nodes = nodes.mapValues { (_, node) ->
                    if (node.taskId == update.to) {
                        node.copy(dependencies = node.dependencies - update.from)
                    } else {
                        node
                    }
                },
            )
            is GraphUpdate.ResetNode -> {
                val target = nodes[update.taskId] ?: return this
                copy(
                    revision = revision + 1,
                    nodes = nodes + (update.taskId to target.copy(
                        state = TaskState.PENDING,
                        result = null,
                        retryCount = 0,
                    )),
                )
            }
        }
    }

    /** Applies a batch of updates sequentially. */
    fun apply(updates: List<GraphUpdate>): TaskGraph {
        var current = this
        updates.forEach { current = current.apply(it) }
        return current
    }

    /**
     * Serializes the graph's shape for diagnostics/telemetry.
     */
    fun describeShape(): String {
        val edges = nodes.values.sumOf { node -> node.dependencies.size }
        return "goal=${goalId} rev=${revision} nodes=${nodes.size} edges=$edges"
    }

    companion object {
        fun empty(goalId: String): TaskGraph = TaskGraph(goalId = goalId)

        fun from(goalId: String, tasks: List<TaskNode>): TaskGraph {
            return TaskGraph(
                goalId = goalId,
                nodes = tasks.associateBy { it.taskId },
            )
        }

        /** Loads a graph from a serialized JSON string. */
        fun fromJson(jsonString: String, json: kotlinx.serialization.json.Json = DEFAULT_JSON): TaskGraph =
            json.decodeFromString(serializer(), jsonString)

        /** Serializes the graph to a JSON string for persistence. */
        fun toJson(graph: TaskGraph, json: kotlinx.serialization.json.Json = DEFAULT_JSON): String =
            json.encodeToString(serializer(), graph)

        private val DEFAULT_JSON = kotlinx.serialization.json.Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        }
    }
}

/**
 * Convenience: graph state snapshot used by the scheduler UI/telemetry layer.
 */
@Serializable
data class TaskGraphState(
    val graph: TaskGraph,
    val running: List<String> = emptyList(),
    val completed: List<String> = emptyList(),
    val waiting: List<String> = emptyList(),
    val progress: Float = 0f,
) {
    fun toJson(json: kotlinx.serialization.json.Json = kotlinx.serialization.json.Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }): JsonObject = json.encodeToJsonElement(serializer(), this).jsonObject
}

/**
 * Fine-grained execution lifecycle of a single task.
 */
@Serializable
enum class TaskState {
    /** Declared in the graph but not yet eligible. */
    @SerialName("pending")
    PENDING,

    /** All dependencies satisfied; waiting for a scheduling slot. */
    @SerialName("ready")
    READY,

    /** Dispatched to the scheduler, awaiting an executor slot. */
    @SerialName("scheduled")
    SCHEDULED,

    /** Executor is running the task. */
    @SerialName("running")
    RUNNING,

    /** Executed successfully but waiting for verification. */
    @SerialName("waiting_verification")
    WAITING_VERIFICATION,

    /** Executed and verified. */
    @SerialName("verified")
    VERIFIED,

    /** Execution completed; verification failed. */
    @SerialName("verification_failed")
    VERIFICATION_FAILED,

    /** Paused because the human was asked for clarification. */
    @SerialName("waiting_human")
    WAITING_HUMAN,

    /** Repeating a failed attempt after a retry decision. */
    @SerialName("retrying")
    RETRYING,

    /** Terminated successfully per the graph's success policy. */
    @SerialName("succeeded")
    SUCCEEDED,

    /** Terminated with a permanent failure. */
    @SerialName("failed")
    FAILED,

    /** Cancelled by the human, deadline, or graph update. */
    @SerialName("cancelled")
    CANCELLED,

    /** Not executed because a dependency failed. */
    @SerialName("skipped")
    SKIPPED,
}

/** Terminal success states. */
val TaskState.isSuccessState: Boolean
    get() = this == TaskState.SUCCEEDED

/** Terminal failure states (excluding cancellation/skip). */
val TaskState.isFailureState: Boolean
    get() = this == TaskState.FAILED

/** Any terminal state. */
val TaskState.isTerminal: Boolean
    get() = when (this) {
        TaskState.SUCCEEDED, TaskState.FAILED,
        TaskState.CANCELLED, TaskState.SKIPPED,
        -> true
        else -> false
    }

/**
 * Structured verification requirements for one task.
 */
@Serializable
data class VerificationSpec(
    val mode: VerificationMode = VerificationMode.BASIC,
    /** Registered verifier id, e.g. "SCREENSHOT_VISION", "REPOSITORY_EXISTS", "EVENT_CREATED". */
    val verifierId: String? = null,
    /** Capability used by a capability-backed verifier to confirm state (e.g. STATISTICS). */
    val verifyCapabilityId: String? = null,
    /** Optional required JSON output keys, used by the schema verifier. */
    val requiredOutputKeys: List<String> = emptyList(),
    val maxAttempts: Int = 1,
    val timeoutMs: Long = 15_000,
)

/**
 * Fallback options declared per task. Numeric budgets cap how often recovery may
 * consume a strategy before escalating.
 */
@Serializable
data class FallbackSpec(
    val alternativeCapabilities: List<String> = emptyList(),
    val maxProviderFallbacks: Int = 2,
    val maxAlternativeCapabilityUses: Int = 1,
)

/**
 * Permission claims a single task requires. Denied by the security enforcer when
 * the granted goal-level context does not cover it.
 */
@Serializable
data class TaskSecurity(
    val required: SecurityContext = SecurityContext(),
)

/**
 * Immutable output record of one executed task.
 */
@Serializable
data class TaskResult(
    val taskId: String,
    val capabilityId: String,
    val providerId: String? = null,
    val outputJson: String = "{}",
    val success: Boolean,
    val error: String? = null,
    val retriesConsumed: Int = 0,
    val executionDurationMs: Long? = null,
    val verified: Boolean = true,
    val verificationEvidence: String? = null,
    val verificationAttempts: Int = 0,
    val agentId: String? = null,
    val finishedAtEpochMs: Long = System.currentTimeMillis(),
)

/**
 * A single executable node in a goal's execution graph.
 *
 * A task only knows its capability — never a concrete provider. Provider selection
 * stays with the Capability Resolution Engine.
 */
@Serializable
data class TaskNode(
    val taskId: String,
    val capabilityId: String,
    val parametersJson: String = "{}",
    val description: String = "",
    /** Optional output schema hint (e.g. JSON key list or free-form contract). */
    val outputSchema: String? = null,
    val maxRetries: Int = 1,
    val timeoutMs: Long? = null,
    val priority: GoalPriority = GoalPriority.MEDIUM,
    val verification: VerificationSpec = VerificationSpec(),
    val fallback: FallbackSpec = FallbackSpec(),
    val dependencies: List<String> = emptyList(),
    val security: TaskSecurity = TaskSecurity(),
    /** Optional sub-agent assignment (multi-agent coordination). */
    val agentId: String? = null,
    val mergePolicy: MergePolicy = MergePolicy.SINGLE,
    val progress: Float = 0f,
    val state: TaskState = TaskState.PENDING,
    val retryCount: Int = 0,
    val result: TaskResult? = null,
)

/**
 * How the outputs of a node's dependencies are combined when this node is a merge point.
 */
@Serializable
enum class MergePolicy {
    /** Execute normally; dependencies are pure ordering constraints. */
    @SerialName("single")
    SINGLE,

    /** Emit the first successful dependency output. */
    @SerialName("first_success")
    FIRST_SUCCESS,

    /** Concatenate dependency outputs into a single text result. */
    @SerialName("concat")
    CONCAT,

    /** Merge dependency JSON outputs into one JSON object. */
    @SerialName("json_merge")
    JSON_MERGE,
}
