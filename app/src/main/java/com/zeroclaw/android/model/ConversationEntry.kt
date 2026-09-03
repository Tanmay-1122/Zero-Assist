/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.model

/**
 * A history entry displayed in the shared conversation drawer.
 *
 * @property id Stable entry identifier.
 * @property workspaceName Display name of the workspace that owns the entry.
 * @property title Optional generated title shown above the preview in the drawer.
 * @property isTitlePending Whether the short title is still being generated.
 * @property preview First-message preview shown in the drawer.
 * @property timestamp Epoch milliseconds when the conversation entry was created.
 * @property agentName Display name of the primary routed agent.
 * @property isStarred Whether the entry is pinned/starred by the user.
 */
data class ConversationEntry(
    val id: String,
    val workspaceName: String,
    val title: String? = null,
    val isTitlePending: Boolean = false,
    val preview: String,
    val timestamp: Long,
    val agentName: String,
    val isStarred: Boolean = false,
)
