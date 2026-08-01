use async_trait::async_trait;
use serde_json::Value;
use std::sync::OnceLock;

// ── Termux Bridge ─────────────────────────────────────────────────────

#[async_trait]
pub trait TermuxBridge: Send + Sync {
    fn has_token(&self) -> bool;
    async fn capabilities(&self, force_refresh: bool) -> Result<Value, String>;
    async fn execute_low_risk(&self, args: Value) -> Result<Value, String>;
}

static TERMUX_BRIDGE: OnceLock<Box<dyn TermuxBridge>> = OnceLock::new();

pub fn register_termux_bridge(bridge: Box<dyn TermuxBridge>) {
    let _ = TERMUX_BRIDGE.set(bridge);
}

pub fn termux_bridge() -> Option<&'static dyn TermuxBridge> {
    TERMUX_BRIDGE.get().map(|b| b.as_ref())
}

pub fn has_termux_bridge() -> bool {
    TERMUX_BRIDGE.get().is_some()
}

// ── Sandbox Bridge ────────────────────────────────────────────────────

#[async_trait]
pub trait SandboxBridge: Send + Sync {
    fn has_token(&self) -> bool;
    async fn health(&self) -> Result<Value, String>;
    async fn execute(&self, args: Value) -> Result<Value, String>;
    async fn manage_process(&self, args: Value) -> Result<Value, String>;
}

static SANDBOX_BRIDGE: OnceLock<Box<dyn SandboxBridge>> = OnceLock::new();

pub fn register_sandbox_bridge(bridge: Box<dyn SandboxBridge>) {
    let _ = SANDBOX_BRIDGE.set(bridge);
}

pub fn sandbox_bridge() -> Option<&'static dyn SandboxBridge> {
    SANDBOX_BRIDGE.get().map(|b| b.as_ref())
}

pub fn has_sandbox_bridge() -> bool {
    SANDBOX_BRIDGE.get().is_some()
}
