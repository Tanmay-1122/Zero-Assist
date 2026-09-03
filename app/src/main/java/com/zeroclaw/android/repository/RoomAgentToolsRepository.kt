/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.repository

import android.content.Context
import com.zeroclaw.android.data.db.agent.AskUserRequestDao
import com.zeroclaw.android.data.db.agent.AgentEscalationDao
import com.zeroclaw.android.data.db.agent.AgentSwarmDao
import com.zeroclaw.android.data.db.agent.LlmTaskDao
import com.zeroclaw.android.data.db.agent.ProjectIntelligenceDao
import com.zeroclaw.android.data.db.agent.AgentToolTraceDao
import com.zeroclaw.android.model.AskUserRequest
import com.zeroclaw.android.model.AgentEscalation
import com.zeroclaw.android.model.AgentSwarm
import com.zeroclaw.android.model.LlmTask
import com.zeroclaw.android.model.ProjectIntelligence
import com.zeroclaw.android.model.AgentToolTrace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import java.util.UUID
import org.json.JSONObject

/**
 * Room-backed implementation of AgentToolsRepository.
 *
 * Handles persistence and retrieval of agent tool invocations with workspace isolation.
 */
class RoomAgentToolsRepository(
    private val askUserRequestDao: AskUserRequestDao,
    private val agentEscalationDao: AgentEscalationDao,
    private val agentSwarmDao: AgentSwarmDao,
    private val llmTaskDao: LlmTaskDao,
    private val projectIntelligenceDao: ProjectIntelligenceDao,
    private val agentToolTraceDao: AgentToolTraceDao,
    context: Context,
) : AgentToolsRepository {

    private val context = context

    // ==================== ask_user ====================

    override suspend fun askUser(
        agentId: String,
        question: String,
        questionType: String,
        choices: List<String>,
        workspaceId: String,
    ): String = withContext(Dispatchers.IO) {
        val requestId = UUID.randomUUID().toString()
        val choicesJson = if (choices.isNotEmpty()) {
            JSONObject().apply {
                put("choices", choices)
            }.toString()
        } else {
            null
        }

        val request = AskUserRequest(
            id = requestId,
            agentId = agentId,
            workspaceId = workspaceId,
            question = question,
            questionType = questionType,
            choicesJson = choicesJson,
            isBlocking = true,
            timeoutSeconds = 300,
            userResponse = null,
            respondedAt = null,
            createdAt = System.currentTimeMillis().toString(),
        )

        askUserRequestDao.insert(request)

        // Write audit trace
        val trace = AgentToolTrace(
            id = UUID.randomUUID().toString(),
            agentId = agentId,
            toolName = "ask_user",
            toolRequestId = requestId,
            status = "created",
            response = null,
            errorMessage = null,
            durationMs = 0,
            createdAt = System.currentTimeMillis().toString(),
        )
        agentToolTraceDao.insertTrace(trace)

        requestId
    }

    override fun observePendingUserAsks(workspaceId: String): Flow<List<AskUserRequest>> {
        return askUserRequestDao.observePendingRequests(workspaceId)
    }

    override suspend fun respondToAsk(requestId: String, response: String) = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis().toString()
        askUserRequestDao.respondToRequest(requestId, response, timestamp)
    }

    // ==================== escalate ====================

    override suspend fun escalateToHuman(
        agentId: String,
        escalationType: String,
        description: String,
        targetRole: String?,
        priority: String,
        workspaceId: String,
    ): String = withContext(Dispatchers.IO) {
        val escalationId = UUID.randomUUID().toString()

        val escalation = AgentEscalation(
            id = escalationId,
            agentId = agentId,
            workspaceId = workspaceId,
            escalationType = escalationType,
            description = description,
            targetRole = targetRole,
            assignedTo = null,
            priority = priority,
            status = "pending",
            resolution = null,
            resolvedAt = null,
            createdAt = System.currentTimeMillis().toString(),
        )

        agentEscalationDao.insert(escalation)

        // Write audit trace
        val trace = AgentToolTrace(
            id = UUID.randomUUID().toString(),
            agentId = agentId,
            toolName = "escalate",
            toolRequestId = escalationId,
            status = "created",
            response = null,
            errorMessage = null,
            durationMs = 0,
            createdAt = System.currentTimeMillis().toString(),
        )
        agentToolTraceDao.insertTrace(trace)

        escalationId
    }

    override fun observePendingEscalations(workspaceId: String): Flow<List<AgentEscalation>> {
        return agentEscalationDao.observePendingEscalations(workspaceId)
    }

    override suspend fun resolveEscalation(
        escalationId: String,
        status: String,
        resolution: String,
    ) = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis().toString()
        agentEscalationDao.resolveEscalation(escalationId, status, resolution, timestamp)
    }

    // ==================== swarm ====================

    override suspend fun createSwarm(
        name: String,
        agentIds: List<String>,
        coordinatorAgentId: String,
        strategy: String,
        workspaceId: String,
    ): String = withContext(Dispatchers.IO) {
        val swarmId = UUID.randomUUID().toString()

        val swarm = AgentSwarm(
            id = swarmId,
            name = name,
            workspaceId = workspaceId,
            agentIds = agentIds,
            coordinatorAgentId = coordinatorAgentId,
            strategy = strategy,
            description = "",
            isActive = true,
            createdAt = System.currentTimeMillis().toString(),
        )

        agentSwarmDao.insert(swarm)

        // Write audit trace
        val trace = AgentToolTrace(
            id = UUID.randomUUID().toString(),
            agentId = coordinatorAgentId,
            toolName = "swarm",
            toolRequestId = swarmId,
            status = "created",
            response = null,
            errorMessage = null,
            durationMs = 0,
            createdAt = System.currentTimeMillis().toString(),
        )
        agentToolTraceDao.insertTrace(trace)

        swarmId
    }

    override fun observeActiveSwarms(workspaceId: String): Flow<List<AgentSwarm>> {
        return agentSwarmDao.observeActiveSwarms(workspaceId)
    }

    override suspend fun getSwarmsByCoordinator(
        agentId: String,
        workspaceId: String,
    ): List<AgentSwarm> = withContext(Dispatchers.IO) {
        agentSwarmDao.getSwarmByCoordinator(workspaceId, agentId)
    }

    override suspend fun deleteSwarm(swarmId: String) = withContext(Dispatchers.IO) {
        agentSwarmDao.delete(agentSwarmDao.getById(swarmId) ?: return@withContext)
    }

    // ==================== llm_task ====================

    override suspend fun createLlmTask(
        agentId: String,
        taskName: String,
        instructions: String,
        context: String?,
        targetModel: String,
        workspaceId: String,
    ): String = withContext(Dispatchers.IO) {
        val taskId = UUID.randomUUID().toString()

        val task = LlmTask(
            id = taskId,
            agentId = agentId,
            workspaceId = workspaceId,
            taskName = taskName,
            description = "",
            instructions = instructions,
            contextData = context,
            targetModel = targetModel,
            priority = "normal",
            status = "pending",
            result = null,
            errorMessage = null,
            estimatedTokens = 5000,
            actualTokens = 0,
            createdAt = System.currentTimeMillis().toString(),
            startedAt = null,
            completedAt = null,
        )

        llmTaskDao.insert(task)

        // Write audit trace
        val trace = AgentToolTrace(
            id = UUID.randomUUID().toString(),
            agentId = agentId,
            toolName = "llm_task",
            toolRequestId = taskId,
            status = "created",
            response = null,
            errorMessage = null,
            durationMs = 0,
            createdAt = System.currentTimeMillis().toString(),
        )
        agentToolTraceDao.insertTrace(trace)

        taskId
    }

    override fun observePendingTasks(workspaceId: String): Flow<List<LlmTask>> {
        return llmTaskDao.observePendingTasks(workspaceId)
    }

    override suspend fun completeLlmTask(
        taskId: String,
        status: String,
        result: String,
        tokensUsed: Int,
    ) = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis().toString()
        llmTaskDao.completeTask(taskId, status, timestamp, timestamp, result, tokensUsed)
    }

    override suspend fun getTotalTokensUsed(workspaceId: String): Int = withContext(Dispatchers.IO) {
        llmTaskDao.getTotalTokensUsed(workspaceId) ?: 0
    }

    // ==================== project_intel ====================

    override suspend fun shareIntelligence(
        sourceWorkspaceId: String,
        targetWorkspaceId: String,
        topicName: String,
        contentSummary: String,
        contentFull: String,
        accessLevel: String,
        workspaceId: String,
    ): String = withContext(Dispatchers.IO) {
        val intelId = UUID.randomUUID().toString()

        val intel = ProjectIntelligence(
            id = intelId,
            sourceWorkspaceId = sourceWorkspaceId,
            targetWorkspaceId = targetWorkspaceId,
            topicName = topicName,
            contentSummary = contentSummary,
            contentFull = contentFull,
            sourceAgentId = null,
            relevanceScore = 0.8f,
            accessLevel = accessLevel,
            createdAt = System.currentTimeMillis().toString(),
            lastAccessedAt = null,
        )

        projectIntelligenceDao.insert(intel)

        // Write audit trace
        val trace = AgentToolTrace(
            id = UUID.randomUUID().toString(),
            agentId = "",
            toolName = "project_intel",
            toolRequestId = intelId,
            status = "created",
            response = null,
            errorMessage = null,
            durationMs = 0,
            createdAt = System.currentTimeMillis().toString(),
        )
        agentToolTraceDao.insertTrace(trace)

        intelId
    }

    override fun observeAccessibleIntelligence(workspaceId: String): Flow<List<ProjectIntelligence>> {
        return projectIntelligenceDao.observeAccessibleIntel(workspaceId)
    }

    override suspend fun searchIntelligenceByTopic(
        workspaceId: String,
        topic: String,
    ): List<ProjectIntelligence> = withContext(Dispatchers.IO) {
        projectIntelligenceDao.searchByTopic(workspaceId, topic)
    }

    override suspend fun recordIntelligenceAccess(intelId: String) = withContext(Dispatchers.IO) {
        val timestamp = System.currentTimeMillis().toString()
        projectIntelligenceDao.recordAccess(intelId, timestamp)
    }

    // ==================== tool_traces ====================

    override suspend fun getAgentToolStats(agentId: String): Map<String, Any> = withContext(Dispatchers.IO) {
        val traces = agentToolTraceDao.getTracesByAgent(agentId, 1000)

        val toolCounts = mutableMapOf<String, Int>()
        val statusCounts = mutableMapOf<String, Int>()

        traces.forEach { trace ->
            toolCounts[trace.toolName] = (toolCounts[trace.toolName] ?: 0) + 1
            statusCounts[trace.status] = (statusCounts[trace.status] ?: 0) + 1
        }

        return@withContext mapOf(
            "totalInvocations" to traces.size,
            "toolCounts" to toolCounts,
            "statusCounts" to statusCounts,
            "lastInvocation" to (traces.firstOrNull()?.createdAt ?: "never"),
        )
    }

    override suspend fun getFailedToolCalls(toolName: String): List<AgentToolTrace> = withContext(Dispatchers.IO) {
        agentToolTraceDao.getFailedToolCalls(toolName)
    }
}
