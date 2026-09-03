/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

@file:Suppress("ReturnCount")

package com.zeroclaw.android.service.uiagent

import android.util.Log
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

class UiAgentLoop(
    private val snapshotProvider: UiSnapshotProvider,
    private val planner: UiAgentPlanner,
    private val executor: UiAgentActionExecutor,
    private val verifier: UiAgentVerifier = UiAgentVerifier(),
    private val safetyPolicy: UiAgentSafetyPolicy = UiAgentSafetyPolicy(),
    private val recoveryPolicy: UiAgentRecoveryPolicy = UiAgentRecoveryPolicy(),
    private val config: UiAgentLoopConfig = UiAgentLoopConfig(),
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val pause: suspend (Long) -> Unit = { timeoutMs -> delay(timeoutMs) },
) {
    suspend fun run(
        goal: UiAgentGoal,
        onStepCompleted: (UiAgentStepRecord) -> Unit = {},
    ): UiAgentLoopResult {
        val startedAt = clock()
        val deadline = startedAt + config.timeoutMs
        val sessionId = "ui-agent-$startedAt"
        val history = mutableListOf<UiAgentStepRecord>()
        var recoveryAttempts = 0
        Log.d(TAG, "loop start: session=$sessionId, goal=${goal.describeForLogs()}, maxSteps=${config.maxSteps}")

        repeat(config.maxSteps) { stepIndex ->
            currentCoroutineContext().ensureActive()
            if (clock() >= deadline) {
                Log.w(TAG, "loop timeout before step: session=$sessionId, step=$stepIndex")
                return UiAgentLoopResult.failed(
                    failureReason = UiAgentLoopFailureReason.TIMED_OUT,
                    failureMessage = "UI agent loop timed out.",
                    history = history,
                )
            }

            val snapshot =
                awaitSnapshot(
                    goal = goal,
                    deadlineAtEpochMs = minOf(deadline, clock() + config.snapshotAcquisitionTimeoutMs),
                )
                    ?: return UiAgentLoopResult.failed(
                        failureReason = UiAgentLoopFailureReason.MISSING_SNAPSHOT,
                        failureMessage = "UI snapshot is unavailable.",
                        history = history,
                    ).also {
                        Log.w(TAG, "snapshot missing: session=$sessionId, step=$stepIndex")
                    }
            Log.d(TAG, "snapshot ready: session=$sessionId, step=$stepIndex, ${snapshot.describeForLogs()}")

            val context =
                UiAgentSessionContext(
                    sessionId = sessionId,
                    goal = goal,
                    stepIndex = stepIndex,
                    maxSteps = config.maxSteps,
                    startedAtEpochMs = startedAt,
                    deadlineAtEpochMs = deadline,
                    history = history.toList(),
                )
            val prompt =
                UiPrompt(
                    goal = goal,
                    snapshot = snapshot,
                    previousDecisions = history.map { step -> step.decision },
                )
            val decision = planner.decide(prompt = prompt, context = context).normalized()
            val expectedState = decision.effectiveExpectedState(snapshot)
            Log.d(
                TAG,
                "decision: session=$sessionId, step=$stepIndex, action=${decision.action.describeForLogs()}, " +
                    "expected=${expectedState.describeForLogs()}, confidence=${decision.confidence}",
            )
            val validationFailure =
                validateDecision(
                    decision = decision,
                    snapshot = snapshot,
                    goal = goal,
            )
            if (validationFailure != null) {
                Log.w(
                    TAG,
                    "validation failed: session=$sessionId, step=$stepIndex, " +
                        "action=${decision.action.describeForLogs()}, reason=${validationFailure.reason}",
                )
                val recoveryOutcome =
                    recordFailureAndMaybeRecover(
                        failure =
                            UiAgentRecoverableFailure.Validation(
                                reason = validationFailure.reason,
                                failureReason = validationFailure.failureReason,
                            ),
                        snapshot = snapshot,
                        decision = decision,
                        goal = goal,
                        history = history,
                        onStepCompleted = onStepCompleted,
                        stepIndex = stepIndex,
                        recoveryAttempts = recoveryAttempts,
                        deadlineAtEpochMs = deadline,
                        validationFailureReason = validationFailure.reason,
                    )
                if (recoveryOutcome.recovered) {
                    recoveryAttempts = recoveryOutcome.nextRecoveryAttempts
                    return@repeat
                }
                return UiAgentLoopResult.failed(
                    failureReason = validationFailure.failureReason,
                    failureMessage = recoveryOutcome.failureMessage ?: validationFailure.reason,
                    history = history,
                )
            }

            val safetyResult =
                safetyPolicy.evaluate(
                    action = decision.action,
                    snapshot = snapshot,
                    goal = goal,
            )
            if (safetyResult is UiAgentSafetyResult.Blocked) {
                Log.w(
                    TAG,
                    "safety blocked: session=$sessionId, step=$stepIndex, " +
                        "action=${decision.action.describeForLogs()}, reason=${safetyResult.reason}",
                )
                history +=
                    UiAgentStepRecord(
                        stepIndex = stepIndex,
                        snapshot = snapshot,
                        decision = decision,
                        safetyResult = safetyResult,
                        completedAtEpochMs = clock(),
                    )
                        .also(onStepCompleted)
                return UiAgentLoopResult.failed(
                    failureReason = UiAgentLoopFailureReason.UNSAFE_ACTION,
                    failureMessage = safetyResult.reason,
                    history = history,
                )
            }

            if (decision.action is UiAgentAction.Abort) {
                Log.w(TAG, "planner aborted: session=$sessionId, step=$stepIndex, reason=${decision.action.reason}")
                history +=
                    UiAgentStepRecord(
                        stepIndex = stepIndex,
                        snapshot = snapshot,
                        decision = decision,
                        safetyResult = safetyResult,
                        completedAtEpochMs = clock(),
                    )
                        .also(onStepCompleted)
                return UiAgentLoopResult.failed(
                    failureReason = UiAgentLoopFailureReason.PLANNER_ABORTED,
                    failureMessage = decision.action.reason,
                    history = history,
                )
            }

            if (decision.action is UiAgentAction.TransientFailure) {
                Log.w(TAG, "planner transient failure: session=$sessionId, step=$stepIndex, reason=${decision.action.reason}")
                val recoveryOutcome =
                    recordFailureAndMaybeRecover(
                        failure =
                            UiAgentRecoverableFailure.Transient(
                                reason = decision.action.reason,
                            ),
                        snapshot = snapshot,
                        decision = decision,
                        goal = goal,
                        history = history,
                        onStepCompleted = onStepCompleted,
                        stepIndex = stepIndex,
                        recoveryAttempts = recoveryAttempts,
                        deadlineAtEpochMs = deadline,
                    )
                if (recoveryOutcome.recovered) {
                    recoveryAttempts = recoveryOutcome.nextRecoveryAttempts
                    return@repeat
                }
                return UiAgentLoopResult.failed(
                    failureReason = UiAgentLoopFailureReason.TRANSIENT_PLANNER_FAILURE,
                    failureMessage = recoveryOutcome.failureMessage ?: decision.action.reason,
                    history = history,
                )
            }

            val executionResult =
                executor.execute(
                    UiAgentExecutionCommand(
                        goal = goal,
                        action = decision.action,
                        snapshot = snapshot,
                        stepIndex = stepIndex,
                        expectedState = expectedState,
                    ),
            )
            if (executionResult is UiAgentExecutionResult.Failed) {
                Log.w(
                    TAG,
                    "execution failed: session=$sessionId, step=$stepIndex, " +
                        "action=${decision.action.describeForLogs()}, retryable=${executionResult.retryable}, " +
                        "reason=${executionResult.reason}",
                )
                val recoveryOutcome =
                    recordFailureAndMaybeRecover(
                        failure =
                            UiAgentRecoverableFailure.Execution(
                                reason = executionResult.reason,
                                retryable = executionResult.retryable,
                            ),
                        snapshot = snapshot,
                        decision = decision,
                        goal = goal,
                        history = history,
                        onStepCompleted = onStepCompleted,
                        stepIndex = stepIndex,
                        recoveryAttempts = recoveryAttempts,
                        deadlineAtEpochMs = deadline,
                        safetyResult = safetyResult,
                        executionResult = executionResult,
                    )
                if (recoveryOutcome.recovered) {
                    recoveryAttempts = recoveryOutcome.nextRecoveryAttempts
                    return@repeat
                }
                return UiAgentLoopResult.failed(
                    failureReason = UiAgentLoopFailureReason.EXECUTION_FAILED,
                    failureMessage = recoveryOutcome.failureMessage ?: executionResult.reason,
                    history = history,
                )
            }

            val verification =
                verifyAfterAction(
                    expectedState = expectedState,
                    deadlineAtEpochMs = minOf(deadline, clock() + decision.verificationTimeoutMs()),
                )

            if (verification.failureReason != null) {
                Log.w(
                    TAG,
                    "verification failed: session=$sessionId, step=$stepIndex, " +
                        "expected=${expectedState.describeForLogs()}, reason=${verification.message}, " +
                        "postSnapshot=${verification.snapshot.describeForLogs()}",
                )
                val recoveryOutcome =
                    recordFailureAndMaybeRecover(
                        failure =
                            UiAgentRecoverableFailure.Verification(
                                reason = verification.message,
                                failureReason = verification.failureReason,
                            ),
                        snapshot = snapshot,
                        decision = decision,
                        goal = goal,
                        history = history,
                        onStepCompleted = onStepCompleted,
                        stepIndex = stepIndex,
                        recoveryAttempts = recoveryAttempts,
                        deadlineAtEpochMs = deadline,
                        safetyResult = safetyResult,
                        executionResult = executionResult,
                        verificationResult = verification.result,
                        postActionSnapshot = verification.snapshot,
                    )
                if (recoveryOutcome.recovered) {
                    recoveryAttempts = recoveryOutcome.nextRecoveryAttempts
                    return@repeat
                }
                return UiAgentLoopResult.failed(
                    failureReason = verification.failureReason,
                    failureMessage = recoveryOutcome.failureMessage ?: verification.message,
                    history = history,
                )
            }

            val step =
                UiAgentStepRecord(
                    stepIndex = stepIndex,
                    snapshot = snapshot,
                    decision = decision,
                    safetyResult = safetyResult,
                    executionResult = executionResult,
                    verificationResult = verification.result,
                    postActionSnapshot = verification.snapshot,
                    completedAtEpochMs = clock(),
                )
            history += step
            onStepCompleted(step)
            Log.d(
                TAG,
                "step complete: session=$sessionId, step=$stepIndex, " +
                    "action=${decision.action.describeForLogs()}, postSnapshot=${verification.snapshot.describeForLogs()}",
            )

            if (decision.action is UiAgentAction.NoOp) {
                Log.d(TAG, "loop complete: session=$sessionId, steps=${history.size}")
                return UiAgentLoopResult(
                    status = UiAgentLoopStatus.COMPLETED,
                    history = history.toList(),
                )
            }
        }

        Log.w(TAG, "step budget exhausted: session=$sessionId, steps=${history.size}")
        return UiAgentLoopResult.failed(
            failureReason = UiAgentLoopFailureReason.STEP_BUDGET_EXHAUSTED,
            failureMessage = "UI agent step budget was exhausted.",
            history = history,
        )
    }

    private suspend fun recordFailureAndMaybeRecover(
        failure: UiAgentRecoverableFailure,
        snapshot: UiSnapshot,
        decision: UiAgentDecision,
        goal: UiAgentGoal,
        history: MutableList<UiAgentStepRecord>,
        onStepCompleted: (UiAgentStepRecord) -> Unit,
        stepIndex: Int,
        recoveryAttempts: Int,
        deadlineAtEpochMs: Long,
        safetyResult: UiAgentSafetyResult = UiAgentSafetyResult.Allowed,
        validationFailureReason: String? = null,
        executionResult: UiAgentExecutionResult? = null,
        verificationResult: UiVerificationResult? = null,
        postActionSnapshot: UiSnapshot? = null,
    ): RecoveryOutcome {
        val remainingAttempts = config.maxRecoveryAttempts - recoveryAttempts
        val recoveryDecision =
            if (remainingAttempts > 0) {
                recoveryPolicy.planRecovery(
                    failure = failure,
                    snapshot = postActionSnapshot ?: snapshot,
                    decision = decision,
                    goal = goal,
                )
            } else {
                UiAgentRecoveryDecision.Terminal(
                    "Recovery attempts exhausted after: ${failure.reason ?: failure.failureReason.name}",
                )
            }

        val recoveryAction = (recoveryDecision as? UiAgentRecoveryDecision.Retry)?.action
        val recoveryExecution =
            if (recoveryAction == null) {
                null
            } else {
                Log.d(
                    TAG,
                    "recovery start: step=$stepIndex, action=${recoveryAction.describeForLogs()}, " +
                        "failure=${failure.failureReason}, reason=${failure.reason}",
                )
                executeRecovery(
                    action = recoveryAction,
                    goal = goal,
                    snapshot = postActionSnapshot ?: snapshot,
                    stepIndex = stepIndex,
                    deadlineAtEpochMs = deadlineAtEpochMs,
                )
            }
        val step =
            UiAgentStepRecord(
                stepIndex = stepIndex,
                snapshot = snapshot,
                decision = decision,
                safetyResult = safetyResult,
                validationFailureReason = validationFailureReason,
                executionResult = executionResult,
                verificationResult = verificationResult,
                postActionSnapshot = postActionSnapshot,
                recoveryAction = recoveryAction,
                recoveryResult = recoveryExecution?.result,
                recoveryAttempt =
                    if (recoveryAction == null) {
                        null
                    } else {
                        recoveryAttempts + 1
                    },
                completedAtEpochMs = clock(),
            )
        history += step
        onStepCompleted(step)
        Log.d(
            TAG,
            "failure recorded: step=$stepIndex, recovery=${recoveryAction.describeForLogs()}, " +
                "recovered=${recoveryExecution?.recovered ?: false}, terminal=${recoveryDecision is UiAgentRecoveryDecision.Terminal}",
        )

        if (recoveryExecution?.recovered == true) {
            return RecoveryOutcome(recovered = true, nextRecoveryAttempts = recoveryAttempts + 1)
        }

        val terminalMessage =
            when (recoveryDecision) {
                is UiAgentRecoveryDecision.Terminal ->
                    recoveryDecision.reason ?: failure.reason

                is UiAgentRecoveryDecision.Retry ->
                    recoveryExecution?.result?.reason ?: failure.reason
            }
        return RecoveryOutcome(
            recovered = false,
            nextRecoveryAttempts = recoveryAttempts + if (recoveryAction == null) 0 else 1,
            failureMessage = terminalMessage,
        )
    }

    private suspend fun executeRecovery(
        action: UiAgentRecoveryAction,
        goal: UiAgentGoal,
        snapshot: UiSnapshot,
        stepIndex: Int,
        deadlineAtEpochMs: Long,
    ): RecoveryExecution {
        currentCoroutineContext().ensureActive()
        if (clock() >= deadlineAtEpochMs) {
            return RecoveryExecution(
                result = UiAgentExecutionResult.Failed("UI agent loop timed out before recovery."),
                recovered = false,
            )
        }

        val executionResult =
            when (action) {
                is UiAgentRecoveryAction.RefreshSnapshot ->
                    UiAgentExecutionResult.Succeeded

                is UiAgentRecoveryAction.WaitForFreshSnapshot -> {
                    pause(config.snapshotPollIntervalMs)
                    UiAgentExecutionResult.Succeeded
                }

                is UiAgentRecoveryAction.PressBack,
                is UiAgentRecoveryAction.HideKeyboard,
                ->
                    executor.execute(
                        UiAgentExecutionCommand(
                            goal = goal,
                            action = UiAgentAction.PressGlobal(UiAgentGlobalAction.BACK),
                            snapshot = snapshot,
                            stepIndex = stepIndex,
                        ),
                    )

                is UiAgentRecoveryAction.ScrollResultList ->
                    executor.execute(
                        UiAgentExecutionCommand(
                            goal = goal,
                            action = UiAgentAction.ScrollNode(action.nodeId, action.direction),
                            snapshot = snapshot,
                            stepIndex = stepIndex,
                        ),
                    )
            }

        if (executionResult is UiAgentExecutionResult.Failed) {
            return RecoveryExecution(result = executionResult, recovered = false)
        }

        val freshSnapshot =
            awaitSnapshot(
                deadlineAtEpochMs = minOf(
                    deadlineAtEpochMs,
                    clock() + config.snapshotAcquisitionTimeoutMs,
                ),
            )
        return if (freshSnapshot == null) {
            RecoveryExecution(
                result =
                    UiAgentExecutionResult.Failed(
                        reason = "UI snapshot is unavailable after recovery.",
                        retryable = true,
                    ),
                recovered = false,
            )
        } else {
            RecoveryExecution(result = executionResult, recovered = true)
        }
    }

    private suspend fun verifyAfterAction(
        expectedState: UiExpectedState?,
        deadlineAtEpochMs: Long,
    ): VerificationAttempt {
        var latestSnapshot =
            awaitSnapshot(deadlineAtEpochMs = deadlineAtEpochMs)
                ?: return VerificationAttempt(
                    failureReason = UiAgentLoopFailureReason.MISSING_SNAPSHOT,
                    message = "UI snapshot is unavailable after executing the action.",
                )
        var latestResult = verifier.verify(expectedState = expectedState, snapshot = latestSnapshot)
        if (latestResult.matched) {
            return VerificationAttempt(snapshot = latestSnapshot, result = latestResult)
        }

        while (clock() < deadlineAtEpochMs) {
            pause(config.verificationPollIntervalMs)
            val polledSnapshot = snapshotProvider.currentSnapshot()
            if (polledSnapshot == null) {
                continue
            }
            latestSnapshot = polledSnapshot
            latestResult = verifier.verify(expectedState = expectedState, snapshot = latestSnapshot)
            if (latestResult.matched) {
                return VerificationAttempt(snapshot = latestSnapshot, result = latestResult)
            }
        }

        return VerificationAttempt(
            snapshot = latestSnapshot,
            result = latestResult,
            failureReason = UiAgentLoopFailureReason.VERIFICATION_TIMEOUT,
            message = latestResult.reason ?: "Expected UI state was not observed before timeout.",
        )
    }

    private suspend fun awaitSnapshot(deadlineAtEpochMs: Long): UiSnapshot? {
        return awaitSnapshot(goal = null, deadlineAtEpochMs = deadlineAtEpochMs)
    }

    private suspend fun awaitSnapshot(
        goal: UiAgentGoal?,
        deadlineAtEpochMs: Long,
    ): UiSnapshot? {
        var snapshot = snapshotProvider.currentSnapshot()
        if (snapshot?.isUsableForGoal(goal) == true) {
            return snapshot
        }

        while (clock() < deadlineAtEpochMs) {
            pause(config.snapshotPollIntervalMs)
            snapshot = snapshotProvider.currentSnapshot()
            if (snapshot?.isUsableForGoal(goal) == true) {
                return snapshot
            }
        }

        return null
    }

    private fun validateDecision(
        decision: UiAgentDecision,
        snapshot: UiSnapshot,
        goal: UiAgentGoal,
    ): UiAgentValidationFailure? =
        when (val action = decision.action) {
            is UiAgentAction.TapNode ->
                validateTapNodeAction(
                    nodeId = action.nodeId,
                    snapshot = snapshot,
                    goal = goal,
                    failureMessage = "Tap target is not a visible enabled clickable node.",
                )

            is UiAgentAction.SetText ->
                validateNodeAction(
                    nodeId = action.nodeId,
                    snapshot = snapshot,
                    failureMessage = "Text target is not a visible enabled editable node.",
                ) { node -> node.editable || UiNodeAction.SET_TEXT in node.actions }

            is UiAgentAction.ScrollNode ->
                validateNodeAction(
                    nodeId = action.nodeId,
                    snapshot = snapshot,
                    failureMessage = "Scroll target is not a visible enabled scrollable node.",
                ) { node -> node.canScroll(action.direction) }

            is UiAgentAction.OpenPackage ->
                if (action.packageName.isBlank()) {
                    UiAgentValidationFailure("Package name is blank.")
                } else {
                    null
                }

            is UiAgentAction.PressGlobal -> null

            is UiAgentAction.Wait ->
                if (action.timeoutMs <= 0L) {
                    UiAgentValidationFailure("Wait timeout must be positive.")
                } else {
                    null
                }

            is UiAgentAction.NoOp ->
                if (action.reason.isBlank()) {
                    UiAgentValidationFailure("No-op reason is blank.")
                } else {
                    null
                }

            is UiAgentAction.Abort ->
                if (action.reason.isBlank()) {
                    UiAgentValidationFailure("Abort reason is blank.")
                } else {
                    null
                }

            is UiAgentAction.TransientFailure ->
                if (action.reason.isBlank()) {
                    UiAgentValidationFailure("Transient failure reason is blank.")
                } else {
                    null
                }
        }

    private fun validateTapNodeAction(
        nodeId: String,
        snapshot: UiSnapshot,
        goal: UiAgentGoal,
        failureMessage: String,
    ): UiAgentValidationFailure? {
        if (nodeId.isBlank()) {
            return UiAgentValidationFailure("Target node id is blank.")
        }
        val node =
            snapshot.nodes.firstOrNull { candidate -> candidate.id == nodeId }
                ?: return UiAgentValidationFailure("Target node $nodeId was not found.")
        if (node.visibleToUser && node.enabled && (node.clickable || UiNodeAction.CLICK in node.actions)) {
            return null
        }
        if (node.isVisibleRecipientLabel(goal) && snapshot.findClickableAncestor(node.id) != null) {
            return null
        }

        return UiAgentValidationFailure(failureMessage)
    }

    private fun validateNodeAction(
        nodeId: String,
        snapshot: UiSnapshot,
        failureMessage: String,
        predicate: (UiNode) -> Boolean,
    ): UiAgentValidationFailure? {
        if (nodeId.isBlank()) {
            return UiAgentValidationFailure("Target node id is blank.")
        }
        val node =
            snapshot.nodes.firstOrNull { candidate -> candidate.id == nodeId }
                ?: return UiAgentValidationFailure("Target node $nodeId was not found.")
        return if (node.visibleToUser && node.enabled && predicate(node)) {
            null
        } else {
            UiAgentValidationFailure(failureMessage)
        }
    }

    private fun UiNode.isVisibleRecipientLabel(goal: UiAgentGoal): Boolean {
        val sendMessageGoal = goal as? UiAgentGoal.SendMessage ?: return false
        val recipient = sendMessageGoal.recipient?.trim()?.takeIf { it.isNotEmpty() } ?: return false
        return visibleToUser &&
            listOfNotNull(text, contentDescription)
                .any { value -> value.contains(recipient, ignoreCase = true) }
    }

    private fun UiSnapshot.findClickableAncestor(nodeId: String): UiNode? {
        val nodesById = nodes.associateBy(UiNode::id)
        var current = nodesById[nodeId]?.parentId?.let(nodesById::get)
        while (current != null) {
            if (current.enabled && (current.clickable || UiNodeAction.CLICK in current.actions)) {
                return current
            }
            current = current.parentId?.let(nodesById::get)
        }
        return null
    }

    private fun UiNode.canScroll(direction: UiAgentScrollDirection): Boolean =
        when (direction) {
            UiAgentScrollDirection.FORWARD -> UiNodeAction.SCROLL_FORWARD in actions
            UiAgentScrollDirection.BACKWARD -> UiNodeAction.SCROLL_BACKWARD in actions
        } && boundsInScreen != null

    private fun UiAgentDecision.effectiveExpectedState(
        snapshot: UiSnapshot,
    ): UiExpectedState? =
        (
            expectedState
                ?: when (val selectedAction = action) {
                is UiAgentAction.Wait -> selectedAction.expectedState
                else -> null
            }
        )?.enrichedFrom(snapshot)

    private fun UiExpectedState.enrichedFrom(snapshot: UiSnapshot): UiExpectedState =
        when (this) {
            is UiExpectedState.NodeAvailable -> {
                val node = snapshot.nodes.firstOrNull { candidate -> candidate.id == nodeId }
                if (node == null) {
                    this
                } else {
                    copy(
                        packageName = packageName ?: node.packageName ?: snapshot.foregroundPackageName,
                        viewIdResourceName = viewIdResourceName ?: node.viewIdResourceName,
                        text = text ?: node.text,
                        contentDescription = contentDescription ?: node.contentDescription,
                    )
                }
            }

            else -> this
        }

    private fun UiAgentDecision.verificationTimeoutMs(): Long =
        when (val selectedAction = action) {
            is UiAgentAction.Wait -> selectedAction.timeoutMs
            else -> config.verificationTimeoutMs
        }

    private data class VerificationAttempt(
        val snapshot: UiSnapshot? = null,
        val result: UiVerificationResult? = null,
        val failureReason: UiAgentLoopFailureReason? = null,
        val message: String? = null,
    )

    private data class RecoveryOutcome(
        val recovered: Boolean,
        val nextRecoveryAttempts: Int,
        val failureMessage: String? = null,
    )

    private data class RecoveryExecution(
        val result: UiAgentExecutionResult,
        val recovered: Boolean,
    )

    private data class UiAgentValidationFailure(
        val reason: String,
        val failureReason: UiAgentLoopFailureReason = UiAgentLoopFailureReason.INVALID_ACTION,
    )

    private companion object {
        const val TAG = "UiAgentLoop"
    }
}

