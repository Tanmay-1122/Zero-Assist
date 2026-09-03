//! Capabilities tool — query a device's supported commands and features.

use crate::device::DeviceRegistry;
use crate::protocol::{ZcCommand, ZcResponse};
use crate::tool_result::{tool_err, tool_ok};
use async_trait::async_trait;
use serde_json::{Value, json};
use std::sync::Arc;
use tokio::sync::RwLock;
use zeroclaw_api::tool::{Tool, ToolResult};

pub struct CapabilitiesTool {
    pub registry: Arc<RwLock<DeviceRegistry>>,
}

#[async_trait]
impl Tool for CapabilitiesTool {
    fn name(&self) -> &str { "capabilities" }
    fn description(&self) -> &str { "Query the capabilities of a connected device." }
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
        let cmd = ZcCommand::simple("capabilities");
        match transport.send(cmd).await {
            Ok(ZcResponse { ok: true, data, .. }) => {
                tool_ok(serde_json::to_string_pretty(&data).unwrap_or_else(|_| data.to_string()))
            }
            Ok(ZcResponse { error: Some(e), .. }) => tool_err(e),
            Ok(_) => tool_err("Device returned no capability data"),
            Err(e) => tool_err(format!("Transport error: {e}")),
        }
    }
}
