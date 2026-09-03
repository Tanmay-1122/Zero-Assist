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
 * Records the outcome of each agent interaction for statistical analysis.
 *
 * Feeds the provider leaderboard and improvement suggestions in the
 * Settings > Providers screen. Each LLM response writes one row here.
 *
 * Outcome values:
 * - `"SUCCESS"` — response delivered without error
 * - `"FAILURE"` — response failed or was an apology
 * - `"RETRY"` — provider returned a retryable error
 * - `"DEGRADED"` — response delivered but with tool errors
 * - `"NEUTRAL"` — unclassified (e.g., short greetings)
 *
 * @property id Auto-generated primary key.
 * @property routeHint The RouteHint active during this interaction.
 * @property provider Provider used (e.g. "anthropic").
 * @property model Model used (e.g. "claude-sonnet-4-20250514").
 * @property outcome Classified outcome string.
 * @property toolCallCount Number of tool calls made during the interaction.
 * @property latencyMs Response latency in milliseconds (first token).
 * @property createdAt Epoch millis when the interaction completed.
 */
@Entity(
    tableName = "interaction_outcomes",
    indices = [
        Index(value = ["provider"]),
        Index(value = ["created_at"]),
    ],
)
data class InteractionOutcomeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    @ColumnInfo(name = "route_hint") val routeHint: String,
    val provider: String,
    val model: String,
    val outcome: String,
    @ColumnInfo(name = "tool_call_count") val toolCallCount: Int,
    @ColumnInfo(name = "latency_ms") val latencyMs: Long,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)
