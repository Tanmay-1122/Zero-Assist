//! Plugin manifest — `~/.zeroclaw/tools/<name>/tool.toml` schema.
//!
//! Each user plugin lives in its own subdirectory and carries a `tool.toml`
//! that describes the tool, how to invoke it, and what parameters it accepts.

use serde::Deserialize;

/// Full plugin manifest — parsed from `tool.toml`.
#[derive(Debug, Deserialize)]
pub struct ToolManifest {
    /// Tool identity and human-readable metadata.
    pub tool: ToolMeta,
    /// How to invoke the tool binary.
    pub exec: ExecConfig,
    /// Optional transport preference and device requirement.
    pub transport: Option<TransportConfig>,
    /// Parameter definitions used to build the JSON Schema for the LLM.
    #[serde(default)]
    pub parameters: Vec<ParameterDef>,
}

/// Tool identity metadata.
#[derive(Debug, Deserialize)]
pub struct ToolMeta {
    /// Unique tool name, used as the function-call key by the LLM.
    pub name: String,
    /// Semantic version string (e.g. `"1.0.0"`).
    pub version: String,
    /// Human-readable description injected into the LLM system prompt.
    pub description: String,
}

/// Execution configuration — how ZeroClaw spawns the tool binary.
#[derive(Debug, Deserialize)]
pub struct ExecConfig {
    /// Path to the tool binary, relative to the manifest directory.
    pub binary: String,
    /// Optional working directory override.
    pub cwd: Option<String>,
    /// Environment variable overrides.
    #[serde(default)]
    pub env: std::collections::HashMap<String, String>,
}

/// Transport configuration — preferred transport and device requirement.
#[derive(Debug, Deserialize)]
pub struct TransportConfig {
    /// Preferred transport type (e.g. `"serial"`, `"swd"`, `"uf2"`).
    pub preferred: Option<String>,
    /// Whether a connected device is required to run this tool.
    #[serde(default)]
    pub device_required: bool,
}

/// A single parameter definition for the LLM JSON schema.
#[derive(Debug, Deserialize)]
pub struct ParameterDef {
    /// Parameter name.
    pub name: String,
    /// JSON schema type (e.g. `"string"`, `"integer"`, `"boolean"`).
    #[serde(rename = "type")]
    pub param_type: String,
    /// Human-readable description.
    pub description: String,
    /// Whether the parameter is required.
    #[serde(default)]
    pub required: bool,
    /// Optional default value.
    pub default: Option<serde_json::Value>,
}
