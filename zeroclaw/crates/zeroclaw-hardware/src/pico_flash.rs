//! Pico flash tool — flash firmware to a Raspberry Pi Pico via UF2.

#![cfg(feature = "hardware")]

use crate::tool_result::{tool_err, tool_ok};
use crate::uf2;
use async_trait::async_trait;
use serde_json::{Value, json};
use std::path::PathBuf;
use zeroclaw_api::tool::{Tool, ToolResult};

pub struct PicoFlashTool;

#[async_trait]
impl Tool for PicoFlashTool {
    fn name(&self) -> &str { "pico_flash" }

    fn description(&self) -> &str {
        "Flash a .uf2 firmware file to a Raspberry Pi Pico in BOOTSEL mode."
    }

    fn parameters_schema(&self) -> Value {
        json!({
            "type": "object",
            "properties": {
                "uf2_path": { "type": "string", "description": "Path to the .uf2 firmware file" }
            },
            "required": ["uf2_path"]
        })
    }

    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let uf2_path = match args.get("uf2_path").and_then(|v| v.as_str()) {
            Some(p) => PathBuf::from(p),
            None => return tool_err("Missing required parameter: uf2_path"),
        };
        match uf2::flash_uf2(&uf2_path).await {
            Ok(()) => tool_ok(format!("Flashed {} to Pico. Device rebooting.", uf2_path.display())),
            Err(e) => tool_err(format!("Flash failed: {e}")),
        }
    }
}
