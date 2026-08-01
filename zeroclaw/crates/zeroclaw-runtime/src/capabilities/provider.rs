use async_trait::async_trait;
use serde_json::Value;

/// Runtime status of a capability — can change dynamically.
#[derive(Debug, Clone, Default)]
pub struct CapabilityStatus {
    pub healthy: bool,
    pub degraded_reason: Option<String>,
    pub supports_network: bool,
    pub supports_packages: bool,
    pub supports_background: bool,
    pub supports_pty: bool,
    pub available_disk_bytes: u64,
    pub available_memory_bytes: u64,
    pub active_sessions: u32,
}

/// A capability provider owns the runtime for a capability.
///
/// Every plugin implements this trait. The provider:
/// - Reports whether it is enabled and healthy
/// - Exposes priority for provider selection
/// - Returns live context about the environment
#[async_trait]
pub trait CapabilityProvider: Send + Sync {
    /// Provider name (e.g., "sandbox", "termux", "sqlite").
    fn name(&self) -> &str;

    /// Whether this provider is currently enabled.
    fn enabled(&self) -> bool;

    /// Priority for provider selection (lower = preferred).
    fn priority(&self) -> u32;

    /// Check if this provider is healthy and available.
    async fn health_check(&self) -> CapabilityStatus;

    /// Return live context about this capability's environment.
    /// Injected into the system prompt for planning.
    async fn context(&self) -> Value;
}
