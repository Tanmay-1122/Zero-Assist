/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! Tool inventory browsing for the Android dashboard.
//!
//! Enumerates all available tools from the daemon config and installed
//! skills without instantiating the actual tool objects (which require
//! runtime dependencies like security policies and memory backends).

use crate::config_compat;
use crate::error::FfiError;

/// A tool specification suitable for display in the Android tools browser.
///
/// Contains metadata about a tool without the actual tool instance, making
/// it safe and lightweight for FFI transfer.
#[derive(Debug, Clone, serde::Serialize, uniffi::Record)]
pub struct FfiToolSpec {
    /// Unique tool name (e.g. `"shell"`, `"file_read"`).
    pub name: String,
    /// Human-readable description of the tool.
    pub description: String,
    /// Origin of the tool: `"built-in"` or the skill name.
    pub source: String,
    /// JSON schema for the tool parameters, or `"{}"` if unavailable.
    pub parameters_json: String,
    /// Whether the tool is usable in the current Android session.
    ///
    /// Session-available tools (memory, web tools) are active.
    /// Tools requiring a `SecurityPolicy` (shell, file I/O, git) are
    /// inactive because they can only execute through daemon-channel routing.
    pub is_active: bool,
    /// Human-readable reason the tool is inactive, or empty string when active.
    ///
    /// Common values:
    /// - `""` -- tool is active
    /// - `"Requires daemon-channel security policy"` -- requires `SecurityPolicy`
    /// - `"Disabled in settings"` -- config flag is off
    pub inactive_reason: String,
}

/// Describes a built-in tool with a static name and description.
struct BuiltInTool {
    /// Tool name as registered in the tool registry.
    name: &'static str,
    /// Brief description of what the tool does.
    description: &'static str,
}

/// Static list of all core built-in tools.
///
/// These tools are always available when the daemon is running.
const CORE_TOOLS: &[BuiltInTool] = &[
    BuiltInTool {
        name: "shell",
        description: "Execute shell commands through daemon-channel security policy. Shell = sandbox: on Android, shell commands are routed through sandbox_execute inside the isolated Alpine environment.",
    },
    BuiltInTool {
        name: "file_read",
        description: "Read file contents with path validation",
    },
    BuiltInTool {
        name: "file_write",
        description: "Write content to files with path validation",
    },
    BuiltInTool {
        name: "memory_store",
        description: "Store a key-value pair in the memory backend",
    },
    BuiltInTool {
        name: "memory_recall",
        description: "Recall memories matching a keyword query",
    },
    BuiltInTool {
        name: "memory_forget",
        description: "Remove a memory entry by key",
    },
    BuiltInTool {
        name: "git_operations",
        description: "Perform git operations in the workspace directory",
    },
    BuiltInTool {
        name: "screenshot",
        description: "Capture screenshots with security policy enforcement",
    },
    BuiltInTool {
        name: "image_info",
        description: "Extract metadata and dimensions from image files",
    },
    BuiltInTool {
        name: "shared_folder_list",
        description: "List files and directories in a shared folder selected via Android file picker",
    },
    BuiltInTool {
        name: "shared_folder_read",
        description: "Read a file from the shared folder. Text files return raw content; binary files return base64",
    },
    BuiltInTool {
        name: "shared_folder_write",
        description: "Write a file or create a directory in the shared folder. Overwrites existing files",
    },
    BuiltInTool {
        name: "workflow_folder_list",
        description: "List files and directories in the default or selected workflow folder",
    },
    BuiltInTool {
        name: "workflow_folder_read",
        description: "Read a file from the workflow folder. Text files return raw content; binary files return base64",
    },
    BuiltInTool {
        name: "workflow_folder_write",
        description: "Write a file or create a directory in the workflow folder. Overwrites existing files",
    },
];

/// Optional tools that depend on config flags.
const BROWSER_TOOLS: &[BuiltInTool] = &[
    BuiltInTool {
        name: "browser_open",
        description: "Open a URL in a headless or remote browser",
    },
    BuiltInTool {
        name: "browser",
        description: "Full browser automation (navigation, clicks, screenshots)",
    },
];

