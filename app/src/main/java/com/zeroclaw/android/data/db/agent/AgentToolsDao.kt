/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.db.agent

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.zeroclaw.android.model.AskUserRequest
import com.zeroclaw.android.model.AgentEscalation
import com.zeroclaw.android.model.AgentSwarm
import com.zeroclaw.android.model.LlmTask
import com.zeroclaw.android.model.ProjectIntelligence
import com.zeroclaw.android.model.AgentToolTrace
import kotlinx.coroutines.flow.Flow

/**
 * Room DAO for AskUserRequest persistence.
 *
 * Tracks user input requests from agents with response and timeout handling.
 */
@Dao
interface AskUserRequestDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(request: AskUserRequest): Long

    @Update
    suspend fun update(request: AskUserRequest)

    @Delete
    suspend fun delete(request: AskUserRequest)

    @Query("SELECT * FROM agent_asks WHERE id = :requestId LIMIT 1")
    suspend fun getById(requestId: String): AskUserRequest?

    @Query("""
        SELECT * FROM agent_asks 
        WHERE workspaceId = :workspaceId AND agentId = :agentId
        ORDER BY createdAt DESC
    """)
    suspend fun getByAgent(workspaceId: String, agentId: String): List<AskUserRequest>

    @Query("""
        SELECT * FROM agent_asks 
        WHERE workspaceId = :workspaceId AND userResponse IS NULL AND isBlocking = 1
        ORDER BY createdAt ASC
    """)
    fun observePendingRequests(workspaceId: String): Flow<List<AskUserRequest>>

    @Query("""
        UPDATE agent_asks 
        SET userResponse = :response, respondedAt = :timestamp
        WHERE id = :requestId
    """)
    suspend fun respondToRequest(requestId: String, response: String, timestamp: String)
}

/**
 * Room DAO for AgentEscalation persistence.
 *
 * Tracks escalations to humans with status and resolution tracking.
 */
@Dao
interface AgentEscalationDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(escalation: AgentEscalation): Long

    @Update
    suspend fun update(escalation: AgentEscalation)

    @Delete
    suspend fun delete(escalation: AgentEscalation)

    @Query("SELECT * FROM agent_escalations WHERE id = :escalationId LIMIT 1")
    suspend fun getById(escalationId: String): AgentEscalation?

    @Query("""
        SELECT * FROM agent_escalations 
        WHERE workspaceId = :workspaceId AND status = 'pending'
        ORDER BY priority DESC, createdAt ASC
    """)
    fun observePendingEscalations(workspaceId: String): Flow<List<AgentEscalation>>

    @Query("""
        SELECT * FROM agent_escalations 
        WHERE workspaceId = :workspaceId AND agentId = :agentId
        ORDER BY createdAt DESC
    """)
    suspend fun getByAgent(workspaceId: String, agentId: String): List<AgentEscalation>

    @Query("""
        UPDATE agent_escalations 
        SET status = :status, resolution = :resolution, resolvedAt = :timestamp
        WHERE id = :escalationId
    """)
    suspend fun resolveEscalation(
        escalationId: String,
        status: String,
        resolution: String,
        timestamp: String,
    )
}

/**
 * Room DAO for AgentSwarm persistence.
 *
 * Manages multi-agent swarm configurations and coordination.
 */
@Dao
interface AgentSwarmDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(swarm: AgentSwarm): Long

    @Update
    suspend fun update(swarm: AgentSwarm)

    @Delete
    suspend fun delete(swarm: AgentSwarm)

    @Query("SELECT * FROM agent_swarms WHERE id = :swarmId LIMIT 1")
    suspend fun getById(swarmId: String): AgentSwarm?

    @Query("""
        SELECT * FROM agent_swarms 
        WHERE workspaceId = :workspaceId AND isActive = 1
        ORDER BY name
    """)
    fun observeActiveSwarms(workspaceId: String): Flow<List<AgentSwarm>>

    @Query("""
        SELECT * FROM agent_swarms 
        WHERE workspaceId = :workspaceId
        ORDER BY name
    """)
    suspend fun getAllByWorkspace(workspaceId: String): List<AgentSwarm>

    @Query("""
        SELECT * FROM agent_swarms 
        WHERE workspaceId = :workspaceId AND coordinatorAgentId = :agentId
        ORDER BY name
    """)
    suspend fun getSwarmByCoordinator(workspaceId: String, agentId: String): List<AgentSwarm>
}

