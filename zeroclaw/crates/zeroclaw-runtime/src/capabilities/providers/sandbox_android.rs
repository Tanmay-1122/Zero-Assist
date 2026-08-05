use async_trait::async_trait;
use serde_json::{Value, json};
use zeroclaw_api::tool::ToolResult;

use crate::capabilities::executor::{CapabilityExecutor, CapabilityRequest};
use crate::capabilities::provider::{CapabilityProvider, CapabilityStatus};
use super::bridge;

pub struct SandboxAndroidProvider {
    name: String,
    enabled: bool,
    priority: u32,
}

impl SandboxAndroidProvider {
    pub fn new() -> Self {
        Self {
            name: "sandbox_android".to_string(),
            enabled: true,
            priority: 10,
        }
    }

    pub fn disabled() -> Self {
        Self {
            name: "sandbox_android".to_string(),
            enabled: false,
            priority: 10,
        }
    }
}

#[async_trait]
impl CapabilityProvider for SandboxAndroidProvider {
    fn name(&self) -> &str { &self.name }
    fn enabled(&self) -> bool { self.enabled }
    fn priority(&self) -> u32 { self.priority }

    async fn health_check(&self) -> CapabilityStatus {
        match bridge::sandbox_bridge() {
            Some(sb) => {
                let has_token = sb.has_token();
                CapabilityStatus {
                    healthy: has_token,
                    degraded_reason: if has_token {
                        None
                    } else {
                        Some("Linux sandbox bridge token not configured. Enable sandbox in Plugins.".to_string())
                    },
                    supports_network: true,
                    supports_packages: true,
                    supports_background: true,
                    supports_pty: false,
                    available_disk_bytes: 0,
                    available_memory_bytes: 0,
                    active_sessions: 0,
                }
            }
            None => CapabilityStatus {
                healthy: false,
                degraded_reason: Some("Sandbox bridge not registered".to_string()),
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
        match bridge::sandbox_bridge() {
            Some(sb) => json!({
                "provider": "sandbox_android",
                "token_configured": sb.has_token(),
                "platform": "android",
                "runtime": "proot_alpine",
            }),
            None => json!({
                "provider": "sandbox_android",
                "token_configured": false,
                "platform": "android",
            }),
        }
    }
}

pub struct SandboxManageProcessProvider {
    name: String,
    enabled: bool,
    priority: u32,
}

impl SandboxManageProcessProvider {
    pub fn new() -> Self {
        Self {
            name: "sandbox_manage_process".to_string(),
            enabled: true,
            priority: 10,
        }
    }

    pub fn disabled() -> Self {
        Self {
            name: "sandbox_manage_process".to_string(),
            enabled: false,
            priority: 10,
        }
    }
}

#[async_trait]
impl CapabilityProvider for SandboxManageProcessProvider {
    fn name(&self) -> &str { &self.name }
    fn enabled(&self) -> bool { self.enabled }
    fn priority(&self) -> u32 { self.priority }

    async fn health_check(&self) -> CapabilityStatus {
        match bridge::sandbox_bridge() {
            Some(sb) => {
                let has_token = sb.has_token();
                CapabilityStatus {
                    healthy: has_token,
                    degraded_reason: if has_token {
                        None
                    } else {
                        Some("Linux sandbox bridge token not configured.".to_string())
                    },
                    supports_network: false,
                    supports_packages: false,
                    supports_background: true,
                    supports_pty: false,
                    available_disk_bytes: 0,
                    available_memory_bytes: 0,
                    active_sessions: 0,
                }
            }
            None => CapabilityStatus {
                healthy: false,
                degraded_reason: Some("Sandbox bridge not registered".to_string()),
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
        match bridge::sandbox_bridge() {
            Some(sb) => json!({
                "provider": "sandbox_manage_process",
                "token_configured": sb.has_token(),
            }),
            None => json!({
                "provider": "sandbox_manage_process",
                "token_configured": false,
            }),
        }
    }
}

pub struct SandboxAndroidExecutor;

impl SandboxAndroidExecutor {
    pub fn new() -> Self { Self }
}

#[async_trait]
impl CapabilityExecutor for SandboxAndroidExecutor {
    async fn execute(&self, request: CapabilityRequest) -> anyhow::Result<ToolResult> {
        let CapabilityRequest::SandboxExecute(req) = request else {
            return Err(anyhow::anyhow!("SandboxAndroidExecutor received unexpected request type"));
        };

        let sb = bridge::sandbox_bridge()
            .ok_or_else(|| anyhow::anyhow!("Sandbox bridge not registered"))?;

        let mut args = json!({
            "command": req.command,
        });

        if let Some(timeout) = req.timeout {
            args["timeout"] = json!(timeout);
        }
        if let Some(working_dir) = req.working_dir {
            args["working_dir"] = json!(working_dir);
        }
        if let Some(env) = req.env {
            args["env"] = json!(env);
        }
        if let Some(background) = req.background {
            args["background"] = json!(background);
        }
        if let Some(fresh) = req.fresh {
            args["fresh"] = json!(fresh);
        }

        match sb.execute(args).await {
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

pub struct SandboxManageProcessExecutor;

impl SandboxManageProcessExecutor {
    pub fn new() -> Self { Self }
}

#[async_trait]
impl CapabilityExecutor for SandboxManageProcessExecutor {
    async fn execute(&self, request: CapabilityRequest) -> anyhow::Result<ToolResult> {
        let CapabilityRequest::SandboxManageProcess(req) = request else {
            return Err(anyhow::anyhow!("SandboxManageProcessExecutor received unexpected request type"));
        };

        let sb = bridge::sandbox_bridge()
            .ok_or_else(|| anyhow::anyhow!("Sandbox bridge not registered"))?;

        let args = json!({
            "action": req.action,
            "session_id": req.session_id,
            "offset": req.offset.unwrap_or(0),
            "limit": req.limit.unwrap_or(200),
        });

        match sb.manage_process(args).await {
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