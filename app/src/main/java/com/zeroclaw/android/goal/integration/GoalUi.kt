/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.goal.integration

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.goal.GoalStatus
import com.zeroclaw.android.goal.graph.TaskGraph
import com.zeroclaw.android.goal.graph.TaskNode
import com.zeroclaw.android.goal.graph.TaskState
import com.zeroclaw.android.goal.graph.isTerminal
import com.zeroclaw.android.goal.memory.GoalDiagnostics
import com.zeroclaw.android.goal.schedule.ExecutionEvent
import com.zeroclaw.android.goal.schedule.GoalExecutionSnapshot
import com.zeroclaw.android.goal.schedule.GoalScheduler
import com.zeroclaw.android.model.content.AssistantEvent
import com.zeroclaw.android.model.content.ContentBlock
import com.zeroclaw.android.model.content.ToolStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Renderable row for one task in the execution tree.
 */
data class TaskRowUi(
    val taskId: String,
    val capabilityId: String,
    val state: TaskState,
    val progress: Float,
    val retryCount: Int,
    val verified: Boolean?,
    val error: String?,
    val agentId: String?,
)

/**
 * Snapshot of goal execution rendered in the developer dashboard / rich UI.
 */
data class GoalExecutionUiState(
    val goalId: String,
    val goalDescription: String,
    val status: GoalStatus,
    val progress: Float,
    val totalTasks: Int,
    val completedTasks: Int,
    val runningTasks: Int,
    val waitingTasks: Int,
    val rows: List<TaskRowUi>,
    val recoveryEvents: List<String>,
    val timeline: List<String>,
)

/** Maps an immutable scheduler snapshot to a renderable UI state. */
fun GoalExecutionSnapshot.toUiState(): GoalExecutionUiState {
    val rows = graph.nodes.values
        .sortedBy { it.taskId }
        .map { node ->
            TaskRowUi(
                taskId = node.taskId,
                capabilityId = node.capabilityId,
                state = node.state,
                progress = node.progress,
                retryCount = node.retryCount,
                verified = node.result?.verified,
                error = node.result?.error,
                agentId = node.agentId,
            )
        }
    return GoalExecutionUiState(
        goalId = graph.goalId,
        goalDescription = goal.description,
        status = status,
        progress = progress,
        totalTasks = totalTasks,
        completedTasks = completedTasks.size,
        runningTasks = runningTasks.size,
        waitingTasks = waitingTasks.size,
        rows = rows,
        recoveryEvents = GoalDiagnostics.getRecoveryEvents(graph.goalId),
        timeline = GoalDiagnostics.getTimeline(graph.goalId).map { "${it.type}: ${it.detail}" }.takeLast(12),
    )
}

/**
 * Goal execution inspector panel: task tree, statuses, progress, recovery events.
 */
@Composable
fun GoalExecutionPanel(
    state: GoalExecutionUiState?,
    modifier: Modifier = Modifier,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "🎯 Goal Execution Engine",
                style = MaterialTheme.typography.titleMedium,
            )
            if (state == null) {
                Text(
                    text = "No goal execution session active.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
                return@Card
            }

            Text(
                text = "${state.goalDescription} (${state.status.name})",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 8.dp),
            )
            LinearProgressIndicator(
                progress = { state.progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp),
            )
            Text(
                text = "${(state.progress * 100).toInt()}%  •  ${state.completedTasks}/${state.totalTasks} tasks  •  " +
                    "${state.runningTasks} running  •  ${state.waitingTasks} waiting",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )

            // Task tree
            val treeText = state.rows.joinToString("\n") { row ->
                val agent = row.agentId?.let { " [$it]" } ?: ""
                val verify = when (row.verified) {
                    true -> " ✓"
                    false -> " ✗"
                    null -> ""
                }
                val retry = if (row.retryCount > 0) " (retries=${row.retryCount})" else ""
                val err = row.error?.let { " ${it.take(80)}" } ?: ""
                "[${row.state.name}] ${row.taskId} (${row.capabilityId})$agent$verify$retry$err"
            }.ifBlank { "(no tasks yet)" }

            Text(
                text = treeText,
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                modifier = Modifier
                    .padding(top = 8.dp)
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(8.dp),
            )

            if (state.recoveryEvents.isNotEmpty()) {
                Text(
                    text = "Recovery events:",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Row(modifier = Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = state.recoveryEvents.takeLast(4).joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        modifier = Modifier
                            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                            .padding(8.dp),
                    )
                }
            }

            if (state.timeline.isNotEmpty()) {
                Text(
                    text = "Timeline:",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
                Text(
                    text = state.timeline.joinToString("\n"),
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(8.dp),
                )
            }
        }
    }
}

