use async_trait::async_trait;
use serde_json::{Value, json};
use zeroclaw_api::tool::ToolResult;

use crate::capabilities::executor::{CapabilityExecutor, CapabilityRequest};
use crate::capabilities::provider::{CapabilityProvider, CapabilityStatus};
use super::bridge;

pub struct TermuxAndroidProvider {
    name: String,
    enabled: bool,
    priority: u32,
}

impl TermuxAndroidProvider {
    pub fn new() -> Self {
        Self {
            name: "termux_android".to_string(),
            enabled: true,
            priority: 20,
        }
    }

    pub fn disabled() -> Self {
        Self {
            name: "termux_android".to_string(),
            enabled: false,
            priority: 20,
        }
    }
}

#[async_trait]
impl CapabilityProvider for TermuxAndroidProvider {
    fn name(&self) -> &str { &self.name }
    fn enabled(&self) -> bool { self.enabled }
    fn priority(&self) -> u32 { self.priority }

    async fn health_check(&self) -> CapabilityStatus {
        match bridge::termux_bridge() {
            Some(tb) => {
                let has_token = tb.has_token();
                CapabilityStatus {
                    healthy: has_token,
                    degraded_reason: if has_token {
                        None
                    } else {
                        Some("Zero-Assist Termux bridge token not configured. Open the app to initialise Termux.".to_string())
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
            None => CapabilityStatus {
                healthy: false,
                degraded_reason: Some("Termux bridge not registered".to_string()),
                supports_network: false,
                supports_packages: false,
                supports_background: false,
                supports_pty: false,
                available_disk_bytes: 0,
                available_memory_bytes: 0,
                active_sessions: 0,
            },
        }
    }

    async fn context(&self) -> Value {
        match bridge::termux_bridge() {
            Some(tb) => json!({
                "provider": "termux_android",
                "token_configured": tb.has_token(),
                "platform": "android",
            }),
            None => json!({
                "provider": "termux_android",
                "token_configured": false,
                "platform": "android",
            }),
        }
    }
}

pub struct TermuxCapabilitiesProvider {
    name: String,
    enabled: bool,
    priority: u32,
}

impl TermuxCapabilitiesProvider {
    pub fn new() -> Self {
        Self {
            name: "termux_capabilities".to_string(),
            enabled: true,
            priority: 20,
        }
    }

    pub fn disabled() -> Self {
        Self {
            name: "termux_capabilities".to_string(),
            enabled: false,
            priority: 20,
        }
    }
}

#[async_trait]
impl CapabilityProvider for TermuxCapabilitiesProvider {
    fn name(&self) -> &str { &self.name }
    fn enabled(&self) -> bool { self.enabled }
    fn priority(&self) -> u32 { self.priority }

    async fn health_check(&self) -> CapabilityStatus {
        match bridge::termux_bridge() {
            Some(tb) => {
                let has_token = tb.has_token();
                CapabilityStatus {
                    healthy: has_token,
                    degraded_reason: if has_token {
                        None
                    } else {
                        Some("Zero-Assist Termux bridge token not configured.".to_string())
                    },
                    supports_network: false,
                    supports_packages: false,
                    supports_background: false,
                    supports_pty: false,
                    available_disk_bytes: 0,
                    available_memory_bytes: 0,
                    active_sessions: 0,
                }
            }
            None => CapabilityStatus {
                healthy: false,
                degraded_reason: Some("Termux bridge not registered".to_string()),
                supports_network: false,
                supports_packages: false,
                supports_background: false,
                supports_pty: false,
                available_disk_bytes: 0,
                available_memory_bytes: 0,
                active_sessions: 0,
            },
        }
    }

    async fn context(&self) -> Value {
        match bridge::termux_bridge() {
            Some(tb) => json!({
                "provider": "termux_capabilities",
                "token_configured": tb.has_token(),
            }),
            None => json!({
                "provider": "termux_capabilities",
                "token_configured": false,
            }),
        }
    }
}

pub struct TermuxAndroidExecutor;

impl TermuxAndroidExecutor {
    pub fn new() -> Self { Self }
}

#[async_trait]
impl CapabilityExecutor for TermuxAndroidExecutor {
    async fn execute(&self, request: CapabilityRequest) -> anyhow::Result<ToolResult> {
        let CapabilityRequest::Termux(req) = request else {
            return Err(anyhow::anyhow!("TermuxAndroidExecutor received unexpected request type"));
        };

        let tb = bridge::termux_bridge()
            .ok_or_else(|| anyhow::anyhow!("Termux bridge not registered"))?;

        let args = json!({
            "command": req.command,
            "arguments": req.arguments.unwrap_or_default(),
            "working_directory": req.working_directory,
            "timeout_seconds": req.timeout_seconds.unwrap_or(30),
        });

        match tb.execute_low_risk(args).await {
            Ok(value) => {
                let success = value.get("success").and_then(|v| v.as_bool()).unwrap_or(false);
                let output = value.to_string();
                Ok(ToolResult {
                    success,
                    output: output.clone(),
                    error: (!success).then_some(output),
                blocks: Vec::new(),
                metadata: None,
                })
            }
            Err(e) => Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some(e),
            blocks: Vec::new(),
            metadata: None,
            }),
        }
    }
}

pub struct TermuxCapabilitiesExecutor;

impl TermuxCapabilitiesExecutor {
    pub fn new() -> Self { Self }
}

#[async_trait]
impl CapabilityExecutor for TermuxCapabilitiesExecutor {
    async fn execute(&self, request: CapabilityRequest) -> anyhow::Result<ToolResult> {
        let CapabilityRequest::TermuxCapabilities(req) = request else {
            return Err(anyhow::anyhow!("TermuxCapabilitiesExecutor received unexpected request type"));
        };

        let tb = bridge::termux_bridge()
            .ok_or_else(|| anyhow::anyhow!("Termux bridge not registered"))?;

        let force_refresh = req.refresh.unwrap_or(false);

        match tb.capabilities(force_refresh).await {
            Ok(value) => Ok(ToolResult {
                success: true,
                output: serde_json::to_string_pretty(&value).unwrap_or_else(|_| value.to_string()),
                error: None,
            blocks: Vec::new(),
            metadata: None,
            }),
            Err(e) => Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some(e),
            blocks: Vec::new(),
            metadata: None,
            }),
        }
    }
}