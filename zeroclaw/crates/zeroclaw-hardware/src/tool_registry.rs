//! ToolRegistry — central store of all available tools.
//!
//! The LLM receives its tool list exclusively from the registry.
//! If a tool is not registered, the LLM cannot call it.

use super::device::DeviceRegistry;
use super::gpio::gpio_tools;
use super::loader::scan_plugin_dir;
use std::collections::HashMap;
use std::sync::Arc;
use thiserror::Error;
use tokio::sync::RwLock;
use zeroclaw_api::tool::{Tool, ToolResult};

// ── ToolError ─────────────────────────────────────────────────────────────────

#[derive(Debug, Error)]
pub enum ToolError {
    #[error("Unknown tool: {0}")]
    NotFound(String),
    #[error("Tool execution failed: {0}")]
    ExecutionFailed(String),
}

// ── ToolRegistry ──────────────────────────────────────────────────────────────

pub struct ToolRegistry {
    tools: HashMap<String, Box<dyn Tool>>,
}

impl ToolRegistry {
    /// Create an empty registry.
    pub fn new() -> Self {
        Self {
            tools: HashMap::new(),
        }
    }

    /// Register a tool.
    pub fn register(&mut self, tool: Box<dyn Tool>) {
        self.tools.insert(tool.name().to_string(), tool);
    }

    /// Look up and dispatch a tool call by name.
    pub async fn dispatch(&self, name: &str, params: serde_json::Value) -> Result<ToolResult, ToolError> {
        match self.tools.get(name) {
            Some(tool) => tool
                .execute(params)
                .await
                .map_err(|e| ToolError::ExecutionFailed(e.to_string())),
            None => Err(ToolError::NotFound(name.to_string())),
        }
    }

    /// List all registered tool names.
    pub fn tool_names(&self) -> Vec<&str> {
        self.tools.keys().map(|s| s.as_str()).collect()
    }

    /// Return all registered tools (for system prompt building).
    pub fn all_tools(&self) -> impl Iterator<Item = &dyn Tool> {
        self.tools.values().map(|t| t.as_ref())
    }

    /// Load built-in GPIO tools + user plugin tools from disk.
    pub async fn load(
        registry: Arc<RwLock<DeviceRegistry>>,
        plugin_dir: Option<&std::path::Path>,
    ) -> Self {
        let mut tool_registry = Self::new();

        // Register built-in GPIO tools.
        for tool in gpio_tools(registry) {
            tool_registry.register(tool);
        }

        // Scan user plugin directory if provided.
        if let Some(dir) = plugin_dir {
            let plugins = scan_plugin_dir(dir);
            for tool in plugins {
                tool_registry.register(tool);
            }
        }

        tool_registry
    }
}

impl Default for ToolRegistry {
    fn default() -> Self {
        Self::new()
    }
}
