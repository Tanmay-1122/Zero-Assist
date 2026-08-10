/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.goal.agents

import com.zeroclaw.android.capability.CapabilityProvider
import com.zeroclaw.android.capability.CapabilityRegistry
import com.zeroclaw.android.capability.CapabilityResolver
import com.zeroclaw.android.capability.ResolutionResult
import com.zeroclaw.android.goal.CancellationToken
import com.zeroclaw.android.goal.graph.TaskNode
import com.zeroclaw.android.goal.memory.GoalDiagnostics
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Specialization of an execution agent.
 */
@Serializable
enum class AgentRole {
    @SerialName("planner")
    PLANNER,

    @SerialName("research")
    RESEARCH,

    @SerialName("code")
    CODE,

    @SerialName("vision")
    VISION,

    @SerialName("device")
    DEVICE,

    @SerialName("mcp")
    MCP,

    @SerialName("generalist")
    GENERALIST,
}

/**
 * Output produced by a sub-agent for one task.
 */
@Serializable
data class AgentOutput(
    val agentId: String,
    val taskId: String,
    val success: Boolean,
    val outputJson: String = "{}",
    val error: String? = null,
    val durationMs: Long? = null,
)

/**
 * A sub-agent executes one assigned task and returns a structured output.
 *
 * Sub-agents generalize the platform's delegate: planners, researchers, coders,
 * vision, device, and MCP agents are all [AgentRole]-typed agents that can run
 * simultaneously on different branches of an execution graph.
 */
interface SubAgent {
    val agentId: String
    val role: AgentRole

    /** Capabilities this agent can satisfy (empty = any, via its own strategy). */
    val supportedCapabilities: Set<String>

    suspend fun run(
        task: TaskNode,
        cancellation: CancellationToken,
    ): AgentOutput
}

/**
 * Capability-backed sub-agent: executes a task through a [TaskExecutor]
 * (which still routes through the Capability Resolution Engine).
 */
class CapabilitySubAgent(
    override val agentId: String,
    override val role: AgentRole,
    private val executor: TaskExecutor,
    override val supportedCapabilities: Set<String> = emptySet(),
) : SubAgent {

    override suspend fun run(task: TaskNode, cancellation: CancellationToken): AgentOutput {
        if (cancellation.isCancellationRequested()) {
            return AgentOutput(agentId, task.taskId, success = false, error = "cancelled")
        }
        val started = System.currentTimeMillis()
        val outcome = executor.execute(task)
        val duration = System.currentTimeMillis() - started
        return when (outcome) {
            is ExecutionOutcome.Output ->
                AgentOutput(agentId, task.taskId, success = true, outputJson = outcome.outputJson, durationMs = duration)
            is ExecutionOutcome.Failure ->
                AgentOutput(agentId, task.taskId, success = false, error = outcome.error, durationMs = duration)
            is ExecutionOutcome.Denied ->
                AgentOutput(agentId, task.taskId, success = false, error = "denied: ${outcome.reason}", durationMs = duration)
        }
    }
}

/**
 * Resolves which agent should own a task based on its capability.
 */
class AgentCoordinator(
    private val agents: List<SubAgent>,
) {
    private val defaultAgent: SubAgent? =
        agents.firstOrNull { it.role == AgentRole.GENERALIST }

    /**
     * Chooses the sub-agent best suited for [task], preferring an explicit agent
     * id, then the set of supported capabilities, then the generalist.
     */
    fun resolveAgent(task: TaskNode): SubAgent? {
        task.agentId?.let { explicit ->
            return agents.firstOrNull { it.agentId == explicit }
        }
        agents.firstOrNull { agent ->
            agent.supportedCapabilities.contains(task.capabilityId)
        }?.let { return it }
        return defaultAgent
    }

    /** Agents available for parallel dispatch. */
    val availableAgents: List<SubAgent> get() = agents
}

/**
 * Merges outputs of multiple simultaneously-executing agents once a merge-point
 * node is reached (Planner merges outputs).
 */
object AgentOutputMerger {

