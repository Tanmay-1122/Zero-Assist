/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! Linux sandbox tool wrappers used by Android sessions.

use std::sync::{Arc, Mutex};
use std::path::PathBuf;

use async_trait::async_trait;
use zeroclaw::tools::{Tool, ToolResult};
use zeroclaw_runtime::capabilities::shell_runtime::ShellRuntime;

use super::fail_result;

/// FFI-native Linux sandbox shell execution tool backed by the local bridge.
///
/// Holds a shared [`ShellRuntime`] that mirrors the sandbox's persistent shell
/// state (cwd, env, exit code, jobs, history). After each command the runtime
/// is updated from the bridge response so that the system prompt's
/// `## Shell Environment` section stays current.
pub(super) struct FfiSandboxExecuteTool {
    shell_runtime: Arc<Mutex<ShellRuntime>>,
}

impl FfiSandboxExecuteTool {
    pub fn new(shell_runtime: Arc<Mutex<ShellRuntime>>) -> Self {
        Self { shell_runtime }
    }
}

#[async_trait]
impl Tool for FfiSandboxExecuteTool {
    fn name(&self) -> &'static str {
        "sandbox_execute"
    }

    fn description(&self) -> &'static str {
        "Execute commands inside an isolated Alpine Linux sandbox (PRoot). Use this for Linux packages (apk add), filesystem operations, compilation, scripts, Python, Node.js, git, and any work that should NOT affect the user's Termux environment. Pre-installed: bash, python3 (pip), nodejs, git, curl, wget, jq, openssh, rsync. The sandbox is self-contained and disposable — changes stay inside the sandbox.\n\n\
        Shell session is PERSISTENT across calls within THIS conversation: cwd, exported environment variables, and any in-shell state carry from one call to the next, just like a normal terminal. So \"cd /tmp\" in one call, then \"pwd\" in the next, returns \"/tmp\". You do NOT need to chain \"cd dir && command\" unless you want directory changes to be one-shot.\n\n\
        Limits and behavior:\n\
        - Output is capped at 15000 characters per stream; for large output, pipe through head/tail.\n\
        - Default timeout: 30s for regular commands, auto-extended to 180s for package installs (pip, apk, npm, cargo, etc.) and builds.\n\
        - For operations that may take longer than 180s, set background=true and check status with sandbox_manage_process.\n\
        - Fullscreen TUIs (top, htop, vim, less, nano) WILL NOT WORK — the sandbox has no PTY. Use non-interactive variants: \"top -bn1\", \"ps aux\", etc.\n\
        - Set fresh=true to run in a one-shot isolated shell that doesn't share state with the persistent session.\n\n\
        INSTALL PACKAGES with: apk add <package>\n\n\
        ERROR HANDLING:\n\
        - Read error diagnostics carefully — they contain specific guidance for fixing issues.\n\
        - For pip \"externally-managed-environment\" errors: use pip install --break-system-packages\n\
        - For timeouts: command output shows progress before timeout; retry with background=true if needed.\n\
        - Docker is NOT available — use native Alpine Linux tools instead.\n\
        - \"which\" returning exit 1 means command not installed — use \"apk search <name>\" to find packages."
    }

    fn parameters_schema(&self) -> serde_json::Value {
        serde_json::json!({
            "type": "object",
            "properties": {
                "command": {
                    "type": "string",
                    "description": "The shell command to execute"
                },
                "timeout": {
                    "type": "integer",
                    "description": "Timeout in seconds (default 30, max 60 foreground / 180 background)"
                },
                "working_dir": {
                    "type": "string",
                    "description": "If set, run the command starting in this directory. The cd persists for subsequent calls."
                },
                "env": {
                    "type": "object",
                    "description": "Per-command environment variable overrides. Scoped to this call only."
                },
                "background": {
                    "type": "boolean",
                    "description": "Run detached as a background job. Returns a session_id; use sandbox_manage_process to check on it."
                },
                "fresh": {
                    "type": "boolean",
                    "description": "If true, run in a one-shot isolated shell. Default false."
                }
            },
            "required": ["command"],
            "additionalProperties": false
        })
    }

    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let command = args.get("command").and_then(|v| v.as_str()).unwrap_or("?").to_string();
        let is_fresh = args.get("fresh").and_then(serde_json::Value::as_bool).unwrap_or(false);
        let is_background = args.get("background").and_then(serde_json::Value::as_bool).unwrap_or(false);
        eprintln!("===== SandboxExecuteTool invoked =====");
        eprintln!("  command = {command}");
        eprintln!("  args = {args}");
        match crate::sandbox_bridge_client::execute(args).await {
            Ok(value) => {
                let success = value
                    .get("success")
                    .and_then(serde_json::Value::as_bool)
                    .unwrap_or(false);
                let exit_code = value
                    .get("exit_code")
                    .and_then(serde_json::Value::as_i64)
                    .unwrap_or(-1) as i32;
                let cwd = value
                    .get("cwd")
                    .and_then(serde_json::Value::as_str)
                    .unwrap_or("/root");
                let stdout = value
                    .get("stdout")
                    .and_then(serde_json::Value::as_str)
                    .unwrap_or("");
                let stderr = value
                    .get("stderr")
                    .and_then(serde_json::Value::as_str)
                    .unwrap_or("");

                // Sync shell state to ShellRuntime so the system prompt stays current.
                // Skip for fresh (isolated) and background (detached) commands.
                if !is_fresh && !is_background {
                    if let Ok(mut rt) = self.shell_runtime.lock() {
                        rt.update_after_command(&command, exit_code, stdout, stderr);
                        // Override cwd with the ground-truth value from the sandbox.
                        rt.cwd = PathBuf::from(cwd);
                    }
                }

                let output =
                    serde_json::to_string_pretty(&value).unwrap_or_else(|_| value.to_string());
                eprintln!("===== SandboxExecuteTool result =====");
                eprintln!("  success = {success}");
                eprintln!("  output = {output}");
                Ok(ToolResult {
                    success,
                    output: output.clone(),
                    error: (!success).then_some(output),
                metadata: None,
                })
            }
            Err(error) => {
                eprintln!("===== SandboxExecuteTool FAILED =====");
                eprintln!("  error = {error}");
                Ok(fail_result(error))
            }
        }
    }
}

