//! GPIO tools — read, write, and configure GPIO pins on connected devices.

use crate::device::DeviceRegistry;
use crate::protocol::{ZcCommand, ZcResponse};
use crate::tool_result::{tool_err, tool_ok};
use async_trait::async_trait;
use serde_json::{Value, json};
use std::sync::Arc;
use tokio::sync::RwLock;
use zeroclaw_api::tool::{Tool, ToolResult};

// ── GpioReadTool ──────────────────────────────────────────────────────────────

pub struct GpioReadTool {
    pub registry: Arc<RwLock<DeviceRegistry>>,
}

#[async_trait]
impl Tool for GpioReadTool {
    fn name(&self) -> &str {
        "gpio_read"
    }

    fn description(&self) -> &str {
        "Read the current value (HIGH or LOW) of a GPIO pin on a connected device."
    }

    fn parameters_schema(&self) -> Value {
        json!({
            "type": "object",
            "properties": {
                "device": { "type": "string", "description": "Device alias (e.g. pico0, arduino0)" },
                "pin":    { "type": "integer", "description": "GPIO pin number" }
            },
            "required": ["device", "pin"]
        })
    }

    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let alias = match args.get("device").and_then(|v| v.as_str()) {
            Some(a) => a.to_string(),
            None => return tool_err("Missing required parameter: device"),
        };
        let pin = match args.get("pin").and_then(|v| v.as_u64()) {
            Some(p) => p,
            None => return tool_err("Missing required parameter: pin"),
        };

        let registry = self.registry.read().await;
        let Some(device) = registry.get(&alias) else {
            return tool_err(format!("Device '{alias}' not found"));
        };
        let Some(ref transport) = device.transport else {
            return tool_err(format!("Device '{alias}' has no active transport"));
        };

        let cmd = ZcCommand::new("gpio_read", json!({ "pin": pin }));
        match transport.send(cmd).await {
            Ok(ZcResponse { ok: true, data, .. }) => {
                let value = data.get("value").and_then(|v| v.as_u64()).unwrap_or(0);
                tool_ok(format!("GPIO{pin} = {} ({})", value, if value == 0 { "LOW" } else { "HIGH" }))
            }
            Ok(ZcResponse { error: Some(e), .. }) => tool_err(e),
            Ok(_) => tool_err("Device returned no data"),
            Err(e) => tool_err(format!("Transport error: {e}")),
        }
    }
}

// ── GpioWriteTool ─────────────────────────────────────────────────────────────

pub struct GpioWriteTool {
    pub registry: Arc<RwLock<DeviceRegistry>>,
}

#[async_trait]
impl Tool for GpioWriteTool {
    fn name(&self) -> &str {
        "gpio_write"
    }

    fn description(&self) -> &str {
        "Set a GPIO pin HIGH (1) or LOW (0) on a connected device."
    }

    fn parameters_schema(&self) -> Value {
        json!({
            "type": "object",
            "properties": {
                "device": { "type": "string",  "description": "Device alias" },
                "pin":    { "type": "integer", "description": "GPIO pin number" },
                "value":  { "type": "integer", "enum": [0, 1], "description": "0=LOW, 1=HIGH" }
            },
            "required": ["device", "pin", "value"]
        })
    }

    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let alias = match args.get("device").and_then(|v| v.as_str()) {
            Some(a) => a.to_string(),
            None => return tool_err("Missing required parameter: device"),
        };
        let pin = match args.get("pin").and_then(|v| v.as_u64()) {
            Some(p) => p,
            None => return tool_err("Missing required parameter: pin"),
        };
        let value = match args.get("value").and_then(|v| v.as_u64()) {
            Some(v) if v <= 1 => v,
            _ => return tool_err("Parameter 'value' must be 0 or 1"),
        };

        let registry = self.registry.read().await;
        let Some(device) = registry.get(&alias) else {
            return tool_err(format!("Device '{alias}' not found"));
        };
        let Some(ref transport) = device.transport else {
            return tool_err(format!("Device '{alias}' has no active transport"));
        };

        let cmd = ZcCommand::new("gpio_write", json!({ "pin": pin, "value": value }));
        match transport.send(cmd).await {
            Ok(ZcResponse { ok: true, .. }) => {
                tool_ok(format!("GPIO{pin} set to {} on '{alias}'", if value == 0 { "LOW" } else { "HIGH" }))
            }
            Ok(ZcResponse { error: Some(e), .. }) => tool_err(e),
            Ok(_) => tool_err("Device returned no confirmation"),
            Err(e) => tool_err(format!("Transport error: {e}")),
        }
    }
}

// ── GpioConfigTool ────────────────────────────────────────────────────────────

pub struct GpioConfigTool {
    pub registry: Arc<RwLock<DeviceRegistry>>,
}

#[async_trait]
impl Tool for GpioConfigTool {
    fn name(&self) -> &str {
        "gpio_config"
    }

    fn description(&self) -> &str {
        "Configure a GPIO pin mode (input, output, input_pullup, input_pulldown) on a connected device."
    }

    fn parameters_schema(&self) -> Value {
        json!({
            "type": "object",
            "properties": {
                "device": { "type": "string", "description": "Device alias" },
                "pin":    { "type": "integer", "description": "GPIO pin number" },
                "mode":   {
                    "type": "string",
                    "enum": ["input", "output", "input_pullup", "input_pulldown"],
                    "description": "Pin mode"
                }
            },
            "required": ["device", "pin", "mode"]
        })
    }

    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let alias = match args.get("device").and_then(|v| v.as_str()) {
            Some(a) => a.to_string(),
            None => return tool_err("Missing required parameter: device"),
        };
        let pin = match args.get("pin").and_then(|v| v.as_u64()) {
            Some(p) => p,
            None => return tool_err("Missing required parameter: pin"),
        };
        let mode = match args.get("mode").and_then(|v| v.as_str()) {
            Some(m) => m.to_string(),
            None => return tool_err("Missing required parameter: mode"),
        };

        let registry = self.registry.read().await;
        let Some(device) = registry.get(&alias) else {
            return tool_err(format!("Device '{alias}' not found"));
        };
        let Some(ref transport) = device.transport else {
            return tool_err(format!("Device '{alias}' has no active transport"));
        };

        let cmd = ZcCommand::new("gpio_config", json!({ "pin": pin, "mode": mode }));
        match transport.send(cmd).await {
            Ok(ZcResponse { ok: true, .. }) => {
                tool_ok(format!("GPIO{pin} configured as '{mode}' on '{alias}'"))
            }
            Ok(ZcResponse { error: Some(e), .. }) => tool_err(e),
            Ok(_) => tool_err("Device returned no confirmation"),
            Err(e) => tool_err(format!("Transport error: {e}")),
        }
    }
}

/// Return all GPIO tools bound to the given device registry.
pub fn gpio_tools(registry: Arc<RwLock<DeviceRegistry>>) -> Vec<Box<dyn Tool>> {
    vec![
        Box::new(GpioReadTool { registry: registry.clone() }),
        Box::new(GpioWriteTool { registry: registry.clone() }),
        Box::new(GpioConfigTool { registry }),
    ]
}