/// HTTP request tool (available when HTTP is enabled).
const HTTP_TOOL: BuiltInTool = BuiltInTool {
    name: "http_request",
    description: "Make HTTP requests with domain allowlist enforcement",
};

/// First-party Termux capability discovery tool backed by Zero-Assist's bridge.
const TERMUX_CAPABILITIES_TOOL: BuiltInTool = BuiltInTool {
    name: "termux_get_capabilities",
    description: "LEGACY / OPT-IN ONLY. Inspect the user's local Termux runtime through Zero Assist's authenticated bridge. Returns installed commands, Python version, workspace paths, proot status, and execution limits. Only registered when the user explicitly enables the Termux plugin; the Linux sandbox (sandbox_execute) is the default shell backend.",
};

/// First-party low-risk Termux execution tool backed by Zero-Assist's bridge.
const TERMUX_RUN_TOOL: BuiltInTool = BuiltInTool {
    name: "termux_run",
    description: "LEGACY / OPT-IN ONLY. Execute commands directly in the user's existing Termux environment on the Android device. Only registered when the user explicitly enables the Termux plugin. Use only when the user explicitly asks to interact with their Termux installation, its files, or Android host tools. For general Linux commands, packages, and scripting, use sandbox_execute instead.",
};

/// First-party Linux sandbox shell execution tool backed by Zero-Assist's bridge.
const SANDBOX_EXECUTE_TOOL: BuiltInTool = BuiltInTool {
    name: "sandbox_execute",
    description: "Execute commands inside an isolated Alpine Linux sandbox (PRoot). Use this for Linux packages (apk add), filesystem operations, compilation, scripts, Python, Node.js, git, and any work that should not affect the user's Termux environment. Pre-installed: bash, python3, nodejs, git, curl, wget, jq, openssh, rsync.",
};

/// First-party Linux sandbox process manager backed by Zero-Assist's bridge.
const SANDBOX_MANAGE_PROCESS_TOOL: BuiltInTool = BuiltInTool {
    name: "sandbox_manage_process",
    description: "Manage background shell processes started with sandbox_execute (background=true). Supports list, log, kill, and remove actions.",
};

/// First-party device control tool backed by Zero-Assist's bridge.
const DEVICE_CONTROL_TOOL: BuiltInTool = BuiltInTool {
    name: "device_control",
    description: "Control the Android device's screen through accessibility gestures. Provide a SINGLE, COMPLETE goal — the internal planner handles multi-step UI execution. Do NOT decompose compound goals into separate calls.",
};

/// Parameters schema for the device_control tool.
const DEVICE_CONTROL_PARAMETERS_JSON: &str = r#"{
  "type": "object",
  "properties": {
    "goal": {
      "type": "string",
      "description": "A clear description of what the phone should do."
    },
    "max_steps": {
      "type": "integer",
      "description": "Maximum steps (default 30, range 1\u201360)."
    }
  },
  "required": ["goal"],
  "additionalProperties": false
}"#;

/// Google Workspace CLI tool backed by the sandbox bridge.
const GOOGLE_WORKSPACE_TOOL: BuiltInTool = BuiltInTool {
    name: "google_workspace",
    description: "Interact with Google Workspace services (Drive, Gmail, Calendar, Sheets, Docs, Slides, Tasks, People, Chat, Classroom, Forms, Keep, Meet, Events) via the gws CLI. Requires sandbox with Node.js and gws installed.",
};

/// Composio integration tool (available when Composio API key is set).
const COMPOSIO_TOOL: BuiltInTool = BuiltInTool {
    name: "composio",
    description: "Access Composio integrations for third-party APIs",
};

/// Composio Sessions compatibility helper (available for `ck_` consumer keys).
const COMPOSIO_SESSIONS_ALIAS_TOOL: BuiltInTool = BuiltInTool {
    name: "composio",
    description: "Use Composio Sessions integrations through MCP discovery",
};

/// Composio Sessions/MCP discovery tool (available for `ck_` consumer keys).
const COMPOSIO_SESSIONS_TOOL: BuiltInTool = BuiltInTool {
    name: "tool_search",
    description: "Discover and activate Composio Sessions/MCP tools on demand",
};

/// Delegate tool (available when agent delegation is configured).
const DELEGATE_TOOL: BuiltInTool = BuiltInTool {
    name: "delegate",
    description: "Delegate tasks to sub-agents with independent context",
};

