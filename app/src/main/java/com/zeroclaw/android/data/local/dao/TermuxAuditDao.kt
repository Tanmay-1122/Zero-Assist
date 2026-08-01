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
import com.zeroclaw.android.data.local.entity.TermuxAuditEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for durable Termux approval and execution audit records.
 */
@Dao
interface TermuxAuditDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: TermuxAuditEntity)

    @Query("SELECT * FROM termux_audit_records WHERE id = :id LIMIT 1")
    suspend fun get(id: String): TermuxAuditEntity?

    @Query(
        """
        SELECT * FROM termux_audit_records
        ORDER BY requested_at_epoch_ms ASC, id ASC
        """,
    )
    suspend fun listAll(): List<TermuxAuditEntity>

    @Query(
        """
        SELECT * FROM termux_audit_records
        ORDER BY requested_at_epoch_ms DESC
        LIMIT :limit
        """,
    )
    fun observeRecent(limit: Int): Flow<List<TermuxAuditEntity>>
}
