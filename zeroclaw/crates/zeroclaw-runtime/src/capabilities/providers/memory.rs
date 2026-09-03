use async_trait::async_trait;
use serde_json::json;
use zeroclaw_api::tool::ToolResult;

use crate::capabilities::executor::{CapabilityExecutor, CapabilityRequest};
use crate::capabilities::provider::{CapabilityProvider, CapabilityStatus};

pub struct MemoryProvider {
    name: String,
    enabled: bool,
    priority: u32,
}

impl MemoryProvider {
    pub fn new() -> Self {
        Self {
            name: "memory".to_string(),
            enabled: true,
            priority: 30,
        }
    }

    /// Create a disabled provider. Registered but invisible to the AI
    /// until the executor is fully implemented.
    pub fn disabled() -> Self {
        Self {
            name: "memory".to_string(),
            enabled: false,
            priority: 30,
        }
    }
}

#[async_trait]
impl CapabilityProvider for MemoryProvider {
    fn name(&self) -> &str { &self.name }
    fn enabled(&self) -> bool { self.enabled }
    fn priority(&self) -> u32 { self.priority }

    async fn health_check(&self) -> CapabilityStatus {
        CapabilityStatus {
            healthy: true,
            degraded_reason: None,
            supports_network: false,
            supports_packages: false,
            supports_background: false,
            supports_pty: false,
            available_disk_bytes: 0,
            available_memory_bytes: 0,
            active_sessions: 0,
        }
    }

    async fn context(&self) -> serde_json::Value {
        json!({ "provider": "memory", "status": "active" })
    }
}

pub struct MemoryExecutor;

impl MemoryExecutor {
    pub fn new() -> Self { Self }
}

#[async_trait]
impl CapabilityExecutor for MemoryExecutor {
    async fn execute(&self, request: CapabilityRequest) -> anyhow::Result<ToolResult> {
        match request {
            CapabilityRequest::Memory(req) => {
                Ok(ToolResult {
                    success: true,
                    output: format!("Memory {} operation: {}", req.action, req.query),
                    error: None,
                blocks: Vec::new(),
                metadata: None,
                })
            }
            _ => Err(anyhow::anyhow!("MemoryExecutor received unexpected request type")),
        }
    }
}