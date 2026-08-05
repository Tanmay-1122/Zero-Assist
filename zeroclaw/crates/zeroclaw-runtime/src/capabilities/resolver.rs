use std::sync::Arc;
use zeroclaw_api::tool::ToolResult;

use super::debug::{DecisionLogger, ExecutionDecision};
use super::executor::{self, CapabilityExecutor, CapabilityRequest};
use super::CapabilityRegistry;
use tracing;  // enabled via zeroclaw-runtime Cargo.toml features

/// Resolves capability names to executor handles.
///
/// Selection policy:
/// 1. Enabled — skip disabled providers
/// 2. Healthy — skip unhealthy providers
/// 3. Priority — lower priority value = preferred (only matters with multiple providers per capability)
///
/// This is the single entry point for capability execution.
#[derive(Clone)]
pub struct CapabilityResolver {
    registry: Arc<std::sync::RwLock<CapabilityRegistry>>,
}

impl CapabilityResolver {
    pub fn new(registry: Arc<std::sync::RwLock<CapabilityRegistry>>) -> Self {
        Self { registry }
    }

    /// Check if a tool name is a registered capability.
    pub fn is_capability(&self, tool_name: &str) -> bool {
        if let Ok(r) = self.registry.read() {
            r.by_stable_name(tool_name).is_some()
        } else {
            false
        }
    }

    /// Resolve a capability name to an executor + parsed request.
    ///
    /// Selection policy (applied in order):
    /// 1. Enabled — skip disabled providers
    /// 2. Healthy — skip unhealthy providers
    /// 3. Priority — lower priority = preferred (for multi-provider capabilities)
    ///
    /// Returns:
    /// - `Some(Ok((executor, request)))` — ready to execute
    /// - `Some(Err(e))` — capability found but request parse failed
    /// - `None` — capability not found, disabled, or unhealthy
    async fn resolve(
        &self,
        tool_name: &str,
        args: &serde_json::Value,
    ) -> Option<anyhow::Result<(Arc<dyn CapabilityExecutor>, CapabilityRequest)>> {
        tracing::debug!(tool_name, "Resolver: resolving capability");

        // Step 1: Parse the typed request from JSON args
        let request = match executor::parse_capability_request(tool_name, args) {
            Ok(r) => r,
            Err(e) => {
                tracing::warn!(tool_name, error = %e, "Resolver: failed to parse request");
                return Some(Err(anyhow::anyhow!("{e}")));
            }
        };

        // Step 2: Look up capability, apply Enabled gate, extract handles
        let (executor, provider) = {
            let registry = self.registry.read().ok()?;
            let capability = registry.by_stable_name(tool_name)?;
            if !capability.provider.enabled() {
                tracing::debug!(tool_name, "Resolver: capability is disabled, skipping");
                return None;
            }
            (Arc::clone(&capability.executor), Arc::clone(&capability.provider))
        };

        // Step 3: Health check (async, no lock held)
        let status = provider.health_check().await;
        if !status.healthy {
            tracing::warn!(tool_name, reason = ?status.degraded_reason, "Resolver: capability is unhealthy");
            return None;
        }

        tracing::debug!(tool_name, "Resolver: resolved successfully");
        Some(Ok((executor, request)))
    }

    /// Full execution pipeline: resolve → execute → return ToolResult.
    /// Returns None when the capability is not available (disabled, unhealthy, not found).
    /// Returns Some(Err) when parsing fails or execution fails.
    pub async fn execute(
        &self,
        tool_name: &str,
        args: &serde_json::Value,
    ) -> Option<anyhow::Result<ToolResult>> {
        let start = std::time::Instant::now();
        tracing::debug!(tool_name, "Resolver: executing capability");

        let resolved = self.resolve(tool_name, args).await?;
        let result = match resolved {
            Ok((executor, request)) => Some(executor.execute(request).await),
            Err(e) => Some(Err(e)),
        };

        let duration = start.elapsed();
        if let Some(Ok(ref res)) = result {
            tracing::debug!(tool_name, duration_ms = duration.as_millis() as u64, success = res.success, "Resolver: execution complete");
        } else if let Some(Err(ref e)) = result {
            tracing::warn!(tool_name, duration_ms = duration.as_millis() as u64, error = %e, "Resolver: execution failed");
        }

        result
    }

