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
 * Room entity for persisting runtime metadata (UI state, expanded status, scroll position, retry history)
 * separately from immutable message content blocks.
 */
@Entity(
    tableName = "block_runtime_states",
    indices = [
        Index(value = ["message_id"]),
        Index(value = ["block_id"], unique = true),
    ],
)
data class BlockRuntimeStateEntity(
    @PrimaryKey
    @ColumnInfo(name = "block_id")
    val blockId: String,

    @ColumnInfo(name = "message_id")
    val messageId: String,

    @ColumnInfo(name = "is_expanded")
    val isExpanded: Boolean = false,

    @ColumnInfo(name = "scroll_position_y")
    val scrollPositionY: Int = 0,

    @ColumnInfo(name = "retry_count")
    val retryCount: Int = 0,

    @ColumnInfo(name = "metadata_json")
    val metadataJson: String = "{}",

    @ColumnInfo(name = "updated_at_ms")
    val updatedAtMs: Long = System.currentTimeMillis(),
)
