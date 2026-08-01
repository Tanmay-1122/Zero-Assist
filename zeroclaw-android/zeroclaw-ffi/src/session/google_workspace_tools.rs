/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! Google Workspace CLI (`gws`) tool wrapper for Android sessions.
//!
//! Routes `gws` commands through the sandbox bridge since the CLI is
//! installed inside the Linux sandbox, not on the Android host.

use async_trait::async_trait;
use serde_json::json;
use zeroclaw::tools::{Tool, ToolResult};

use super::fail_result;

/// Maximum output size in bytes (1MB).
const MAX_OUTPUT_BYTES: usize = 1_048_576;

/// FFI-native Google Workspace tool backed by the sandbox bridge.
///
/// Builds the same `gws` CLI invocation as the upstream
/// [`GoogleWorkspaceTool`](zeroclaw::tools::GoogleWorkspaceTool) and
/// routes it through the sandbox bridge's `/execute` endpoint.
pub(super) struct FfiGoogleWorkspaceTool {
    allowed_services: Vec<String>,
    allowed_operations: Vec<zeroclaw_config::schema::GoogleWorkspaceAllowedOperation>,
    credentials_path: Option<String>,
    default_account: Option<String>,
    rate_limit_per_minute: u32,
    timeout_secs: u64,
    audit_log: bool,
}

impl FfiGoogleWorkspaceTool {
    pub fn new(config: zeroclaw_config::schema::GoogleWorkspaceConfig) -> Self {
        use zeroclaw_config::schema::DEFAULT_GWS_SERVICES;

        let services = if config.allowed_services.is_empty() {
            DEFAULT_GWS_SERVICES
                .iter()
                .map(|s| (*s).to_string())
                .collect()
        } else {
            config
                .allowed_services
                .into_iter()
                .map(|s| s.trim().to_string())
                .collect()
        };
        let operations = config
            .allowed_operations
            .into_iter()
            .map(|op| zeroclaw_config::schema::GoogleWorkspaceAllowedOperation {
                service: op.service.trim().to_string(),
                resource: op.resource.trim().to_string(),
                sub_resource: op.sub_resource.as_deref().map(|s| s.trim().to_string()),
                methods: op.methods.iter().map(|m| m.trim().to_string()).collect(),
            })
            .collect();
        Self {
            allowed_services: services,
            allowed_operations: operations,
            credentials_path: config.credentials_path,
            default_account: config.default_account,
            rate_limit_per_minute: config.rate_limit_per_minute,
            timeout_secs: config.timeout_secs,
            audit_log: config.audit_log,
        }
    }

    fn is_operation_allowed(
        &self,
        service: &str,
        resource: &str,
        sub_resource: Option<&str>,
        method: &str,
    ) -> bool {
        if self.allowed_operations.is_empty() {
            return true;
        }
        self.allowed_operations.iter().any(|op| {
            op.service == service
                && op.resource == resource
                && op.sub_resource.as_deref() == sub_resource
                && op.methods.iter().any(|m| m == method)
        })
    }
}

