package com.zeroclaw.android.model

/**
 * Enum representing the role of an agent in the ZeroClaw system.
 *
 * @property displayName Human-readable name for the role.
 * @property icon Emoji representing the role in the UI.
 */
enum class AgentRole(
    val displayName: String,
    val icon: String,
) {
    MASTER("Master", "👑"),
    RESEARCHER("Researcher", "🔍"),
    CODER("Coder", "💻"),
    PLANNER("Planner", "📋"),
    WRITER("Writer", "✍️"),
    ANALYST("Analyst", "📊"),
    EXECUTOR("Executor", "⚡"),
    GENERAL("General", "🤖"),
}
