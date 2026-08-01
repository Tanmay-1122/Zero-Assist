//! Uno Q Setup — guided setup and configuration for Arduino Uno Q peripheral bridge.

use crate::device::DeviceRegistry;
use crate::protocol::{ZcCommand, ZcResponse};
use crate::tool_result::{tool_err, tool_ok};
use async_trait::async_trait;
use serde_json::{Value, json};
use std::sync::Arc;
use tokio::sync::RwLock;
use zeroclaw_api::tool::{Tool, ToolResult};

pub struct UnoQSetupTool {
    pub registry: Arc<RwLock<DeviceRegistry>>,
}

#[async_trait]
impl Tool for UnoQSetupTool {
    fn name(&self) -> &str { "uno_q_setup" }
    fn description(&self) -> &str {
        "Configure an Arduino Uno Q peripheral bridge — I2C/SPI parameters and peripheral addresses."
    }
    fn parameters_schema(&self) -> Value {
        json!({
            "type": "object",
            "properties": {
                "device":      { "type": "string",  "description": "Device alias" },
                "i2c_speed":   { "type": "integer", "enum": [100000, 400000], "description": "I2C speed Hz" },
                "spi_mode":    { "type": "integer", "enum": [0, 1, 2, 3], "description": "SPI mode" },
                "spi_speed":   { "type": "integer", "description": "SPI speed Hz" },
                "peripherals": { "type": "array",   "description": "List of peripheral configs" }
            },
            "required": ["device"]
        })
    }
    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let alias = match args.get("device").and_then(|v| v.as_str()) {
            Some(a) => a.to_string(),
            None => return tool_err("Missing required parameter: device"),
        };
        let mut config = serde_json::Map::new();
        for key in &["i2c_speed", "spi_mode", "spi_speed", "peripherals"] {
            if let Some(v) = args.get(*key) {
                config.insert(key.to_string(), v.clone());
            }
        }
        let registry = self.registry.read().await;
        let Some(device) = registry.get(&alias) else {
            return tool_err(format!("Device '{alias}' not found"));
        };
        let Some(ref transport) = device.transport else {
            return tool_err(format!("Device '{alias}' has no active transport"));
        };
        let cmd = ZcCommand::new("configure", Value::Object(config));
        match transport.send(cmd).await {
            Ok(ZcResponse { ok: true, data, .. }) => {
                let summary = if data.is_null() {
                    format!("Uno Q bridge '{alias}' configured successfully")
                } else {
                    format!("Uno Q bridge '{alias}' configured:\n{}", serde_json::to_string_pretty(&data).unwrap_or_default())
                };
                tool_ok(summary)
            }
            Ok(ZcResponse { error: Some(e), .. }) => tool_err(format!("Setup failed: {e}")),
            Ok(_) => tool_err("Bridge returned no setup confirmation"),
            Err(e) => tool_err(format!("Transport error: {e}")),
        }
    }
}
