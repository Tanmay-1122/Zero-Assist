//! Uno Q Bridge — communication bridge tools for Arduino Uno Q variant.

use crate::device::DeviceRegistry;
use crate::protocol::{ZcCommand, ZcResponse};
use crate::tool_result::{tool_err, tool_ok};
use async_trait::async_trait;
use serde_json::{Value, json};
use std::sync::Arc;
use tokio::sync::RwLock;
use zeroclaw_api::tool::{Tool, ToolResult};

pub struct UnoQBridgeSendTool {
    pub registry: Arc<RwLock<DeviceRegistry>>,
}

#[async_trait]
impl Tool for UnoQBridgeSendTool {
    fn name(&self) -> &str { "uno_q_send" }
    fn description(&self) -> &str {
        "Send a command to an Arduino Uno Q bridge device and receive its response."
    }
    fn parameters_schema(&self) -> Value {
        json!({
            "type": "object",
            "properties": {
                "device":  { "type": "string", "description": "Device alias" },
                "command": { "type": "string", "description": "Command string" },
                "params":  { "type": "object", "description": "Optional parameters", "additionalProperties": true }
            },
            "required": ["device", "command"]
        })
    }
    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let alias = match args.get("device").and_then(|v| v.as_str()) {
            Some(a) => a.to_string(),
            None => return tool_err("Missing required parameter: device"),
        };
        let command = match args.get("command").and_then(|v| v.as_str()) {
            Some(c) => c.to_string(),
            None => return tool_err("Missing required parameter: command"),
        };
        let cmd_params = args.get("params").cloned().unwrap_or(Value::Object(serde_json::Map::new()));
        let registry = self.registry.read().await;
        let Some(device) = registry.get(&alias) else {
            return tool_err(format!("Device '{alias}' not found"));
        };
        let Some(ref transport) = device.transport else {
            return tool_err(format!("Device '{alias}' has no active transport"));
        };
        let cmd = ZcCommand::new(command.clone(), cmd_params);
        match transport.send(cmd).await {
            Ok(ZcResponse { ok: true, data, .. }) => {
                tool_ok(serde_json::to_string_pretty(&data).unwrap_or_else(|_| data.to_string()))
            }
            Ok(ZcResponse { error: Some(e), .. }) => tool_err(format!("Command '{command}' failed: {e}")),
            Ok(_) => tool_err("Bridge returned no response data"),
            Err(e) => tool_err(format!("Transport error: {e}")),
        }
    }
}

pub struct UnoQBridgeStatusTool {
    pub registry: Arc<RwLock<DeviceRegistry>>,
}

#[async_trait]
impl Tool for UnoQBridgeStatusTool {
    fn name(&self) -> &str { "uno_q_status" }
    fn description(&self) -> &str {
        "Query the status and connected peripherals of an Arduino Uno Q bridge device."
    }
    fn parameters_schema(&self) -> Value {
        json!({
            "type": "object",
            "properties": {
                "device": { "type": "string", "description": "Device alias" }
            },
            "required": ["device"]
        })
    }
    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let alias = match args.get("device").and_then(|v| v.as_str()) {
            Some(a) => a.to_string(),
            None => return tool_err("Missing required parameter: device"),
        };
        let registry = self.registry.read().await;
        let Some(device) = registry.get(&alias) else {
            return tool_err(format!("Device '{alias}' not found"));
        };
        let Some(ref transport) = device.transport else {
            return tool_err(format!("Device '{alias}' has no active transport"));
        };
        let cmd = ZcCommand::simple("status");
        match transport.send(cmd).await {
            Ok(ZcResponse { ok: true, data, .. }) => {
                tool_ok(serde_json::to_string_pretty(&data).unwrap_or_else(|_| data.to_string()))
            }
            Ok(ZcResponse { error: Some(e), .. }) => tool_err(e),
            Ok(_) => tool_err("Bridge returned no status data"),
            Err(e) => tool_err(format!("Transport error: {e}")),
        }
    }
}
