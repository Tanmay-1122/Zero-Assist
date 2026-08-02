/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! Termux tool wrappers used by Android sessions.

use async_trait::async_trait;
use zeroclaw::tools::{Tool, ToolResult};

use super::fail_result;

/// FFI-native Termux capability discovery tool backed by the local bridge.
pub(super) struct FfiTermuxCapabilitiesTool;

#[async_trait]
impl Tool for FfiTermuxCapabilitiesTool {
    fn name(&self) -> &'static str {
        "termux_get_capabilities"
    }

    fn description(&self) -> &'static str {
        "LEGACY / OPT-IN ONLY. Inspect the user's local Termux runtime through Zero Assist's authenticated bridge. Returns installed command availability, Python version, workspace paths, proot status, and execution limits. This tool is only registered when the user explicitly enables the Termux plugin — the Linux sandbox (sandbox_execute) is the default shell backend."
    }

    fn parameters_schema(&self) -> serde_json::Value {
        serde_json::json!({
            "type": "object",
            "properties": {
                "refresh": {
                    "type": "boolean",
                    "description": "Optional. When true, request fresh capability discovery from the bridge."
                }
            },
            "additionalProperties": false
        })
    }

    async fn execute(&self, _args: serde_json::Value) -> anyhow::Result<ToolResult> {
        match crate::termux_bridge_client::capabilities().await {
            Ok(value) => Ok(ToolResult {
                success: true,
                output: serde_json::to_string_pretty(&value).unwrap_or_else(|_| value.to_string()),
                error: None,
            metadata: None,
            }),
            Err(error) => Ok(fail_result(error)),
        }
    }
}

/// FFI-native low-risk Termux command runner backed by the local bridge.
pub(super) struct FfiTermuxRunTool;

#[async_trait]
impl Tool for FfiTermuxRunTool {
    fn name(&self) -> &'static str {
        "termux_run"
    }

    fn description(&self) -> &'static str {
        "LEGACY / OPT-IN ONLY. Execute commands directly in the user's existing Termux environment on the Android device. This tool is only registered when the user explicitly enables the Termux plugin. Use ONLY when the user explicitly asks to interact with their Termux installation, its files, or Android host tools. For ALL general Linux commands, packages, and scripting, use sandbox_execute instead — it provides the full Alpine Linux environment without affecting Termux."
    }

    fn parameters_schema(&self) -> serde_json::Value {
        serde_json::json!({
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
        })
    }

    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        match crate::termux_bridge_client::execute_low_risk(args).await {
            Ok(value) => {
                let success = value
                    .get("success")
                    .and_then(serde_json::Value::as_bool)
                    .unwrap_or(false);
                let output =
                    serde_json::to_string_pretty(&value).unwrap_or_else(|_| value.to_string());
                Ok(ToolResult {
                    success,
                    output: output.clone(),
                    error: (!success).then_some(output),
                metadata: None,
                })
            }
            Err(error) => Ok(fail_result(error)),
        }
    }
}