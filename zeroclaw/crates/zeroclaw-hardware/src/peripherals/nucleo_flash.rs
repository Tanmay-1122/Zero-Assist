//! Nucleo flash tool — flash STM32 Nucleo boards via probe-rs.

use crate::tool_result::{tool_err, tool_ok};
use async_trait::async_trait;
use serde_json::{Value, json};
use tokio::process::Command;
use zeroclaw_api::tool::{Tool, ToolResult};

pub struct NucleoFlashTool;

#[async_trait]
impl Tool for NucleoFlashTool {
    fn name(&self) -> &str { "nucleo_flash" }
    fn description(&self) -> &str {
        "Flash a firmware binary (.elf or .bin) to an STM32 Nucleo board via ST-LINK using probe-rs."
    }
    fn parameters_schema(&self) -> Value {
        json!({
            "type": "object",
            "properties": {
                "firmware": { "type": "string", "description": "Path to firmware file (.elf or .bin)" },
                "chip":     { "type": "string", "description": "Chip identifier (e.g. STM32F401RETx). Auto-detect if omitted." }
            },
            "required": ["firmware"]
        })
    }
    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let firmware = match args.get("firmware").and_then(|v| v.as_str()) {
            Some(f) => f.to_string(),
            None => return tool_err("Missing required parameter: firmware"),
        };
        let mut cmd_args = vec!["download".to_string(), "--verify".to_string()];
        if let Some(chip) = args.get("chip").and_then(|v| v.as_str()) {
            cmd_args.push("--chip".to_string());
            cmd_args.push(chip.to_string());
        }
        cmd_args.push(firmware.clone());
        let output = Command::new("probe-rs").args(&cmd_args).output().await;
        match output {
            Ok(o) if o.status.success() => {
                tool_ok(format!("Flash successful: {firmware}\n{}", String::from_utf8_lossy(&o.stdout).trim()))
            }
            Ok(o) => tool_err(format!("probe-rs failed: {}", String::from_utf8_lossy(&o.stderr).trim())),
            Err(e) => tool_err(format!("Failed to run probe-rs: {e}. Install: cargo install probe-rs-tools")),
        }
    }
}
