//! Pico code tools — read/write/execute MicroPython code on a Pico via mpremote.

#![cfg(feature = "hardware")]

use crate::tool_result::{tool_err, tool_ok};
use async_trait::async_trait;
use serde_json::{Value, json};
use tokio::process::Command;
use zeroclaw_api::tool::{Tool, ToolResult};

fn find_mpremote() -> Option<String> {
    for candidate in &["mpremote", "python3 -m mpremote", "python -m mpremote"] {
        if which::which(candidate.split_whitespace().next().unwrap_or("mpremote")).is_ok() {
            return Some(candidate.to_string());
        }
    }
    None
}

async fn run_mpremote(port: &str, args: &[&str]) -> Result<String, String> {
    let mpremote = find_mpremote()
        .ok_or_else(|| "mpremote not found — install with: pip install mpremote".to_string())?;
    let mut cmd_parts = mpremote.split_whitespace().collect::<Vec<_>>();
    let program = cmd_parts.remove(0).to_string();
    let mut cmd = Command::new(&program);
    for part in &cmd_parts { cmd.arg(part); }
    cmd.arg("connect").arg(port);
    for arg in args { cmd.arg(arg); }
    let output = cmd.output().await.map_err(|e| format!("Failed to spawn mpremote: {e}"))?;
    if output.status.success() {
        Ok(String::from_utf8_lossy(&output.stdout).trim().to_string())
    } else {
        Err(String::from_utf8_lossy(&output.stderr).trim().to_string())
    }
}

pub struct DeviceReadCodeTool;

#[async_trait]
impl Tool for DeviceReadCodeTool {
    fn name(&self) -> &str { "device_read_code" }
    fn description(&self) -> &str { "Read a file from a connected MicroPython device using mpremote." }
    fn parameters_schema(&self) -> Value {
        json!({
            "type": "object",
            "properties": {
                "port": { "type": "string", "description": "Serial port" },
                "path": { "type": "string", "description": "File path on device" }
            },
            "required": ["port", "path"]
        })
    }
    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let port = match args.get("port").and_then(|v| v.as_str()) {
            Some(p) => p.to_string(),
            None => return tool_err("Missing required parameter: port"),
        };
        let path = match args.get("path").and_then(|v| v.as_str()) {
            Some(p) => p.to_string(),
            None => return tool_err("Missing required parameter: path"),
        };
        match run_mpremote(&port, &["cat", &path]).await {
            Ok(content) => tool_ok(content),
            Err(e) => tool_err(format!("Failed to read {path} from {port}: {e}")),
        }
    }
}

pub struct DeviceWriteCodeTool;

#[async_trait]
impl Tool for DeviceWriteCodeTool {
    fn name(&self) -> &str { "device_write_code" }
    fn description(&self) -> &str { "Write a file to a connected MicroPython device using mpremote." }
    fn parameters_schema(&self) -> Value {
        json!({
            "type": "object",
            "properties": {
                "port":    { "type": "string", "description": "Serial port" },
                "path":    { "type": "string", "description": "Destination path on device" },
                "content": { "type": "string", "description": "File content to write" }
            },
            "required": ["port", "path", "content"]
        })
    }
    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let port = match args.get("port").and_then(|v| v.as_str()) {
            Some(p) => p.to_string(),
            None => return tool_err("Missing required parameter: port"),
        };
        let path = match args.get("path").and_then(|v| v.as_str()) {
            Some(p) => p.to_string(),
            None => return tool_err("Missing required parameter: path"),
        };
        let content = match args.get("content").and_then(|v| v.as_str()) {
            Some(c) => c.to_string(),
            None => return tool_err("Missing required parameter: content"),
        };
        let tmp = match tempfile::NamedTempFile::with_suffix(".py") {
            Ok(t) => t,
            Err(e) => return tool_err(format!("Temp file error: {e}")),
        };
        if let Err(e) = std::fs::write(tmp.path(), &content) {
            return tool_err(format!("Failed to write temp file: {e}"));
        }
        let local = tmp.path().to_string_lossy().to_string();
        let copy_arg = format!("{}:{}", local, path);
        match run_mpremote(&port, &["cp", &copy_arg]).await {
            Ok(_) => tool_ok(format!("Written {path} to device at {port}")),
            Err(e) => tool_err(format!("Failed to write {path} to {port}: {e}")),
        }
    }
}

pub struct DeviceExecTool;

#[async_trait]
impl Tool for DeviceExecTool {
    fn name(&self) -> &str { "device_exec" }
    fn description(&self) -> &str { "Execute a Python expression on a connected MicroPython device." }
    fn parameters_schema(&self) -> Value {
        json!({
            "type": "object",
            "properties": {
                "port": { "type": "string", "description": "Serial port" },
                "code": { "type": "string", "description": "MicroPython code to execute" }
            },
            "required": ["port", "code"]
        })
    }
    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let port = match args.get("port").and_then(|v| v.as_str()) {
            Some(p) => p.to_string(),
            None => return tool_err("Missing required parameter: port"),
        };
        let code = match args.get("code").and_then(|v| v.as_str()) {
            Some(c) => c.to_string(),
            None => return tool_err("Missing required parameter: code"),
        };
        match run_mpremote(&port, &["exec", &code]).await {
            Ok(output) => tool_ok(output),
            Err(e) => tool_err(format!("Execution failed on {port}: {e}")),
        }
    }
}
