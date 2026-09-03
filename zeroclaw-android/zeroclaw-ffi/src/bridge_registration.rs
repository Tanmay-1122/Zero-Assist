use async_trait::async_trait;
use serde_json::Value;

use zeroclaw_runtime::capabilities::providers::bridge::{
    SandboxBridge, TermuxBridge,
    register_termux_bridge, register_sandbox_bridge,
};

pub(crate) struct FfiTermuxBridge;

#[async_trait]
impl TermuxBridge for FfiTermuxBridge {
    fn has_token(&self) -> bool {
        crate::termux_bridge_client::has_configured_bridge_auth_token()
    }

    async fn capabilities(&self, _force_refresh: bool) -> Result<Value, String> {
        crate::termux_bridge_client::capabilities().await
    }

    async fn execute_low_risk(&self, args: Value) -> Result<Value, String> {
        crate::termux_bridge_client::execute_low_risk(args).await
    }
}

pub(crate) struct FfiSandboxBridge;

#[async_trait]
impl SandboxBridge for FfiSandboxBridge {
    fn has_token(&self) -> bool {
        crate::sandbox_bridge_client::has_configured_bridge_auth_token()
    }

    async fn health(&self) -> Result<Value, String> {
        crate::sandbox_bridge_client::health().await
    }

    async fn execute(&self, args: Value) -> Result<Value, String> {
        crate::sandbox_bridge_client::execute(args).await
    }

    async fn manage_process(&self, args: Value) -> Result<Value, String> {
        crate::sandbox_bridge_client::manage_process(args).await
    }
}

pub(crate) fn register_all_bridges() {
    register_termux_bridge(Box::new(FfiTermuxBridge));
    register_sandbox_bridge(Box::new(FfiSandboxBridge));
}
