/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.goal

import com.zeroclaw.android.capability.SecurityLevel
import com.zeroclaw.android.goal.agents.CapabilityTaskExecutor
import com.zeroclaw.android.goal.agents.TaskExecutor
import com.zeroclaw.android.goal.graph.TaskNode
import com.zeroclaw.android.goal.memory.GoalMemoryService
import com.zeroclaw.android.goal.memory.GoalStore
import com.zeroclaw.android.goal.memory.InMemoryGoalStore
import com.zeroclaw.android.goal.plan.AlternativePlanProvider
import com.zeroclaw.android.goal.plan.GoalPlanner
import com.zeroclaw.android.goal.recover.ClarificationResponder
import com.zeroclaw.android.goal.recover.RecoveryEngine
import com.zeroclaw.android.goal.schedule.ExecutionEvent
import com.zeroclaw.android.goal.schedule.GoalExecutionSnapshot
import com.zeroclaw.android.goal.schedule.GoalPersistenceListener
import com.zeroclaw.android.goal.schedule.GoalScheduler
import com.zeroclaw.android.goal.schedule.ResourceLimits
import com.zeroclaw.android.goal.verify.DefaultVerifiers
import com.zeroclaw.android.goal.verify.VerificationEngine
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Classification of a goal's runtime shape.
 *
 * - [ONE_SHOT]: executed once, immediately.
 * - [LONG_RUNNING]: a long-lived goal which may be persisted and resumed across sessions.
 * - [SCHEDULED]: held by memory; begins when its scheduled time arrives.
 * - [RECOVERABLE]: re-entrant execution after an interruption (crash) or explicit retry.
 */
@Serializable
enum class GoalType {
    @SerialName("one_shot")
    ONE_SHOT,

    @SerialName("long_running")
    LONG_RUNNING,

    @SerialName("scheduled")
    SCHEDULED,

    @SerialName("recoverable")
    RECOVERABLE,
}

/**
 * Scheduling priority. Higher priority tasks are dispatched first by the scheduler.
 */
@Serializable
enum class GoalPriority {
    @SerialName("low")
    LOW,

    @SerialName("medium")
    MEDIUM,

    @SerialName("high")
    HIGH,

    @SerialName("critical")
    CRITICAL,
}

/**
 * Top-level lifecycle state of a goal.
 */
@Serializable
enum class GoalStatus {
    @SerialName("planned")
    PLANNED,

    @SerialName("running")
    RUNNING,

    @SerialName("paused")
    PAUSED,

    @SerialName("completed")
    COMPLETED,

    @SerialName("failed")
    FAILED,

    @SerialName("cancelled")
    CANCELLED,
}

/**
 * How strictly a task outcome must be verified before it counts as done.
 */
@Serializable
enum class VerificationMode {
    /** No verification is performed; execution outcome is trusted. */
    @SerialName("none")
    NONE,

    /** A cheap structural check (e.g. non-empty output, schema presence). */
    @SerialName("basic")
    BASIC,

    /** Deep verification (vision check, repository existence, calendar event lookup). */
    @SerialName("strict")
    STRICT,
}

/**
 * Ordered recovery strategies a goal may employ when a task fails verification or execution.
 */
@Serializable
enum class FailurePolicyAction {
    /** Re-run the same task with exponential backoff up to a retry budget. */
    @SerialName("retry")
    RETRY,

    /** Re-dispatch the task, excluding the provider that just failed. */
    @SerialName("fallback_provider")
    FALLBACK_PROVIDER,

    /** Swap the task's capability for an equivalent alternative capability. */
    @SerialName("alternative_capability")
    ALTERNATIVE_CAPABILITY,

    /** Ask the planner to mutate the executing graph (e.g. insert alternative airports). */
    @SerialName("replan")
    REPLAN,

    /** Pause the goal and ask the human to choose a direction. */
    @SerialName("human_clarification")
    HUMAN_CLARIFICATION,

    /** Terminate the goal with a failure result. */
    @SerialName("abort")
    ABORT,
}

/**
 * Permissions a goal or task requires. The scheduler's security enforcer blocks any
 * task that requires a permission which is not granted.
 */
@Serializable
data class SecurityContext(
    val network: Boolean = false,
    val filesystem: Boolean = false,
    val deviceControl: Boolean = false,
    val mcpAccess: Boolean = false,
    val sandboxAccess: Boolean = false,
) {
    /** True when [required] never demands more than what this context grants. */
    fun covers(required: SecurityContext): Boolean {
        return (!required.network || network) &&
            (!required.filesystem || filesystem) &&
            (!required.deviceControl || deviceControl) &&
            (!required.mcpAccess || mcpAccess) &&
            (!required.sandboxAccess || sandboxAccess)
    }
}

/**
 * Configurable verification policy for a goal. Verification occurs after execution
 * but before the task counts as succeeded.
 */