/// FFI-native Linux sandbox process management tool backed by the local bridge.
pub(super) struct FfiSandboxManageProcessTool;

#[async_trait]
impl Tool for FfiSandboxManageProcessTool {
    fn name(&self) -> &'static str {
        "sandbox_manage_process"
    }

    fn description(&self) -> &'static str {
        "Manage background shell processes started with sandbox_execute (background=true).\n\n\
        Actions:\n\
        - list: Show all running and finished background processes\n\
        - log: Get output from a process (params: session_id, offset, limit)\n\
        - kill: Terminate a running process (params: session_id)\n\
        - remove: Remove a finished process from the list (params: session_id)"
    }

    fn parameters_schema(&self) -> serde_json::Value {
        serde_json::json!({
            "type": "object",
            "properties": {
                "action": {
                    "type": "string",
                    "enum": ["list", "log", "kill", "remove"],
                    "description": "Action to perform: list, log, kill, or remove"
                },
                "session_id": {
                    "type": "string",
                    "description": "Session ID of the process (required for log, kill, remove)"
                },
                "offset": {
                    "type": "integer",
                    "description": "Line offset for log output (default: 0)"
                },
                "limit": {
                    "type": "integer",
                    "description": "Max lines to return for log (default: 200)"
                }
            },
            "required": ["action"],
            "additionalProperties": false
        })
    }

    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let action = args.get("action").and_then(|v| v.as_str()).unwrap_or("?");
        eprintln!("===== SandboxManageProcessTool invoked =====");
        eprintln!("  action = {action}");
        eprintln!("  args = {args}");
        match crate::sandbox_bridge_client::manage_process(args).await {
            Ok(value) => {
                let success = value
                    .get("success")
                    .and_then(serde_json::Value::as_bool)
                    .unwrap_or(false);
                let output =
                    serde_json::to_string_pretty(&value).unwrap_or_else(|_| value.to_string());
                eprintln!("===== SandboxManageProcessTool result =====");
                eprintln!("  success = {success}");
                eprintln!("  output = {output}");
                Ok(ToolResult {
                    success,
                    output: output.clone(),
                    error: (!success).then_some(output),
                metadata: None,
                })
            }
            Err(error) => {
                eprintln!("===== SandboxManageProcessTool FAILED =====");
                eprintln!("  error = {error}");
                Ok(fail_result(error))
            }
        }
    }
}