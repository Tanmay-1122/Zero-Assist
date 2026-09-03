//! Aardvark-backed tools stub: i2c_scan, i2c_read, i2c_write, spi_transfer, gpio_aardvark.
//! Full implementation requires `aardvark-sys`. These stubs compile cleanly and
//! return an informative error when called.

#![cfg(feature = "hardware")]

use crate::tool_result::tool_err;
use async_trait::async_trait;
use serde_json::{Value, json};
use zeroclaw_api::tool::{Tool, ToolResult};

const NOT_AVAILABLE: &str =
    "Aardvark hardware tools require `aardvark-sys` which is not compiled \
     in this workspace build. Rebuild with the upstream `hardware` feature for full support.";

macro_rules! aardvark_stub_tool {
    ($name:ident, $tool_name:expr, $desc:expr, $schema:expr) => {
        pub struct $name;

        #[async_trait]
        impl Tool for $name {
            fn name(&self) -> &str {
                $tool_name
            }
            fn description(&self) -> &str {
                $desc
            }
            fn parameters_schema(&self) -> Value {
                $schema
            }
            async fn execute(&self, _args: serde_json::Value) -> anyhow::Result<ToolResult> {
                tool_err(NOT_AVAILABLE)
            }
        }
    };
}

aardvark_stub_tool!(
    I2cScanTool,
    "i2c_scan",
    "Scan the I2C bus for connected devices using a Total Phase Aardvark adapter.",
    json!({
        "type": "object",
        "properties": {
            "device": { "type": "string", "description": "Device alias (e.g. aardvark0)" }
        },
        "required": ["device"]
    })
);

aardvark_stub_tool!(
    I2cReadTool,
    "i2c_read",
    "Read bytes from an I2C device register via a Total Phase Aardvark adapter.",
    json!({
        "type": "object",
        "properties": {
            "device":   { "type": "string",  "description": "Device alias" },
            "address":  { "type": "integer", "description": "7-bit I2C address" },
            "register": { "type": "integer", "description": "Register address" },
            "length":   { "type": "integer", "description": "Number of bytes to read" }
        },
        "required": ["device", "address", "register", "length"]
    })
);

aardvark_stub_tool!(
    I2cWriteTool,
    "i2c_write",
    "Write bytes to an I2C device register via a Total Phase Aardvark adapter.",
    json!({
        "type": "object",
        "properties": {
            "device":   { "type": "string",  "description": "Device alias" },
            "address":  { "type": "integer", "description": "7-bit I2C address" },
            "register": { "type": "integer", "description": "Register address" },
            "data":     { "type": "array",   "items": { "type": "integer" }, "description": "Bytes to write" }
        },
        "required": ["device", "address", "register", "data"]
    })
);

aardvark_stub_tool!(
    SpiTransferTool,
    "spi_transfer",
    "Perform a full-duplex SPI transfer via a Total Phase Aardvark adapter.",
    json!({
        "type": "object",
        "properties": {
            "device": { "type": "string", "description": "Device alias" },
            "data":   { "type": "array",  "items": { "type": "integer" }, "description": "Bytes to send" }
        },
        "required": ["device", "data"]
    })
);

aardvark_stub_tool!(
    GpioAardvarkTool,
    "gpio_aardvark",
    "Read/write GPIO pins via a Total Phase Aardvark adapter.",
    json!({
        "type": "object",
        "properties": {
            "device":    { "type": "string",  "description": "Device alias" },
            "pin":       { "type": "integer", "description": "GPIO pin index" },
            "direction": { "type": "string",  "enum": ["read", "write"], "description": "Pin direction" },
            "value":     { "type": "integer", "enum": [0, 1], "description": "Value to write (write mode only)" }
        },
        "required": ["device", "pin", "direction"]
    })
);

/// Return all Aardvark stub tools as boxed trait objects.
pub fn aardvark_tools() -> Vec<Box<dyn zeroclaw_api::tool::Tool>> {
    vec![
        Box::new(I2cScanTool),
        Box::new(I2cReadTool),
        Box::new(I2cWriteTool),
        Box::new(SpiTransferTool),
        Box::new(GpioAardvarkTool),
    ]
}
