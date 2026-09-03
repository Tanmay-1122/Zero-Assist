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
import com.zeroclaw.android.data.local.entity.EmailConfigEntity
import kotlinx.coroutines.flow.Flow

/**
 * Data access object for the singleton email configuration row.
 *
 * Only a single row with id=1 exists. The email password itself is
 * stored separately in EncryptedSharedPreferences.
 */
@Dao
interface EmailConfigDao {
    /**
     * Observes the email configuration as a reactive stream.
     *
     * Emits null if no configuration has been saved yet, or the current
     * [EmailConfigEntity] whenever it changes.
     *
     * @return A [Flow] emitting the current email configuration or null.
     */
    @Query("SELECT * FROM email_config WHERE id = 1")
    fun observe(): Flow<EmailConfigEntity?>

    /**
     * Returns the current email configuration, or null if none exists.
     *
     * @return The [EmailConfigEntity] singleton or null.
     */
    @Query("SELECT * FROM email_config WHERE id = 1")
    suspend fun get(): EmailConfigEntity?

    /**
     * Inserts or replaces the singleton email configuration row.
     *
     * @param entity The email configuration to persist.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: EmailConfigEntity)
}
