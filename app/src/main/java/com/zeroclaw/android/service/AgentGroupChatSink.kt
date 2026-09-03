/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.model.Agent

/**
 * Receives visible group-chat events emitted from the daemon/service layer.
 *
 * This keeps the daemon bridge decoupled from Compose while still letting the
 * UI react to multi-agent orchestration events in real time.
 *
 * Only brief, human-readable summaries belong here. Raw prompts, embeddings,
 * full context, and other backend payloads must stay inside the daemon layer.
 * Inter-agent communication should surface as exactly one task assignment,
 * status update, or result summary in the visible chat.
 */
interface AgentGroupChatSink {
    fun onAgentAssigning(fromAgent: Agent, toAgent: Agent, taskSummary: String)

    fun onAgentStatusUpdate(agent: Agent, status: String)

    fun onAgentResult(agent: Agent, summary: String)

    fun onAgentTypingStart(agentId: String)

    fun onAgentTypingStop(agentId: String)

    fun onAgentStreamingChunk(messageId: String, chunk: String)
}