/**
 * Room DAO for LlmTask persistence.
 *
 * Tracks dynamic LLM task creation and execution.
 */
@Dao
interface LlmTaskDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(task: LlmTask): Long

    @Update
    suspend fun update(task: LlmTask)

    @Delete
    suspend fun delete(task: LlmTask)

    @Query("SELECT * FROM llm_tasks WHERE id = :taskId LIMIT 1")
    suspend fun getById(taskId: String): LlmTask?

    @Query("""
        SELECT * FROM llm_tasks 
        WHERE workspaceId = :workspaceId AND status NOT IN ('completed', 'failed', 'cancelled')
        ORDER BY priority DESC, createdAt ASC
    """)
    fun observePendingTasks(workspaceId: String): Flow<List<LlmTask>>

    @Query("""
        SELECT * FROM llm_tasks 
        WHERE workspaceId = :workspaceId AND agentId = :agentId
        ORDER BY createdAt DESC
    """)
    suspend fun getByAgent(workspaceId: String, agentId: String): List<LlmTask>

    @Query("""
        UPDATE llm_tasks 
        SET status = :status, startedAt = :startedAt, completedAt = :completedAt, 
            result = :result, actualTokens = :actualTokens
        WHERE id = :taskId
    """)
    suspend fun completeTask(
        taskId: String,
        status: String,
        startedAt: String,
        completedAt: String,
        result: String,
        actualTokens: Int,
    )

    @Query("SELECT SUM(actualTokens) FROM llm_tasks WHERE workspaceId = :workspaceId")
    suspend fun getTotalTokensUsed(workspaceId: String): Int?
}

/**
 * Room DAO for ProjectIntelligence persistence.
 *
 * Cross-workspace knowledge sharing and access control.
 */
@Dao
interface ProjectIntelligenceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(intel: ProjectIntelligence): Long

    @Update
    suspend fun update(intel: ProjectIntelligence)

    @Delete
    suspend fun delete(intel: ProjectIntelligence)

    @Query("SELECT * FROM project_intel WHERE id = :intelId LIMIT 1")
    suspend fun getById(intelId: String): ProjectIntelligence?

    @Query("""
        SELECT * FROM project_intel 
        WHERE (targetWorkspaceId = '*' OR targetWorkspaceId = :workspaceId OR targetWorkspaceId LIKE '%' || :workspaceId || '%')
        AND accessLevel IN ('shared', 'public')
        ORDER BY relevanceScore DESC, lastAccessedAt DESC
    """)
    fun observeAccessibleIntel(workspaceId: String): Flow<List<ProjectIntelligence>>

    @Query("""
        SELECT * FROM project_intel 
        WHERE sourceWorkspaceId = :workspaceId
        ORDER BY createdAt DESC
    """)
    suspend fun getBySourceWorkspace(workspaceId: String): List<ProjectIntelligence>

    @Query("""
        SELECT * FROM project_intel 
        WHERE topicName LIKE '%' || :topic || '%'
        AND (targetWorkspaceId = '*' OR targetWorkspaceId = :workspaceId)
        ORDER BY relevanceScore DESC
    """)
    suspend fun searchByTopic(workspaceId: String, topic: String): List<ProjectIntelligence>

    @Query("""
        UPDATE project_intel 
        SET lastAccessedAt = :timestamp
        WHERE id = :intelId
    """)
    suspend fun recordAccess(intelId: String, timestamp: String)
}
