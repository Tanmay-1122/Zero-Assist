//! Arduino flash tool — flash firmware via arduino-cli.

use crate::tool_result::{tool_err, tool_ok};
use async_trait::async_trait;
use serde_json::{Value, json};
use tokio::process::Command;
use zeroclaw_api::tool::{Tool, ToolResult};

pub struct ArduinoFlashTool;

#[async_trait]
impl Tool for ArduinoFlashTool {
    fn name(&self) -> &str { "arduino_flash" }
    fn description(&self) -> &str {
        "Flash a compiled Arduino sketch to a connected board using arduino-cli."
    }
    fn parameters_schema(&self) -> Value {
        json!({
            "type": "object",
            "properties": {
                "port":   { "type": "string", "description": "Serial port" },
                "fqbn":   { "type": "string", "description": "Fully qualified board name (e.g. arduino:avr:uno)" },
                "sketch": { "type": "string", "description": "Path to compiled .hex or sketch directory" }
            },
            "required": ["port", "fqbn", "sketch"]
        })
    }
    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let port   = args.get("port").and_then(|v| v.as_str()).unwrap_or("");
        let fqbn   = args.get("fqbn").and_then(|v| v.as_str()).unwrap_or("");
        let sketch = args.get("sketch").and_then(|v| v.as_str()).unwrap_or("");
        if port.is_empty() || fqbn.is_empty() || sketch.is_empty() {
            return tool_err("Parameters 'port', 'fqbn', and 'sketch' are all required");
        }
        let output = Command::new("arduino-cli")
            .args(["upload", "-p", port, "--fqbn", fqbn, sketch])
            .output().await;
        match output {
            Ok(o) if o.status.success() => {
                tool_ok(format!("Flash successful.\n{}", String::from_utf8_lossy(&o.stdout).trim()))
            }
            Ok(o) => tool_err(format!("arduino-cli upload failed: {}", String::from_utf8_lossy(&o.stderr).trim())),
            Err(e) => tool_err(format!("Failed to run arduino-cli: {e}")),
        }
    }
}
