//! Smart room peripheral tools — sensor reading and actuator control.

use crate::device::DeviceRegistry;
use crate::protocol::{ZcCommand, ZcResponse};
use crate::tool_result::{tool_err, tool_ok};
use async_trait::async_trait;
use serde_json::{Value, json};
use std::sync::Arc;
use tokio::sync::RwLock;
use zeroclaw_api::tool::{Tool, ToolResult};

pub struct SmartRoomSensorTool {
    pub registry: Arc<RwLock<DeviceRegistry>>,
}

#[async_trait]
impl Tool for SmartRoomSensorTool {
    fn name(&self) -> &str { "smartroom_sensor" }
    fn description(&self) -> &str {
        "Read temperature and humidity from a smart room sensor (AHT10/DHT22 etc)."
    }
    fn parameters_schema(&self) -> Value {
        json!({
            "type": "object",
            "properties": {
                "device": { "type": "string", "description": "Device alias (e.g. pico0)" },
                "sensor": { "type": "string", "enum": ["aht10","aht20","dht11","dht22"], "description": "Sensor model (default: aht10)" }
            },
            "required": ["device"]
        })
    }
    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let alias = match args.get("device").and_then(|v| v.as_str()) {
            Some(a) => a.to_string(),
            None => return tool_err("Missing required parameter: device"),
        };
        let sensor = args.get("sensor").and_then(|v| v.as_str()).unwrap_or("aht10");
        let registry = self.registry.read().await;
        let Some(device) = registry.get(&alias) else {
            return tool_err(format!("Device '{alias}' not found"));
        };
        let Some(ref transport) = device.transport else {
            return tool_err(format!("Device '{alias}' has no active transport"));
        };
        let cmd = ZcCommand::new("sensor_read", json!({ "sensor": sensor }));
        match transport.send(cmd).await {
            Ok(ZcResponse { ok: true, data, .. }) => {
                tool_ok(serde_json::to_string_pretty(&data).unwrap_or_else(|_| data.to_string()))
            }
            Ok(ZcResponse { error: Some(e), .. }) => tool_err(e),
            Ok(_) => tool_err("Device returned no sensor data"),
            Err(e) => tool_err(format!("Transport error: {e}")),
        }
    }
}

pub struct SmartRoomActuatorTool {
    pub registry: Arc<RwLock<DeviceRegistry>>,
}

#[async_trait]
impl Tool for SmartRoomActuatorTool {
    fn name(&self) -> &str { "smartroom_actuator" }
    fn description(&self) -> &str {
        "Control a relay or actuator on a smart room device (on/off)."
    }
    fn parameters_schema(&self) -> Value {
        json!({
            "type": "object",
            "properties": {
                "device":   { "type": "string",  "description": "Device alias" },
                "actuator": { "type": "string",  "description": "Actuator name (e.g. relay0, fan, light)" },
                "state":    { "type": "boolean", "description": "true=ON, false=OFF" }
            },
            "required": ["device", "actuator", "state"]
        })
    }
    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let alias = match args.get("device").and_then(|v| v.as_str()) {
            Some(a) => a.to_string(),
            None => return tool_err("Missing required parameter: device"),
        };
        let actuator = match args.get("actuator").and_then(|v| v.as_str()) {
            Some(a) => a.to_string(),
            None => return tool_err("Missing required parameter: actuator"),
        };
        let state = match args.get("state").and_then(|v| v.as_bool()) {
            Some(s) => s,
            None => return tool_err("Missing required parameter: state"),
        };
        let registry = self.registry.read().await;
        let Some(device) = registry.get(&alias) else {
            return tool_err(format!("Device '{alias}' not found"));
        };
        let Some(ref transport) = device.transport else {
            return tool_err(format!("Device '{alias}' has no active transport"));
        };
        let cmd = ZcCommand::new("actuator_set", json!({ "actuator": actuator, "state": state }));
        match transport.send(cmd).await {
            Ok(ZcResponse { ok: true, .. }) => {
                tool_ok(format!("Actuator '{actuator}' on '{alias}' set to {}", if state { "ON" } else { "OFF" }))
            }
            Ok(ZcResponse { error: Some(e), .. }) => tool_err(e),
            Ok(_) => tool_err("Device returned error without message"),
            Err(e) => tool_err(format!("Transport error: {e}")),
        }
    }
}
