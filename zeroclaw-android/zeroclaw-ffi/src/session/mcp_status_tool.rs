/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! MCP server status tool for Android sessions.
//!
//! Reports the configured MCP servers and their live connection status
//! (connected / unreachable, tool counts, failure reason). Registered
//! whenever MCP is enabled so the model can answer connectivity
//! questions even when a server is unreachable and its tools never
//! made it into the registry.

use async_trait::async_trait;
use serde_json::json;
use zeroclaw::tools::{Tool, ToolResult};

use super::{fail_result, MCP_STATUS_SNAPSHOT};

/// Tool that reports configured MCP servers and their connection status.
pub(super) struct FfiMcpStatusTool;

impl FfiMcpStatusTool {
    pub(super) fn new() -> Self {
        Self
    }
}

#[async_trait]
impl Tool for FfiMcpStatusTool {
    fn name(&self) -> &'static str {
        "mcp_status"
    }

    fn description(&self) -> &'static str {
        "Report the configured MCP (Model Context Protocol) servers and \
         their connection status: connected, unreachable, or errored, with \
         tool counts and failure reasons. Use this to answer questions about \
         MCP connectivity or which MCP servers are available. Note: MCP \
         tools, when connected, are invoked directly under a \
         '<server>__<tool>' naming scheme."
    }

    fn parameters_schema(&self) -> serde_json::Value {
        json!({
            "type": "object",
            "properties": {},
            "additionalProperties": false
        })
    }

    async fn execute(&self, _args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let statuses = super::get_live_mcp_statuses().await;

        if statuses.is_empty() {
            return Ok(ToolResult {
                success: true,
                output: "No MCP servers are configured.".to_string(),
                error: None,
                blocks: Vec::new(),
                metadata: None,
            });
        }

        let entries: Vec<serde_json::Value> = statuses
            .iter()
            .map(|s| {
                let sanitized_name = zeroclaw::tools::sanitize_identifier(&s.name);
                if s.connected {
                    json!({
                        "name": s.name,
                        "status": "connected",
                        "tools": s.tool_count,
                        "subagent_tool": format!("mcp_agent_{sanitized_name}"),
                    })
                } else if s.error.is_none() {
                    json!({
                        "name": s.name,
                        "status": "ready",
                        "mode": "lazy (connects on demand)",
                        "subagent_tool": format!("mcp_agent_{sanitized_name}"),
                        "description": format!("Server is configured and ready. Send goals/tasks to sub-agent tool `mcp_agent_{sanitized_name}`."),
                    })
                } else {
                    json!({
                        "name": s.name,
                        "status": "unreachable",
                        "tools": 0,
                        "error": s.error.as_deref().unwrap_or("connection failed"),
                    })
                }
            })
            .collect();

        let output = serde_json::to_string_pretty(&json!({
            "mcp_enabled": true,
            "servers": entries,
        }))
        .unwrap_or_else(|e| format!("failed to serialize MCP status: {e}"));

        eprintln!("[MCP STATUS TOOL] Returning status for {} server(s)", statuses.len());
        for s in &statuses {
            eprintln!(
                "[MCP STATUS TOOL]   name={} connected={} tools={} error={:?}",
                s.name, s.connected, s.tool_count, s.error
            );
        }

        Ok(ToolResult {
            success: true,
            output,
            error: None,
            blocks: Vec::new(),
            metadata: None,
        })
    }
}