    /// Access the shared decision logger from the registry.
    pub fn logger(&self) -> Option<Arc<std::sync::Mutex<DecisionLogger>>> {
        self.registry.read().ok().map(|r| Arc::clone(r.logger()))
    }

    /// List all enabled capabilities with their metadata.
    pub fn list_capabilities(&self) -> Vec<super::CapabilitySummary> {
        self.registry
            .read()
            .ok()
            .map(|r| r.list_enabled())
            .unwrap_or_default()
    }
}

/// A Tool wrapper that delegates to CapabilityResolver.
///
/// Stores only the capability name and a reference to the resolver.
/// All other metadata (description, schema) is derived from the registry.
pub struct CapabilityTool {
    stable_name: String,
    resolver: Arc<CapabilityResolver>,
    description: String,
}

impl CapabilityTool {
    pub fn new(stable_name: &str, resolver: Arc<CapabilityResolver>) -> Self {
        // Look up description from registry at construction time.
        let description = if let Ok(registry) = resolver.registry.read() {
            if let Some(cap) = registry.by_stable_name(stable_name) {
                cap.description.clone()
            } else {
                String::new()
            }
        } else {
            String::new()
        };
        Self {
            stable_name: stable_name.to_string(),
            resolver,
            description,
        }
    }

    /// Look up the capability's display name from the registry.
    fn display_name(&self) -> String {
        if let Ok(registry) = self.resolver.registry.read() {
            if let Some(cap) = registry.by_stable_name(&self.stable_name) {
                return cap.display_name.clone();
            }
        }
        self.stable_name.clone()
    }
}

#[async_trait::async_trait]
impl zeroclaw_api::tool::Tool for CapabilityTool {
    fn name(&self) -> &str {
        &self.stable_name
    }

    fn description(&self) -> &str {
        &self.description
    }

    fn parameters_schema(&self) -> serde_json::Value {
        // Try to get a custom schema from the registry first.
        if let Ok(registry) = self.resolver.registry.read() {
            if let Some(cap) = registry.by_stable_name(&self.stable_name) {
                if let Some(ref schema) = cap.parameters_schema {
                    return schema.clone();
                }
            }
        }
        // Fallback to the generic shell-style schema.
        serde_json::json!({
            "type": "object",
            "properties": {
                "command": {
                    "type": "string",
                    "description": "The command to execute"
                },
                "approved": {
                    "type": "boolean",
                    "description": "Set true to explicitly approve medium/high-risk commands in supervised mode",
                    "default": false
                }
            },
            "required": ["command"]
        })
    }

    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let start = std::time::Instant::now();

        let result = match self.resolver.execute(&self.stable_name, &args).await {
            Some(Ok(res)) => Ok(res),
            Some(Err(e)) => Err(e),
            None => Err(anyhow::anyhow!(
                "Capability '{}' is not available or disabled",
                self.stable_name
            )),
        };

        let duration = start.elapsed();

        if let Ok(ref res) = result {
            if let Some(logger) = self.resolver.logger() {
                if let Ok(mut logger) = logger.lock() {
                    let display_name = self.display_name();
                    let mut decision = ExecutionDecision::new(
                        &self.stable_name,
                        &display_name,
                        &self.stable_name,
                        &self.stable_name,
                        "resolved_via_resolver",
                    );
                    decision.duration = duration;
                    decision.success = res.success;
                    if let Some(ref err) = res.error {
                        decision.reason = err.clone();
                    }
                    logger.log(decision);
                }
            }
        }

        result
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::capabilities::provider::{CapabilityProvider, CapabilityStatus};
    use async_trait::async_trait;
    use std::sync::Arc;
    use zeroclaw_api::tool::Tool;

    struct TestExecutor {
        name: String,
    }

    #[async_trait]
    impl CapabilityExecutor for TestExecutor {
        async fn execute(&self, request: CapabilityRequest) -> anyhow::Result<ToolResult> {
            Ok(ToolResult {
                success: true,
                output: format!("{} executed: {:?}", self.name, request),
                error: None,
            blocks: Vec::new(),
            metadata: None,
            })
        }
    }

