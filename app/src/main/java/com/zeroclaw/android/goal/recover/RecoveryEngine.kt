/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.goal.recover

import com.zeroclaw.android.diagnostics.RichRuntimeDiagnostics
import com.zeroclaw.android.goal.FailurePolicy
import com.zeroclaw.android.goal.FailurePolicyAction
import com.zeroclaw.android.goal.Goal
import com.zeroclaw.android.goal.HumanClarificationRequest
import com.zeroclaw.android.goal.agents.ExecutionOutcome
import com.zeroclaw.android.goal.graph.FallbackSpec
import com.zeroclaw.android.goal.graph.TaskNode
import com.zeroclaw.android.goal.plan.AlternativePlanProvider
import kotlinx.coroutines.delay

/**
 * What the scheduler should do next after an execution/verification failure.
 */
sealed interface RecoveryDecision {
    /** Retry the same task after [backoffMs]. */
    data class Retry(
        val attempt: Int,
        val backoffMs: Long,
        val reason: String,
    ) : RecoveryDecision

    /** Re-execute, skipping the failed provider ids. */
    data class UseFallbackProvider(
        val excludedProviders: Set<String>,
        val reason: String,
    ) : RecoveryDecision

    /** Re-execute using a different capability. */
    data class UseAlternativeCapability(
        val capabilityId: String,
        val reason: String,
    ) : RecoveryDecision

    /** Mutate the running graph via the planner's alternatives. */
    data class Replan(
        val reason: String,
    ) : RecoveryDecision

    /** Pause and surface a question to the human. */
    data class AskHuman(
        val request: HumanClarificationRequest,
        val reason: String,
    ) : RecoveryDecision

    /** Give up on the task and the goal. */
    data class Abort(
        val reason: String,
    ) : RecoveryDecision
}

/**
 * Optional hook so the human can answer a pending clarification.
 */
fun interface ClarificationResponder {
    /**
     * Returns the human's decision, or null to keep waiting.
     */
    suspend fun ask(request: HumanClarificationRequest): HumanClarificationAnswer?
}

/**
 * The human's reply to a clarification request.
 */
sealed interface HumanClarificationAnswer {
    /** Proceed with the suggested course. */
    data class Proceed(val instruction: String = "") : HumanClarificationAnswer

    /** Abort the task. */
    data class Abort(val reason: String = "Aborted by user") : HumanClarificationAnswer

    /** Try a different capability explicitly named by the user. */
    data class UseCapability(val capabilityId: String) : HumanClarificationAnswer
}

/**
 * Automatic recovery orchestrator. Walks a goal's [FailurePolicy] action list in
 * order, applying per-strategy budgets, and emits structured decisions the
 * scheduler can execute. Recovery is automatic whenever the policy allows it.
 */
class RecoveryEngine(
    private val responder: ClarificationResponder? = null,
    private val replanProvider: AlternativePlanProvider? = null,
) {

    /**
     * Decides the next action for a failed task.
     *
     * @param failedProviderId the provider that failed on the last attempt (if any).
     * @param excludedProviders providers already excluded by earlier recovery rounds.
     */
    suspend fun decide(
        goal: Goal,
        task: TaskNode,
        outcome: ExecutionOutcome,
        retryCount: Int,
        fallbackUsed: FallbackUsage,
        excludedProviders: Set<String> = emptySet(),
        failedProviderId: String? = null,
    ): RecoveryDecision {
        val policy = goal.failurePolicy
        val availableActions = policy.actions
        if (availableActions.isEmpty()) {
            return RecoveryDecision.Abort("No failure policy configured")
        }

        val reason = when (outcome) {
            is ExecutionOutcome.Output -> "verification failed for ${task.capabilityId}"
            is ExecutionOutcome.Failure -> "execution failed: ${outcome.error}"
            is ExecutionOutcome.Denied -> "security denied: ${outcome.reason}"
        }

        for (action in availableActions) {
            when (action) {
                FailurePolicyAction.RETRY -> {
                    if (retryCount < policy.maxRetries) {
                        val backoff = policy.retryBackoffMs * (1L shl retryCount.coerceAtMost(5))
                        return RecoveryDecision.Retry(attempt = retryCount + 1, backoffMs = backoff, reason = reason)
                    }
                }

                FailurePolicyAction.FALLBACK_PROVIDER -> {
                    val providerId = (outcome as? ExecutionOutcome.Failure)?.providerId ?: failedProviderId
                    if (providerId != null && fallbackUsed.providerFallbacks < task.fallback.maxProviderFallbacks) {
                        return RecoveryDecision.UseFallbackProvider(
                            excludedProviders = excludedProviders + providerId,
                            reason = "$reason; excluding provider $providerId",
                        )
                    }
                }

                FailurePolicyAction.ALTERNATIVE_CAPABILITY -> {
                    val candidates = task.fallback.alternativeCapabilities
                    if (fallbackUsed.alternativeUses < task.fallback.maxAlternativeCapabilityUses &&
                        candidates.isNotEmpty()
                    ) {
                        val next = candidates.getOrNull(fallbackUsed.alternativeUses)
                        if (next != null) {
                            return RecoveryDecision.UseAlternativeCapability(
                                capabilityId = next,
                                reason = "$reason; switching to $next",
                            )
                        }
                    }
                }

                FailurePolicyAction.REPLAN -> {
                    if (replanProvider != null) {
                        return RecoveryDecision.Replan(reason = reason)
                    }
                }

                FailurePolicyAction.HUMAN_CLARIFICATION -> {
                    if (responder != null) {
                        val request = HumanClarificationRequest(
                            requestId = "clar_${goal.id}_${task.taskId}_$retryCount",
                            goalId = goal.id,
                            taskId = task.taskId,
                            question = "Task ${task.taskId} (${task.capabilityId}) failed: $reason. How should I proceed?",
                            options = listOf("Retry", "Skip", "Abort goal"),
                        )
                        return RecoveryDecision.AskHuman(request = request, reason = reason)
                    }
                }

                FailurePolicyAction.ABORT -> return RecoveryDecision.Abort(reason)
            }
        }

        // All actions exhausted without a decision — abort with diagnostics.
        RichRuntimeDiagnostics.record("GOAL_RECOVERY", "Exhausted recovery policy for ${task.taskId}")
        return RecoveryDecision.Abort("Recovery policy exhausted: $reason")
    }
}

/**
 * Tracks how many times each fallback strategy was consumed for one task execution.
 */
data class FallbackUsage(
    val providerFallbacks: Int = 0,
    val alternativeUses: Int = 0,
) {
    fun withProviderFallback() = copy(providerFallbacks = providerFallbacks + 1)
    fun withAlternative() = copy(alternativeUses = alternativeUses + 1)
}

/**
 * Time-slice helper for retry backoff (kept small and testable).
 */
internal suspend fun applyBackoff(backoffMs: Long) {
    if (backoffMs > 0) delay(backoffMs)
}