/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.agents

import com.zeroclaw.android.model.Agent
import com.zeroclaw.android.model.AgentMessageType
import com.zeroclaw.android.model.AgentRole
import com.zeroclaw.android.model.ApprovalState
import kotlinx.coroutines.async
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MasterDelegationGateTest {
    private val masterAgent =
        Agent(
            id = "master-1",
            name = "Master",
            provider = "openai",
            modelName = "gpt-4.1",
            role = AgentRole.MASTER,
            avatar = "\uD83D\uDC51",
            isMaster = true,
            accentColor = 0xFFB88918,
        )

    private val coderAgent =
        Agent(
            id = "coder-1",
            name = "Coder",
            provider = "openai",
            modelName = "gpt-4.1",
            role = AgentRole.CODER,
            avatar = "\uD83D\uDCBB",
            accentColor = 0xFF0074D9,
        )

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `approval emits request then task assignment when approved`() =
        runTest {
            val messages = mutableListOf<com.zeroclaw.android.model.AgentChatMessage>()
            val gate = buildGate(messages)

            val result =
                async {
                    gate.delegateTaskWithApproval(
                        masterAgent = masterAgent,
                        subAgent = coderAgent,
                        task = "Implement the new onboarding flow.",
                    )
                }

            runCurrent()
            val approvalMessage = messages.single()
            assertEquals(AgentMessageType.APPROVAL_REQUEST, approvalMessage.messageType)

            assertTrue(gate.resolveApproval(approvalMessage.id, approved = true))
            assertTrue(result.await())
            assertEquals(
                listOf(AgentMessageType.APPROVAL_REQUEST, AgentMessageType.TASK_ASSIGNMENT),
                messages.map { it.messageType },
            )
            assertEquals(ApprovalState.APPROVED, messages.first().approvalState)
        }

    @Test
    @OptIn(ExperimentalCoroutinesApi::class)
    fun `rejection resumes pipeline and emits master replan status`() =
        runTest {
            val messages = mutableListOf<com.zeroclaw.android.model.AgentChatMessage>()
            val gate = buildGate(messages)

            val result =
                async {
                    gate.delegateTaskWithApproval(
                        masterAgent = masterAgent,
                        subAgent = coderAgent,
                        task = "Deploy the patch to production.",
                    )
                }

            runCurrent()
            val approvalMessage = messages.single()
            assertTrue(gate.resolveApproval(approvalMessage.id, approved = false))

            assertFalse(result.await())
            assertEquals(
                listOf(AgentMessageType.APPROVAL_REQUEST, AgentMessageType.STATUS_UPDATE),
                messages.map { it.messageType },
            )
            assertEquals(ApprovalState.REJECTED, messages.first().approvalState)
            assertTrue(messages.last().content.contains("re-plan", ignoreCase = true))
        }

    @Test
    fun `resolveApproval returns false for unknown message`() {
        val gate = buildGate(mutableListOf())
        assertFalse(gate.resolveApproval("missing", approved = true))
    }

    private fun buildGate(
        messages: MutableList<com.zeroclaw.android.model.AgentChatMessage>,
    ): MasterDelegationGate =
        MasterDelegationGate(
            emitMessage = { message -> messages += message },
            updateMessage = { messageId, update ->
                val index = messages.indexOfFirst { it.id == messageId }
                if (index >= 0) {
                    messages[index] = update(messages[index])
                }
            },
        )
}
