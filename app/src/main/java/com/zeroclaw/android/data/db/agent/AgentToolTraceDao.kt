/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.db.agent

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.zeroclaw.android.model.AgentToolTrace
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for agent tool trace records (execution debugging).
 *
 * Provides methods to record and query tool execution traces for
 * auditing and performance analysis.
 */
@Dao
interface AgentToolTraceDao {
    /**
     * Insert a new tool execution trace record.
     */
    @Insert
    suspend fun insertTrace(trace: AgentToolTrace)

    /**
     * Get all traces for a specific agent.
     */
    @Query("""
        SELECT * FROM agent_tool_traces
        WHERE agentId = :agentId
        ORDER BY createdAt DESC
        LIMIT :limit
    """)
    suspend fun getTracesByAgent(
        agentId: String,
        limit: Int = 100
    ): List<AgentToolTrace>

    /**
     * Get all traces for a specific tool across all agents.
     */
    @Query("""
        SELECT * FROM agent_tool_traces
        WHERE toolName = :toolName
        ORDER BY createdAt DESC
        LIMIT :limit
    """)
    suspend fun getTracesByTool(
        toolName: String,
        limit: Int = 100
    ): List<AgentToolTrace>

    /**
     * Observe all traces as a Flow.
     */
    @Query("""
        SELECT * FROM agent_tool_traces
        ORDER BY createdAt DESC
    """)
    fun observeTraces(): Flow<List<AgentToolTrace>>

    /**
     * Get failed tool calls for a specific tool.
     */
    @Query("""
        SELECT * FROM agent_tool_traces
        WHERE toolName = :toolName AND status != 'success'
        ORDER BY createdAt DESC
        LIMIT :limit
    """)
    suspend fun getFailedToolCalls(
        toolName: String,
        limit: Int = 50
    ): List<AgentToolTrace>

    /**
     * Delete a trace record.
     */
    @Delete
    suspend fun deleteTrace(trace: AgentToolTrace)

    /**
     * Delete all traces older than a specified timestamp.
     */
    @Query("""
        DELETE FROM agent_tool_traces
        WHERE createdAt < :beforeTimestamp
    """)
    suspend fun deleteOlderThan(beforeTimestamp: String)

    /**
     * Get trace count by status.
     */
    @Query("""
        SELECT status, COUNT(*) as count FROM agent_tool_traces
        GROUP BY status
    """)
    suspend fun getTraceCountByStatus(): List<TraceCountByStatus>

    /**
     * Data class for trace count results.
     */
    data class TraceCountByStatus(
        val status: String,
        val count: Int
    )
}
