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
 * Room metadata row for one persisted agent conversation.
 *
 * Messages remain in `agent_chat_messages`; this table only stores the fields
 * needed to render and restore archived conversations in the history drawer.
 */
@Entity(
    tableName = "conversations",
    indices = [
        Index(value = ["last_message_at_ms"]),
        Index(value = ["workspace_name"]),
    ],
)
data class ConversationEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "workspace_name")
    val workspaceName: String,

    @ColumnInfo(name = "primary_agent_name")
    val primaryAgentName: String,

    @ColumnInfo(name = "preview")
    val preview: String,

    @ColumnInfo(name = "title")
    val title: String? = null,

    @ColumnInfo(name = "is_title_pending")
    val isTitlePending: Boolean = true,

    @ColumnInfo(name = "created_at_ms")
    val createdAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "last_message_at_ms")
    val lastMessageAt: Long = System.currentTimeMillis(),
)
