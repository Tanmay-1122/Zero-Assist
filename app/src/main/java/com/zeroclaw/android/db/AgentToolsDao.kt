/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.db

import com.zeroclaw.android.data.db.agent.AskUserRequestDao
import com.zeroclaw.android.data.db.agent.AgentEscalationDao
import com.zeroclaw.android.data.db.agent.AgentSwarmDao
import com.zeroclaw.android.data.db.agent.LlmTaskDao
import com.zeroclaw.android.data.db.agent.ProjectIntelligenceDao
import com.zeroclaw.android.data.db.agent.AgentToolTraceDao

/**
 * Composite DAO interface for all agent tools.
 *
 * Provides access to individual DAOs for each agent tool type.
 * Used by ZeroClawDatabase to expose all tool DAOs.
 */
interface AgentToolsDao {
    fun askUserDao(): AskUserRequestDao
    fun escalationDao(): AgentEscalationDao
    fun swarmDao(): AgentSwarmDao
    fun taskDao(): LlmTaskDao
    fun projectIntelDao(): ProjectIntelligenceDao
    fun traceDao(): AgentToolTraceDao
}

/**
 * Default implementation of AgentToolsDao.
 *
 * This class is instantiated by Room when creating the database,
 * injecting concrete implementations of each DAO.
 */
class AgentToolsDaoImpl(
    private val askUserRequestDao: AskUserRequestDao,
    private val agentEscalationDao: AgentEscalationDao,
    private val agentSwarmDao: AgentSwarmDao,
    private val llmTaskDao: LlmTaskDao,
    private val projectIntelligenceDao: ProjectIntelligenceDao,
    private val agentToolTraceDao: AgentToolTraceDao,
) : AgentToolsDao {
    override fun askUserDao(): AskUserRequestDao = askUserRequestDao
    override fun escalationDao(): AgentEscalationDao = agentEscalationDao
    override fun swarmDao(): AgentSwarmDao = agentSwarmDao
    override fun taskDao(): LlmTaskDao = llmTaskDao
    override fun projectIntelDao(): ProjectIntelligenceDao = projectIntelligenceDao
    override fun traceDao(): AgentToolTraceDao = agentToolTraceDao
}
