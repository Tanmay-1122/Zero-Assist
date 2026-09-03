/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.goal.plan

import com.zeroclaw.android.capability.CapabilityRegistry
import com.zeroclaw.android.goal.GoalPlanStep
import com.zeroclaw.android.goal.GoalSpec
import com.zeroclaw.android.goal.VerificationPolicy
import com.zeroclaw.android.goal.graph.FallbackSpec
import com.zeroclaw.android.goal.graph.GraphUpdate
import com.zeroclaw.android.goal.graph.TaskGraph
import com.zeroclaw.android.goal.graph.TaskNode
import com.zeroclaw.android.goal.graph.TaskSecurity
import com.zeroclaw.android.goal.graph.TaskState
import com.zeroclaw.android.goal.graph.VerificationSpec
import java.util.concurrent.atomic.AtomicLong

/**
 * Planning strategy used by the LLM planner. Receives the goal text and returns
 * ordered capability steps. Swappable for offline/testing scenarios.
 */
fun interface GoalPlanningStrategy {
    suspend fun planSteps(goalText: String, availableCapabilities: List<String>): List<GoalPlanStep>
}

/**
 * Converts a user goal into an executable [TaskGraph].
 *
 * The planner never touches providers — every step names a capability only.
 * Capability availability is validated against the existing [CapabilityRegistry];
 * unknown capabilities become graph nodes that will fail at execution time unless
 * a fallback/replan strategy repairs them.
 */
interface GoalPlanner {
    suspend fun plan(spec: GoalSpec): TaskGraph
}

/**
 * Deterministic planner for scripted/offline plans: uses [GoalSpec.steps] directly,
 * wiring dependencies into the graph.
 */
class DeterministicGoalPlanner(
    private val taskIdPrefix: String = "t",
) : GoalPlanner {
    private val idCounter = AtomicLong(0)

    override suspend fun plan(spec: GoalSpec): TaskGraph {
        val steps = spec.steps
        if (steps.isEmpty()) {
            throw IllegalArgumentException(
                "DeterministicGoalPlanner requires explicit GoalSpec.steps for goal ${spec.goalId}",
            )
        }
        return buildGraph(spec, steps, taskIdPrefix, idCounter)
    }
}

/**
 * Planner that asks an LLM (via an injectable [GoalPlanningStrategy]) to derive the
 * step graph from the goal text, then wires dependencies and policies.
 *
 * In production the strategy invokes the daemon's LLM channel; in tests it is
 * scripted, keeping the planner fully deterministic.
 */
class LLMGoalPlanner(
    private val strategy: GoalPlanningStrategy,
    private val taskIdPrefix: String = "g",
) : GoalPlanner {
    private val idCounter = AtomicLong(0)

    override suspend fun plan(spec: GoalSpec): TaskGraph {
        val available = CapabilityRegistry.getAllCapabilities().map { it.id }
        val steps = if (spec.steps.isNotEmpty()) {
            spec.steps
        } else {
            strategy.planSteps(spec.userGoal, available)
        }
        if (steps.isEmpty()) {
            throw IllegalArgumentException("Planner produced no steps for goal ${spec.goalId}")
        }
        return buildGraph(spec, steps, taskIdPrefix, idCounter)
    }
}

/**
 * Validates whether every capability a graph names is registered. Unknown
 * capabilities are returned so callers can pre-empt with a replan.
 */
fun TaskGraph.missingCapabilities(): List<String> =
    nodes.values.map { it.capabilityId }.distinct().filter { id ->
        CapabilityRegistry.getCapability(id) == null
    }

private fun buildGraph(
    spec: GoalSpec,
    steps: List<GoalPlanStep>,
    taskIdPrefix: String,
    idCounter: AtomicLong,
): TaskGraph {
    val taskIds = steps.map { step ->
        step.taskId ?: "${taskIdPrefix}_${step.capabilityId.lowercase()}_${idCounter.incrementAndGet()}"
    }

    val nodes = steps.mapIndexed { index, step ->
        TaskNode(
            taskId = taskIds[index],
            capabilityId = step.capabilityId,
            parametersJson = step.parametersJson,
            description = step.capabilityId,
            priority = spec.priority,
            verification = verificationSpecFromPolicy(spec.verificationPolicy),
            fallback = FallbackSpec(),
            security = TaskSecurity(spec.securityContext),
            dependencies = resolveDependencies(step, taskIds, steps, index, taskIdPrefix),
            state = TaskState.PENDING,
        )
    }
    return TaskGraph.from(goalId = spec.goalId, tasks = nodes)
}

/**
 * Resolves step dependencies to concrete task ids. Supports:
 * - explicit task ids from [GoalSpec.steps],
 * - capability ids (binds to the first earlier step using that capability),
 * - generated ids of earlier steps (index-based).
 */
private fun resolveDependencies(
    step: GoalPlanStep,
    taskIds: List<String>,
    steps: List<GoalPlanStep>,
    index: Int,
    taskIdPrefix: String,
): List<String> {
    if (step.dependsOn.isEmpty()) return emptyList()
    val resolved = step.dependsOn.map { dep ->
        when {
            dep in taskIds && taskIds.indexOf(dep) < index -> dep
            steps.subList(0, index).any { it.capabilityId == dep } -> {
                val firstIndex = steps.subList(0, index).indexOfFirst { it.capabilityId == dep }
                taskIds[firstIndex]
            }
            dep.startsWith(taskIdPrefix) && dep in taskIds -> dep
            else -> throw IllegalArgumentException(
                "Step ${step.capabilityId} depends on unknown task/capability: $dep",
            )
        }
    }
    return resolved.distinct()
}

