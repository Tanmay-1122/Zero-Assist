package com.zeroclaw.android.service.devicecontrol

import java.util.Locale

/**
 * Persistent, mutable context that survives across planner steps within
 * a single device-control request.
 *
 * The executor populates this once at the start and updates it after
 * each action. The planner prompt builder reads it to maintain clear
 * sub-goal progress tracking for complex multi-step tasks.
 */
class TaskContext(
    val originalGoal: String,
) {
    /** Parsed intent category (e.g. "send_message", "open_app", "navigate", "multi_step"). */
    var intentCategory: String? = null

    /** Target entity extracted from the goal (e.g. contact name "Tanmay"). */
    var target: String? = null

    /** Message/body content for messaging tasks. */
    var messageContent: String? = null

    /** Resolved app package name, set once after first open_app. */
    var resolvedPackage: String? = null

    /** Resolved app human-readable name. */
    var resolvedAppName: String? = null

    /** Package name observed at each step for stale-state detection. */
    var currentObservedPackage: String? = null

    /** Fingerprint of the last observed screen (int hash). */
    var lastScreenFingerprint: Int = 0

    /** Steps since the screen last meaningfully changed. */
    var stepsSinceScreenChange: Int = 0

    /** Actions that have already been attempted and failed (for avoidance). */
    val failedActions = mutableListOf<String>()

    /** Actions that have been successfully executed (for completion checks). */
    val completedActions = mutableListOf<String>()

    /**
     * Sub-goals automatically decomposed from complex compound goals.
     */
    val subGoals = mutableListOf<SubGoal>()

    data class SubGoal(
        val index: Int,
        val description: String,
        var status: Status = Status.PENDING,
    ) {
        enum class Status { PENDING, IN_PROGRESS, COMPLETED, FAILED }
    }

    /**
     * Updates sub-goal status after an action is executed.
     */
    fun updateSubGoalProgress(action: DeviceAction, actionSuccess: Boolean) {
        if (subGoals.isEmpty()) return

        val active = subGoals.firstOrNull { it.status == SubGoal.Status.IN_PROGRESS } ?: return

        if (!actionSuccess) {
            val failCount = failedActions.count { it.contains(action::class.simpleName ?: "") }
            if (failCount >= 3) {
                active.status = SubGoal.Status.FAILED
                advanceNextSubGoal()
            }
            return
        }

        val descLower = active.description.lowercase(Locale.US)
        val actionName = action::class.simpleName ?: ""

        val completed = when {
            action is DeviceAction.OpenApp -> descLower.contains("open") || descLower.contains("launch") || descLower.contains("start")
            action is DeviceAction.ClickText || action is DeviceAction.ClickIndex || action is DeviceAction.ClickAt -> {
                descLower.contains("click") || descLower.contains("tap") || descLower.contains("take") || descLower.contains("select") || descLower.contains("open") || descLower.contains("press")
            }
            action is DeviceAction.TypeText -> descLower.contains("type") || descLower.contains("search") || descLower.contains("enter") || descLower.contains("send") || descLower.contains("write")
            action is DeviceAction.ShareFile -> descLower.contains("send") || descLower.contains("share")
            else -> false
        }

        if (completed) {
            active.status = SubGoal.Status.COMPLETED
            advanceNextSubGoal()
        }
    }

    private fun advanceNextSubGoal() {
        val next = subGoals.firstOrNull { it.status == SubGoal.Status.PENDING }
        if (next != null) {
            next.status = SubGoal.Status.IN_PROGRESS
        }
    }

    /**
     * Returns a compact summary of accumulated context and sub-goal progress
     * for inclusion in the planner prompt.
     */
    fun toPromptContext(): String = buildString {
        if (subGoals.isNotEmpty()) {
            appendLine("SUB_GOALS_PROGRESS:")
            subGoals.forEach { sg ->
                val marker = when (sg.status) {
                    SubGoal.Status.COMPLETED -> "[✓]"
                    SubGoal.Status.IN_PROGRESS -> "[➜ ACTIVE]"
                    SubGoal.Status.FAILED -> "[✗ FAILED]"
                    SubGoal.Status.PENDING -> "[ ]"
                }
                appendLine("  $marker ${sg.index}. ${sg.description}")
            }
        } else if (intentCategory != null) {
            appendLine("TASK_CONTEXT:")
            appendLine("  intent: $intentCategory")
            if (target != null) appendLine("  target: $target")
            if (messageContent != null) appendLine("  message: \"${messageContent}\"")
            if (resolvedAppName != null) appendLine("  app: $resolvedAppName ($resolvedPackage)")
        }

        if (completedActions.isNotEmpty()) {
            appendLine("COMPLETED_STEPS_COUNT: ${completedActions.size}")
        }
        if (failedActions.isNotEmpty()) {
            appendLine("RECENT_FAILURES: ${failedActions.takeLast(3).joinToString(", ")}")
        }
        if (stepsSinceScreenChange > 2) {
            appendLine("WARNING: Screen has not changed for $stepsSinceScreenChange steps.")
        }
    }

    /**
     * Parses compound goals into explicit sub-goals.
     */
    fun inferFromGoal() {
        val lower = originalGoal.lowercase(Locale.US)

        // Break compound goals on conjunctions, commas, or explicit step dividers
        val rawSteps = originalGoal
            .split(Regex("""(?i)\b(?:and\s+then|then|after\s+that|next|also)\b|,|;"""))
            .map { it.trim() }
            .filter { it.length >= 3 }

        if (rawSteps.size > 1) {
            intentCategory = "multi_step"
            subGoals.clear()
            rawSteps.forEachIndexed { i, desc ->
                subGoals.add(
                    SubGoal(
                        index = i + 1,
                        description = desc,
                        status = if (i == 0) SubGoal.Status.IN_PROGRESS else SubGoal.Status.PENDING,
                    )
                )
            }
            return
        }

        // Messaging pattern: "message <target> <content>" or "text <target> <content>"
        val msgMatch = Regex(
            """(?:message|text|send\s+(?:a\s+)?(?:message|msg)\s+to)\s+(\S+)(?:\s+(.+))?""",
            RegexOption.IGNORE_CASE,
        ).find(originalGoal)
        if (msgMatch != null) {
            intentCategory = "send_message"
            target = msgMatch.groupValues[1]
            messageContent = msgMatch.groupValues[2].trim().ifEmpty { null }
            return
        }

        // Open app pattern: "open/launch/start <app>"
        val openMatch = Regex(
            """(?:open|launch|start)\s+(?:the\s+)?(.+)""",
            RegexOption.IGNORE_CASE,
        ).find(originalGoal)
        if (openMatch != null) {
            val appName = openMatch.groupValues[1].trim()
            if (!Regex("""\b(?:and|then|also|after)\b""", RegexOption.IGNORE_CASE).containsMatchIn(appName)) {
                intentCategory = "open_app"
                resolvedAppName = appName
                return
            }
        }

        // Navigation pattern: "go to <place>", "navigate to <place>"
        val navMatch = Regex(
            """(?:go\s+to|navigate\s+to|open)\s+(.+)""",
            RegexOption.IGNORE_CASE,
        ).find(originalGoal)
        if (navMatch != null) {
            intentCategory = "navigate"
            target = navMatch.groupValues[1].trim()
        }

        // System actions
        when {
            lower.matches(Regex("""(?:go\s+)?home""")) -> intentCategory = "system_home"
            lower.matches(Regex("""back""")) -> intentCategory = "system_back"
            lower.matches(Regex("""recents?""")) -> intentCategory = "system_recents"
        }
    }
}