/// Tools available in the Android session without a [`SecurityPolicy`].
///
/// Memory, web, and shared folder tools run directly in the FFI session and are always
/// active when the daemon is running and their respective handlers are registered.
const SESSION_TOOLS: &[&str] = &[
    "memory_store",
    "memory_recall",
    "memory_forget",
    "shared_folder_list",
    "shared_folder_read",
    "shared_folder_write",
    "workflow_folder_list",
    "workflow_folder_read",
    "workflow_folder_write",
];

/// Tools that require a [`SecurityPolicy`] and can only execute via daemon
/// channel routing (e.g. Telegram, Discord).
///
/// These are listed in the tool browser for visibility but cannot be
/// invoked from the Android session directly.
///
/// Used in tests to validate that every core tool is classified as either
/// a session tool or a security-policy tool.
#[cfg(test)]
const SECURITY_POLICY_TOOLS: &[&str] = &[
    "shell",
    "file_read",
    "file_write",
    "git_operations",
    "screenshot",
    "image_info",
];

/// Tool names that are incompatible with Android and should be hidden
/// from the tools browser UI. These tools require desktop CLI binaries
/// or capabilities not available on Android.
const ANDROID_EXCLUDED_TOOLS: &[&str] = &["browser", "screenshot"];

/// Inactive reason for tools that require daemon-channel routing.
const REASON_DAEMON_ONLY: &str =
    "Requires daemon-channel security policy; Android device commands use Termux approval";
const REASON_DISABLED: &str = "Disabled in settings";
const REASON_COMPOSIO_SESSIONS_PENDING: &str =
    "Configured; availability is verified when a chat session connects to Composio MCP";
const REASON_COMPOSIO_CLI_USER_KEY: &str =
    "CLI user login keys are not supported here; use the MCP consumer key from Composio";
const TERMUX_CAPABILITIES_PARAMETERS_JSON: &str = r#"{
  "type": "object",
  "properties": {
    "refresh": {
      "type": "boolean",
      "description": "Optional. When true, request fresh capability discovery from the bridge."
    }
  },
  "additionalProperties": false
}"#;

const TERMUX_RUN_PARAMETERS_JSON: &str = r#"{
  "type": "object",
  "properties": {
    "command": {
      "type": "string",
      "description": "Command name or executable path. Low-risk diagnostics run directly; medium/high-risk argv commands require user approval."
    },
    "arguments": {
      "type": "array",
      "items": { "type": "string" },
      "description": "Discrete argv arguments. Use a flat array such as [\"test1.txt\", \"test2.txt\"] for touch. Shell heredocs, command chaining, pipes, redirection, and interpreters require user approval; create a script file first instead of passing << heredoc syntax."
    },
    "working_directory": {
      "type": "string",
      "description": "Optional Termux working directory. Direct low-risk execution uses Termux home/usr paths; outside paths require user approval and may fail if Termux lacks access."
    },
    "timeout_seconds": {
      "type": "integer",
      "minimum": 1,
      "maximum": 120,
      "description": "Optional execution timeout. Defaults to 30 seconds."
    }
  },
  "required": ["command"],
  "additionalProperties": false
}"#;

const SANDBOX_EXECUTE_PARAMETERS_JSON: &str = r#"{
  "type": "object",
  "properties": {
    "command": {"type": "string", "description": "The shell command to execute"},
    "timeout": {"type": "integer", "description": "Timeout in seconds (default 30, max 60)"},
    "working_dir": {"type": "string", "description": "If set, run the command starting in this directory. The cd persists for subsequent calls."},
    "env": {"type": "object", "description": "Per-command environment variable overrides. Scoped to this call only."},
    "background": {"type": "boolean", "description": "Run detached as a background job. Returns a session_id; use sandbox_manage_process to check status."},
    "fresh": {"type": "boolean", "description": "If true, run in a one-shot isolated shell. Default false."}
  },
  "required": ["command"],
  "additionalProperties": false
}"#;