private fun UiSnapshot.isUsableForGoal(goal: UiAgentGoal?): Boolean {
    val targetPackageName = goal.targetPackageNameOrNull() ?: return true
    return hasTargetPackageRoot(targetPackageName)
}

private fun UiSnapshot.hasTargetPackageRoot(targetPackageName: String): Boolean {
    val nodesById = nodes.associateBy(UiNode::id)
    return rootNodeIds.any { rootId ->
        nodesById[rootId]?.subtreeContainsPackage(targetPackageName, nodesById) == true
    }
}

private fun UiNode.subtreeContainsPackage(
    targetPackageName: String,
    nodesById: Map<String, UiNode>,
): Boolean {
    if (packageName == targetPackageName) {
        return true
    }
    return childIds.any { childId ->
        nodesById[childId]?.subtreeContainsPackage(targetPackageName, nodesById) == true
    }
}

private fun UiAgentGoal?.targetPackageNameOrNull(): String? =
    when (this) {
        is UiAgentGoal.Generic -> targetPackageName
        is UiAgentGoal.SendMessage -> targetPackageName
        null -> null
    }

data class UiAgentLoopConfig(
    val maxSteps: Int = 12,
    val maxRecoveryAttempts: Int = 3,
    val timeoutMs: Long = 120_000L,
    val verificationTimeoutMs: Long = 4_000L,
    val verificationPollIntervalMs: Long = 200L,
    val snapshotAcquisitionTimeoutMs: Long = 5_000L,
    val snapshotPollIntervalMs: Long = 200L,
) {
    init {
        require(maxSteps > 0) { "maxSteps must be positive." }
        require(maxRecoveryAttempts >= 0) { "maxRecoveryAttempts cannot be negative." }
        require(timeoutMs > 0L) { "timeoutMs must be positive." }
        require(verificationTimeoutMs > 0L) { "verificationTimeoutMs must be positive." }
        require(verificationPollIntervalMs > 0L) {
            "verificationPollIntervalMs must be positive."
        }
        require(snapshotAcquisitionTimeoutMs > 0L) {
            "snapshotAcquisitionTimeoutMs must be positive."
        }
        require(snapshotPollIntervalMs > 0L) {
            "snapshotPollIntervalMs must be positive."
        }
    }
}

