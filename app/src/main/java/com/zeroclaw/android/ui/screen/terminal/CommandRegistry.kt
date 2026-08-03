/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.terminal

/**
 * Result of parsing a terminal input line.
 *
 * The [TerminalViewModel] uses this sealed hierarchy to decide whether to
 * evaluate a Rhai expression against the daemon, execute a local UI action,
 * or route plain text as a chat message.
 */
sealed interface CommandResult {
    /**
     * A Rhai expression to be evaluated by the FFI engine.
     *
     * @property expression Valid Rhai source text.
     */
    data class RhaiExpression(
        val expression: String,
    ) : CommandResult

    /**
     * A local action handled entirely by the ViewModel.
     *
     * @property action Action identifier such as "help" or "clear".
     */
    data class LocalAction(
        val action: String,
        val args: List<String> = emptyList(),
        val rawArgsText: String = "",
        val displayText: String = "",
    ) : CommandResult

    /**
     * Plain text routed as a chat message through `send()`.
     *
     * @property text The user message with Rhai-unsafe characters escaped.
     */
    data class ChatMessage(
        val text: String,
    ) : CommandResult
}

/**
 * Definition of a slash command available in the terminal REPL.
 *
 * Each command knows how to translate its argument list into a Rhai
 * expression string that the FFI engine can evaluate.
 *
 * @property name The command name without the leading slash (e.g. "status").
 * @property description Brief description shown in the autocomplete overlay.
 * @property usage Usage hint with argument placeholders (empty when none).
 * @property toExpression Translates a split argument list into a Rhai expression string,
 *     or `null` for commands handled locally by the ViewModel.
 */
data class SlashCommand(
    val name: String,
    val description: String,
    val usage: String = "",
    val toExpression: (args: List<String>) -> String?,
)

/**
 * Registry of all slash commands available in the terminal REPL.
 *
 * Commands are registered declaratively and looked up by exact name or
 * prefix for autocomplete. The [parseAndTranslate] entry point handles
 * the full lifecycle: slash-command dispatch, local actions, and
 * fall-through to plain chat messages.
 */
object CommandRegistry {
    /** Default limit for event queries when the user omits the argument. */
    private const val DEFAULT_EVENT_LIMIT = 20

    /** Default limit for memory listing and recall queries. */
    private const val DEFAULT_MEMORY_LIMIT = 50

    /** Default limit for memory recall results. */
    private const val DEFAULT_RECALL_LIMIT = 20

    /** Default limit for trace queries when the user omits the argument. */
    private const val DEFAULT_TRACE_LIMIT = 20

    /** All registered slash commands, in display order. */
    val commands: List<SlashCommand> = buildCommandList()

    /**
     * Finds a command by exact name match.
     *
     * @param name Command name without the leading slash.
     * @return The matching [SlashCommand], or `null` if none exists.
     */
    fun find(name: String): SlashCommand? = commands.find { it.name == name }

    /**
     * Identifies the registered slash command referenced by a raw input line.
     *
     * Returns `null` for non-command input and unknown slash commands.
     */
    fun identifyCommand(input: String): SlashCommand? {
        val trimmed = input.trim()
        if (!trimmed.startsWith("/")) return null
        val withoutSlash = trimmed.removePrefix("/")
        return findLongestMatch(withoutSlash)?.first
    }

    /**
     * Returns all commands whose name starts with the given prefix.
     *
     * Used by the autocomplete overlay to filter suggestions as the
     * user types.
     *
     * @param prefix Partial command name without the leading slash.
     * @return Commands matching the prefix, in registration order.
     */
    fun matches(prefix: String): List<SlashCommand> = commands.filter { it.name.startsWith(prefix, ignoreCase = true) }

    /**
     * Parses a raw terminal input line and translates it into a [CommandResult].
     *
     * Slash commands are dispatched to their registered [SlashCommand.toExpression]
     * lambda. Local commands (`/help`, `/clear`) produce [CommandResult.LocalAction].
     * Any other input is treated as a plain chat message routed through `send()`.
     *
     * @param input The raw text entered by the user.
     * @return A [CommandResult] ready for the ViewModel to act on.
     */
    fun parseAndTranslate(input: String): CommandResult {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) {
            return CommandResult.ChatMessage("")
        }

        if (!trimmed.startsWith("/")) {
            return CommandResult.ChatMessage(escapeForRhai(trimmed))
        }

        val withoutSlash = trimmed.removePrefix("/")
        val match = findLongestMatch(withoutSlash)