    /**
     * Merges [outputs] according to [mergePolicy].
     */
    fun merge(outputs: List<AgentOutput>, mergePolicy: com.zeroclaw.android.goal.graph.MergePolicy): AgentOutput {
        val owner = outputs.firstOrNull()?.agentId ?: "planner"
        val firstTask = outputs.firstOrNull()?.taskId ?: "merged"
        return when (mergePolicy) {
            com.zeroclaw.android.goal.graph.MergePolicy.SINGLE -> outputs.firstOrNull { it.success }
                ?: AgentOutput(owner, firstTask, success = false, error = "no successful output")

            com.zeroclaw.android.goal.graph.MergePolicy.FIRST_SUCCESS -> outputs.firstOrNull { it.success }
                ?: AgentOutput(owner, firstTask, success = false, error = "no successful output")

            com.zeroclaw.android.goal.graph.MergePolicy.CONCAT -> AgentOutput(
                agentId = owner,
                taskId = firstTask,
                success = true,
                outputJson = outputs.joinToString("\n") { out -> out.outputJson },
            )

            com.zeroclaw.android.goal.graph.MergePolicy.JSON_MERGE -> {
                val merged = buildJsonObject {
                    outputs.withIndex().forEach { (index, out) ->
                        val obj = runCatching { Json.parseToJsonElement(out.outputJson).jsonObject }.getOrNull()
                        if (obj != null) {
                            obj.forEach { (key, value) -> put(key, value) }
                        } else {
                            putJsonObject("output_$index") { put("raw", out.outputJson) }
                        }
                    }
                }
                AgentOutput(
                    agentId = owner,
                    taskId = firstTask,
                    success = outputs.all { it.success },
                    outputJson = merged.toString(),
                )
            }
        }
    }
}

/**
 * Outcome of one execution attempt of a task.
 */
sealed interface ExecutionOutcome {
    /** Provider produced output; verification decides whether it actually worked. */
    data class Output(
        val providerId: String,
        val outputJson: String,
        val parsed: JsonObject? = null,
    ) : ExecutionOutcome

    /** Execution failed before producing usable output. */
    data class Failure(
        val error: String,
        val providerId: String? = null,
        val recoverable: Boolean = true,
    ) : ExecutionOutcome

    /** The task was refused by the security layer. */
    data class Denied(val reason: String) : ExecutionOutcome
}

/**
 * A task executor carries one [TaskNode] to completion through the capability layer.
 *
 * The executor intentionally knows nothing about providers except through the
 * existing Capability Resolution Engine; it only orchestrates capability ids,
 * parameter payloads, and recovery exclusions.
 */
interface TaskExecutor {
    /**
     * Executes [task], skipping any provider whose id appears in [excludedProviders]
     * (used by FALLBACK_PROVIDER recovery).
     */
    suspend fun execute(
        task: TaskNode,
        excludedProviders: Set<String> = emptySet(),
    ): ExecutionOutcome
}

/**
 * Default executor routing through [CapabilityResolver] (unchanged resolution
 * behavior) with a recovery path that re-resolves providers while excluding the
 * provider that just failed.
 */
class CapabilityTaskExecutor(
    private val defaultGrantedSecurityLevel: com.zeroclaw.android.capability.SecurityLevel =
        com.zeroclaw.android.capability.SecurityLevel.DEVICE_CONTROL,
) : TaskExecutor {

    override suspend fun execute(
        task: TaskNode,
        excludedProviders: Set<String>,
    ): ExecutionOutcome {
        GoalDiagnostics.record(task.taskId, "GOAL_EXEC", "Executing via capability ${task.capabilityId}")

        val resolution: ResolutionResult = if (excludedProviders.isEmpty()) {
            // Normal path: unmodified Capability Resolution Engine.
            CapabilityResolver.resolveAndExecute(
                capabilityId = task.capabilityId,
                parametersJson = task.parametersJson,
                grantedSecurityLevel = defaultGrantedSecurityLevel,
            )
        } else {
            resolveWithExclusions(task, excludedProviders)
        }

        return when (resolution) {
            is ResolutionResult.Success -> {
                val parsed = runCatching {
                    Json.parseToJsonElement(resolution.outputJson).jsonObject
                }.getOrNull()
                ExecutionOutcome.Output(
                    providerId = resolution.providerId,
                    outputJson = resolution.outputJson,
                    parsed = parsed,
                )
            }
            is ResolutionResult.Failure -> ExecutionOutcome.Failure(
                error = resolution.reason,
            )
        }
    }

    /**
     * Recovery-path resolution that reproduces the resolver's priority ordering
     * and fallback chain, minus excluded providers. Provider negotiation itself
     * stays inside the registry; no capability-layer code is modified.
     */
    private suspend fun resolveWithExclusions(
        task: TaskNode,
        excludedProviders: Set<String>,
    ): ResolutionResult {
        val available = CapabilityRegistry.getProviders(task.capabilityId)
            .filter { it.isAvailable && it.providerId !in excludedProviders }

        if (available.isEmpty()) {
            return ResolutionResult.Failure(
                task.capabilityId,
                "No active provider available for ${task.capabilityId} (after excluding $excludedProviders)",
            )
        }

        var lastError: String? = null
        for (provider: CapabilityProvider in available) {
            try {
                val outputJson = provider.execute(task.parametersJson)
                return ResolutionResult.Success(
                    providerId = provider.providerId,
                    capabilityId = task.capabilityId,
                    outputJson = outputJson,
                )
            } catch (e: Exception) {
                lastError = e.message ?: "Unknown error"
            }
        }
        return ResolutionResult.Failure(
            task.capabilityId,
            "All remaining providers failed for ${task.capabilityId}. Last error: $lastError",
        )
    }
}
