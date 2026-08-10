/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.goal

import com.zeroclaw.android.capability.Capability
import com.zeroclaw.android.capability.CapabilityProvider
import com.zeroclaw.android.capability.CapabilityRegistry
import com.zeroclaw.android.capability.SecurityLevel
import com.zeroclaw.android.goal.agents.ExecutionOutcome
import com.zeroclaw.android.goal.agents.TaskExecutor
import com.zeroclaw.android.goal.graph.TaskGraph
import com.zeroclaw.android.goal.graph.TaskNode
import com.zeroclaw.android.goal.schedule.ResourceLimits
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Test executor with per-capability scripted handlers, delay injection, and call logs.
 */
class ScriptedExecutor : TaskExecutor {
    val calls = ConcurrentHashMap.newKeySet<String>()
    val callLog = Collections.synchronizedList(mutableListOf<String>())
    private val handlers = ConcurrentHashMap<String, suspend (String) -> ExecutionOutcome>()
    private val delayMs = AtomicLong(0)
    val concurrentMax = AtomicLong(0)
    private val active = AtomicLong(0)
    private val perCapabilityCounts = ConcurrentHashMap<String, AtomicLong>()

    fun on(capabilityId: String, handler: suspend (String) -> ExecutionOutcome) {
        handlers[capabilityId] = handler
    }

    fun failOnceThenSucceed(capabilityId: String) {
        val attempts = AtomicLong(0)
        on(capabilityId) { _ ->
            if (attempts.incrementAndGet() == 1L) {
                ExecutionOutcome.Failure("first attempt failed")
            } else {
                ExecutionOutcome.Output("scripted", "{\"ok\":true}")
            }
        }
    }

    fun setDelay(ms: Long) {
        delayMs.set(ms)
    }

    fun alwaysBlock() {
        on("*") { _ ->
            kotlinx.coroutines.delay(Long.MAX_VALUE)
            ExecutionOutcome.Failure("unreachable")
        }
    }

    fun countFor(capabilityId: String): Long =
        perCapabilityCounts[capabilityId]?.get() ?: 0L

    override suspend fun execute(task: TaskNode, excludedProviders: Set<String>): ExecutionOutcome {
        callLog.add(task.taskId)
        val activeNow = active.incrementAndGet()
        concurrentMax.updateAndGet { max -> if (activeNow > max) activeNow else max }
        try {
            if (delayMs.get() > 0) delay(delayMs.get())
            val handler = handlers[task.capabilityId] ?: handlers["*"]
            perCapabilityCounts.computeIfAbsent(task.capabilityId) { AtomicLong() }.incrementAndGet()
            if (handler == null) {
                return ExecutionOutcome.Failure("no provider for capability ${task.capabilityId}")
            }
            calls.add(task.taskId)
            return handler.invoke(task.parametersJson)
        } finally {
            active.decrementAndGet()
        }
    }
}

/**
 * Builder for scheduler test graphs.
 */
fun chainGraph(goalId: String, capabilities: List<String>): TaskGraph {
    val nodes = capabilities.mapIndexed { index, capability ->
        val taskId = "task_$index"
        TaskNode(
            taskId = taskId,
            capabilityId = capability,
            parametersJson = "{}",
            dependencies = if (index == 0) emptyList() else listOf("task_${index - 1}"),
        )
    }
    return TaskGraph.from(goalId, nodes)
}

fun parallelGraph(goalId: String, capability: String, count: Int): TaskGraph {
    val nodes = (0 until count).map { index ->
        TaskNode(taskId = "p_$index", capabilityId = capability, parametersJson = "{}")
    }
    return TaskGraph.from(goalId, nodes)
}

fun testGoal(
    id: String,
    description: String = id,
    failurePolicy: FailurePolicy = FailurePolicy(),
    verificationPolicy: VerificationPolicy = VerificationPolicy(),
    securityContext: SecurityContext = SecurityContext(network = true),
    priority: GoalPriority = GoalPriority.MEDIUM,
): Goal = Goal(
    id = id,
    description = description,
    failurePolicy = failurePolicy,
    verificationPolicy = verificationPolicy,
    securityContext = securityContext,
    priority = priority,
)

/** Registers a temporary capability with providers and returns cleanup via unique id. */
fun registerTestCapability(
    capabilityId: String,
    vararg providers: CapabilityProvider,
): Capability {
    val capability = Capability(
        id = capabilityId,
        description = "Test capability",
        securityLevel = SecurityLevel.SAFE,
    )
    CapabilityRegistry.registerCapability(capability)
    providers.forEach { CapabilityRegistry.registerProvider(it) }
    return capability
}

fun provider(
    providerId: String,
    capabilityId: String,
    priority: Int,
    output: String,
    fail: Boolean = false,
): CapabilityProvider = object : CapabilityProvider {
    override val providerId: String = providerId
    override val capabilityId: String = capabilityId
    override val priority: Int = priority
    override val isAvailable: Boolean = true
    override suspend fun execute(parametersJson: String): String {
        if (fail) throw RuntimeException("provider $providerId failed")
        return output
    }
}

fun ResourceLimits.withConcurrency(tasks: Int): ResourceLimits =
    copy(maxConcurrentTasks = tasks)