#[async_trait]
impl Tool for FfiGoogleWorkspaceTool {
    fn name(&self) -> &'static str {
        "google_workspace"
    }

    fn description(&self) -> &'static str {
        "Interact with Google Workspace services (Drive, Gmail, Calendar, Sheets, Docs, Slides, Tasks, People, Chat, Classroom, Forms, Keep, Meet, Events) via the gws CLI. Requires sandbox with Node.js and gws installed."
    }

    fn parameters_schema(&self) -> serde_json::Value {
        json!({
            "type": "object",
            "properties": {
                "service": {
                    "type": "string",
                    "description": "Google Workspace service (drive, gmail, calendar, sheets, docs, slides, tasks, people, chat, classroom, forms, keep, meet, events)"
                },
                "resource": {
                    "type": "string",
                    "description": "Service resource (files, messages, events, spreadsheets, etc.)"
                },
                "method": {
                    "type": "string",
                    "description": "Method to call (list, get, create, update, delete)"
                },
                "sub_resource": {
                    "type": "string",
                    "description": "Optional sub-resource for nested operations"
                },
                "params": {
                    "type": "object",
                    "description": "URL/query parameters as key-value pairs"
                },
                "body": {
                    "type": "object",
                    "description": "Request body for POST/PATCH/PUT operations"
                },
                "format": {
                    "type": "string",
                    "enum": ["json", "table", "yaml", "csv"],
                    "description": "Output format (default: json)"
                },
                "page_all": {
                    "type": "boolean",
                    "description": "Auto-paginate through all results"
                },
                "page_limit": {
                    "type": "integer",
                    "description": "Max pages to fetch when using page_all (default: 10)"
                }
            },
            "required": ["service", "resource", "method"],
            "additionalProperties": false
        })
    }

    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let service = args
            .get("service")
            .and_then(|v| v.as_str())
            .ok_or_else(|| anyhow::anyhow!("Missing 'service' parameter"))?;
        let resource = args
            .get("resource")
            .and_then(|v| v.as_str())
            .ok_or_else(|| anyhow::anyhow!("Missing 'resource' parameter"))?;
        let method = args
            .get("method")
            .and_then(|v| v.as_str())
            .ok_or_else(|| anyhow::anyhow!("Missing 'method' parameter"))?;

        let sub_resource: Option<&str> = args.get("sub_resource").and_then(|v| v.as_str());

        // Validate inputs — only lowercase alphanumeric, underscore, hyphen.
        for (label, value) in [
            ("service", service),
            ("resource", resource),
            ("method", method),
        ] {
            if !value
                .chars()
                .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '_' || c == '-')
            {
                return Ok(ToolResult {
                    success: false,
                    output: String::new(),
                    error: Some(format!(
                        "Invalid characters in '{label}': only lowercase alphanumeric, underscore, and hyphen are allowed"
                    )),
                metadata: None,
                });
            }
        }
        if let Some(sub) = sub_resource {
            if !sub
                .chars()
                .all(|c| c.is_ascii_lowercase() || c.is_ascii_digit() || c == '_' || c == '-')
            {
                return Ok(ToolResult {
                    success: false,
                    output: String::new(),
                    error: Some(
                        "Invalid characters in 'sub_resource': only lowercase alphanumeric, underscore, and hyphen are allowed"
                            .into(),
                    ),
                metadata: None,
                });
            }
        }

        if !self.allowed_services.iter().any(|s| s == service) {
            return Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some(format!(
                    "Service '{service}' is not in the allowed services list. Allowed: {}",
                    self.allowed_services.join(", ")
                )),
            metadata: None,
            });
        }

        if !self.is_operation_allowed(service, resource, sub_resource, method) {
            let op_path = match sub_resource {
                Some(sub) => format!("{service}/{resource}/{sub}/{method}"),
                None => format!("{service}/{resource}/{method}"),
            };
            return Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some(format!(
                    "Operation '{op_path}' is not in the allowed operations list"
                )),
            metadata: None,
            });
        }

        // Build gws CLI arguments.
        let mut cmd_args: Vec<String> = vec![service.into(), resource.into()];
        if let Some(sub) = sub_resource {
            cmd_args.push(sub.into());
        }
        cmd_args.push(method.into());

        if let Some(params) = args.get("params") {
            if !params.is_object() {
                return Ok(ToolResult {
                    success: false,
                    output: String::new(),
                    error: Some("'params' must be an object".into()),
                metadata: None,
                });
            }
            cmd_args.push("--params".into());
            cmd_args.push(params.to_string());
        }

        if let Some(body) = args.get("body") {
            if !body.is_object() {
                return Ok(ToolResult {
                    success: false,
                    output: String::new(),
                    error: Some("'body' must be an object".into()),
                metadata: None,
                });
            }
            cmd_args.push("--json".into());
            cmd_args.push(body.to_string());
        }

        if let Some(format_val) = args.get("format").and_then(|v| v.as_str()) {
            match format_val {
                "json" | "table" | "yaml" | "csv" => {
                    cmd_args.push("--format".into());
                    cmd_args.push(format_val.into());
                }
                _ => {
                    return Ok(ToolResult {
                        success: false,
                        output: String::new(),
                        error: Some(format!(
                            "Invalid format '{format_val}': must be json, table, yaml, or csv"
                        )),
                    metadata: None,
                    });
                }
            }
        }

        let page_all = args
            .get("page_all")
            .and_then(|v| v.as_bool())
            .unwrap_or(false);
        let page_limit = args.get("page_limit").and_then(|v| v.as_u64());
        if page_all {
            cmd_args.push("--page-all".into());
        }
        if page_all || page_limit.is_some() {
            cmd_args.push("--page-limit".into());
            cmd_args.push(page_limit.unwrap_or(10).to_string());
        }

        if let Some(ref account) = self.default_account {
            cmd_args.push("--account".into());
            cmd_args.push(account.clone());
        }

        if self.audit_log {
            tracing::info!(
                tool = "google_workspace",
                service = service,
                resource = resource,
                sub_resource = sub_resource.unwrap_or(""),
                method = method,
                "gws audit: executing API call via sandbox bridge"
            );
        }

        // Build the shell command string for the sandbox bridge.
        let gws_cmd = build_gws_command_string(&cmd_args);
        let bridge_args = json!({
            "command": gws_cmd,
            "timeout": self.timeout_secs,
        });

        match crate::sandbox_bridge_client::execute(bridge_args).await {
            Ok(value) => {
                let success = value
                    .get("success")
                    .and_then(serde_json::Value::as_bool)
                    .unwrap_or(false);
                let raw_output = serde_json::to_string_pretty(&value)
                    .unwrap_or_else(|_| value.to_string());
                let output = truncate_output(&raw_output);
                Ok(ToolResult {
                    success,
                    output: output.clone(),
                    error: if success { None } else { Some(output) },
                metadata: None,
                })
            }
            Err(error) => Ok(fail_result(error)),
        }
    }
}

/// Shell-escapes arguments and builds a `gws` command string.
fn build_gws_command_string(args: &[String]) -> String {
    let mut cmd = String::from("gws");
    for arg in args {
        cmd.push(' ');
        // Simple shell-escaping: wrap in single quotes if needed.
        if arg
            .chars()
            .any(|c| c.is_whitespace() || c == '\'' || c == '"' || c == '$' || c == '\\')
        {
            cmd.push('\'');
            for c in arg.chars() {
                if c == '\'' {
                    cmd.push_str("'\\''");
                } else {
                    cmd.push(c);
                }
            }
            cmd.push('\'');
        } else {
            cmd.push_str(arg);
        }
    }
    cmd
}

/// Truncate output to MAX_OUTPUT_BYTES, preserving valid UTF-8.
fn truncate_output(s: &str) -> String {
    if s.len() <= MAX_OUTPUT_BYTES {
        return s.to_string();
    }
    let mut boundary = MAX_OUTPUT_BYTES;
    while boundary > 0 && !s.is_char_boundary(boundary) {
        boundary -= 1;
    }
    let mut result = s[..boundary].to_string();
    result.push_str("\n... [output truncated at 1MB]");
    result
}