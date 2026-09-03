//! Arduino upload tool — compile and upload sketches via arduino-cli.

use crate::tool_result::{tool_err, tool_ok};
use async_trait::async_trait;
use serde_json::{Value, json};
use tokio::process::Command;
use zeroclaw_api::tool::{Tool, ToolResult};

pub struct ArduinoUploadTool;

#[async_trait]
impl Tool for ArduinoUploadTool {
    fn name(&self) -> &str { "arduino_upload" }
    fn description(&self) -> &str {
        "Compile and upload an Arduino sketch (.ino) using arduino-cli."
    }
    fn parameters_schema(&self) -> Value {
        json!({
            "type": "object",
            "properties": {
                "port":       { "type": "string", "description": "Serial port" },
                "fqbn":       { "type": "string", "description": "Fully qualified board name" },
                "sketch_dir": { "type": "string", "description": "Path to sketch directory" }
            },
            "required": ["port", "fqbn", "sketch_dir"]
        })
    }
    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let port       = args.get("port").and_then(|v| v.as_str()).unwrap_or("");
        let fqbn       = args.get("fqbn").and_then(|v| v.as_str()).unwrap_or("");
        let sketch_dir = args.get("sketch_dir").and_then(|v| v.as_str()).unwrap_or("");
        if port.is_empty() || fqbn.is_empty() || sketch_dir.is_empty() {
            return tool_err("Parameters 'port', 'fqbn', and 'sketch_dir' are all required");
        }
        let compile = Command::new("arduino-cli").args(["compile", "--fqbn", fqbn, sketch_dir]).output().await;
        match compile {
            Ok(o) if !o.status.success() => {
                return tool_err(format!("Compilation failed: {}", String::from_utf8_lossy(&o.stderr).trim()));
            }
            Err(e) => return tool_err(format!("Failed to run arduino-cli compile: {e}")),
            _ => {}
        }
        let upload = Command::new("arduino-cli").args(["upload", "-p", port, "--fqbn", fqbn, sketch_dir]).output().await;
        match upload {
            Ok(o) if o.status.success() => tool_ok(format!("Compiled and uploaded {sketch_dir} to {port}")),
            Ok(o) => tool_err(format!("Upload failed: {}", String::from_utf8_lossy(&o.stderr).trim())),
            Err(e) => tool_err(format!("Failed to run arduino-cli upload: {e}")),
        }
    }
}
