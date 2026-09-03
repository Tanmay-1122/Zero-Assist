package com.zeroclaw.android.service.devicecontrol

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.util.Log
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/**
 * Waits for a UI change up to [timeoutMs]. Falls back gracefully if the
 * service fires an event immediately or the timeout elapses.
 */
private suspend fun awaitUiChange(
    service: DeviceControlServiceBridge,
    tag: String,
    preFingerprint: ScreenFingerprint,
    timeoutMs: Long,
) {
    try {
        service.waitForUiChange(tag, timeoutMs, preFingerprint)
    } catch (_: Exception) { /* timeout is fine */ }
}

class DeviceControlExecutor(
    context: Context,
    private val planner: DeviceControlPlanner,
    private val observer: ScreenObserver = ScreenObserver(),
    private val recovery: RecoveryEngine = RecoveryEngine(),
    private val maxSteps: Int = 30,
    private val serviceProvider: () -> DeviceControlServiceBridge? = { DeviceControlAccessibilityService.instance() },
) {
    private val appContext: Context = context.applicationContext
    private val launcher = AppLauncher(context.applicationContext)
    private val quickIntents = DeviceControlQuickIntents(context.applicationContext)
    private val fileShare = FileShareController(context.applicationContext)

    companion object {
        private const val TAG = "DeviceControlExec"

        /** Maximum follow-up actions per planner response (prevents blind execution). */
        private const val MAX_FOLLOW_UP_ACTIONS = 4

        /** Maximum event-driven wait for UI transitions (ms). */
        private const val UI_CHANGE_TIMEOUT_MS = 3_000L

        /** Ceiling for any blind settle delay where no fingerprint exists yet. */
        private const val MAX_BLIND_DELAY_MS = 300L

        /** Settle wait when a snapshot unexpectedly has no actionable nodes. */
        private const val SETTLE_TIMEOUT_MS = 400L

        /** Post-recovery settle wait. */
        private const val RECOVERY_SETTLE_MS = 300L

        /** Post-launch settle window for YouTube/URL quick intents. */
        private const val LAUNCH_SETTLE_MS = 650L

        /** Post-bootstrap-launch blind pause. */
        private const val BOOTSTRAP_SETTLE_MS = 200L

        /** App name suffixes that indicate compound goals (e.g. "open chrome settings"). */
        private val APP_SUFFIX_INDICATORS = Regex(
            """\s+(?:settings|preferences|config|configuration|menu|info|information|details|about|help|account|profile|dashboard|manager|launcher|store|hub|center|panel|tab|page|screen|view|mode|search|dm|dms|chat|pro|plus|premium|lite|free|beta|dev|debug|test)$""",
            RegexOption.IGNORE_CASE,
        )

        /** Direct element click pattern. */
        private val DIRECT_CLICK_PATTERN = Regex(
            """^(?:please\s+|just\s+|can\s+you\s+)?(?:click|tap|press)\s+(?:the\s+)?(.+)\s*$""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Pure system navigation actions that are terminal.
         * Only matches goals that are EXACTLY one of these actions with optional filler words.
         */
        private val SIMPLE_GOAL_PATTERN = Regex(
            """^(?:please\s+|just\s+|can\s+you\s+)?(?:go\s+)?(?:to\s+)?(home|back|recents?|notifications?)\s*$""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Matches initial app-open command at start of compound goals.
         */
        private val BOOTSTRAP_APP_PATTERN = Regex(
            """^(?:please\s+|just\s+|can\s+you\s+)?(?:open|launch|start)\s+(?:the\s+)?(.+?)(?=\s+(?:and|then|after\s+that|next|also|plus|with|while)\b|,|\+|$)""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Matches goals that are ONLY an app-open command with no additional work.
         * Strict: the app name must be a single word or simple multi-word name
         * with NO punctuation, NO conjunctions, and NO UI-element suffixes.
         */
        private val TERMINAL_APP_PATTERN = Regex(
            """^(?:please\s+|just\s+|can\s+you\s+)?(?:open|launch|start)\s+(?:the\s+)?([a-zA-Z0-9][a-zA-Z0-9\s]*?)\s*$""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Compound conjunctions that indicate additional work beyond opening an app.
         * These appear in the FULL goal, not just the captured app name.
         */
        private val COMPOUND_CONJUNCTIONS = Regex(
            """,|\s+(?:and|then|after\s+that|next|also|plus|with|while)\s+|\+""",
            RegexOption.IGNORE_CASE,
        )

        /**
         * Non-app terms that should NOT trigger bootstrap app launching.
         */
        private val NON_APP_BOOTSTRAP_TERMS = setOf(
            "quick settings", "quick setting", "quick",
            "flashlight", "torch",
            "developer options", "about phone", "build number",
            "notifications", "notification shade", "control center",
            "status bar", "volume", "bluetooth", "wifi", "hotspot",
        )

        private fun cleanAppName(raw: String): String {
            return raw.trim()
                .replace(Regex("^(?:the|a|an)\\s+", RegexOption.IGNORE_CASE), "")
                .replace(Regex("\\s+(?:app|application)$", RegexOption.IGNORE_CASE), "")
                .trim()
        }
    }

    private data class FastPathAttempt(
        val handled: Boolean,
        val result: DeviceControlResult? = null,
    )

    /**
     * Mutable state for the planner loop, extracted to keep each function
     * under ~8 local variables (JIT allocates one class per function;
     * fewer variables = smaller class = less memory pressure).
     */
    private class LoopState {
        var lastAction: DeviceAction? = null
        var lastResult: String? = null
        var failures = 0
        var repeated = 0
        var lastSignature = ""
        val actionHistory = mutableListOf<String>()
        var lastFingerprint: ScreenFingerprint? = null
        val fingerprintHistory = mutableListOf<Int>()

        /** Fingerprint of the cached screen description below. */
        var cachedScreenFingerprint: ScreenFingerprint? = null

        /** Cached planner-facing screen description (rebuilt only on change). */
        var cachedScreenDescription: String? = null

        /** Cached node list matching the description above. */
        var cachedNodes: List<UiNodeSnapshot>? = null

        fun invalidateScreenCache() {
            cachedScreenFingerprint = null
            cachedScreenDescription = null
            cachedNodes = null
        }
    }

    suspend fun execute(goal: String): DeviceControlResult {
        val requestId = java.util.UUID.randomUUID().toString().take(8)
        val trace = DeviceControlPerformanceTrace(requestId, goal.hashCode())
        startOverlayIfPermitted()
        Log.d(TAG, "[$requestId] execute goal='$goal' maxSteps=$maxSteps")

        trace.markCallbackEntry()
        DeviceControlMonitor.onControlStarted(goal, maxSteps)

        // ── PHASE: Accessibility diagnostics ──────────────────────────────
        val service = serviceProvider()
        if (service == null) {
            val connected = DeviceControlAccessibilityService.connected.value
            Log.e(TAG, "[$requestId] ACCESSIBILITY_DISABLED: instance()=null connected=$connected")
            trace.markFailure()
            trace.emit()
            val failure = DeviceControlResult.Failure(
                message = "Accessibility service is not enabled. " +
                    "Please enable Zero-Assist in Android Settings → Accessibility.",
                steps = 0,
                errorCode = DeviceControlResult.ErrorCode.ACCESSIBILITY_DISABLED,
                retryable = true,
            )
            DeviceControlMonitor.onControlFailed(failure.message)
            return failure
        }

        // Check if we have an active window (accessibility is fully operational)
        val hasActiveWindow = service.currentPackage() != null
        if (!hasActiveWindow) {
            Log.w(TAG, "[$requestId] NO_ACTIVE_WINDOW: currentPackage()=null")
            trace.markFailure()
            trace.emit()
            val failure = DeviceControlResult.Failure(
                message = "Accessibility service is connected but has no active window. " +
                    "The device screen may be off or locked.",
                steps = 0,
                errorCode = DeviceControlResult.ErrorCode.NO_ACTIVE_WINDOW,
                retryable = true,
            )
            DeviceControlMonitor.onControlFailed(failure.message)
            return failure
        }

        val fgBefore = service.currentPackage()
        Log.i(TAG, "[$requestId] accessibility_ok: fg=$fgBefore")

        // ── PHASE: Task context initialization ──────────────────────────────
        val taskContext = TaskContext(goal)
        taskContext.inferFromGoal()
        taskContext.currentObservedPackage = fgBefore

        trace.markExecutorStart()

        // ── PHASE: Fast-path classification ──────────────────────────────
        val fastPathResult = classifyAndExecuteFastPath(service, goal, requestId, trace)
        if (fastPathResult != null) {
            trace.markExecutorEnd()
            trace.markCallbackExit()
            trace.emit()
            when (fastPathResult) {
                is DeviceControlResult.Success -> {
                    DeviceControlMonitor.onStepStarted(1, "Fast-path execution")
                    DeviceControlMonitor.onStepFinished(1, "Completed quick action", true)
                    DeviceControlMonitor.onControlCompleted(fastPathResult.message)
                }
                is DeviceControlResult.Failure -> {
                    DeviceControlMonitor.onControlFailed(fastPathResult.message)
                }
                is DeviceControlResult.Cancelled -> {
                    DeviceControlMonitor.onControlCancelled()
                }
            }
            return fastPathResult
        }

        // ── PHASE: Planner loop ──────────────────────────────────────────
        val state = LoopState()
        try {
            repeat(maxSteps) { index ->
                coroutineContext.ensureActive()
                if (DeviceControlMonitor.isCancellationRequested()) {
                    Log.w(TAG, "[$requestId] cancellation requested by user")
                    DeviceControlMonitor.onControlCancelled()
                    return DeviceControlResult.Cancelled(index)
                }
                val stepResult = executeLoopStep(
                    service, goal, requestId, index, taskContext, state, trace,
                )
                if (stepResult != null) {
                    trace.markExecutorEnd()
                    trace.markCallbackExit()
                    trace.emit()
                    when (stepResult) {
                        is DeviceControlResult.Success -> DeviceControlMonitor.onControlCompleted(stepResult.message)
                        is DeviceControlResult.Failure -> DeviceControlMonitor.onControlFailed(stepResult.message)
                        is DeviceControlResult.Cancelled -> DeviceControlMonitor.onControlCancelled()
                    }
                    return stepResult
                }
            }
        } catch (e: CancellationException) {
            Log.w(TAG, "[$requestId] cancelled", e)
            trace.markCancelled()
            trace.markExecutorEnd()
            trace.markCallbackExit()
            trace.emit()
            DeviceControlMonitor.onControlCancelled()
            return DeviceControlResult.Cancelled(0)
        } catch (t: Throwable) {
            Log.e(TAG, "[$requestId] unexpected error: ${t::class.simpleName}: ${t.message}", t)
            trace.markFailure()
            trace.markExecutorEnd()
            trace.markCallbackExit()
            trace.emit()
            val failure = DeviceControlResult.Failure(
                message = "Device control failed: ${t.message}",
                steps = 0,
                cause = t,
                errorCode = DeviceControlResult.ErrorCode.INTERNAL_ERROR,
            )
            DeviceControlMonitor.onControlFailed(failure.message)
            return failure
        }
        trace.markFailure()
        trace.markExecutorEnd()
        trace.markCallbackExit()
        trace.emit()
        val failure = DeviceControlResult.Failure(
            message = "Reached the maximum of $maxSteps steps.",
            steps = maxSteps,
            errorCode = DeviceControlResult.ErrorCode.MAX_STEPS_REACHED,
        )
        DeviceControlMonitor.onControlFailed(failure.message)
        return failure
    }

    /**
     * Single iteration of the planner loop. Returns a [DeviceControlResult]
     * when the goal is reached or a hard stop is needed, or null to continue.
     */
    private suspend fun executeLoopStep(
        service: DeviceControlServiceBridge,
        goal: String,
        requestId: String,
        index: Int,
        taskContext: TaskContext,
        state: LoopState,
        trace: DeviceControlPerformanceTrace,
    ): DeviceControlResult? {
        // ── Snapshot with fingerprint (cached when screen unchanged) ──
        val snapStart = trace.beginSnapshot()
        val quickFp = service.snapshotFingerprint()
        var (screenDesc, fingerprint, cachedNodes) =
            if (
                state.cachedScreenFingerprint != null &&
                quickFp.isSameScreen(state.cachedScreenFingerprint!!) &&
                state.cachedScreenDescription != null &&
                state.cachedNodes != null
            ) {
                Triple(
                    state.cachedScreenDescription!!,
                    state.cachedScreenFingerprint!!,
                    state.cachedNodes!!,
                )
            } else {
                observer.describeWithFingerprint(service, goal).also { fresh ->
                    state.cachedScreenFingerprint = fresh.second
                    state.cachedScreenDescription = fresh.first
                    state.cachedNodes = fresh.third
                }
            }
        if (fingerprint.actionableNodeCount == 0) {
            awaitUiChange(service, "settled_$index", fingerprint, SETTLE_TIMEOUT_MS)
            val settledSnap = observer.describeWithFingerprint(service, goal)
            if (settledSnap.second.actionableNodeCount > 0) {
                screenDesc = settledSnap.first
                fingerprint = settledSnap.second
                cachedNodes = settledSnap.third
                state.cachedScreenFingerprint = fingerprint
                state.cachedScreenDescription = screenDesc
                state.cachedNodes = cachedNodes
            }
        }
        trace.endSnapshot(snapStart)

        Log.d(TAG, "[$requestId] step=${index + 1} fp=${fingerprint.toLogString()} prev_fp=${state.lastFingerprint?.toLogString()}")

        // ── Fingerprint-based loop detection ──────────────────────
        state.fingerprintHistory.add(fingerprint.contentHash)
        if (state.fingerprintHistory.size > 10) state.fingerprintHistory.removeAt(0)
        if (detectFingerprintLoop(state.fingerprintHistory)) {
            Log.w(TAG, "[$requestId] fingerprint loop detected, triggering recovery")
            state.actionHistory.add("LOOP DETECTED (same screen repeating)")
            trace.addRecovery()
            val freshSnapStart = trace.beginSnapshot()
            val freshScreen = observer.describe(service, goal)
            trace.endSnapshot(freshSnapStart)
            perform(service, recovery.recover(state.lastAction, freshScreen, state.failures), cachedNodes)
            val waitStart = System.currentTimeMillis()
            awaitUiChange(service, "loop_recovery_fp", fingerprint, RECOVERY_SETTLE_MS)
            trace.addWait(System.currentTimeMillis() - waitStart)
            state.invalidateScreenCache()
            state.lastFingerprint = fingerprint
            return null
        }

        // ── Detect no-change stall ────────────────────────────────
        if (state.lastFingerprint != null && fingerprint.isSameScreen(state.lastFingerprint!!)) {
            taskContext.stepsSinceScreenChange++
        } else {
            taskContext.stepsSinceScreenChange = 0
        }

        // ── Build planner request with task context ───────────────
        val decision = planner.nextAction(
            PlannerRequest(
                requestId = requestId,
                goal = goal,
                step = index + 1,
                maxSteps = maxSteps,
                currentPackage = service.currentPackage(),
                screen = screenDesc,
                previousAction = state.lastAction?.javaClass?.simpleName,
                previousResult = state.lastResult,
                failureCount = state.failures,
                actionHistory = state.actionHistory.toList(),
                taskContext = taskContext,
            )
        )

        Log.d(TAG, "[$requestId] step=${index + 1} action=${decision.action::class.simpleName} complete=${decision.isComplete} followUps=${decision.followUpActions.size} reasoning='${decision.reasoning.take(80)}'")

        if (decision.action is DeviceAction.Done) {
            val msg = (decision.action as DeviceAction.Done).message
            if (msg.startsWith("Planner failed")) {
                Log.e(TAG, "[$requestId] PLANNER FAILED at step ${index + 1}: $msg")
                trace.markFailure()
                return DeviceControlResult.Failure(
                    message = msg,
                    steps = index + 1,
                    errorCode = DeviceControlResult.ErrorCode.PLANNER_FAILED,
                    retryable = true,
                )
            }
            Log.i(TAG, "[$requestId] GOAL COMPLETE (Done action) at step ${index + 1}: $msg")
            trace.markSuccess()
            return DeviceControlResult.Success(msg, index + 1)
        }

        // ── Single-action loop detection ──────────────────────────
        updateLoopDetection(state, decision, fingerprint)

        if (state.repeated > 2) {
            Log.w(TAG, "[$requestId] loop detected: action repeated ${state.repeated} times on same screen, triggering recovery")
            state.actionHistory.add("${decision.action::class.simpleName} → LOOP BLOCKED")
            state.lastResult = "Blocked repeated action on same screen."
            state.failures++
            state.lastAction = decision.action
            trace.addRecovery()
            val freshSnapStart = trace.beginSnapshot()
            val freshScreen = observer.describe(service, goal)
            trace.endSnapshot(freshSnapStart)
            perform(service, recovery.recover(state.lastAction, freshScreen, state.failures), cachedNodes)
            val waitStart = System.currentTimeMillis()
            awaitUiChange(service, "loop_recovery", fingerprint, RECOVERY_SETTLE_MS)
            trace.addWait(System.currentTimeMillis() - waitStart)
            state.invalidateScreenCache()
            state.lastFingerprint = fingerprint
            return null
        }

        // ── Execute primary action ────────────────────────────────
        val actionDesc = decision.action.toHumanDescription()
        DeviceControlMonitor.onStepStarted(index + 1, actionDesc)

        val actionStart = trace.beginAction()
        val actionSuccess = perform(service, decision.action, cachedNodes)
        trace.endAction(actionStart)
        state.lastResult = if (actionSuccess) "Action succeeded" else "Action failed"
        state.lastAction = decision.action
        taskContext.completedActions.add(decision.action::class.simpleName ?: "Unknown")
        taskContext.updateSubGoalProgress(decision.action, actionSuccess)

        val actionEntry = formatActionEntry(decision.action, actionSuccess)
        state.actionHistory.add(actionEntry)
        if (state.actionHistory.size > 15) state.actionHistory.removeAt(0)
        Log.d(TAG, "[$requestId] history[${state.actionHistory.size}]: $actionEntry")

        DeviceControlMonitor.onStepFinished(index + 1, actionDesc, actionSuccess)

        if (!actionSuccess) {
            return handleActionFailure(goal, service, decision, fingerprint, taskContext, state, requestId, index, trace)
        }

        state.failures = 0

        // ── Execute follow-up actions (multi-action plan) ─────────
        executeFollowUps(service, decision, taskContext, state, requestId, index, trace, cachedNodes)

        // ── Post-action wait + verification ───────────────────────
        return postActionVerify(goal, service, decision, fingerprint, taskContext, state, requestId, index, trace)
    }

    /** Format a human-readable action entry for the history log. */
    private fun formatActionEntry(action: DeviceAction, success: Boolean): String = buildString {
        append(action::class.simpleName ?: "Unknown")
        when (action) {
            is DeviceAction.OpenApp -> append("(${action.appName})")
            is DeviceAction.ClickText -> append("('${action.text}')")
            is DeviceAction.TypeText -> append("('${action.text}')")
            is DeviceAction.ClickIndex -> append("(#${action.index})")
            is DeviceAction.ClickAt -> append("(${action.x},${action.y})")
            is DeviceAction.Scroll -> append("(${action.direction})")
            else -> {}
        }
        append(" → ${if (success) "OK" else "FAIL"}")
    }

    /** Update loop detection counters after getting a planner decision. */
    private fun updateLoopDetection(
        state: LoopState,
        decision: PlannerDecision,
        fingerprint: ScreenFingerprint,
    ) {
        val currentKey = actionKey(decision.action)
        val screenActionCombo = "${fingerprint.contentHash}::$currentKey"

        val isRepeatedAction = currentKey == state.lastSignature ||
            screenActionCombo in state.actionHistory

        if (isRepeatedAction) {
            state.repeated++
        } else {
            state.repeated = 1
        }

        state.actionHistory.add(screenActionCombo)
        if (state.actionHistory.size > 20) state.actionHistory.removeAt(0)
        state.lastSignature = currentKey
    }

    /** Handle a failed action — trigger recovery or hard-stop. */
    private suspend fun handleActionFailure(
        goal: String,
        service: DeviceControlServiceBridge,
        decision: PlannerDecision,
        fingerprint: ScreenFingerprint,
        taskContext: TaskContext,
        state: LoopState,
        requestId: String,
        index: Int,
        trace: DeviceControlPerformanceTrace,
    ): DeviceControlResult? {
        state.failures++
        val actionEntry = formatActionEntry(decision.action, false)
        taskContext.failedActions.add(actionEntry)

        if (state.failures >= 4) {
            Log.w(TAG, "[$requestId] hard stop: ${state.failures} consecutive failures")
            trace.markFailure()
            return DeviceControlResult.Failure(
                message = "Device control is stuck after repeated failures.",
                steps = index + 1,
                errorCode = DeviceControlResult.ErrorCode.STUCK,
            )
        }

        trace.addRecovery()
        val freshSnapStart = trace.beginSnapshot()
        val freshScreen = observer.describe(service, goal)
        trace.endSnapshot(freshSnapStart)
        perform(service, recovery.recover(state.lastAction, freshScreen, state.failures))
        state.lastFingerprint = fingerprint
        val waitStart = System.currentTimeMillis()
        awaitUiChange(service, "fail_recovery", fingerprint, RECOVERY_SETTLE_MS)
        trace.addWait(System.currentTimeMillis() - waitStart)
        state.invalidateScreenCache()
        return null
    }

    /** Execute bounded follow-up actions from a multi-action planner response. */
    private suspend fun executeFollowUps(
        service: DeviceControlServiceBridge,
        decision: PlannerDecision,
        taskContext: TaskContext,
        state: LoopState,
        requestId: String,
        index: Int,
        trace: DeviceControlPerformanceTrace,
        cachedNodes: List<UiNodeSnapshot>? = null,
    ) {
        if (decision.followUpActions.isEmpty()) return
        val followUps = decision.followUpActions.take(MAX_FOLLOW_UP_ACTIONS)
        Log.d(TAG, "[$requestId] executing ${followUps.size} follow-up actions")

        for (fuAction in followUps) {
            coroutineContext.ensureActive()

            // For TypeText follow-ups, wait for an editable field to appear first
            if (fuAction is DeviceAction.TypeText) {
                val waitStart = System.currentTimeMillis()
                waitForEditableField(service, "fu_edit_$index", 1500L)
                trace.addWait(System.currentTimeMillis() - waitStart)
            }

            val fuStart = trace.beginAction()
            val fuSuccess = perform(service, fuAction, cachedNodes)
            trace.endAction(fuStart)
            taskContext.completedActions.add(fuAction::class.simpleName ?: "Unknown")
            taskContext.updateSubGoalProgress(fuAction, fuSuccess)

            val fuEntry = "${fuAction::class.simpleName ?: "Unknown"} → ${if (fuSuccess) "OK" else "FAIL"}"
            state.actionHistory.add(fuEntry)
            Log.d(TAG, "[$requestId] follow-up: $fuEntry")

            if (!fuSuccess) {
                Log.w(TAG, "[$requestId] follow-up action failed, stopping follow-ups")
                taskContext.failedActions.add(fuEntry)
                break
            }

            val waitStart = System.currentTimeMillis()
            try {
                service.waitForUiChange(
                    "fu_$index",
                    400L,
                    ScreenFingerprint.compute(cachedNodes ?: service.snapshot(), service.currentPackage()),
                )
            } catch (_: Exception) { /* timeout is fine */ }
            trace.addWait(System.currentTimeMillis() - waitStart)
        }
    }

    /**
     * Wait for an editable field to appear in the accessibility tree.
     * Used before TypeText follow-ups to ensure the keyboard has opened.
     */
    private suspend fun waitForEditableField(
        service: DeviceControlServiceBridge,
        tag: String,
        timeoutMs: Long,
    ) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val nodes = service.snapshot()
            val hasEditable = nodes.any { it.editable }
            if (hasEditable) {
                Log.d(TAG, "[$tag] editable field found")
                return
            }
            try {
                service.waitForUiChange(tag, 300L, ScreenFingerprint.compute(nodes, service.currentPackage()))
            } catch (_: Exception) { /* timeout is fine */ }
        }
        Log.w(TAG, "[$tag] timeout waiting for editable field")
    }

    /** Post-action: wait for UI change, verify fingerprint, update task context. */
    private suspend fun postActionVerify(
        goal: String,
        service: DeviceControlServiceBridge,
        decision: PlannerDecision,
        preFingerprint: ScreenFingerprint,
        taskContext: TaskContext,
        state: LoopState,
        requestId: String,
        index: Int,
        trace: DeviceControlPerformanceTrace,
    ): DeviceControlResult? {
        // If an app launch already moved us to a different package, skip the
        // remaining open-app wait window entirely.
        if (decision.action is DeviceAction.OpenApp) {
            val pkgNow = service.currentPackage()
            if (pkgNow != null && pkgNow != taskContext.currentObservedPackage) {
                Log.d(TAG, "[$requestId] package changed to $pkgNow — skipping remaining open-app wait")
                val earlyFp = service.snapshotFingerprint()
                taskContext.currentObservedPackage = pkgNow
                if (taskContext.resolvedPackage == null) taskContext.resolvedPackage = pkgNow
                state.lastFingerprint = earlyFp
                state.invalidateScreenCache()
                trace.addWait(0)
                return null
            }
        }

        val waitMs = delayAfter(decision.action)
        val waitStart = System.currentTimeMillis()
        try {
            service.waitForUiChange("post_$index", waitMs, preFingerprint)
        } catch (_: Exception) { /* timeout is fine */ }
        trace.addWait(System.currentTimeMillis() - waitStart)

        // Use the fast fingerprint-only snapshot for change detection.
        // The full describeWithFingerprint is deferred to the next loop iteration
        // where the LLM needs the screen description.
        val postSnapStart = trace.beginSnapshot()
        val postFingerprint2 = service.snapshotFingerprint()
        trace.endSnapshot(postSnapStart)
        val screenChanged = postFingerprint2.hasChanged(preFingerprint)

        Log.d(TAG, "[$requestId] post-action: pre_fp=${preFingerprint.toLogString()} post_fp=${postFingerprint2.toLogString()} changed=$screenChanged")

        if (decision.isComplete && !screenChanged && decision.action !is DeviceAction.Wait) {
            Log.w(TAG, "[$requestId] planner claimed is_complete but screen unchanged after ${decision.action::class.simpleName}. Continuing loop for re-evaluation.")
            state.actionHistory.add("${decision.action::class.simpleName} → screen unchanged, re-evaluating")
        }

        // The cached screen description is only valid while the screen is
        // untouched; drop it as soon as the action had a visible effect.
        if (screenChanged) state.invalidateScreenCache()

        val currentPkg = service.currentPackage()
        if (currentPkg != null) {
            taskContext.currentObservedPackage = currentPkg
            if (taskContext.resolvedPackage == null && decision.action is DeviceAction.OpenApp) {
                taskContext.resolvedPackage = currentPkg
            }
        }

        state.lastFingerprint = postFingerprint2
        return null
    }

    /**
     * Phase 2: Terminal fast paths and bootstrap optimizations.
     *
     * RULE: If the goal contains ANY comma, conjunction, or additional instruction
     * beyond "open/launch/start [app]", it is COMPOUND and must go to the planner.
     * The fast-path only fires for unambiguous single-action goals.
     */
    private suspend fun classifyAndExecuteFastPath(
        service: DeviceControlServiceBridge,
        goal: String,
        requestId: String,
        trace: DeviceControlPerformanceTrace,
    ): DeviceControlResult? {
        val trimmed = goal.trim()

        val deterministicAttempt = executeDeterministicKickoff(service, trimmed, requestId, trace)
        if (deterministicAttempt.handled) {
            return deterministicAttempt.result
        }

        // ── REJECT compound goals immediately (with Bootstrap App Launch) ──
        if (COMPOUND_CONJUNCTIONS.containsMatchIn(trimmed)) {
            BOOTSTRAP_APP_PATTERN.find(trimmed)?.let { match ->
                val rawAppName = match.groupValues[1].trim()
                val appName = cleanAppName(rawAppName)
                if (appName.length >= 2 && !NON_APP_BOOTSTRAP_TERMS.contains(appName.lowercase(Locale.US))) {
                    Log.i(TAG, "[$requestId] bootstrap app launch triggered for compound goal: '$appName'")
                    val launchResult = launcher.launch(appName)
                    if (launchResult.success) {
                        Log.d(TAG, "[$requestId] bootstrap launch '$appName' succeeded (pkg=${launchResult.packageName}), brief pause for UI render")
                        delay(BOOTSTRAP_SETTLE_MS.coerceAtMost(MAX_BLIND_DELAY_MS))
                    } else {
                        Log.d(TAG, "[$requestId] bootstrap launch '$appName' skipped/failed: ${launchResult.message}")
                    }
                }
            }
            Log.d(TAG, "[$requestId] compound goal routed to planner: '${trimmed.take(80)}'")
            return null
        }

        // ── Terminal fast path: pure system actions ───────────────────────
        SIMPLE_GOAL_PATTERN.matchEntire(trimmed)?.let { match ->
            val action = when (match.groupValues[1].lowercase(Locale.US)) {
                "home" -> DeviceAction.Home
                "back" -> DeviceAction.Back
                "recents", "recent" -> DeviceAction.Recents
                "notifications", "notification" -> DeviceAction.Notifications
                else -> return null
            }
            Log.i(TAG, "[$requestId] terminal fast path: ${action::class.simpleName}")
            val actionStart = trace.beginAction()
            val success = perform(service, action)
            trace.endAction(actionStart)
            return if (success) {
                trace.markSuccess()
                DeviceControlResult.Success("Executed ${action::class.simpleName}.", 1)
            } else {
                trace.markFailure()
                DeviceControlResult.Failure(
                    message = "Failed to execute ${action::class.simpleName}.",
                    steps = 1,
                    errorCode = DeviceControlResult.ErrorCode.ACTION_FAILED,
                )
            }
        }

        // ── Direct element tap fast path ─────────────────────────────────
        DIRECT_CLICK_PATTERN.matchEntire(trimmed)?.let { match ->
            val targetLabel = match.groupValues[1].trim()
            if (!targetLabel.any { !it.isLetterOrDigit() && !it.isWhitespace() }) {
                val nodes = service.snapshot()
                val matchingNode = nodes.firstOrNull {
                    it.clickable && (it.text.equals(targetLabel, true) || it.contentDescription.equals(targetLabel, true))
                }
                if (matchingNode != null) {
                    Log.i(TAG, "[$requestId] direct element tap fast path: ClickText('$targetLabel')")
                    val actionStart = trace.beginAction()
                    val success = service.clickText(targetLabel, nodes)
                    trace.endAction(actionStart)
                    if (success) {
                        trace.markSuccess()
                        return DeviceControlResult.Success("Clicked \"$targetLabel\".", 1)
                    }
                }
            }
        }

        // ── App-open fast path ───────────────────────────────────────────
        val appMatch = TERMINAL_APP_PATTERN.matchEntire(trimmed) ?: return null
        val appName = appMatch.groupValues[1].trim()

        if (appName.any { !it.isLetterOrDigit() && !it.isWhitespace() }) {
            Log.d(TAG, "[$requestId] app name contains punctuation '$appName', routing to planner")
            return null
        }

        if (APP_SUFFIX_INDICATORS.containsMatchIn(appName)) {
            Log.d(TAG, "[$requestId] compound goal with suffix detected '$appName', routing to planner")
            return null
        }

        Log.i(TAG, "[$requestId] terminal app fast path: OpenApp($appName)")
        val actionStart = trace.beginAction()
        val launchResult = launcher.launch(appName)
        trace.endAction(actionStart)

        if (launchResult.success) {
            Log.i(TAG, "[$requestId] fast-path launch OK: pkg=${launchResult.packageName}")
            trace.markSuccess()
            return DeviceControlResult.Success("Opened $appName.", 1)
        }

        Log.w(TAG, "[$requestId] bootstrap OpenApp failed for '$appName': ${launchResult.message} (code=${launchResult.errorCode}), falling back to planner")
        return null
    }

    /**
     * Detect fingerprint loops: A→B→A→B or A→A→A.
     */
    private suspend fun executeDeterministicKickoff(
        service: DeviceControlServiceBridge,
        goal: String,
        requestId: String,
        trace: DeviceControlPerformanceTrace,
    ): FastPathAttempt {
        val youtubeQuery = quickIntents.extractYouTubeQuery(goal)
        if (youtubeQuery != null) {
            val actionStart = trace.beginAction()
            val result = quickIntents.launchYouTubeSearch(youtubeQuery)
            trace.endAction(actionStart)
            if (!result.success) return FastPathAttempt(handled = false)

            Log.i(TAG, "[$requestId] deterministic YouTube kickoff query='${youtubeQuery.take(80)}' pkg=${result.packageName}")
            val waitStart = System.currentTimeMillis()
            awaitUiChange(service, "yt_settle", service.snapshotFingerprint(), LAUNCH_SETTLE_MS)
            trace.addWait(System.currentTimeMillis() - waitStart)

            return if (quickIntents.requiresPlannerAfterYouTubeSearch(goal)) {
                FastPathAttempt(handled = true)
            } else {
                trace.markSuccess()
                FastPathAttempt(
                    handled = true,
                    result = DeviceControlResult.Success(
                        "Opened YouTube search results for \"$youtubeQuery\".",
                        1,
                    ),
                )
            }
        }

        val url = quickIntents.extractUrl(goal) ?: return FastPathAttempt(handled = false)
        val actionStart = trace.beginAction()
        val result = quickIntents.launchUrl(url, goal)
        trace.endAction(actionStart)
        if (!result.success) return FastPathAttempt(handled = false)

        Log.i(TAG, "[$requestId] deterministic URL kickoff url='${url.take(120)}' pkg=${result.packageName}")
        val waitStart = System.currentTimeMillis()
        awaitUiChange(service, "url_settle", service.snapshotFingerprint(), LAUNCH_SETTLE_MS)
        trace.addWait(System.currentTimeMillis() - waitStart)

        return if (quickIntents.requiresPlannerAfterUrlOpen(goal)) {
            FastPathAttempt(handled = true)
        } else {
            trace.markSuccess()
            FastPathAttempt(
                handled = true,
                result = DeviceControlResult.Success("Opened $url.", 1),
            )
        }
    }

    private fun detectFingerprintLoop(history: List<Int>): Boolean {
        if (history.size < 4) return false
        val last4 = history.takeLast(4)
        // A→A→A pattern
        if (last4[0] == last4[1] && last4[1] == last4[2] && last4[2] == last4[3]) return true
        // A→B→A→B pattern
        if (last4[0] == last4[2] && last4[1] == last4[3] && last4[0] != last4[1]) return true
        return false
    }

    private fun actionKey(action: DeviceAction): String = when (action) {
        is DeviceAction.ClickText -> "tap:${action.text.lowercase(Locale.US)}"
        is DeviceAction.ClickAt -> "tap_at:${action.x.toInt()},${action.y.toInt()}"
        is DeviceAction.ClickIndex -> "tap_idx:${action.index}"
        is DeviceAction.TypeText -> "type:${action.text}"
        is DeviceAction.Scroll -> "scroll:${action.direction}"
        is DeviceAction.Swipe -> "swipe"
        is DeviceAction.OpenApp -> "open:${action.appName.lowercase(Locale.US)}"
        is DeviceAction.Wait -> "wait"
        is DeviceAction.ShareFile -> "share"
        is DeviceAction.Done -> "done"
        else -> action.javaClass.simpleName ?: ""
    }

    private suspend fun perform(
        service: DeviceControlServiceBridge,
        action: DeviceAction,
        cachedNodes: List<UiNodeSnapshot>? = null,
    ): Boolean = when (action) {
        is DeviceAction.ClickText -> service.clickText(action.text, cachedNodes)
        is DeviceAction.ClickIndex -> service.clickIndex(action.index, cachedNodes)
        is DeviceAction.ClickAt -> service.clickAt(action.x, action.y)
        is DeviceAction.TypeText -> service.typeText(action.text, action.fieldHint)
        DeviceAction.PressEnter -> service.pressEnter()
        is DeviceAction.Scroll -> service.scroll(action.direction)
        is DeviceAction.Swipe -> service.swipe(action.startX, action.startY, action.endX, action.endY, action.durationMs)
        DeviceAction.Back -> service.back()
        DeviceAction.Home -> service.home()
        DeviceAction.Recents -> service.recents()
        DeviceAction.Notifications -> service.notifications()
        is DeviceAction.OpenApp -> {
            val result = launcher.launch(action.appName, action.packageName)
            Log.d(TAG, "OpenApp '${action.appName}' pkg=${action.packageName} → success=${result.success} resolved=${result.packageName} fg=${result.diagnostics?.foregroundPackageAfterLaunch}")
            result.success
        }
        is DeviceAction.Wait -> { delay(action.millis.coerceIn(100, 10_000)); true }
        is DeviceAction.ShareFile -> fileShare.share(Uri.parse(action.uri), action.mimeType, action.targetPackage)
        is DeviceAction.Done -> true
    }

    /**
     * Adaptive delay: shorter when event-driven waiting is available.
     * The executor will use waitForUiChange instead of a blind sleep.
     */
    private fun delayAfter(action: DeviceAction): Long = when (action) {
        is DeviceAction.OpenApp -> 800
        is DeviceAction.TypeText -> 200
        is DeviceAction.ClickText, is DeviceAction.ClickAt, is DeviceAction.ClickIndex -> 150
        is DeviceAction.Scroll, is DeviceAction.Swipe -> 150
        is DeviceAction.PressEnter -> 200
        else -> 100
    }

    /**
     * Starts the device-control overlay service when SYSTEM_ALERT_WINDOW is
     * granted. The overlay self-dismisses once [DeviceControlMonitor] reports
     * a terminal state; failure to start never blocks device control itself.
     */
    private fun startOverlayIfPermitted() {
        if (!Settings.canDrawOverlays(appContext)) {
            Log.w(TAG, "SYSTEM_ALERT_WINDOW not granted — overlay will be skipped")
            return
        }
        try {
            appContext.startService(Intent(appContext, DeviceControlOverlayService::class.java))
        } catch (e: Exception) {
            Log.w(TAG, "overlay service start failed: ${e.message}")
        }
    }
}
