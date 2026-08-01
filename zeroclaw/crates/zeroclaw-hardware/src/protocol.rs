//! ZeroClaw serial JSON protocol — the firmware contract.
//!
//! These types define the newline-delimited JSON wire format shared between
//! the ZeroClaw host and device firmware (Pico, Arduino, ESP32, Nucleo).
//!
//! Wire format:
//!   Host → Device:  `{"cmd":"gpio_write","params":{"pin":25,"value":1}}\n`
//!   Device → Host:  `{"ok":true,"data":{"pin":25,"value":1,"state":"HIGH"}}\n`
//!
//! Both sides MUST agree on these struct definitions. Any change here is a
//! breaking firmware contract change.

use serde::{Deserialize, Serialize};

/// Host-to-device command.
///
/// Serialized as one JSON line terminated by `\n`.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ZcCommand {
    /// Command name (e.g. `"gpio_read"`, `"ping"`, `"reboot_bootsel"`).
    pub cmd: String,
    /// Command parameters — schema depends on the command.
    #[serde(default)]
    pub params: serde_json::Value,
}

impl ZcCommand {
    /// Create a new command with the given name and parameters.
    pub fn new(cmd: impl Into<String>, params: serde_json::Value) -> Self {
        Self {
            cmd: cmd.into(),
            params,
        }
    }

    /// Create a parameterless command (e.g. `ping`, `capabilities`).
    pub fn simple(cmd: impl Into<String>) -> Self {
        Self {
            cmd: cmd.into(),
            params: serde_json::Value::Object(serde_json::Map::new()),
        }
    }
}

/// Device-to-host response.
///
/// Serialized as one JSON line terminated by `\n`.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub struct ZcResponse {
    /// Whether the command succeeded.
    pub ok: bool,
    /// Response data — schema depends on the command.
    #[serde(default)]
    pub data: serde_json::Value,
    /// Human-readable error message (only set when `ok == false`).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

impl ZcResponse {
    /// Create a successful response with data.
    pub fn ok(data: serde_json::Value) -> Self {
        Self {
            ok: true,
            data,
            error: None,
        }
    }

    /// Create an error response.
    pub fn err(message: impl Into<String>) -> Self {
        Self {
            ok: false,
            data: serde_json::Value::Null,
            error: Some(message.into()),
        }
    }
}