const SANDBOX_MANAGE_PROCESS_PARAMETERS_JSON: &str = r#"{
  "type": "object",
  "properties": {
    "action": {"type": "string", "enum": ["list", "log", "kill", "remove"], "description": "Action to perform: list, log, kill, or remove"},
    "session_id": {"type": "string", "description": "Session ID of the process (required for log, kill, remove)"},
    "offset": {"type": "integer", "description": "Line offset for log output (default: 0)"},
    "limit": {"type": "integer", "description": "Max lines to return for log (default: 200)"}
  },
  "required": ["action"],
  "additionalProperties": false
}"#;

const GOOGLE_WORKSPACE_PARAMETERS_JSON: &str = r#"{
  "type": "object",
  "properties": {
    "service": {"type": "string", "description": "Google Workspace service (drive, gmail, calendar, sheets, docs, slides, tasks, people, chat, classroom, forms, keep, meet, events)"},
    "resource": {"type": "string", "description": "Service resource (files, messages, events, spreadsheets, etc.)"},
    "method": {"type": "string", "description": "Method to call (list, get, create, update, delete)"},
    "sub_resource": {"type": "string", "description": "Optional sub-resource for nested operations"},
    "params": {"type": "object", "description": "URL/query parameters as key-value pairs"},
    "body": {"type": "object", "description": "Request body for POST/PATCH/PUT operations"},
    "format": {"type": "string", "enum": ["json", "table", "yaml", "csv"], "description": "Output format (default: json)"},
    "page_all": {"type": "boolean", "description": "Auto-paginate through all results"},
    "page_limit": {"type": "integer", "description": "Max pages to fetch when using page_all (default: 10)"}
  },
  "required": ["service", "resource", "method"],
  "additionalProperties": false
}"#;

/// Converts a [`BuiltInTool`] to an [`FfiToolSpec`] with `"built-in"` source.
///
/// The default active status is determined by whether the tool name appears
/// in [`SESSION_TOOLS`] (active) or [`SECURITY_POLICY_TOOLS`] (inactive).
/// Conditional tools (browser, HTTP, composio, delegate) default to inactive
/// and are overridden to active when added by [`list_tools_inner`].
fn builtin_to_spec(tool: &BuiltInTool) -> FfiToolSpec {
    let is_session = SESSION_TOOLS.contains(&tool.name);
    FfiToolSpec {
        name: tool.name.to_string(),
        description: tool.description.to_string(),
        source: "built-in".to_string(),
        parameters_json: "{}".to_string(),
        is_active: is_session,
        inactive_reason: if is_session {
            String::new()
        } else {
            REASON_DAEMON_ONLY.to_string()
        },
    }
}

fn active_builtin_spec(tool: &BuiltInTool) -> FfiToolSpec {
    let mut spec = builtin_to_spec(tool);
    spec.is_active = true;
    spec.inactive_reason = String::new();
    spec
}

fn composio_tool_specs_for_key(key: Option<&str>) -> Vec<FfiToolSpec> {
    let Some(key) = key.map(str::trim).filter(|value| !value.is_empty()) else {
        return Vec::new();
    };

    if config_compat::is_composio_sessions_key(key) {
        let mut composio = builtin_to_spec(&COMPOSIO_SESSIONS_ALIAS_TOOL);
        composio.inactive_reason = REASON_COMPOSIO_SESSIONS_PENDING.to_string();

        let mut tool_search = builtin_to_spec(&COMPOSIO_SESSIONS_TOOL);
        tool_search.inactive_reason = REASON_COMPOSIO_SESSIONS_PENDING.to_string();

        return vec![composio, tool_search];
    }

    if config_compat::is_composio_cli_user_key(key) {
        let mut spec = builtin_to_spec(&COMPOSIO_TOOL);
        spec.inactive_reason = REASON_COMPOSIO_CLI_USER_KEY.to_string();
        return vec![spec];
    }

    vec![active_builtin_spec(&COMPOSIO_TOOL)]
}