/**
 * Streams goal execution progress into the existing rich runtime (Phase J).
 *
 * Each goal task becomes a [ContentBlock.ToolCard] inside the block tree; a
 * stable task-tree container is updated on every graph mutation so users can
 * observe current/completed/waiting tasks, retries, and verification status.
 *
 * This layer only *emits* into [com.zeroclaw.android.runtime.BlockRuntime];
 * it never rewires the planner, runtime, or block framework.
 */
class GoalRuntimeBridge(
    private val scheduler: GoalScheduler,
    private val conversationId: String,
    private val messageId: String,
    private val runtime: com.zeroclaw.android.runtime.BlockRuntime,
    private val prefix: String = "goaee_",
) {
    private var collectJob: Job? = null

    fun start(scope: CoroutineScope) {
        stop()
        collectJob = scope.launch {
            scheduler.events.collectLatest { event -> handle(event) }
        }
    }

    fun stop() {
        collectJob?.cancel()
        collectJob = null
    }

    private suspend fun handle(event: ExecutionEvent) {
        when (event) {
            is ExecutionEvent.TaskStarted -> {
                runtime.processAssistantEvent(
                    AssistantEvent.BlockStarted(
                        messageId = messageId,
                        conversationId = conversationId,
                        block = toToolCard(
                            event.taskId,
                            event.capabilityId,
                            event.agentId,
                            TaskState.RUNNING,
                        ),
                    ),
                )
            }
            is ExecutionEvent.TaskCompleted -> {
                val block = toToolCard(
                    event.taskId,
                    event.result.capabilityId,
                    event.result.agentId,
                    TaskState.SUCCEEDED,
                    result = event.result.outputJson,
                )
                emitCard(block)
            }
            is ExecutionEvent.TaskFailed -> {
                val block = toToolCard(
                    event.taskId,
                    event.result.capabilityId,
                    event.result.agentId,
                    TaskState.FAILED,
                    result = event.result.error,
                )
                emitCard(block)
            }
            is ExecutionEvent.TaskCancelled -> {
                emitCard(
                    toToolCard(event.taskId, "cancelled", null, TaskState.CANCELLED),
                )
            }
            is ExecutionEvent.TaskSkipped -> {
                emitCard(
                    toToolCard(event.taskId, "skipped", null, TaskState.SKIPPED),
                )
            }
            is ExecutionEvent.GraphUpdated -> {
                val graph = scheduler.snapshotGraph()
                emitTree(graph)
            }
            is ExecutionEvent.Replanned -> {
                GoalDiagnostics.record(event.goalId, "UI_REPLAN", "revision=${event.revision}")
                val graph = scheduler.snapshotGraph()
                emitTree(graph)
            }
            is ExecutionEvent.GoalProgress -> {
                val graph = scheduler.snapshotGraph()
                emitTree(graph)
            }
            is ExecutionEvent.GoalCompleted -> emitFinal(event.goal, "completed")
            is ExecutionEvent.GoalFailed -> emitFinal(event.goal, "failed")
            is ExecutionEvent.GoalCancelled -> emitFinal(event.goal, "cancelled")
            is ExecutionEvent.HumanClarificationRequested -> {
                val callout = ContentBlock.Callout(
                    blockId = "${prefix}clar_${event.request.requestId}",
                    sequenceIndex = 0,
                    kind = "warning",
                    title = "Clarification needed",
                    content = event.request.question,
                )
                runtime.processAssistantEvent(
                    AssistantEvent.BlockStarted(
                        messageId = messageId,
                        conversationId = conversationId,
                        block = callout,
                    ),
                )
            }
            is ExecutionEvent.GoalStarted,
            is ExecutionEvent.TaskVerifying,
            is ExecutionEvent.RecoveryOccurred,
            -> Unit
        }
    }

    private suspend fun emitCard(block: ContentBlock.ToolCard) {
        runtime.processAssistantEvent(
            AssistantEvent.BlockUpdated(
                messageId = messageId,
                conversationId = conversationId,
                block = block,
            ),
        )
        runtime.processAssistantEvent(
            AssistantEvent.BlockFinished(
                messageId = messageId,
                conversationId = conversationId,
                blockId = block.blockId,
            ),
        )
    }

    private fun toToolCard(
        taskId: String,
        capabilityId: String,
        agentId: String?,
        state: TaskState,
        result: String? = null,
    ): ContentBlock.ToolCard {
        val status = when (state) {
            TaskState.SUCCEEDED, TaskState.VERIFIED -> ToolStatus.SUCCESS
            TaskState.FAILED, TaskState.VERIFICATION_FAILED -> ToolStatus.ERROR
            TaskState.CANCELLED, TaskState.SKIPPED -> ToolStatus.CANCELLED
            TaskState.RUNNING, TaskState.RETRYING, TaskState.WAITING_VERIFICATION -> ToolStatus.EXECUTING
            else -> ToolStatus.PENDING
        }
        val resultBlocks = if (result.isNullOrBlank()) {
            emptyList()
        } else {
            listOf(
                ContentBlock.Text(
                    blockId = "${prefix}${taskId}_result",
                    sequenceIndex = 0,
                    text = result.take(2000),
                ),
            )
        }
        return ContentBlock.ToolCard(
            blockId = "${prefix}${taskId}",
            sequenceIndex = 0,
            toolCallId = taskId,
            toolName = capabilityId,
            status = status,
            inputJson = "",
            resultBlocks = resultBlocks,
            executionDurationMs = null,
        )
    }

    /**
     * Emits the current task tree as a stable container block (task tree in Rich UI).
     */
    private suspend fun emitTree(graph: TaskGraph) {
        val progress = snapshotProgress(graph)
        val header = ContentBlock.Callout(
            blockId = "${prefix}header",
            sequenceIndex = 0,
            kind = "info",
            title = "Goal execution — ${(progress * 100).toInt()}%",
            content = "${graph.nodes.values.count { it.state == TaskState.RUNNING || it.state == TaskState.SCHEDULED }} running, " +
                "${graph.nodes.values.count { it.state.isTerminal }} of ${graph.size()} tasks terminal",
        )
        val childCards = graph.nodes.values
            .sortedBy { it.taskId }
            .map { node ->
                toToolCard(node.taskId, node.capabilityId, node.agentId, node.state)
            }
        val container = ContentBlock.Container(
            blockId = "${prefix}tree",
            sequenceIndex = 0,
            layoutType = "column",
            children = listOf(header) + childCards,
        )
        runtime.processAssistantEvent(
            AssistantEvent.BlockUpdated(
                messageId = messageId,
                conversationId = conversationId,
                block = container,
            ),
        )
    }

    private fun snapshotProgress(graph: TaskGraph): Float {
        val total = graph.size()
        if (total == 0) return 0f
        return graph.nodes.values.count { it.state.isTerminal }.toFloat() / total
    }

    private suspend fun emitFinal(goal: com.zeroclaw.android.goal.Goal, outcome: String) {
        val callout = ContentBlock.Callout(
            blockId = "${prefix}final",
            sequenceIndex = 0,
            kind = if (outcome == "completed") "success" else "warning",
            title = "Goal $outcome",
            content = goal.description,
        )
        runtime.processAssistantEvent(
            AssistantEvent.BlockStarted(
                messageId = messageId,
                conversationId = conversationId,
                block = callout,
            ),
        )
        runtime.processAssistantEvent(
            AssistantEvent.BlockFinished(
                messageId = messageId,
                conversationId = conversationId,
                blockId = callout.blockId,
            ),
        )
    }
}
