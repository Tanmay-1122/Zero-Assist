use async_trait::async_trait;
use serde_json::json;
use std::sync::Arc;
use zeroclaw_api::tool::ToolResult;

use crate::capabilities::executor::{CapabilityExecutor, CapabilityRequest};
use crate::capabilities::provider::{CapabilityProvider, CapabilityStatus};
use crate::platform::RuntimeAdapter;
use crate::security::SecurityPolicy;

/// Termux capability provider.
///
/// Provides shell access on Android via Termux. Disabled on non-Android platforms.
#[cfg(not(target_os = "android"))]
pub fn is_termux_available() -> bool { false }

#[cfg(target_os = "android")]
pub fn is_termux_available() -> bool { true }

pub struct TermuxProvider {
    name: String,
    enabled: bool,
    priority: u32,
}

impl TermuxProvider {
    pub fn new() -> Self {
        let available = is_termux_available();
        Self {
            name: "termux".to_string(),
            enabled: available,
            priority: 20, // Lower priority than sandbox (10)
        }
    }
}

#[async_trait]
impl CapabilityProvider for TermuxProvider {
    fn name(&self) -> &str {
        &self.name
    }

    fn enabled(&self) -> bool {
        self.enabled
    }

    fn priority(&self) -> u32 {
        self.priority
    }

    async fn health_check(&self) -> CapabilityStatus {
        CapabilityStatus {
            healthy: is_termux_available(),
            degraded_reason: if is_termux_available() {
                None
            } else {
                Some("Termux is only available on Android".to_string())
            },
            supports_network: false,
            supports_packages: true,
            supports_background: true,
            supports_pty: true,
            available_disk_bytes: 0,
            available_memory_bytes: 0,
            active_sessions: 0,
        }
    }

    async fn context(&self) -> serde_json::Value {
        json!({
            "provider": "termux",
            "available": is_termux_available(),
            "platform": std::env::consts::OS,
        })
    }
}

/// Termux shell executor.
///
/// Executes commands via the Termux bridge. On non-Android platforms
/// this returns a "not available" error.
pub struct TermuxExecutor {
    _security: Arc<SecurityPolicy>,
    _runtime: Arc<dyn RuntimeAdapter>,
    _timeout_secs: u64,
}

impl TermuxExecutor {
    pub fn new(
        security: Arc<SecurityPolicy>,
        runtime: Arc<dyn RuntimeAdapter>,
        timeout_secs: u64,
    ) -> Self {
        Self {
            _security: security,
            _runtime: runtime,
            _timeout_secs: timeout_secs,
        }
    }
}

#[async_trait]
impl CapabilityExecutor for TermuxExecutor {
    async fn execute(&self, request: CapabilityRequest) -> anyhow::Result<ToolResult> {
        match request {
            CapabilityRequest::Termux(req) => {
                if !is_termux_available() {
                    return Ok(ToolResult {
                        success: false,
                        output: String::new(),
                        error: Some("Termux is not available on this platform".to_string()),
                    metadata: None,
                    });
                }
                let _ = req;
                Ok(ToolResult {
                    success: false,
                    output: String::new(),
                    error: Some("Termux execution not yet implemented".to_string()),
                metadata: None,
                })
            }
            _ => Err(anyhow::anyhow!(
                "TermuxExecutor received unexpected request type"
            )),
        }
    }
}