data class UiAgentStepRecord(
    val stepIndex: Int,
    val snapshot: UiSnapshot,
    val decision: UiAgentDecision,
    val safetyResult: UiAgentSafetyResult = UiAgentSafetyResult.Allowed,
    val validationFailureReason: String? = null,
    val executionResult: UiAgentExecutionResult? = null,
    val verificationResult: UiVerificationResult? = null,
    val postActionSnapshot: UiSnapshot? = null,
    val recoveryAction: UiAgentRecoveryAction? = null,
    val recoveryResult: UiAgentExecutionResult? = null,
    val recoveryAttempt: Int? = null,
    val completedAtEpochMs: Long,
)

data class UiAgentLoopResult(
    val status: UiAgentLoopStatus,
    val failureReason: UiAgentLoopFailureReason? = null,
    val failureMessage: String? = null,
    val history: List<UiAgentStepRecord> = emptyList(),
) {
    val completed: Boolean
        get() = status == UiAgentLoopStatus.COMPLETED

    companion object {
        fun failed(
            failureReason: UiAgentLoopFailureReason,
            failureMessage: String?,
            history: List<UiAgentStepRecord>,
        ): UiAgentLoopResult =
            UiAgentLoopResult(
                status = UiAgentLoopStatus.FAILED,
                failureReason = failureReason,
                failureMessage = failureMessage,
                history = history.toList(),
            )
    }
}