@Serializable
data class VerificationPolicy(
    val mode: VerificationMode = VerificationMode.BASIC,
    /** Id of a registered [com.zeroclaw.android.goal.verify.TaskVerifier]. Null = engine default. */
    val verifierId: String? = null,
    /** Number of verification attempts allowed before the outcome counts as unverified. */
    val maxAttempts: Int = 1,
)

/**
 * Configurable failure policy defining the ordered recovery strategy for a goal.
 */
@Serializable
data class FailurePolicy(
    val actions: List<FailurePolicyAction> = listOf(FailurePolicyAction.RETRY),
    val maxRetries: Int = 2,
    val retryBackoffMs: Long = 200,
)

/**
 * Declared artifact a goal promises to deliver.
 */
@Serializable
data class ExpectedOutput(
    val name: String,
    val description: String = "",
    val required: Boolean = true,
)

/**
 * Serializable runtime bookkeeping for a goal.
 */
@Serializable
data class GoalRuntimeMetadata(
    val status: GoalStatus = GoalStatus.PLANNED,
    val startedAtEpochMs: Long? = null,
    val completedAtEpochMs: Long? = null,
    val scheduledForEpochMs: Long? = null,
    val currentTaskId: String? = null,
    val attempt: Int = 0,
    val lastError: String? = null,
)

/**
 * A goal, independent of any specific tool or provider.
 *
 * Goals are serializable so they can be persisted by the memory layer, resumed after
 * an interruption, and replayed by the scheduler.
 */
@Serializable
data class Goal(
    val id: String,
    val type: GoalType = GoalType.ONE_SHOT,
    val priority: GoalPriority = GoalPriority.MEDIUM,
    val description: String,
    val dependencies: List<String> = emptyList(),
    val expectedOutputs: List<ExpectedOutput> = emptyList(),
    val verificationPolicy: VerificationPolicy = VerificationPolicy(),
    val failurePolicy: FailurePolicy = FailurePolicy(),
    val securityContext: SecurityContext = SecurityContext(),
    val deadlineEpochMs: Long? = null,
    val runtime: GoalRuntimeMetadata = GoalRuntimeMetadata(),
    /** Progress in 0f..1f updated live by the scheduler. */
    val progress: Float = 0f,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
)

/**
 * Structured description of what the planner should produce for a goal.
 * This is the "What is required to accomplish the user's goal?" payload.
 */
@Serializable
data class GoalSpec(
    val goalId: String,
    val userGoal: String,
    /** Ordered/parallel capability steps. If empty the planner infers from the LLM. */
    val steps: List<GoalPlanStep> = emptyList(),
    val priority: GoalPriority = GoalPriority.MEDIUM,
    val parallelism: Int = 4,
    val verificationPolicy: VerificationPolicy = VerificationPolicy(),
    val failurePolicy: FailurePolicy = FailurePolicy(),
    val securityContext: SecurityContext = SecurityContext(),
    val deadlineEpochMs: Long? = null,
)

/**
 * A single requested step fed to the planner. Only declares a capability — never a provider.
 */
@Serializable
data class GoalPlanStep(
    val capabilityId: String,
    val parametersJson: String = "{}",
    /** Task ids this step depends on within the same plan. */
    val dependsOn: List<String> = emptyList(),
    /** Stable task id. Defaults to a generated id when blank. */
    val taskId: String? = null,
)

/**
 * A shared, non-serializable cancellation signal scoped to one goal execution.
 */
class CancellationToken {
    private val cancelled = AtomicBoolean(false)
    private var onCancelled: (() -> Unit)? = null

    fun requestCancellation() {
        if (cancelled.compareAndSet(false, true)) {
            onCancelled?.invoke()
        }
    }

    fun isCancellationRequested(): Boolean = cancelled.get()

    fun setOnCancelled(handler: () -> Unit) {
        onCancelled = handler
    }

    fun asBoolean(): Boolean = cancelled.get()

    companion object {
        val NONE: CancellationToken? = null
    }
}

/**
 * A question surfaced to the human when the recovery engine reaches
 * [FailurePolicyAction.HUMAN_CLARIFICATION].
 */
data class HumanClarificationRequest(
    val requestId: String,
    val goalId: String,
    val taskId: String,
    val question: String,
    val options: List<String> = emptyList(),
    val context: JsonElement? = null,
)

/**
 * High-level facade presenting the full goal execution stack:
 *
 * ```
 * User Goal → GoalPlanner → Execution Graph → GoalScheduler
 *           → Capability Resolver → Provider → Verification → Recovery → Completion
 * ```
 *
 * The Capability Resolution Engine stays fully responsible for *how* work is
 * executed; this engine decides *what* to execute and *when*.
 */