private fun verificationSpecFromPolicy(policy: VerificationPolicy): VerificationSpec =
    VerificationSpec(
        mode = policy.mode,
        verifierId = policy.verifierId,
        maxAttempts = policy.maxAttempts,
    )

/**
 * Derives replacement nodes for a failed plan segment, given the failed task's
 * capability and the reason. Used by [ReplanningEngine] when a task fails and the
 * goal's failure policy allows REPLAN.
 */
fun interface AlternativePlanProvider {
    suspend fun alternativesFor(
        goalId: String,
        failedTask: TaskNode,
        failureReason: String,
    ): List<GoalPlanStep>
}

/**
 * Applies structural mutations to an execution graph while execution is running.
 *
 * The canonical example: "Need Flights" fails with "No Flights Available" →
 * the graph gains new "Alternative Airports" nodes and edges, downstream nodes
 * are re-admitted, and execution continues from the same revision — no restart.
 */
class ReplanningEngine {
    private val counter = AtomicLong(0)

    /**
     * Validates and applies [update] to [graph], returning the new revision.
     */
    fun applyUpdate(graph: TaskGraph, update: GraphUpdate): TaskGraph {
        require(graph.validateUpdate(update)) {
            "Rejected graph update $update against ${graph.describeShape()}"
        }
        return graph.apply(update)
    }

    /**
     * Applies a batch of updates. Rejected earlier versions simply finalize into
     * a new revision; callers observe the latest one.
     */
    fun applyUpdates(graph: TaskGraph, updates: List<GraphUpdate>): TaskGraph {
        return updates.fold(graph) { current, update ->
            if (current.validateUpdate(update)) current.apply(update) else current
        }
    }

    /**
     * Generates the raw [GraphUpdate]s to replace [failedTaskId] with
     * [alternativeSteps], rewiring downstream dependants onto the replacement.
     * Used by the scheduler so it can commit updates atomically to a running graph.
     */
    suspend fun buildUpdates(
        graph: TaskGraph,
        failedTaskId: String,
        alternativeSteps: List<GoalPlanStep>,
        alternatives: AlternativePlanProvider? = null,
        replacementReason: String = "replan",
        replaceAncestors: Boolean = true,
    ): List<GraphUpdate> {
        val failed = graph.node(failedTaskId) ?: return emptyList()
        val steps = if (alternativeSteps.isNotEmpty()) {
            alternativeSteps
        } else {
            alternatives?.alternativesFor(graph.goalId, failed, replacementReason).orEmpty()
        }
        if (steps.isEmpty()) return emptyList()

        val newIds = steps.map { step ->
            step.taskId ?: "rp_${failedTaskId}_${counter.incrementAndGet()}"
        }
        val replacementNodes: List<TaskNode> = steps.mapIndexed { index, step ->
            val prevId = if (index == 0) null else newIds[index - 1]
            TaskNode(
                taskId = newIds[index],
                capabilityId = step.capabilityId,
                parametersJson = step.parametersJson,
                description = "alt(${failedTaskId}): ${step.capabilityId}",
                priority = failed.priority,
                verification = failed.verification,
                fallback = failed.fallback,
                security = failed.security,
                agentId = failed.agentId,
                dependencies = buildList {
                    addAll(step.dependsOn)
                    prevId?.let { add(it) }
                }.distinct(),
                state = TaskState.PENDING,
            )
        }

        val downstreamNewDeps = if (replaceAncestors && newIds.size > 1) {
            graph.successors(failedTaskId).map { node ->
                GraphUpdate.ReplaceNode(
                    node.copy(dependencies = node.dependencies - failedTaskId + newIds.last()),
                )
            }
        } else {
            emptyList()
        }

        return buildList {
            add(GraphUpdate.AddNodes(replacementNodes))
            addAll(downstreamNewDeps)
            if (!replaceAncestors) {
                graph.successors(failedTaskId).forEach { node ->
                    if (node.dependencies.contains(failedTaskId)) {
                        add(GraphUpdate.AddEdge(from = newIds.last(), to = node.taskId))
                        add(GraphUpdate.RemoveEdge(from = failedTaskId, to = node.taskId))
                    }
                }
            }
            add(GraphUpdate.RemoveNode(failedTaskId))
        }
    }

    /**
     * Swaps a failed task for one or more alternative steps (the planner's
     * "Alternative Airports" correction) and rewires downstream dependencies so
     * the graph continues without restart.
     *
     * @param replaceAncestors when true, existing dependants of the failed task
     *   become dependants of the last replacement node.
     */
    suspend fun insertAlternativeSegment(
        graph: TaskGraph,
        failedTaskId: String,
        alternativeSteps: List<GoalPlanStep>,
        alternatives: AlternativePlanProvider? = null,
        replacementReason: String = "replan",
        replaceAncestors: Boolean = true,
    ): TaskGraph {
        val updates = buildUpdates(
            graph = graph,
            failedTaskId = failedTaskId,
            alternativeSteps = alternativeSteps,
            alternatives = alternatives,
            replacementReason = replacementReason,
            replaceAncestors = replaceAncestors,
        )
        return applyUpdates(graph, updates)
    }
}
