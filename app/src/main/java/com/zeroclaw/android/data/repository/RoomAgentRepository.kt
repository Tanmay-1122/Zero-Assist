/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.repository

import androidx.room.withTransaction
import com.zeroclaw.android.backup.SyncRepository
import com.zeroclaw.android.data.local.SeedData
import com.zeroclaw.android.data.local.ZeroClawDatabase
import com.zeroclaw.android.data.local.entity.toEntity
import com.zeroclaw.android.data.local.entity.toModel
import com.zeroclaw.android.model.Agent
import com.zeroclaw.android.model.AgentRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Room-backed [AgentRepository] implementation.
 *
 * Delegates all persistence operations to [AgentDao] and maps between
 * entity and domain model layers.
 *
 * @param database The Room database instance for transactions.
 */
class RoomAgentRepository(
    private val database: ZeroClawDatabase,
    private val syncRepository: SyncRepository? = null,
) : AgentRepository {
    private val dao = database.agentDao()
    override val agents: Flow<List<Agent>> =
        dao.observeAll().map { entities -> entities.map { it.toModel() } }

    override val masterAgent: Flow<Agent?> =
        dao.observeMasterAgent().map { it?.toModel() }

    override val activeAgentsByPriority: Flow<List<Agent>> =
        dao.observeActiveAgentsByPriority().map { entities -> entities.map { it.toModel() } }

    override suspend fun getById(id: String): Agent? = dao.getById(id)?.toModel()

    override suspend fun save(agent: Agent) {
        if (!agent.isMaster) {
            dao.upsertAgent(agent.toEntity())
            syncRepository?.markPendingSync()
            return
        }

        database.withTransaction {
            dao.unsetAllMasters()
            dao.upsertAgent(agent.toEntity())
        }
        syncRepository?.markPendingSync()
    }

    override suspend fun delete(id: String) {
        dao.deleteById(id)
        syncRepository?.markPendingSync()
    }

    override suspend fun toggleEnabled(id: String) {
        dao.toggleEnabled(id)
        syncRepository?.markPendingSync()
    }

    /**
     * Seeds the agents table with default agents if it is empty.
     *
     * Uses [insertAllIgnoreConflicts] so that existing user-created agents
     * are never overwritten. Safe to call on every app launch.
     */
    suspend fun seedDefaultAgents() {
        dao.insertAllIgnoreConflicts(SeedData.seedAgents())
    }

    override fun observeByRole(role: AgentRole): Flow<List<Agent>> =
        dao.observeByRole(role.name).map { entities -> entities.map { it.toModel() } }
}
