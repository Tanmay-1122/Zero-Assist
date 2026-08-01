/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.sandbox

import com.zeroclaw.android.model.ToolSpec
import com.zeroclaw.android.service.AppOwnedToolCatalog

private const val TOOL_DESCRIPTION_SHELL = "Run shell commands in Alpine Linux sandbox with persistent state. Pre-installed: bash, python3, nodejs, git, curl. Timeout: 30s (180s for installs). Use background=true for long tasks. Install: apk add <pkg>. For pip: --break-system-packages if needed."

private const val TOOL_DESCRIPTION_MANAGE = "Manage background processes: list/log/kill/remove sessions started with background=true."

private const val TOOL_DESCRIPTION_GWS = "Access Google Workspace services (Drive, Gmail, Calendar, Sheets, Docs, etc.) via gws CLI in sandbox. Requires OAuth setup in Settings."

/**
 * [AppOwnedToolCatalog] implementation for the Linux sandbox plugin.
 *
 * Surfaces three tools to the daemon's tool list:
 *  - `sandbox_execute`: run shell commands inside Alpine Linux via proot.
 *  - `sandbox_manage_process`: manage background processes started from the shell.
 *  - `google_workspace`: interact with Google Workspace services via the gws CLI.
 *
 * Tools are listed as inactive when the sandbox is not yet ready, so the
 * AI model receives a clear "not installed" signal rather than a runtime error.
 */
class ProotToolCatalog(private val sandboxManager: LinuxSandboxManager) : AppOwnedToolCatalog {

    override suspend fun listTools(): List<ToolSpec> {
        val ready = sandboxManager.state.value is SandboxState.Ready
        val inactiveReason = if (ready) "" else "Linux Sandbox plugin is not installed. Enable it in Plugins → Installed."

        return listOf(
            ToolSpec(
                name = "sandbox_execute",
                description = TOOL_DESCRIPTION_SHELL,
                source = "linux-sandbox",
                parametersJson = SHELL_PARAMS_JSON,
                isActive = ready,
                inactiveReason = inactiveReason,
            ),
            ToolSpec(
                name = "sandbox_manage_process",
                description = TOOL_DESCRIPTION_MANAGE,
                source = "linux-sandbox",
                parametersJson = MANAGE_PARAMS_JSON,
                isActive = ready,
                inactiveReason = inactiveReason,
            ),
            ToolSpec(
                name = "google_workspace",
                description = TOOL_DESCRIPTION_GWS,
                source = "linux-sandbox",
                parametersJson = GWS_PARAMS_JSON,
                isActive = ready && sandboxManager.isGoogleWorkspaceCliInstalled(),
                inactiveReason = if (!ready) "Linux Sandbox plugin is not installed. Enable it in Plugins → Installed."
                    else if (!sandboxManager.isGoogleWorkspaceCliInstalled()) "Google Workspace CLI not installed. Run 'Install Packages' in Sandbox settings."
                    else "",
            ),
        )
    }
}

private const val GWS_PARAMS_JSON = """{
  "type": "object",
  "properties": {
    "service": {"type": "string", "description": "Google Workspace service (e.g. drive, gmail, calendar, sheets, docs, slides, tasks, people, chat, classroom, forms, keep, meet, events)"},
    "resource": {"type": "string", "description": "Service resource (e.g. files, messages, events, spreadsheets)"},
    "method": {"type": "string", "description": "Method to call on the resource (e.g. list, get, create, update, delete)"},
    "sub_resource": {"type": "string", "description": "Optional sub-resource for nested operations"},
    "params": {"type": "object", "description": "URL/query parameters as key-value pairs"},
    "body": {"type": "object", "description": "Request body for POST/PATCH/PUT operations"},
    "format": {"type": "string", "enum": ["json", "table", "yaml", "csv"], "description": "Output format (default: json)"},
    "page_all": {"type": "boolean", "description": "Auto-paginate through all results"},
    "page_limit": {"type": "integer", "description": "Max pages to fetch when using page_all (default: 10)"}
  },
  "required": ["service", "resource", "method"]
}"""

// JSON schemas for tool parameters
private const val SHELL_PARAMS_JSON = """{
  "type": "object",
  "properties": {
    "command": {"type": "string", "description": "The shell command to execute"},
    "timeout": {"type": "integer", "description": "Timeout in seconds (default 30, max 60)"},
    "working_dir": {"type": "string", "description": "If set, run the command starting in this directory. The cd persists for subsequent calls."},
    "env": {"type": "object", "description": "Per-command environment variable overrides. Scoped to this call only."},
    "background": {"type": "boolean", "description": "Run detached as a background job. Returns a session_id; use sandbox_manage_process to check status."},
    "fresh": {"type": "boolean", "description": "If true, run in a one-shot isolated shell. Default false."}
  },
  "required": ["command"]
}"""

private const val MANAGE_PARAMS_JSON = """{
  "type": "object",
  "properties": {
    "action": {"type": "string", "description": "Action to perform: list, log, kill, or remove"},
    "session_id": {"type": "string", "description": "Session ID of the process (required for log, kill, remove)"},
    "offset": {"type": "integer", "description": "Line offset for log output (default: 0)"},
    "limit": {"type": "integer", "description": "Max lines to return for log (default: 200)"}
  },
  "required": ["action"]
}"""