/// Lists all available tools based on daemon configuration and installed skills.
///
/// Enumerates built-in tools that are always active, conditionally adds
/// browser/HTTP/Composio/delegate tools based on config flags, then
/// appends tools from all installed skills.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running.
#[allow(clippy::too_many_lines)]
pub(crate) fn list_tools_inner() -> Result<Vec<FfiToolSpec>, FfiError> {
    let config = crate::runtime::with_daemon_config(zeroclaw::Config::clone)?;
    let workspace_dir = config.workspace_dir.clone();
    let browser_enabled = config.browser.enabled;
    let http_enabled = config.http_request.enabled;
    let composio_enabled = config.composio.enabled;
    let composio_key = config.composio.api_key.clone();
    let has_agents = !config.agents.is_empty();
    let shared_folder_enabled = crate::runtime::is_shared_folder_enabled().unwrap_or(false);
    let workflow_folder_enabled = crate::runtime::is_workflow_folder_enabled().unwrap_or(false);

    let mut specs: Vec<FfiToolSpec> = CORE_TOOLS
        .iter()
        .filter(|t| !ANDROID_EXCLUDED_TOOLS.contains(&t.name))
        .map(builtin_to_spec)
        .collect();
    apply_folder_tool_state(
        &mut specs,
        &[
            "shared_folder_list",
            "shared_folder_read",
            "shared_folder_write",
        ],
        shared_folder_enabled,
    );
    apply_folder_tool_state(
        &mut specs,
        &[
            "workflow_folder_list",
            "workflow_folder_read",
            "workflow_folder_write",
        ],
        workflow_folder_enabled,
    );

    if browser_enabled {
        specs.extend(
            BROWSER_TOOLS
                .iter()
                .filter(|t| !ANDROID_EXCLUDED_TOOLS.contains(&t.name))
                .map(|t| {
                    let mut s = builtin_to_spec(t);
                    s.is_active = true;
                    s.inactive_reason = String::new();
                    s
                }),
        );
    }

    if http_enabled {
        let mut s = builtin_to_spec(&HTTP_TOOL);
        s.is_active = true;
        s.inactive_reason = String::new();
        specs.push(s);
    }

    let termux_inactive_reason = if crate::termux_bridge_client::has_configured_bridge_auth_token()
    {
        "Termux bridge readiness is verified by the Android runtime status provider.".to_string()
    } else {
        "Zero Assist has not configured the Termux bridge token yet.".to_string()
    };
    let mut termux_capabilities_spec = builtin_to_spec(&TERMUX_CAPABILITIES_TOOL);
    termux_capabilities_spec.parameters_json = TERMUX_CAPABILITIES_PARAMETERS_JSON.to_string();
    termux_capabilities_spec.is_active = false;
    termux_capabilities_spec
        .inactive_reason
        .clone_from(&termux_inactive_reason);
    specs.push(termux_capabilities_spec);

    let mut termux_run_spec = builtin_to_spec(&TERMUX_RUN_TOOL);
    termux_run_spec.parameters_json = TERMUX_RUN_PARAMETERS_JSON.to_string();
    termux_run_spec.is_active = false;
    termux_run_spec.inactive_reason = termux_inactive_reason;
    specs.push(termux_run_spec);

    let sandbox_inactive_reason = if crate::sandbox_bridge_client::has_configured_bridge_auth_token()
    {
        "Linux sandbox bridge readiness is verified by the Android runtime status provider.".to_string()
    } else {
        "Zero Assist has not configured the Linux sandbox bridge token yet.".to_string()
    };
    let mut sandbox_execute_spec = builtin_to_spec(&SANDBOX_EXECUTE_TOOL);
    sandbox_execute_spec.parameters_json = SANDBOX_EXECUTE_PARAMETERS_JSON.to_string();
    sandbox_execute_spec.is_active = false;
    sandbox_execute_spec
        .inactive_reason
        .clone_from(&sandbox_inactive_reason);
    specs.push(sandbox_execute_spec);

    let mut sandbox_manage_process_spec = builtin_to_spec(&SANDBOX_MANAGE_PROCESS_TOOL);
    sandbox_manage_process_spec.parameters_json = SANDBOX_MANAGE_PROCESS_PARAMETERS_JSON.to_string();
    sandbox_manage_process_spec.is_active = false;
    sandbox_manage_process_spec.inactive_reason = sandbox_inactive_reason;
    specs.push(sandbox_manage_process_spec);

    let mut device_control_spec = builtin_to_spec(&DEVICE_CONTROL_TOOL);
    device_control_spec.parameters_json = DEVICE_CONTROL_PARAMETERS_JSON.to_string();
    device_control_spec.is_active = true;
    device_control_spec.inactive_reason =
        "Device control handler is registered at app startup. Enable the accessibility service in Android Settings.".to_string();
    specs.push(device_control_spec);

    let google_workspace_enabled = config.google_workspace.enabled;
    let gws_inactive_reason = if !google_workspace_enabled {
        "Google Workspace plugin is disabled. Enable it in Plugins.".to_string()
    } else if !crate::sandbox_bridge_client::has_configured_bridge_auth_token() {
        "Linux Sandbox with gws CLI required".to_string()
    } else {
        String::new()
    };
    let mut google_workspace_spec = builtin_to_spec(&GOOGLE_WORKSPACE_TOOL);
    google_workspace_spec.parameters_json = GOOGLE_WORKSPACE_PARAMETERS_JSON.to_string();
    google_workspace_spec.is_active =
        google_workspace_enabled && crate::sandbox_bridge_client::has_configured_bridge_auth_token();
    google_workspace_spec.inactive_reason = gws_inactive_reason;
    specs.push(google_workspace_spec);

    if composio_enabled {
        specs.extend(composio_tool_specs_for_key(composio_key.as_deref()));
    }

    if has_agents {
        let mut s = builtin_to_spec(&DELEGATE_TOOL);
        s.is_active = true;
        s.inactive_reason = String::new();
        specs.push(s);
    }

    let skills = crate::skills::load_skills_from_workspace(&workspace_dir);
    for (skill, tools) in &skills {
        for tool in tools {
            specs.push(FfiToolSpec {
                name: tool.name.clone(),
                description: tool.description.clone(),
                source: skill.name.clone(),
                parameters_json: "{}".to_string(),
                is_active: true,
                inactive_reason: String::new(),
            });
        }
    }

    Ok(specs)
}