enum class UiAgentLoopStatus {
    COMPLETED,
    FAILED,
}

enum class UiAgentLoopFailureReason {
    MISSING_SNAPSHOT,
    INVALID_ACTION,
    UNSAFE_ACTION,
    PLANNER_ABORTED,
    EXECUTION_FAILED,
    VERIFICATION_TIMEOUT,
    STEP_BUDGET_EXHAUSTED,
    TIMED_OUT,
    TRANSIENT_PLANNER_FAILURE,
}

private fun UiSnapshot?.describeForLogs(): String =
    if (this == null) {
        "snapshot=null"
    } else {
        "snapshot(package=${foregroundPackageName ?: "none"}, roots=${rootNodeIds.size}, nodes=${nodes.size}, " +
            "titlePresent=${!foregroundWindowTitle.isNullOrBlank()})"
    }

private fun UiAgentGoal.describeForLogs(): String =
    when (this) {
        is UiAgentGoal.Generic ->
            "Generic(instruction=${instruction.privacySummary()}, target=${targetPackageName ?: "none"}, " +
                "targetQuery=${targetAppQuery?.privacySummary() ?: "none"})"
        is UiAgentGoal.SendMessage ->
            "SendMessage(recipientPresent=${!recipient.isNullOrBlank()}, message=${message.privacySummary()}, " +
                "target=${targetPackageName ?: "none"}, targetQuery=${targetAppQuery?.privacySummary() ?: "none"})"
    }