        if (match != null) {
            val (command, remainingArgs) = match
            val expression = command.toExpression(remainingArgs)
            if (expression == null) {
                return CommandResult.LocalAction(
                    action = command.name,
                    args = remainingArgs,
                    rawArgsText = input.removePrefix("/").removePrefix(command.name).trim(),
                    displayText = trimmed,
                )
            }
            return CommandResult.RhaiExpression(expression)
        }

        return CommandResult.ChatMessage(escapeForRhai(trimmed))
    }

    /**
     * Finds the command with the longest matching name prefix from the input.
     *
     * Multi-word commands like "cost daily" must match before single-word
     * commands like "cost". The input after the matched name is split into
     * the argument list.
     *
     * @param input Text after the leading slash.
     * @return A pair of the matched command and its argument list, or `null`.
     */
    private fun findLongestMatch(input: String): Pair<SlashCommand, List<String>>? {
        val sorted = commands.sortedByDescending { it.name.length }
        for (command in sorted) {
            if (input == command.name ||
                input.startsWith(command.name + " ")
            ) {
                val argsText = input.removePrefix(command.name).trim()
                val args =
                    if (argsText.isEmpty()) {
                        emptyList()
                    } else {
                        argsText.split(" ").filter { it.isNotEmpty() }
                    }
                return command to args
            }
        }
        return null
    }

    /**
     * Escapes a string for use inside a Rhai double-quoted string literal.
     *
     * Backslashes are doubled and double quotes are escaped so that the
     * resulting string can be safely embedded between `"` delimiters.
     *
     * @param text Raw user text.
     * @return Escaped text safe for Rhai string literals.
     */
    private fun escapeForRhai(text: String): String = text.replace("\\", "\\\\").replace("\"", "\\\"")

    /**
     * Wraps a value in Rhai double quotes after escaping.
     *
     * @param value Raw string value.
     * @return A quoted Rhai string literal, e.g. `"hello"`.
     */
    private fun rhaiString(value: String): String = "\"${escapeForRhai(value)}\""

    /**
     * Builds the complete list of registered slash commands.
     *
     * @return All commands in display order.
     */
    @Suppress("LongMethod", "CognitiveComplexMethod", "CyclomaticComplexMethod")
    private fun buildCommandList(): List<SlashCommand> =
        listOf(
            SlashCommand(
                name = "status",
                description = "Show daemon status",
                toExpression = { "status()" },
            ),
            SlashCommand(
                name = "version",
                description = "Show Zero-Assist version",
                toExpression = { "version()" },
            ),
            SlashCommand(
                name = "health",
                description = "Show health summary or component health",
                usage = "[component]",
                toExpression = { args ->
                    if (args.isEmpty()) {
                        "health()"
                    } else {
                        "health_component(${rhaiString(args.first())})"
                    }
                },
            ),
            SlashCommand(
                name = "doctor",
                description = "Run diagnostic checks",
                usage = "[config_path] [data_dir]",
                toExpression = { args ->
                    if (args.size >= 2) {
                        "doctor(${rhaiString(args[0])}, ${rhaiString(args[1])})"
                    } else {
                        "doctor()"
                    }
                },
            ),
            SlashCommand(
                name = "cost daily",
                description = "Show cost for a specific day (defaults to today)",
                usage = "[year] [month] [day]",
                toExpression = { args ->
                    if (args.size >= 3) {
                        "cost_daily(${args[0]}, ${args[1]}, ${args[2]})"
                    } else {
                        "cost_daily()"
                    }
                },
            ),
            SlashCommand(
                name = "cost monthly",
                description = "Show cost for a specific month (defaults to current)",
                usage = "[year] [month]",
                toExpression = { args ->
                    if (args.size >= 2) {
                        "cost_monthly(${args[0]}, ${args[1]})"
                    } else {
                        "cost_monthly()"
                    }
                },
            ),
            SlashCommand(
                name = "cost",
                description = "Show total cost summary",
                toExpression = { "cost()" },
            ),
            SlashCommand(
                name = "budget",
                description = "Check budget against estimated spend",
                usage = "<amount>",
                toExpression = { args ->
                    val amount = args.firstOrNull() ?: "0.0"
                    "budget($amount)"
                },
            ),
            SlashCommand(
                name = "events",
                description = "Show recent events",
                usage = "[limit]",
                toExpression = { args ->
                    val limit = args.firstOrNull() ?: DEFAULT_EVENT_LIMIT.toString()
                    "events($limit)"
                },
            ),
            SlashCommand(
                name = "skills tools",
                description = "List tools provided by a skill",
                usage = "<name>",
                toExpression = { args ->
                    "skill_tools(${rhaiString(args.firstOrNull().orEmpty())})"
                },
            ),
            SlashCommand(
                name = "skills install",
                description = "Install a skill from a source",
                usage = "<source>",
                toExpression = { args ->
                    "skill_install(${rhaiString(args.firstOrNull().orEmpty())})"
                },
            ),
            SlashCommand(
                name = "skills remove",
                description = "Remove an installed skill",
                usage = "<name>",
                toExpression = { args ->
                    "skill_remove(${rhaiString(args.firstOrNull().orEmpty())})"
                },
            ),
            SlashCommand(
                name = "skills",
                description = "List installed skills",
                toExpression = { "skills()" },
            ),
            SlashCommand(
                name = "tools",
                description = "List available tools",
                toExpression = { "tools()" },
            ),
            SlashCommand(
                name = "sandbox status",
                description = "Show sandbox bridge readiness",
                toExpression = { "sandbox_status()" },
            ),
            SlashCommand(
                name = "sandbox",
                description = "Run a command inside the Alpine Linux sandbox",
                usage = "<command...>",
                toExpression = { args ->
                    val command = args.joinToString(" ")
                    "sandbox(${rhaiString(command)})"
                },
            ),
            SlashCommand(
                name = "termux status",
                description = "Show Termux bridge readiness (legacy, opt-in)",
                toExpression = { null },
            ),
            SlashCommand(
                name = "termux start",
                description = "Start the Zero-Assist Termux bridge (legacy, opt-in)",
                toExpression = { null },
            ),
            SlashCommand(
                name = "termux setup",
                description = "Repair Termux after storage reset and start the bridge (legacy, opt-in)",
                toExpression = { null },
            ),
            SlashCommand(
                name = "termux doctor",
                description = "Run Termux runtime diagnostics (legacy, opt-in)",
                toExpression = { null },
            ),
            SlashCommand(
                name = "termux smoke",
                description = "Run a safe Termux command execution smoke test (legacy, opt-in)",
                toExpression = { null },
            ),
            SlashCommand(
                name = "termux capabilities",
                description = "Show Termux commands exposed to the AI (legacy, opt-in)",
                toExpression = { null },
            ),

            SlashCommand(
                name = "memories",
                description = "List memories, optionally filtered by category",
                usage = "[category]",
                toExpression = { args ->
                    if (args.isEmpty()) {
                        "memories($DEFAULT_MEMORY_LIMIT)"
                    } else {
                        "memories_by_category(${rhaiString(args.first())}, $DEFAULT_MEMORY_LIMIT)"
                    }
                },
            ),
            SlashCommand(
                name = "memory recall",
                description = "Search memories by query",
                usage = "<query>",
                toExpression = { args ->
                    val query = args.joinToString(" ")
                    "memory_recall(${rhaiString(query)}, $DEFAULT_RECALL_LIMIT)"
                },
            ),
            SlashCommand(
                name = "memory forget",
                description = "Delete a memory by key",
                usage = "<key>",
                toExpression = { args ->
                    "memory_forget(${rhaiString(args.firstOrNull().orEmpty())})"
                },
            ),
            SlashCommand(
                name = "memory count",
                description = "Show total memory count",
                toExpression = { "memory_count()" },
            ),
            SlashCommand(
                name = "config",
                description = "Show running daemon config",
                toExpression = { "config()" },
            ),
            SlashCommand(
                name = "validate",
                description = "Validate a TOML config string",
                usage = "<toml>",
                toExpression = { args ->
                    val toml = args.joinToString(" ")
                    "validate_config(${rhaiString(toml)})"
                },
            ),
            SlashCommand(
                name = "traces",
                description = "Show recent traces, optionally filtered",
                usage = "[filter]",
                toExpression = { args ->
                    if (args.isEmpty()) {
                        "traces($DEFAULT_TRACE_LIMIT)"
                    } else {
                        val filter = args.joinToString(" ")
                        "traces_filter(${rhaiString(filter)}, $DEFAULT_TRACE_LIMIT)"
                    }
                },
            ),
            SlashCommand(
                name = "bind",
                description = "Bind a user identity to a channel",
                usage = "<channel> <user_id>",
                toExpression = { args ->
                    if (args.size >= 2) {
                        val channel = args[0]
                        val userId = args.drop(1).joinToString(" ")
                        "bind(${rhaiString(channel)}, ${rhaiString(userId)})"
                    } else {
                        "bind(\"\", \"\")"
                    }
                },
            ),
            SlashCommand(
                name = "allowlist",
                description = "Show channel allowlist",
                usage = "<channel>",
                toExpression = { args ->
                    "allowlist(${rhaiString(args.firstOrNull().orEmpty())})"
                },
            ),
            SlashCommand(
                name = "swap",
                description = "Swap the active provider and model",
                usage = "<provider> <model>",
                toExpression = { args ->
                    if (args.size >= 2) {
                        "swap_provider(${rhaiString(args[0])}, ${rhaiString(args[1])})"
                    } else {
                        "swap_provider(\"\", \"\")"
                    }
                },
            ),
            SlashCommand(
                name = "models",
                description = "List available models for a provider",
                usage = "<provider>",
                toExpression = { args ->
                    "models(${rhaiString(args.firstOrNull().orEmpty())})"
                },
            ),
            SlashCommand(
                name = "auth remove",
                description = "Remove an auth profile",
                usage = "<provider> <profile>",
                toExpression = { args ->
                    if (args.size >= 2) {
                        "auth_remove(${rhaiString(args[0])}, ${rhaiString(args[1])})"
                    } else {
                        "auth_remove(\"\", \"\")"
                    }
                },
            ),
            SlashCommand(
                name = "auth",
                description = "List auth profiles",
                toExpression = { "auth_list()" },
            ),
            SlashCommand(
                name = "agents",
                description = "List all available agents",
                toExpression = { "agents()" },
            ),
            SlashCommand(
                name = "agent",
                description = "Get details of a specific agent",
                usage = "<name>",
                toExpression = { args ->
                    "agent_get(${rhaiString(args.firstOrNull().orEmpty())})"
                },
            ),
            SlashCommand(
                name = "delegate",
                description = "Delegate a task to an agent",
                usage = "<agent> <task...>",
                toExpression = { args ->
                    if (args.size >= 2) {
                        val agent = args.first()
                        val task = args.drop(1).joinToString(" ")
                        "delegate(${rhaiString(agent)}, ${rhaiString(task)})"
                    } else {
                        "delegate(\"\", \"\")"
                    }
                },
            ),
            SlashCommand(
                name = "group start",
                description = "Start a group chat session",
                usage = "[agent1] [agent2] ...",
                toExpression = { args ->
                    if (args.isEmpty()) {
                        "group_start()"
                    } else {
                        val agents = args.map { rhaiString(it) }.joinToString(", ")
                        "group_start_with([$agents])"
                    }
                },
            ),
            SlashCommand(
                name = "group stop",
                description = "Stop the active group chat session",
                toExpression = { "group_stop()" },
            ),
            SlashCommand(
                name = "group status",
                description = "Show group chat session status",
                toExpression = { "group_status()" },
            ),
            SlashCommand(
                name = "group add",
                description = "Add an agent to the active group chat",
                usage = "<agent>",
                toExpression = { args ->
                    "group_add(${rhaiString(args.firstOrNull().orEmpty())})"
                },
            ),
            SlashCommand(
                name = "group remove",
                description = "Remove an agent from the active group chat",
                usage = "<agent>",
                toExpression = { args ->
                    "group_remove(${rhaiString(args.firstOrNull().orEmpty())})"
                },
            ),
            SlashCommand(
                name = "group message",
                description = "Send a message to the group chat",
                usage = "<message...>",
                toExpression = { args ->
                    if (args.isNotEmpty()) {
                        val message = args.joinToString(" ")
                        "group_message(${rhaiString(message)})"
                    } else {
                        "group_message(\"\")"
                    }
                },
            ),
            SlashCommand(
                name = "local infer",
                description = "Run on-device inference with cloud fallback",
                usage = "<prompt...>",
                toExpression = { null },
            ),
            SlashCommand(
                name = "local summarize",
                description = "Summarize text on-device with cloud fallback",
                usage = "<text...>",
                toExpression = { null },
            ),
            SlashCommand(
                name = "local proofread",
                description = "Proofread text on-device with cloud fallback",
                usage = "<text...>",
                toExpression = { null },
            ),
            SlashCommand(
                name = "local rewrite",
                description = "Rewrite text on-device with cloud fallback",
                usage = "<text...>",
                toExpression = { null },
            ),
            SlashCommand(
                name = "local describe",
                description = "Describe the attached image on-device with cloud fallback",
                usage = "[prompt...]",
                toExpression = { null },
            ),
            SlashCommand(
                name = "camera",
                description = "Open the local camera capture flow",
                usage = "[prompt...]",
                toExpression = { null },
            ),
            SlashCommand(
                name = "screen capture",
                description = "Capture the current screen for local vision",
                usage = "[prompt...]",
                toExpression = { null },
            ),
            SlashCommand(
                name = "script",
                description = "Reserved hook for future Rhai runtime integration",
                usage = "[request...]",
                toExpression = { null },
            ),
            SlashCommand(
                name = "help",
                description = "Show available commands",
                toExpression = { null },
            ),
            SlashCommand(
                name = "clear",
                description = "Clear terminal history",
                toExpression = { null },
            ),
        )
}
