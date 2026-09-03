/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.termux

import com.zeroclaw.android.model.ToolSpec
import com.zeroclaw.android.service.AppOwnedToolCatalog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class TermuxToolCatalog(
    private val statusProvider: TermuxRuntimeStatusProvider,
    pluginEnabled: Boolean = true,
) : AppOwnedToolCatalog {
    private val _pluginEnabled = MutableStateFlow(pluginEnabled)
    val pluginEnabled: StateFlow<Boolean> = _pluginEnabled

    fun setPluginEnabled(enabled: Boolean) {
        _pluginEnabled.value = enabled
    }

    override suspend fun listTools(): List<ToolSpec> {
        val status = statusProvider.currentStatus()
        val enabled = _pluginEnabled.value
        val active = enabled && status.isReady
        val reason =
            when {
                !enabled -> "Termux plugin is disabled."
                else -> status.inactiveReason()
            }
        return listOf(
            ToolSpec(
                name = CAPABILITIES_TOOL_NAME,
                description = "Get Termux capabilities: commands, Python version, paths, limits.",
                source = "termux",
                parametersJson = TERMUX_CAPABILITIES_PARAMETERS_JSON,
                isActive = active,
                inactiveReason = reason,
            ),
            ToolSpec(
                name = RUN_TOOL_NAME,
                description = "Execute commands in Termux. Low-risk runs directly, high-risk requires approval.",
                source = "termux",
                parametersJson = TERMUX_RUN_PARAMETERS_JSON,
                isActive = active,
                inactiveReason = reason,
            ),
        )
    }

    private companion object {
        private const val CAPABILITIES_TOOL_NAME = "termux_get_capabilities"
        private const val RUN_TOOL_NAME = "termux_run"
        private const val TERMUX_CAPABILITIES_PARAMETERS_JSON =
            """
            {
              "type": "object",
              "properties": {
                "refresh": {
                  "type": "boolean",
                  "description": "Whether to force a fresh bridge capability probe."
                }
              },
              "additionalProperties": false
            }
            """
        private const val TERMUX_RUN_PARAMETERS_JSON =
            """
            {
              "type": "object",
              "properties": {
                "command": {
                  "type": "string",
                  "description": "Command name or executable path. Low-risk diagnostics run directly; medium/high-risk argv commands require user approval."
                },
                "arguments": {
                  "type": "array",
                  "items": { "type": "string" },
                  "description": "Optional argv arguments. Use a flat array such as [\"test1.txt\", \"test2.txt\"] for touch. Shell heredocs, redirection, chaining, and arbitrary scripts require user approval; create a script file first instead of passing << heredoc syntax."
                },
                "working_directory": {
                  "type": "string",
                  "description": "Optional Termux working directory. Direct low-risk execution uses Termux home/usr paths; outside paths require user approval and may fail if Termux lacks access."
                },
                "timeout_seconds": {
                  "type": "integer",
                  "minimum": 1,
                  "maximum": 120,
                  "description": "Command timeout in seconds."
                }
              },
              "required": ["command"],
              "additionalProperties": false
            }
            """
    }
}
