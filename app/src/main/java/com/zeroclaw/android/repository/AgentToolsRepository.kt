/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.repository

import com.zeroclaw.android.model.AskUserRequest
import com.zeroclaw.android.model.AgentEscalation
import com.zeroclaw.android.model.AgentSwarm
import com.zeroclaw.android.model.LlmTask
import com.zeroclaw.android.model.ProjectIntelligence
import com.zeroclaw.android.model.AgentToolTrace
import kotlinx.coroutines.flow.Flow

/**
 * Repository interface for advanced agent tools and capabilities.
 *
 * Manages ask_user, escalate, swarm coordination, llm_task, and project_intel.
 */
interface AgentToolsRepository {

    // ==================== ask_user ====================

    /**
     * Create a user input request from an agent.
     *
     * @param agentId Agent requesting input.
     * @param question The question to ask.
     * @param questionType Type of answer expected.
     * @param choices List of options (for choice type).
     * @param workspaceId Workspace context.
     * @return Request ID.
     */
    suspend fun askUser(
        agentId: String,
        question: String,
        questionType: String = "text",
        choices: List<String> = emptyList(),
        workspaceId: String = "default",
    ): String

    /**
     * Get pending user input requests.
     *
     * @param workspaceId Workspace to query.
     * @return Flow of pending requests.
     */
    fun observePendingUserAsks(workspaceId: String): Flow<List<AskUserRequest>>

    /**
     * Submit user's response to an ask request.
     *
     * @param requestId Request ID to respond to.
     * @param response User's answer.
     */
    suspend fun respondToAsk(requestId: String, response: String)

    // ==================== escalate ====================

    /**
     * Escalate an issue to a human.
     *
     * @param agentId Agent escalating.
     * @param escalationType Type of escalation.
     * @param description Why it was escalated.
     * @param targetRole Target user role.
     * @param priority Escalation priority.
     * @param workspaceId Workspace context.
     * @return Escalation ID.
     */
    suspend fun escalateToHuman(
        agentId: String,
        escalationType: String,
        description: String,
        targetRole: String? = null,
        priority: String = "normal",
        workspaceId: String = "default",
    ): String

    /**
     * Get pending escalations.
     *
     * @param workspaceId Workspace to query.
     * @return Flow of pending escalations.
     */
    fun observePendingEscalations(workspaceId: String): Flow<List<AgentEscalation>>

    /**
     * Resolve an escalation.
     *
     * @param escalationId Escalation to resolve.
     * @param status Resolution status.
     * @param resolution How it was resolved.
     */
    suspend fun resolveEscalation(escalationId: String, status: String, resolution: String)

    // ==================== swarm ====================

    /**
     * Create a multi-agent swarm.
     *
     * @param name Swarm name.
     * @param agentIds Agent IDs to include.
     * @param coordinatorAgentId Agent that coordinates.
     * @param strategy Coordination strategy.
     * @param workspaceId Workspace context.
     * @return Swarm ID.
     */
    suspend fun createSwarm(
        name: String,
        agentIds: List<String>,
        coordinatorAgentId: String,
        strategy: String = "sequential",
        workspaceId: String = "default",
    ): String

    /**
     * Get all active swarms.
     *
     * @param workspaceId Workspace to query.
     * @return Flow of swarms.
     */
    fun observeActiveSwarms(workspaceId: String): Flow<List<AgentSwarm>>

    /**
     * Get swarms coordinated by an agent.
     *
     * @param agentId Agent ID.
     * @param workspaceId Workspace context.
     * @return List of swarms.
     */
    suspend fun getSwarmsByCoordinator(agentId: String, workspaceId: String = "default"): List<AgentSwarm>

    /**
     * Delete a swarm.
     *
     * @param swarmId Swarm to delete.
     */
    suspend fun deleteSwarm(swarmId: String)

    // ==================== llm_task ====================

    /**
     * Create a dynamic LLM task.
     *
     * @param agentId Agent creating the task.
     * @param taskName Task name.
     * @param instructions Task instructions.
     * @param context Context data for the task.
     * @param targetModel LLM model to use.
     * @param workspaceId Workspace context.
     * @return Task ID.
     */
    suspend fun createLlmTask(
        agentId: String,
        taskName: String,
        instructions: String,
        context: String? = null,
        targetModel: String = "gpt4",
        workspaceId: String = "default",
    ): String

    /**
     * Get pending LLM tasks.
     *
     * @param workspaceId Workspace to query.
     * @return Flow of pending tasks.
     */
    fun observePendingTasks(workspaceId: String): Flow<List<LlmTask>>

    /**
     * Complete an LLM task.
     *
     * @param taskId Task to complete.
     * @param status Final status.
     * @param result Task result/output.
     * @param tokensUsed Tokens consumed.
     */
    suspend fun completeLlmTask(
        taskId: String,
        status: String,
        result: String,
        tokensUsed: Int,
    )

    /**
     * Get token usage statistics.
     *
     * @param workspaceId Workspace to query.
     * @return Total tokens used.
     */
    suspend fun getTotalTokensUsed(workspaceId: String): Int

    // ==================== project_intel ====================

    /**
     * Share knowledge across workspaces.
     *
     * @param sourceWorkspaceId Source workspace.
     * @param targetWorkspaceId Target workspace(s) ("*" for all).
     * @param topicName Knowledge topic.
     * @param contentSummary Brief summary.
     * @param contentFull Full content.
     * @param accessLevel Access level.
     * @param workspaceId Workspace creating the intel.
     * @return Intel ID.
     */
    suspend fun shareIntelligence(
        sourceWorkspaceId: String,
        targetWorkspaceId: String,
        topicName: String,
        contentSummary: String,
        contentFull: String,
        accessLevel: String = "workspace",
        workspaceId: String = "default",
    ): String

    /**
     * Get accessible intelligence.
     *
     * @param workspaceId Workspace to query.
     * @return Flow of accessible intel.
     */
    fun observeAccessibleIntelligence(workspaceId: String): Flow<List<ProjectIntelligence>>

    /**
     * Search intelligence by topic.
     *
     * @param workspaceId Workspace context.
     * @param topic Topic to search for.
     * @return List of matching intel.
     */
    suspend fun searchIntelligenceByTopic(
        workspaceId: String,
        topic: String,
    ): List<ProjectIntelligence>

    /**
     * Record that intelligence was accessed.
     *
     * @param intelId Intel ID accessed.
     */
    suspend fun recordIntelligenceAccess(intelId: String)

    // ==================== tool_traces ====================

    /**
     * Get tool statistics for an agent.
     *
     * @param agentId Agent ID.
     * @return Tool usage statistics.
     */
    suspend fun getAgentToolStats(agentId: String): Map<String, Any>

    /**
     * Get failed tool calls for debugging.
     *
     * @param toolName Tool name to filter by.
     * @return Failed tool calls.
     */
    suspend fun getFailedToolCalls(toolName: String): List<AgentToolTrace>
}
