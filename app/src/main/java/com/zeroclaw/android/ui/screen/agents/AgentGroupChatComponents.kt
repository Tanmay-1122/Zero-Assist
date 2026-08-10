/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.agents

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.model.AgentChatMessage
import com.zeroclaw.android.model.AgentLiveState
import com.zeroclaw.android.model.AgentMessageType
import com.zeroclaw.android.model.AgentRole
import com.zeroclaw.android.model.AgentStatus
import com.zeroclaw.android.model.ApprovalState
import com.zeroclaw.android.ui.component.LinkifiedText
import com.zeroclaw.android.ui.screen.terminal.StreamingPhase
import com.zeroclaw.android.ui.screen.terminal.StreamingState
import com.zeroclaw.android.ui.screen.terminal.ThinkingCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Main group chat message list.
 */
@Composable
fun AgentGroupChatMessageList(
    messages: List<AgentChatMessage>,
    typingAgentIds: Set<String>,
    agentNameForId: (String?) -> String,
    onApproveMessage: (String) -> Unit,
    onRejectMessage: (String) -> Unit,
    liveStates: Map<String, AgentLiveState> = emptyMap(),
    onCancelAgent: (String) -> Unit = {},
    masterAgentId: String? = null,
    masterStreamingState: StreamingState,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val typingAgents =
        typingAgentIds
            .map { agentId -> agentId to agentNameForId(agentId) }
            .sortedBy { (_, agentName) -> agentName }
    val lastMessage = messages.lastOrNull()
    val lastMessageContentLength = lastMessage?.content?.length ?: 0
    val lastMessageStreaming = lastMessage?.isStreaming == true

    LaunchedEffect(messages.size, typingAgents.size, lastMessageContentLength, lastMessageStreaming) {
        val lastIndex = messages.lastIndex + typingAgents.size
        if (lastIndex >= 0) {
            listState.animateScrollToItem(lastIndex)
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 16.dp),
    ) {
        itemsIndexed(
            items = messages,
            key = { index, message -> agentChatMessageRenderKey(message, index) },
        ) { _, message ->
            AgentMessageBubble(
                message = message,
                agentNameForId = agentNameForId,
                onApprove = { onApproveMessage(message.id) },
                onReject = { onRejectMessage(message.id) },
            )
        }

        items(typingAgents, key = { (agentId, _) -> "thinking-$agentId" }) { (agentId, agentName) ->
            val liveState = liveStates[agentId]
            val isMaster = agentId == masterAgentId

            if (isMaster) {
                // Full ThinkingCard for the default/master agent (same as terminal)
                val phase = if (masterStreamingState.phase != StreamingPhase.IDLE && masterStreamingState.phase != StreamingPhase.COMPLETE) {
                    masterStreamingState.phase
                } else {
                    liveState?.let { agentStatusToStreamingPhase(it.status) } ?: StreamingPhase.THINKING
                }
                
                ThinkingCard(
                    thinkingText = masterStreamingState.thinkingText.ifEmpty { liveState?.currentTask ?: "" },
                    visible = true,
                    onCancel = { onCancelAgent(agentId) },
                    activeTools = masterStreamingState.activeTools,
                    toolResults = masterStreamingState.toolResults,
                    phase = phase,
                    providerRound = masterStreamingState.providerRound,
                    toolCallCount = masterStreamingState.toolCallCount,
                    llmDurationSecs = masterStreamingState.llmDurationSecs,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            } else {
                // Compact typing pill for sub-agents
                AgentTypingPill(
                    agentName = agentName,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }
        }
    }
}

/**
 * Single message bubble in the group chat.
 */
@Composable
fun AgentMessageBubble(
    message: AgentChatMessage,
    agentNameForId: (String?) -> String,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (message.messageType == AgentMessageType.APPROVAL_REQUEST) {
        ApprovalMessageBubble(
            message = message,
            agentNameForId = agentNameForId,
            onApprove = onApprove,
            onReject = onReject,
            modifier = modifier,
        )
        return
    }

    if (message.senderId == "system" || message.messageType == AgentMessageType.SYSTEM_EVENT) {
        SystemEventMessage(message = message, modifier = modifier)
        return
    }

    if (message.messageType == AgentMessageType.ON_DEVICE_RESULT) {
        OnDeviceResultMessage(message = message, modifier = modifier)
        return
    }

    val isUserMessage = message.senderId == "user"
    val isMasterMessage = message.senderRole == AgentRole.MASTER
    val bubbleWidth = if (isUserMessage) 0.82f else 0.88f
    val borderColor =
        when {
            isUserMessage -> Color.Transparent
            isMasterMessage -> MaterialTheme.colorScheme.primary
            else -> Color(message.senderColor)
        }

    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = if (isUserMessage) Alignment.End else Alignment.Start,
    ) {
        if (!isUserMessage) {
            SenderTag(
                message = message,
                modifier = Modifier
                    .fillMaxWidth(bubbleWidth)
                    .padding(horizontal = 12.dp, vertical = 2.dp),
            )
        }

        Surface(
            shape = RoundedCornerShape(18.dp),
            color = bubbleContainerColor(message = message, isMasterMessage = isMasterMessage),
            border =
                if (borderColor == Color.Transparent) {
                    null
                } else {
                    BorderStroke(1.5.dp, borderColor)
                },
            modifier = Modifier
                .fillMaxWidth(bubbleWidth)
                .padding(horizontal = 12.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            ) {
                if (message.targetAgentId != null && !isUserMessage) {
                    Text(
                        text = "To ${agentNameForId(message.targetAgentId)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = bubbleContentColor(message, isMasterMessage).copy(alpha = 0.72f),
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }

                message.effectiveBlocks.forEach { block ->
                    androidx.compose.runtime.key(block.blockId) {
                        RenderContentBlock(
                            block = block,
                            textColor = bubbleContentColor(message, isMasterMessage),
                            isStreaming = message.isStreaming,
                        )
                    }
                }

                Text(
                    text = formatMessageTime(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = bubbleContentColor(message, isMasterMessage).copy(alpha = 0.62f),
                    modifier = Modifier
                        .align(if (isUserMessage) Alignment.End else Alignment.Start)
                        .padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun OnDeviceResultMessage(
    message: AgentChatMessage,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.12f),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.45f)),
        ) {
            Text(
                text = message.senderName,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }

        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.32f)),
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(top = 6.dp),
        ) {
            Column(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                message.effectiveBlocks.forEach { block ->
                    androidx.compose.runtime.key(block.blockId) {
                        RenderContentBlock(
                            block = block,
                            textColor = MaterialTheme.colorScheme.onSurface,
                            isStreaming = message.isStreaming,
                        )
                    }
                }
                Text(
                    text = formatMessageTime(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun SenderTag(
    message: AgentChatMessage,
    modifier: Modifier = Modifier,
) {
    val isMasterMessage = message.senderRole == AgentRole.MASTER
    val accentColor = if (isMasterMessage) MaterialTheme.colorScheme.primary else Color(message.senderColor)
    val backgroundColor = accentColor.copy(alpha = 0.14f)
    val label = if (isMasterMessage) "Master" else message.senderName

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Surface(
            shape = RoundedCornerShape(999.dp),
            color = backgroundColor,
            border = BorderStroke(1.dp, accentColor.copy(alpha = 0.55f)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(
                    text = message.senderRole.icon,
                    style = MaterialTheme.typography.labelLarge,
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = accentColor,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Text(
            text = formatMessageTime(message.timestamp),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SystemEventMessage(
    message: AgentChatMessage,
    modifier: Modifier = Modifier,
) {
    LinkifiedText(
        text = message.content,
        style =
            MaterialTheme.typography.labelMedium.copy(
                fontStyle = FontStyle.Italic,
                textAlign = TextAlign.Center,
            ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 4.dp),
    )
}

@Composable
private fun ApprovalMessageBubble(
    message: AgentChatMessage,
    agentNameForId: (String?) -> String,
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        SenderTag(
            message = message,
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .padding(bottom = 4.dp),
        )

        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.42f),
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth(0.9f),
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = "Approval Required",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "${message.senderName} -> ${agentNameForId(message.targetAgentId)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinkifiedText(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                when (message.approvalState) {
                    ApprovalState.PENDING -> ApprovalControl(
                        onApprove = onApprove,
                        onReject = onReject,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    ApprovalState.APPROVED, ApprovalState.REJECTED -> ApprovalStateIndicator(
                        state = message.approvalState,
                        modifier = Modifier.fillMaxWidth(),
                    )

                    else -> Unit
                }
            }
        }
    }
}

@Composable
private fun ApprovalControl(
    onApprove: () -> Unit,
    onReject: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onApprove,
            modifier = Modifier
                .weight(1f)
                .height(42.dp),
        ) {
            Text("\u2713 Approve")
        }
        OutlinedButton(
            onClick = onReject,
            modifier = Modifier
                .weight(1f)
                .height(42.dp),
        ) {
            Text("\u2717 Reject")
        }
    }
}

@Composable
private fun ApprovalStateIndicator(
    state: ApprovalState,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        when (state) {
            ApprovalState.APPROVED -> {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Approved",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "Approved",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            ApprovalState.REJECTED -> {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Rejected",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = "Rejected",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            else -> Unit
        }
    }
}

/**
 * Compact "agent is thinking" pill shown for non-master (sub) agents.
 */
@Composable
private fun AgentTypingPill(
    agentName: String,
    modifier: Modifier = Modifier,
) {
    val transition = rememberInfiniteTransition(label = "typing-dots")
    val dotProgress = transition.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "dot-progress",
    )
    val dots = ".".repeat(dotProgress.value.toInt().coerceIn(1, 3))
    Text(
        text = "[$agentName$dots]",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontStyle = FontStyle.Italic,
        modifier = modifier.fillMaxWidth(),
    )
}

/**
 * Maps [AgentStatus] to a [StreamingPhase] so the shared [ThinkingCard]
 * can display the correct header label for each agent.
 */
private fun agentStatusToStreamingPhase(status: AgentStatus): StreamingPhase =
    when (status) {
        AgentStatus.THINKING   -> StreamingPhase.THINKING
        AgentStatus.EXECUTING  -> StreamingPhase.TOOL_EXECUTING
        AgentStatus.DELEGATING -> StreamingPhase.CALLING_PROVIDER
        AgentStatus.WAITING    -> StreamingPhase.THINKING
        AgentStatus.ERROR      -> StreamingPhase.ERROR
        AgentStatus.DONE       -> StreamingPhase.COMPLETE
        AgentStatus.IDLE       -> StreamingPhase.IDLE
    }

@Composable
private fun streamingText(
    content: String,
    isStreaming: Boolean,
): String {
    if (!isStreaming) {
        return content
    }

    val transition = rememberInfiniteTransition(label = "streaming-cursor")
    val cursorAlpha =
        transition.animateFloat(
            initialValue = 0.2f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 650),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "cursor-alpha",
        )

    return buildString {
        append(content)
        if (cursorAlpha.value > 0.5f) {
            append(" ")
            append("\u258B")
        }
    }
}

@Composable
private fun bubbleContainerColor(
    message: AgentChatMessage,
    isMasterMessage: Boolean,
): Color {
    return when {
        message.senderId == "user" -> MaterialTheme.colorScheme.primaryContainer
        isMasterMessage -> MaterialTheme.colorScheme.primaryContainer
        message.messageType == AgentMessageType.TASK_ASSIGNMENT ->
            Color(message.senderColor).copy(alpha = 0.12f)
        else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    }
}

@Composable
private fun bubbleContentColor(
    message: AgentChatMessage,
    isMasterMessage: Boolean,
): Color {
    return when {
        message.senderId == "user" -> MaterialTheme.colorScheme.onPrimaryContainer
        isMasterMessage -> MaterialTheme.colorScheme.onPrimaryContainer
        else -> MaterialTheme.colorScheme.onSurface
    }
}

private fun formatMessageTime(timestamp: Long): String {
    val format = SimpleDateFormat("HH:mm", Locale.getDefault())
    return format.format(Date(timestamp))
}

internal fun agentChatMessageRenderKey(
    message: AgentChatMessage,
    index: Int,
): String = "agent-chat:${message.id}:${message.timestamp}:${message.messageType}:$index"
