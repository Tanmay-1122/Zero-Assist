//! Serial peripheral — USB-CDC serial communication tools for peripherals.

#![cfg(feature = "hardware")]

use crate::tool_result::{tool_err, tool_ok};
use async_trait::async_trait;
use serde_json::{Value, json};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio_serial::SerialStream;
use zeroclaw_api::tool::{Tool, ToolResult};

const DEFAULT_BAUD: u32 = 115_200;
const DEFAULT_TIMEOUT_MS: u64 = 5_000;

pub struct SerialReadLineTool;

#[async_trait]
impl Tool for SerialReadLineTool {
    fn name(&self) -> &str { "serial_read_line" }
    fn description(&self) -> &str { "Read one line of text from a serial peripheral device." }
    fn parameters_schema(&self) -> Value {
        json!({
            "type": "object",
            "properties": {
                "port":       { "type": "string",  "description": "Serial port path" },
                "baud_rate":  { "type": "integer", "description": "Baud rate (default 115200)" },
                "timeout_ms": { "type": "integer", "description": "Timeout in ms (default 5000)" }
            },
            "required": ["port"]
        })
    }
    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let port = match args.get("port").and_then(|v| v.as_str()) {
            Some(p) => p.to_string(),
            None => return tool_err("Missing required parameter: port"),
        };
        let baud = args.get("baud_rate").and_then(|v| v.as_u64()).unwrap_or(DEFAULT_BAUD as u64) as u32;
        let timeout_ms = args.get("timeout_ms").and_then(|v| v.as_u64()).unwrap_or(DEFAULT_TIMEOUT_MS);
        let timeout = std::time::Duration::from_millis(timeout_ms);

        let result = tokio::time::timeout(timeout, async {
            let builder = tokio_serial::new(&port, baud);
            let stream = SerialStream::open(&builder).map_err(|e| format!("Cannot open {port}: {e}"))?;
            let mut reader = BufReader::new(stream);
            let mut line = String::new();
            reader.read_line(&mut line).await.map_err(|e| format!("Read error: {e}"))?;
            Ok::<_, String>(line.trim().to_string())
        }).await;

        match result {
            Ok(Ok(line)) => tool_ok(line),
            Ok(Err(e)) => tool_err(e),
            Err(_) => tool_err(format!("Timed out after {timeout_ms}ms reading from {port}")),
        }
    }
}

pub struct SerialWriteLineTool;

#[async_trait]
impl Tool for SerialWriteLineTool {
    fn name(&self) -> &str { "serial_write_line" }
    fn description(&self) -> &str { "Write a line of text to a serial peripheral device." }
    fn parameters_schema(&self) -> Value {
        json!({
            "type": "object",
            "properties": {
                "port":      { "type": "string", "description": "Serial port path" },
                "data":      { "type": "string", "description": "Text to send" },
                "baud_rate": { "type": "integer", "description": "Baud rate (default 115200)" }
            },
            "required": ["port", "data"]
        })
    }
    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let port = match args.get("port").and_then(|v| v.as_str()) {
            Some(p) => p.to_string(),
            None => return tool_err("Missing required parameter: port"),
        };
        let data = match args.get("data").and_then(|v| v.as_str()) {
            Some(d) => format!("{d}\n"),
            None => return tool_err("Missing required parameter: data"),
        };
        let baud = args.get("baud_rate").and_then(|v| v.as_u64()).unwrap_or(DEFAULT_BAUD as u64) as u32;
        let builder = tokio_serial::new(&port, baud);
        match SerialStream::open(&builder) {
            Ok(mut stream) => match stream.write_all(data.as_bytes()).await {
                Ok(_) => tool_ok(format!("Sent {} bytes to {port}", data.len())),
                Err(e) => tool_err(format!("Write error: {e}")),
            },
            Err(e) => tool_err(format!("Cannot open {port}: {e}")),
        }
    }
}
