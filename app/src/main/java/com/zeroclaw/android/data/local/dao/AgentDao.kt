/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import com.zeroclaw.android.data.local.entity.AgentEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for agent CRUD operations.
 */
@Dao
interface AgentDao {
    /**
     * Observes all agents ordered by name.
     *
     * @return A [Flow] emitting the current list of agents on every change.
     */
    @Query("SELECT * FROM agents ORDER BY name ASC")
    fun observeAll(): Flow<List<AgentEntity>>

    /**
     * Returns the agent with the given [id], or null if not found.
     *
     * @param id Unique agent identifier.
     * @return The matching [AgentEntity] or null.
     */
    @Query("SELECT * FROM agents WHERE id = :id")
    suspend fun getById(id: String): AgentEntity?

    /**
     * Inserts or updates an agent.
     *
     * @param entity The agent entity to upsert.
     */
    @Upsert
    suspend fun upsert(entity: AgentEntity)

    /**
     * Deletes the agent with the given [id].
     *
     * @param id Unique agent identifier.
     */
    @Query("DELETE FROM agents WHERE id = :id")
    suspend fun deleteById(id: String)

    /**
     * Toggles the enabled state of the agent with the given [id].
     *
     * @param id Unique agent identifier.
     */
    @Query("UPDATE agents SET is_enabled = NOT is_enabled WHERE id = :id")
    suspend fun toggleEnabled(id: String)

    /**
     * Observes all agents with the given role.
     *
     * @param role The role to filter by (e.g. "MASTER").
     * @return A [Flow] emitting the current list of agents with the specified role.
     */
    @Query("SELECT * FROM agents WHERE role = :role")
    fun observeByRole(role: String): Flow<List<AgentEntity>>

    /**
     * Observes the master agent, or null if none exists.
     *
     * @return A [Flow] emitting the current master agent or null.
     */
    @Query("SELECT * FROM agents WHERE is_master = 1 LIMIT 1")
    fun observeMasterAgent(): Flow<AgentEntity?>

    /**
     * Observes all enabled agents ordered by priority descending.
     *
     * @return A [Flow] emitting the current list of active agents by priority.
     */
    @Query("SELECT * FROM agents WHERE is_enabled = 1 ORDER BY priority DESC")
    fun observeActiveAgentsByPriority(): Flow<List<AgentEntity>>

    /**
     * Saves an agent, ensuring only one master exists.
     * If the agent has isMaster = true, sets all other agents' isMaster to false first.
     *
     * @param entity The agent entity to upsert.
     */
    @Upsert
    suspend fun upsertAgent(entity: AgentEntity)

    /**
     * Unsets the master flag for all agents.
     */
    @Query("UPDATE agents SET is_master = 0")
    suspend fun unsetAllMasters()

    /**
     * Inserts agents, ignoring any that already exist by primary key.
     *
     * Used for seeding initial data without overwriting user changes.
     *
     * @param entities The agent entities to insert.
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAllIgnoreConflicts(entities: List<AgentEntity>)
}