class GoalEngine(
    val scheduler: GoalScheduler,
    val planner: GoalPlanner,
    val memory: GoalMemoryService,
) {
    val events: SharedFlow<ExecutionEvent> = scheduler.events
    val snapshot: StateFlow<GoalExecutionSnapshot?> = scheduler.snapshot

    /**
     * Plans and executes a goal described by [spec].
     */
    suspend fun planAndExecute(
        spec: GoalSpec,
        goalBuilder: (GoalSpec) -> Goal = { defaultGoalFrom(it) },
    ): GoalExecutionSnapshot {
        val graph = planner.plan(spec)
        val goal = goalBuilder(spec)
        return scheduler.execute(goal, graph)
    }

    /**
     * Resumes an interrupted goal from its persisted snapshot.
     */
    suspend fun resumeGoal(goalId: String): Goal? = memory.resume(goalId, scheduler)

    companion object {
        fun defaultGoalFrom(spec: GoalSpec): Goal = Goal(
            id = spec.goalId,
            description = spec.userGoal,
            priority = spec.priority,
            verificationPolicy = spec.verificationPolicy,
            failurePolicy = spec.failurePolicy,
            securityContext = spec.securityContext,
            deadlineEpochMs = spec.deadlineEpochMs,
        )
    }
}

/**
 * Standard wiring of the goal stack against the existing capability layer.
 */
class GoalEngineFactory(
    private val executor: TaskExecutor = CapabilityTaskExecutor(
        defaultGrantedSecurityLevel = SecurityLevel.DEVICE_CONTROL,
    ),
    private val resourceLimits: ResourceLimits = ResourceLimits(maxConcurrentTasks = 4),
    private val identityGrant: SecurityContext = GoalSecurityEnforcer.READ_ONLY,
    private val store: GoalStore = InMemoryGoalStore(),
    private val replanProvider: AlternativePlanProvider? = null,
    private val responder: ClarificationResponder? = null,
) {
    fun create(
        planner: GoalPlanner,
        scope: CoroutineScope,
        persistenceListener: GoalPersistenceListener? = null,
    ): GoalEngine {
        val verificationEngine = VerificationEngine().also { engine ->
            DefaultVerifiers.registerAll(engine)
        }
        val recoveryEngine = RecoveryEngine(responder = responder, replanProvider = replanProvider)
        val scheduler = GoalScheduler(
            executor = executor,
            verificationEngine = verificationEngine,
            recoveryEngine = recoveryEngine,
            replanProvider = replanProvider,
            scope = scope,
            resourceLimits = resourceLimits,
            identityGrant = identityGrant,
            persistenceListener = persistenceListener,
        )
        val memory = GoalMemoryService(store)
        return GoalEngine(scheduler = scheduler, planner = planner, memory = memory)
    }
}

/**
 * Decides whether a task may execute given the granting context of its goal.
 *
 * The scheduler calls [authorize] before dispatching anything to an executor —
 * unauthorized work is never scheduled.
 */
class GoalSecurityEnforcer {

    /**
     * Grants the union of the app's [identityGrant] and the goal's own context.
     */
    fun effectiveGrant(goal: Goal, identityGrant: SecurityContext): SecurityContext {
        return SecurityContext(
            network = goal.securityContext.network || identityGrant.network,
            filesystem = goal.securityContext.filesystem || identityGrant.filesystem,
            deviceControl = goal.securityContext.deviceControl || identityGrant.deviceControl,
            mcpAccess = goal.securityContext.mcpAccess || identityGrant.mcpAccess,
            sandboxAccess = goal.securityContext.sandboxAccess || identityGrant.sandboxAccess,
        )
    }

    /**
     * True when the task's required permissions are covered by [grant].
     */
    fun authorize(task: TaskNode, grant: SecurityContext): Boolean {
        return grant.covers(task.security.required)
    }

    /**
     * Deny-list reporting for diagnostics.
     */
    fun deniedReasons(task: TaskNode, grant: SecurityContext): List<String> {
        val required = task.security.required
        return buildList {
            if (required.network && !grant.network) add("NETWORK")
            if (required.filesystem && !grant.filesystem) add("FILESYSTEM")
            if (required.deviceControl && !grant.deviceControl) add("DEVICE_CONTROL")
            if (required.mcpAccess && !grant.mcpAccess) add("MCP_ACCESS")
            if (required.sandboxAccess && !grant.sandboxAccess) add("SANDBOX_ACCESS")
        }
    }

    companion object {
        /** Fully-open grant; used when the user has granted device-level autonomy. */
        val FULL_ACCESS = SecurityContext(
            network = true,
            filesystem = true,
            deviceControl = true,
            mcpAccess = true,
            sandboxAccess = true,
        )

        /** Read-only grant; safe default. */
        val READ_ONLY = SecurityContext(network = true)
    }
}
