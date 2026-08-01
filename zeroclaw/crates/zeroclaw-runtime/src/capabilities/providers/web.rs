use async_trait::async_trait;
use serde_json::json;
use zeroclaw_api::tool::ToolResult;

use crate::capabilities::executor::{CapabilityExecutor, CapabilityRequest};
use crate::capabilities::provider::{CapabilityProvider, CapabilityStatus};

pub struct WebProvider {
    name: String,
    enabled: bool,
    priority: u32,
}

impl WebProvider {
    pub fn new() -> Self {
        Self {
            name: "web".to_string(),
            enabled: true,
            priority: 40,
        }
    }

    /// Create a disabled provider. Registered but invisible to the AI
    /// until the executor is fully implemented.
    pub fn disabled() -> Self {
        Self {
            name: "web".to_string(),
            enabled: false,
            priority: 40,
        }
    }
}

#[async_trait]
impl CapabilityProvider for WebProvider {
    fn name(&self) -> &str { &self.name }
    fn enabled(&self) -> bool { self.enabled }
    fn priority(&self) -> u32 { self.priority }

    async fn health_check(&self) -> CapabilityStatus {
        CapabilityStatus {
            healthy: true,
            degraded_reason: None,
            supports_network: true,
            supports_packages: false,
            supports_background: false,
            supports_pty: false,
            available_disk_bytes: 0,
            available_memory_bytes: 0,
            active_sessions: 0,
        }
    }

    async fn context(&self) -> serde_json::Value {
        json!({ "provider": "web", "status": "active" })
    }
}

pub struct WebExecutor;

impl WebExecutor {
    pub fn new() -> Self { Self }
}

#[async_trait]
impl CapabilityExecutor for WebExecutor {
    async fn execute(&self, request: CapabilityRequest) -> anyhow::Result<ToolResult> {
        match request {
            CapabilityRequest::Web(req) => {
                Ok(ToolResult {
                    success: true,
                    output: format!("Web {} operation: {:?} {:?}", req.action, req.url, req.query),
                    error: None,
                metadata: None,
                })
            }
            _ => Err(anyhow::anyhow!("WebExecutor received unexpected request type")),
        }
    }
}