    struct TestProvider {
        name: String,
        enabled: bool,
        healthy: bool,
    }

    #[async_trait]
    impl CapabilityProvider for TestProvider {
        fn name(&self) -> &str { &self.name }
        fn enabled(&self) -> bool { self.enabled }
        fn priority(&self) -> u32 { 10 }
        async fn health_check(&self) -> CapabilityStatus {
            CapabilityStatus {
                healthy: self.healthy,
                degraded_reason: if self.healthy { None } else { Some("test unhealthy".into()) },
                ..CapabilityStatus::default()
            }
        }
        async fn context(&self) -> serde_json::Value { serde_json::json!({}) }
    }

    fn test_registry() -> (Arc<std::sync::RwLock<CapabilityRegistry>>, Arc<CapabilityResolver>) {
        let mut registry = CapabilityRegistry::new();
        let executor: Arc<dyn CapabilityExecutor> = Arc::new(TestExecutor { name: "shell_exec".into() });
        registry.register(
            "shell", "Shell", "Test shell",
            Arc::new(TestProvider { name: "sandbox".into(), enabled: true, healthy: true }),
            executor,
        );
        let executor2: Arc<dyn CapabilityExecutor> = Arc::new(TestExecutor { name: "mem_exec".into() });
        registry.register(
            "memory", "Memory", "Test memory",
            Arc::new(TestProvider { name: "memory_provider".into(), enabled: false, healthy: true }),
            executor2,
        );
        let r = Arc::new(std::sync::RwLock::new(registry));
        let resolver = Arc::new(CapabilityResolver::new(Arc::clone(&r)));
        (r, resolver)
    }

    #[tokio::test]
    async fn resolver_execute_returns_for_enabled_capability() {
        let (_reg, resolver) = test_registry();
        let args = serde_json::json!({"command": "echo hello"});
        let result = resolver.execute("shell", &args).await;
        assert!(result.is_some(), "resolver should return Some for enabled capability");
        let inner = result.unwrap();
        assert!(inner.is_ok(), "resolver should return Ok for valid request");
        let res = inner.unwrap();
        assert!(res.success);
        assert!(res.output.contains("shell_exec"));
    }

    #[tokio::test]
    async fn resolver_execute_returns_none_for_disabled_provider() {
        let (_reg, resolver) = test_registry();
        let args = serde_json::json!({"action": "store", "query": "test"});
        let result = resolver.execute("memory", &args).await;
        assert!(result.is_none(), "disabled capability should return None");
    }

    #[tokio::test]
    async fn resolver_execute_returns_err_for_unknown_capability() {
        let (_reg, resolver) = test_registry();
        let args = serde_json::json!({"command": "ls"});
        let result = resolver.execute("nonexistent", &args).await;
        assert!(result.is_some(), "unknown capability should return Some(Err)");
        assert!(result.unwrap().is_err(), "unknown capability should be an Err");
    }

    #[tokio::test]
    async fn resolver_execute_returns_err_for_invalid_request() {
        let (_reg, resolver) = test_registry();
        let args = serde_json::json!({"wrong_field": "value"});
        let result = resolver.execute("shell", &args).await;
        assert!(result.is_some(), "resolver should return Some even for parse errors");
        assert!(result.unwrap().is_err(), "parse errors should be propagated as Err");
    }

    #[tokio::test]
    async fn resolver_list_capabilities_only_includes_enabled() {
        let (_reg, resolver) = test_registry();
        let caps = resolver.list_capabilities();
        assert_eq!(caps.len(), 1, "only shell should be listed");
        assert_eq!(caps[0].stable_name, "shell");
    }

    #[tokio::test]
    async fn capability_tool_executes_through_resolver() {
        let (_reg, resolver) = test_registry();
        let cap_tool = CapabilityTool::new("shell", Arc::clone(&resolver));
        let args = serde_json::json!({"command": "echo hello"});
        let result = cap_tool.execute(args).await;
        assert!(result.is_ok(), "CapabilityTool should execute successfully");
        let res = result.unwrap();
        assert!(res.success, "execution should succeed");
        assert!(res.output.contains("shell_exec"), "should contain executor name");
    }