private fun UiAgentAction.describeForLogs(): String =
    when (this) {
        is UiAgentAction.TapNode -> "TapNode(nodeId=$nodeId)"
        is UiAgentAction.SetText -> "SetText(nodeId=$nodeId, text=${text.privacySummary()})"
        is UiAgentAction.ScrollNode -> "ScrollNode(nodeId=$nodeId, direction=$direction)"
        is UiAgentAction.OpenPackage -> "OpenPackage(packageName=$packageName)"
        is UiAgentAction.PressGlobal -> "PressGlobal(action=$action)"
        is UiAgentAction.Wait -> "Wait(timeoutMs=$timeoutMs, expected=${expectedState.describeForLogs()})"
        is UiAgentAction.NoOp -> "NoOp(reason=${reason.privacySummary()})"
        is UiAgentAction.Abort -> "Abort(reason=${reason.privacySummary()})"
        is UiAgentAction.TransientFailure -> "TransientFailure(reason=${reason.privacySummary()})"
    }

private fun UiExpectedState?.describeForLogs(): String =
    when (this) {
        null -> "none"
        is UiExpectedState.ForegroundPackage -> "ForegroundPackage(packageName=$packageName)"
        is UiExpectedState.TextVisible -> "TextVisible(text=${text.privacySummary()}, package=${packageName ?: "none"})"
        is UiExpectedState.NodeAvailable ->
            "NodeAvailable(nodeId=$nodeId, package=${packageName ?: "none"}, viewId=${viewIdResourceName ?: "none"}, " +
                "textPresent=${!text.isNullOrBlank()}, descriptionPresent=${!contentDescription.isNullOrBlank()})"
        UiExpectedState.RootReady -> "RootReady"
    }

private fun UiAgentRecoveryAction?.describeForLogs(): String =
    when (this) {
        null -> "none"
        is UiAgentRecoveryAction.RefreshSnapshot -> "RefreshSnapshot"
        is UiAgentRecoveryAction.WaitForFreshSnapshot -> "WaitForFreshSnapshot"
        is UiAgentRecoveryAction.PressBack -> "PressBack"
        is UiAgentRecoveryAction.HideKeyboard -> "HideKeyboard"
        is UiAgentRecoveryAction.ScrollResultList -> "ScrollResultList(nodeId=$nodeId, direction=$direction)"
    }

private fun String.privacySummary(): String =
    "len=${length},hash=${hashCode().toString(16)}"
