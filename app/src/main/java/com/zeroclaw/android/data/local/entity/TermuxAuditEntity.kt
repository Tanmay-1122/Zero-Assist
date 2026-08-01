/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Durable audit row for every Termux command decision and execution outcome.
 */
@Entity(
    tableName = "termux_audit_records",
    indices = [
        Index(value = ["state"]),
        Index(value = ["risk"]),
        Index(value = ["updated_at_epoch_ms"]),
        Index(value = ["fingerprint"]),
    ],
)
data class TermuxAuditEntity(
    @PrimaryKey
    val id: String,
    val state: String,
    val risk: String,
    @ColumnInfo(name = "command_preview")
    val commandPreview: String,
    @ColumnInfo(name = "requested_at_epoch_ms")
    val requestedAtEpochMs: Long,
    @ColumnInfo(name = "updated_at_epoch_ms")
    val updatedAtEpochMs: Long,
    val reason: String?,
    @ColumnInfo(name = "working_directory")
    val workingDirectory: String?,
    val fingerprint: String?,
)