    #[tokio::test]
    async fn capability_tool_fails_for_disabled_capability() {
        let (_reg, resolver) = test_registry();
        let cap_tool = CapabilityTool::new("memory", Arc::clone(&resolver));
        let args = serde_json::json!({"action": "store", "query": "test"});
        let result = cap_tool.execute(args).await;
        assert!(result.is_err(), "disabled capability should fail at execute time");
        let err = result.err().unwrap().to_string();
        assert!(err.contains("not available") || err.contains("disabled"),
            "error should indicate unavailability: {err}");
    }

    #[tokio::test]
    async fn capability_tool_fails_for_unknown_capability() {
        let (_reg, resolver) = test_registry();
        let cap_tool = CapabilityTool::new("nonexistent", resolver);
        let args = serde_json::json!({"command": "ls"});
        let result = cap_tool.execute(args).await;
        assert!(result.is_err(), "unknown capability should fail");
    }

    // ── Plugin visibility tests ─────────────────────────────────────

    #[tokio::test]
    async fn plugin_visibility_shell_enabled_is_visible() {
        let (_reg, resolver) = test_registry();
        let caps = resolver.list_capabilities();
        let names: Vec<&str> = caps.iter().map(|c| c.stable_name.as_str()).collect();
        assert!(names.contains(&"shell"), "shell should be visible when enabled");
    }

    #[tokio::test]
    async fn plugin_visibility_disabled_capability_hidden_from_list() {
        let (_reg, resolver) = test_registry();
        let caps = resolver.list_capabilities();
        let names: Vec<&str> = caps.iter().map(|c| c.stable_name.as_str()).collect();
        assert!(!names.contains(&"memory"), "disabled memory should not appear in list");
    }

    #[tokio::test]
    async fn plugin_visibility_shell_disabled_hidden_from_list() {
        let mut registry = CapabilityRegistry::new();
        let executor: Arc<dyn CapabilityExecutor> = Arc::new(TestExecutor { name: "s".into() });
        // Register shell as DISABLED
        registry.register(
            "shell", "Shell", "Test shell",
            Arc::new(TestProvider { name: "sandbox".into(), enabled: false, healthy: true }),
            executor,
        );
        let r = Arc::new(std::sync::RwLock::new(registry));
        let resolver = Arc::new(CapabilityResolver::new(Arc::clone(&r)));

        // Should not appear in list
        let caps = resolver.list_capabilities();
        assert!(caps.is_empty(), "no capabilities should be visible when shell is disabled");

        // Should fail at execution time
        let tool = CapabilityTool::new("shell", Arc::clone(&resolver));
        let result = tool.execute(serde_json::json!({"command": "ls"})).await;
        assert!(result.is_err(), "shell tool should fail when provider is disabled");
    }

    #[tokio::test]
    async fn plugin_visibility_termux_disabled_invisible() {
        // Termux is registered as disabled (non-Android). Verify it never appears.
        let mut registry = CapabilityRegistry::new();
        let executor: Arc<dyn CapabilityExecutor> = Arc::new(TestExecutor { name: "t".into() });
        registry.register(
            "termux", "Termux", "Android shell",
            Arc::new(TestProvider { name: "termux_provider".into(), enabled: false, healthy: false }),
            executor,
        );
        // Also add shell so the list isn't empty
        let shell_exec: Arc<dyn CapabilityExecutor> = Arc::new(TestExecutor { name: "s".into() });
        registry.register(
            "shell", "Shell", "Test shell",
            Arc::new(TestProvider { name: "sandbox".into(), enabled: true, healthy: true }),
            shell_exec,
        );
        let r = Arc::new(std::sync::RwLock::new(registry));
        let resolver = Arc::new(CapabilityResolver::new(Arc::clone(&r)));

        // Termux should not appear in list
        let caps = resolver.list_capabilities();
        let names: Vec<&str> = caps.iter().map(|c| c.stable_name.as_str()).collect();
        assert!(!names.contains(&"termux"), "disabled termux should not appear in list");
        assert!(names.contains(&"shell"), "shell should still be visible");

        // Termux should fail at execution time
        let tool = CapabilityTool::new("termux", Arc::clone(&resolver));
        let result = tool.execute(serde_json::json!({"command": "echo"})).await;
        assert!(result.is_err(), "termux tool should fail when provider is disabled");
    }
}