fn apply_folder_tool_state(specs: &mut [FfiToolSpec], tool_names: &[&str], enabled: bool) {
    if enabled {
        return;
    }
    for spec in specs
        .iter_mut()
        .filter(|spec| tool_names.contains(&spec.name.as_str()))
    {
        spec.is_active = false;
        spec.inactive_reason = REASON_DISABLED.to_string();
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;

    #[test]
    fn test_list_tools_not_running() {
        let result = list_tools_inner();
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::StateError { detail } => {
                assert!(detail.contains("not running"));
            }
            other => panic!("expected StateError, got {other:?}"),
        }
    }

    #[test]
    fn test_core_tools_count() {
        assert_eq!(CORE_TOOLS.len(), 17);
    }

    #[test]
    fn test_builtin_to_spec() {
        let tool = &CORE_TOOLS[0];
        let spec = builtin_to_spec(tool);
        assert_eq!(spec.name, "shell");
        assert_eq!(spec.source, "built-in");
        assert_eq!(spec.parameters_json, "{}");
        assert!(!spec.description.is_empty());
    }

    #[test]
    fn test_browser_tools_count() {
        assert_eq!(BROWSER_TOOLS.len(), 2);
    }

    #[test]
    fn test_session_tools_are_active() {
        for &name in SESSION_TOOLS {
            let tool = CORE_TOOLS
                .iter()
                .find(|t| t.name == name)
                .unwrap_or_else(|| panic!("session tool {name} missing from CORE_TOOLS"));
            let spec = builtin_to_spec(tool);
            assert!(spec.is_active, "{name} should be active");
            assert!(
                spec.inactive_reason.is_empty(),
                "{name} should have empty inactive_reason"
            );
        }
    }

    #[test]
    fn test_security_policy_tools_are_inactive() {
        for &name in SECURITY_POLICY_TOOLS {
            let tool = CORE_TOOLS
                .iter()
                .find(|t| t.name == name)
                .unwrap_or_else(|| panic!("security tool {name} missing from CORE_TOOLS"));
            let spec = builtin_to_spec(tool);
            assert!(!spec.is_active, "{name} should be inactive");
            assert_eq!(
                spec.inactive_reason, REASON_DAEMON_ONLY,
                "{name} should have daemon-only reason"
            );
        }
    }

    #[test]
    fn test_android_facing_tool_descriptions_reduce_ambiguous_routing() {
        let shell = CORE_TOOLS.iter().find(|t| t.name == "shell").unwrap();
        assert!(shell.description.contains("daemon-channel security policy"));
        assert!(shell.description.contains("sandbox_execute"));

        assert!(TERMUX_RUN_TOOL.description.contains("user's existing Termux environment"));
        assert!(TERMUX_RUN_TOOL.description.contains("sandbox_execute instead"));
    }

    #[test]
    fn test_session_and_security_cover_all_core_tools() {
        for tool in CORE_TOOLS {
            assert!(
                SESSION_TOOLS.contains(&tool.name) || SECURITY_POLICY_TOOLS.contains(&tool.name),
                "core tool {:?} is in neither SESSION_TOOLS nor SECURITY_POLICY_TOOLS",
                tool.name
            );
        }
    }

    #[test]
    fn test_excluded_tools_not_in_core_filtered() {
        let filtered: Vec<&BuiltInTool> = CORE_TOOLS
            .iter()
            .filter(|t| !ANDROID_EXCLUDED_TOOLS.contains(&t.name))
            .collect();
        assert!(!filtered.iter().any(|t| t.name == "screenshot"));
        assert!(filtered.iter().any(|t| t.name == "shell"));
    }

    #[test]
    fn test_excluded_tools_not_in_browser_filtered() {
        let filtered: Vec<&BuiltInTool> = BROWSER_TOOLS
            .iter()
            .filter(|t| !ANDROID_EXCLUDED_TOOLS.contains(&t.name))
            .collect();
        assert!(!filtered.iter().any(|t| t.name == "browser"));
        assert!(filtered.iter().any(|t| t.name == "browser_open"));
    }

    #[test]
    fn test_conditional_tools_default_inactive() {
        let http = builtin_to_spec(&HTTP_TOOL);
        assert!(!http.is_active, "http_request should default to inactive");
        assert_eq!(http.inactive_reason, REASON_DAEMON_ONLY);

        let composio = builtin_to_spec(&COMPOSIO_TOOL);
        assert!(!composio.is_active, "composio should default to inactive");

        let delegate = builtin_to_spec(&DELEGATE_TOOL);
        assert!(!delegate.is_active, "delegate should default to inactive");

        for browser_tool in BROWSER_TOOLS {
            let spec = builtin_to_spec(browser_tool);
            assert!(
                !spec.is_active,
                "{} should default to inactive",
                browser_tool.name
            );
        }
    }

    #[test]
    fn test_composio_project_key_activates_legacy_tool() {
        let specs = composio_tool_specs_for_key(Some("ak_test_project_key"));

        assert_eq!(specs.len(), 1);
        assert_eq!(specs[0].name, "composio");
        assert!(specs[0].is_active);
        assert!(specs[0].inactive_reason.is_empty());
    }

    #[test]
    fn test_composio_sessions_key_lists_pending_tool_search() {
        let specs = composio_tool_specs_for_key(Some("ck_test_sessions_key"));

        assert_eq!(specs.len(), 2);
        let composio = specs
            .iter()
            .find(|spec| spec.name == "composio")
            .expect("composio Sessions helper should be present");
        assert!(!composio.is_active);
        assert_eq!(composio.inactive_reason, REASON_COMPOSIO_SESSIONS_PENDING);
        assert!(composio.description.contains("Sessions"));

        let tool_search = specs
            .iter()
            .find(|spec| spec.name == "tool_search")
            .expect("tool_search should be listed for Composio Sessions");
        assert!(!tool_search.is_active);
        assert_eq!(
            tool_search.inactive_reason,
            REASON_COMPOSIO_SESSIONS_PENDING
        );
    }

    #[test]
    fn test_composio_cli_user_key_is_inactive() {
        let specs = composio_tool_specs_for_key(Some("uak_test_user_key"));

        assert_eq!(specs.len(), 1);
        assert_eq!(specs[0].name, "composio");
        assert!(!specs[0].is_active);
        assert_eq!(specs[0].inactive_reason, REASON_COMPOSIO_CLI_USER_KEY);
    }

    #[test]
    fn test_composio_blank_key_adds_no_tools() {
        assert!(composio_tool_specs_for_key(Some("   ")).is_empty());
        assert!(composio_tool_specs_for_key(None).is_empty());
    }
}
