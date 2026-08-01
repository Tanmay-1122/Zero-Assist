//! Raspberry Pi self-discovery and native GPIO tools (Linux only).

#![cfg(all(feature = "peripheral-rpi", target_os = "linux"))]

use crate::tool_result::{tool_err, tool_ok};
use async_trait::async_trait;
use serde_json::{Value, json};
use zeroclaw_api::tool::{Tool, ToolResult};

pub fn is_raspberry_pi() -> bool {
    std::fs::read_to_string("/proc/device-tree/model")
        .map(|m| m.to_lowercase().contains("raspberry pi"))
        .unwrap_or(false)
}

pub fn rpi_model() -> Option<String> {
    std::fs::read_to_string("/proc/device-tree/model")
        .ok()
        .map(|m| m.trim_end_matches('\0').trim().to_string())
}

fn sysfs_gpio_read(pin: u32) -> anyhow::Result<u8> {
    let path = format!("/sys/class/gpio/gpio{pin}/value");
    let val = std::fs::read_to_string(&path)?;
    Ok(val.trim().parse::<u8>()?)
}

fn sysfs_gpio_write(pin: u32, value: u8) -> anyhow::Result<()> {
    let path = format!("/sys/class/gpio/gpio{pin}/value");
    std::fs::write(path, if value == 0 { "0\n" } else { "1\n" })?;
    Ok(())
}

fn sysfs_gpio_export(pin: u32) -> anyhow::Result<()> {
    let path = format!("/sys/class/gpio/gpio{pin}");
    if !std::path::Path::new(&path).exists() {
        std::fs::write("/sys/class/gpio/export", pin.to_string())?;
    }
    Ok(())
}

pub struct RpiGpioReadTool;

#[async_trait]
impl Tool for RpiGpioReadTool {
    fn name(&self) -> &str { "rpi_gpio_read" }
    fn description(&self) -> &str { "Read a GPIO pin state on the host Raspberry Pi via sysfs." }
    fn parameters_schema(&self) -> Value {
        json!({
            "type": "object",
            "properties": {
                "pin": { "type": "integer", "description": "BCM GPIO pin number" }
            },
            "required": ["pin"]
        })
    }
    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let pin = match args.get("pin").and_then(|v| v.as_u64()) {
            Some(p) => p as u32,
            None => return tool_err("Missing required parameter: pin"),
        };
        if let Err(e) = sysfs_gpio_export(pin) {
            return tool_err(format!("Failed to export GPIO{pin}: {e}"));
        }
        match sysfs_gpio_read(pin) {
            Ok(val) => tool_ok(format!("GPIO{pin} = {val}")),
            Err(e) => tool_err(format!("Failed to read GPIO{pin}: {e}")),
        }
    }
}

pub struct RpiGpioWriteTool;

#[async_trait]
impl Tool for RpiGpioWriteTool {
    fn name(&self) -> &str { "rpi_gpio_write" }
    fn description(&self) -> &str { "Write a GPIO pin state on the host Raspberry Pi via sysfs." }
    fn parameters_schema(&self) -> Value {
        json!({
            "type": "object",
            "properties": {
                "pin":   { "type": "integer", "description": "BCM GPIO pin number" },
                "value": { "type": "integer", "enum": [0, 1], "description": "0=LOW, 1=HIGH" }
            },
            "required": ["pin", "value"]
        })
    }
    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let pin = match args.get("pin").and_then(|v| v.as_u64()) {
            Some(p) => p as u32,
            None => return tool_err("Missing required parameter: pin"),
        };
        let value = match args.get("value").and_then(|v| v.as_u64()) {
            Some(v) if v <= 1 => v as u8,
            _ => return tool_err("Parameter 'value' must be 0 or 1"),
        };
        if let Err(e) = sysfs_gpio_export(pin) {
            return tool_err(format!("Failed to export GPIO{pin}: {e}"));
        }
        match sysfs_gpio_write(pin, value) {
            Ok(()) => tool_ok(format!("GPIO{pin} set to {value}")),
            Err(e) => tool_err(format!("Failed to write GPIO{pin}: {e}")),
        }
    }
}

pub fn rpi_tools() -> Vec<Box<dyn Tool>> {
    vec![Box::new(RpiGpioReadTool), Box::new(RpiGpioWriteTool)]
}
