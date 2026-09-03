/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.agents

import com.zeroclaw.android.model.Agent
import com.zeroclaw.android.model.AgentChatMessage
import com.zeroclaw.android.model.ApprovalState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel

/**
 * Pause/resume gate for master-to-sub-agent delegation.
 *
 * The gate emits an approval request into the chat, suspends on a
 * [CompletableDeferred], and resumes once the UI resolves that request.
 */
internal class MasterDelegationGate(
    private val emitMessage: (AgentChatMessage) -> Unit,
    private val updateMessage: (String, (AgentChatMessage) -> AgentChatMessage) -> Unit,
) {
    private val pendingApprovals = LinkedHashMap<String, CompletableDeferred<Boolean>>()

    suspend fun delegateTaskWithApproval(
        masterAgent: Agent,
        subAgent: Agent,
        task: String,
        rejectionResponse: String = "Delegation was rejected. I will re-plan instead.",
    ): Boolean {
        val approvalMessage =
            AgentChatMessage.approvalRequest(
                senderId = masterAgent.id,
                senderName = masterAgent.name,
                senderAvatar = masterAgent.avatar,
                senderColor = masterAgent.accentColor,
                senderRole = masterAgent.role,
                content = task,
                targetAgentId = subAgent.id,
            )
        val approvalDeferred = CompletableDeferred<Boolean>()
        pendingApprovals[approvalMessage.id] = approvalDeferred
        emitMessage(approvalMessage)

        val approved =
            try {
                approvalDeferred.await()
            } finally {
                pendingApprovals.remove(approvalMessage.id)
            }

        if (approved) {
            emitMessage(
                AgentChatMessage.taskAssignment(
                    senderId = masterAgent.id,
                    senderName = masterAgent.name,
                    senderAvatar = masterAgent.avatar,
                    senderColor = masterAgent.accentColor,
                    senderRole = masterAgent.role,
                    content = task,
                    targetAgentId = subAgent.id,
                ),
            )
        } else {
            emitMessage(
                AgentChatMessage.statusUpdate(
                    senderId = masterAgent.id,
                    senderName = masterAgent.name,
                    senderAvatar = masterAgent.avatar,
                    senderColor = masterAgent.accentColor,
                    senderRole = masterAgent.role,
                    content = rejectionResponse,
                ),
            )
        }

        return approved
    }

    fun resolveApproval(messageId: String, approved: Boolean): Boolean {
        val deferred = pendingApprovals[messageId] ?: return false
        updateMessage(messageId) { message ->
            message.copy(
                approvalState = if (approved) ApprovalState.APPROVED else ApprovalState.REJECTED,
            )
        }
        pendingApprovals.remove(messageId)
        deferred.complete(approved)
        return true
    }

    fun cancelPendingApprovals() {
        pendingApprovals.values.forEach { deferred -> deferred.cancel() }
        pendingApprovals.clear()
    }
}
