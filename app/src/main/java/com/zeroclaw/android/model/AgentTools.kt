/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Asks the user for input during agent execution (blocks until user responds).
 *
 * @property id Unique identifier for this ask request.
 * @property agentId Agent that initiated the ask.
 * @property workspaceId Workspace context.
 * @property question The question or prompt for the user.
 * @property questionType Type of answer expected: "text", "choice", "number", "boolean", "file".
 * @property choices List of options (for choice type).
 * @property isBlocking Whether execution should wait for answer.
 * @property timeoutSeconds Max time to wait for answer (0 = no timeout).
 * @property userResponse The user's answer (null if not yet answered).
 * @property respondedAt ISO-8601 timestamp when user answered.
 * @property createdAt ISO-8601 timestamp when ask was created.
 */
@Entity(tableName = "agent_asks")
@Serializable
data class AskUserRequest(
    @PrimaryKey
    val id: String,

    val agentId: String,

    val workspaceId: String = "default",

    val question: String,

    val questionType: String = "text", // text, choice, number, boolean, file

    @SerialName("choices_json")
    val choicesJson: String? = null, // JSON array of strings

    val isBlocking: Boolean = true,

    val timeoutSeconds: Int = 300,

    val userResponse: String? = null,

    val respondedAt: String? = null,

    val createdAt: String = "",
)

/**
 * Represents an escalation to a human for complex decision-making.
 *
 * @property id Unique identifier.
 * @property agentId Agent that escalated.
 * @property workspaceId Workspace context.
 * @property escalationType Type of escalation: "approval", "intervention", "review", "emergency".
 * @property description Why escalation occurred.
 * @property targetRole User role who should handle this (e.g., "admin", "lead", "on_call").
 * @property assignedTo Optional user ID to assign to.
 * @property priority "low", "normal", "high", "critical".
 * @property status "pending", "acknowledged", "resolved", "rejected".
 * @property resolution How the human resolved it.
 * @property resolvedAt ISO-8601 timestamp when resolved.
 * @property createdAt ISO-8601 timestamp.
 */
@Entity(tableName = "agent_escalations")
@Serializable
data class AgentEscalation(
    @PrimaryKey
    val id: String,

    val agentId: String,

    val workspaceId: String = "default",

    val escalationType: String, // approval, intervention, review, emergency

    val description: String,

    val targetRole: String? = null,

    val assignedTo: String? = null,

    val priority: String = "normal", // low, normal, high, critical

    val status: String = "pending", // pending, acknowledged, resolved, rejected

    val resolution: String? = null,

    val resolvedAt: String? = null,

    val createdAt: String = "",
)

/**
 * Multi-agent swarm configuration for coordinated execution.
 *
 * @property id Unique identifier.
 * @property name Swarm name (e.g., "data_processing_team").
 * @property workspaceId Workspace context.
 * @property agentIds List of agent IDs in the swarm.
 * @property coordinatorAgentId The agent that coordinates the swarm.
 * @property strategy Coordination strategy: "sequential", "parallel", "dag", "voting".
 * @property description What this swarm does.
 * @property isActive Whether swarm is available.
 * @property createdAt ISO-8601 timestamp.
 */
@Entity(tableName = "agent_swarms")
@Serializable
data class AgentSwarm(
    @PrimaryKey
    val id: String,

    val name: String,

    val workspaceId: String = "default",

    @SerialName("agent_ids")
    val agentIds: List<String> = emptyList(),

    val coordinatorAgentId: String? = null,

    val strategy: String = "sequential", // sequential, parallel, dag, voting

    val description: String = "",

    val isActive: Boolean = true,

    val createdAt: String = "",
)

/**
 * Dynamic LLM task created during execution.
 *
 * @property id Unique identifier.
 * @property agentId Agent that created this task.
 * @property workspaceId Workspace context.
 * @property taskName Human-readable task name.
 * @property description What the task should accomplish.
 * @property instructions Detailed instructions for task execution.
 * @property contextData Context/data for the task (JSON).
 * @property targetModel LLM model to use ("gpt4", "claude", "local", etc).
 * @property priority "low", "normal", "high".
 * @property status "created", "pending", "running", "completed", "failed", "cancelled".
 * @property result Output from task execution.
 * @property errorMessage Error if task failed.
 * @property estimatedTokens Approximate tokens needed.
 * @property actualTokens Tokens used in execution.
 * @property createdAt ISO-8601 timestamp.
 * @property startedAt ISO-8601 when execution began.
 * @property completedAt ISO-8601 when execution finished.
 */
@Entity(tableName = "llm_tasks")
@Serializable
data class LlmTask(
    @PrimaryKey
    val id: String,

    val agentId: String,

    val workspaceId: String = "default",

    val taskName: String,

    val description: String = "",

    val instructions: String,

    @SerialName("context_json")
    val contextData: String? = null,

    val targetModel: String = "gpt4",

    val priority: String = "normal", // low, normal, high

    val status: String = "created", // created, pending, running, completed, failed, cancelled

    val result: String? = null,

    val errorMessage: String? = null,

    val estimatedTokens: Int = 0,

    val actualTokens: Int = 0,

    val createdAt: String = "",

    val startedAt: String? = null,

    val completedAt: String? = null,
)

/**
 * Cross-workspace knowledge reference for project/domain intelligence.
 *
 * @property id Unique identifier.
 * @property sourceWorkspaceId Workspace this knowledge originates from.
 * @property targetWorkspaceId Workspace(s) this is shared with (comma-separated or "*" for all).
 * @property topicName Knowledge topic area.
 * @property contentSummary Brief summary of the knowledge.
 * @property contentFull Complete knowledge content.
 * @property sourceAgentId Which agent contributed this knowledge.
 * @property relevanceScore How relevant to target workspace (0-1).
 * @property accessLevel "private", "workspace", "shared", "public".
 * @property createdAt ISO-8601 timestamp.
 * @property lastAccessedAt ISO-8601 when last used.
 */
@Entity(tableName = "project_intel")
@Serializable
data class ProjectIntelligence(
    @PrimaryKey
    val id: String,

    val sourceWorkspaceId: String,

    val targetWorkspaceId: String, // "*" for all, comma-separated list, or specific ID

    val topicName: String,

    val contentSummary: String,

    val contentFull: String,

    val sourceAgentId: String? = null,

    val relevanceScore: Float = 0.5f,

    val accessLevel: String = "workspace", // private, workspace, shared, public

    val createdAt: String = "",

    val lastAccessedAt: String? = null,
)

/**
 * Execution trace for agent tool invocations.
 *
 * Tracks when ask_user, escalate, swarm, llm_task are invoked for debugging and audit.
 *
 * @property id Unique identifier.
 * @property agentId Agent that made the tool call.
 * @property toolName Which tool was invoked.
 * @property toolRequestId ID of the specific request (askRequest ID, escalation ID, etc).
 * @property status "pending", "executing", "completed", "failed".
 * @property response The tool's response.
 * @property errorMessage Error if tool call failed.
 * @property durationMs How long the tool call took.
 * @property createdAt ISO-8601 timestamp.
 */
@Entity(tableName = "agent_tool_traces")
@Serializable
data class AgentToolTrace(
    @PrimaryKey
    val id: String,

    val agentId: String,

    val toolName: String, // "ask_user", "escalate", "swarm", "llm_task", "project_intel"

    val toolRequestId: String,

    val status: String = "pending",

    val response: String? = null,

    val errorMessage: String? = null,

    val durationMs: Long = 0L,

    val createdAt: String = "",
)
