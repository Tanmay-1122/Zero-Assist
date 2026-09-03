pub mod debug;
pub mod executor;
pub mod provider;
pub mod providers;
pub mod resolver;
pub mod shell_runtime;

use std::collections::HashMap;
use std::sync::{Arc, Mutex};

use crate::capabilities::debug::DecisionLogger;

/// Opaque handle for a registered capability.
/// Internally routed — never visible to the LLM.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub struct CapabilityId(u64);

impl CapabilityId {
    pub fn as_u64(&self) -> u64 {
        self.0
    }
}

/// A registered capability with its provider and executor.
pub struct Capability {
    pub id: CapabilityId,
    /// Stable name the LLM uses to call this capability (e.g., "shell").
    pub stable_name: String,
    /// Human-readable name for prompts/UI (e.g., "Shell").
    pub display_name: String,
    /// Short description of what this capability provides.
    pub description: String,
    /// The provider that owns this capability.
    pub provider: Arc<dyn provider::CapabilityProvider>,
    /// The executor that runs actions for this capability.
    pub executor: Arc<dyn executor::CapabilityExecutor>,
    /// Current runtime status.
    pub status: Arc<std::sync::RwLock<provider::CapabilityStatus>>,
    /// Optional custom JSON Schema for tool parameters.
    /// When set, CapabilityTool exposes this schema to the LLM instead of
    /// the generic shell schema. Required for capabilities
    /// that have non-trivial parameter shapes.
    pub parameters_schema: Option<serde_json::Value>,
}

/// Registry of all capabilities. The single source of truth for what the AI can do.
pub struct CapabilityRegistry {
    by_stable_name: HashMap<String, CapabilityId>,
    by_id: HashMap<CapabilityId, Capability>,
    next_id: u64,
    logger: Arc<Mutex<DecisionLogger>>,
}

impl CapabilityRegistry {
    pub fn new() -> Self {
        Self {
            by_stable_name: HashMap::new(),
            by_id: HashMap::new(),
            next_id: 1,
            logger: Arc::new(Mutex::new(DecisionLogger::new(500))),
        }
    }

    pub fn logger(&self) -> &Arc<Mutex<DecisionLogger>> {
        &self.logger
    }

    /// Register a capability and its provider + executor.
    /// Returns the assigned CapabilityId.
    pub fn register(
        &mut self,
        stable_name: &str,
        display_name: &str,
        description: &str,
        provider: Arc<dyn provider::CapabilityProvider>,
        executor: Arc<dyn executor::CapabilityExecutor>,
    ) -> CapabilityId {
        let id = CapabilityId(self.next_id);
        self.next_id += 1;

        let status = Arc::new(std::sync::RwLock::new(provider::CapabilityStatus {
            healthy: true,
            degraded_reason: None,
            supports_network: false,
            supports_packages: false,
            supports_background: false,
            supports_pty: false,
            available_disk_bytes: 0,
            available_memory_bytes: 0,
            active_sessions: 0,
        }));

        let cap = Capability {
            id,
            stable_name: stable_name.to_string(),
            display_name: display_name.to_string(),
            description: description.to_string(),
            provider,
            executor,
            status,
            parameters_schema: None,
        };

        self.by_stable_name
            .insert(stable_name.to_string(), id);
        self.by_id.insert(id, cap);

        id
    }

    /// Set a custom parameters_schema on a registered capability.
    ///
    /// When set, CapabilityTool exposes this JSON Schema to the LLM instead of
    /// the generic shell schema. This is required for capabilities
    /// that have non-trivial parameter shapes.
    pub fn set_parameters_schema(
        &mut self,
        stable_name: &str,
        schema: serde_json::Value,
    ) {
        if let Some(id) = self.by_stable_name.get(stable_name).copied() {
            if let Some(cap) = self.by_id.get_mut(&id) {
                cap.parameters_schema = Some(schema);
            }
        }
    }

    /// Look up a capability by its stable name (what the LLM sends).
    pub fn by_stable_name(&self, name: &str) -> Option<&Capability> {
        self.by_stable_name
            .get(name)
            .and_then(|id| self.by_id.get(id))
    }

    /// Look up a capability by its ID.
    pub fn by_id(&self, id: CapabilityId) -> Option<&Capability> {
        self.by_id.get(&id)
    }

    /// List all registered stable names.
    pub fn list_names(&self) -> Vec<&str> {
        self.by_stable_name.keys().map(|s| s.as_str()).collect()
    }

    /// List all enabled capabilities with their summaries.
    pub fn list_enabled(&self) -> Vec<CapabilitySummary> {
        self.by_id
            .values()
            .filter(|c| c.provider.enabled())
            .map(|c| CapabilitySummary {
                stable_name: c.stable_name.clone(),
                display_name: c.display_name.clone(),
                description: c.description.clone(),
            })
            .collect()
    }

    /// Number of registered capabilities.
    pub fn len(&self) -> usize {
        self.by_id.len()
    }

    pub fn is_empty(&self) -> bool {
        self.by_id.is_empty()
    }
}

/// Public summary of a capability for prompts and UI.
#[derive(Debug, Clone)]
pub struct CapabilitySummary {
    pub stable_name: String,
    pub display_name: String,
    pub description: String,
}

/// Build the default capability registry, resolver, and runtime state.
///
/// Called once at startup. CapabilityTool wrappers are created by the caller
/// from `registry.list_enabled()` so that plugin visibility is automatic:
/// disabled capabilities never produce a CapabilityTool and the AI never sees them.
pub fn build_default_registry(
    security: Arc<crate::security::SecurityPolicy>,
    runtime: Arc<dyn crate::platform::RuntimeAdapter>,
    timeout_secs: u64,
) -> (
    Arc<std::sync::RwLock<CapabilityRegistry>>,
    resolver::CapabilityResolver,
    Arc<std::sync::Mutex<debug::DecisionLogger>>,
    Arc<std::sync::Mutex<shell_runtime::ShellRuntime>>,
) {
    use crate::capabilities::executor::CapabilityExecutor;
    use crate::capabilities::providers::memory::{MemoryExecutor, MemoryProvider};
    use crate::capabilities::providers::sandbox_android::{SandboxAndroidExecutor, SandboxAndroidProvider, SandboxManageProcessExecutor, SandboxManageProcessProvider};
    use crate::capabilities::providers::shell::{ShellExecutor, ShellProvider};
    use crate::capabilities::providers::termux_android::{TermuxAndroidExecutor, TermuxAndroidProvider, TermuxCapabilitiesExecutor, TermuxCapabilitiesProvider};
    use crate::capabilities::providers::web::{WebExecutor, WebProvider};
    use crate::capabilities::shell_runtime::ShellRuntime;

    let runtime_state = Arc::new(std::sync::Mutex::new(ShellRuntime::new(
        security.workspace_dir.clone(),
    )));

    let shell_executor: Arc<dyn CapabilityExecutor> = Arc::new(ShellExecutor::new(
        security.clone(),
        runtime.clone(),
        timeout_secs,
        Arc::clone(&runtime_state),
    ));

    let mut registry = CapabilityRegistry::new();

    registry.register(
        "shell",
        "Shell",
        "Persistent Linux environment with full shell access",
        Arc::new(ShellProvider::with_runtime_state(Arc::clone(&runtime_state))),
        Arc::clone(&shell_executor),
    );

    registry.register(
        "termux_get_capabilities",
        "Termux Capabilities",
        "Inspect the user's local Termux runtime through Zero Assist's authenticated bridge. Returns installed commands, Python version, workspace paths, proot status, and execution limits.",
        Arc::new(TermuxCapabilitiesProvider::new()),
        Arc::new(TermuxCapabilitiesExecutor::new()),
    );

    registry.register(
        "termux_run",
        "Termux Run",
        "Execute commands directly in the user's existing Termux environment on the Android device. Use only when the user explicitly asks to interact with their Termux installation, its files, or Android host tools. For general Linux commands, packages, and scripting, use sandbox_execute instead.",
        Arc::new(TermuxAndroidProvider::new()),
        Arc::new(TermuxAndroidExecutor::new()),
    );

    registry.register(
        "memory",
        "Memory",
        "Persistent memory storage and recall with semantic search",
        Arc::new(MemoryProvider::disabled()),
        Arc::new(MemoryExecutor::new()),
    );

    registry.register(
        "web",
        "Web",
        "Web search and page fetching capabilities",
        Arc::new(WebProvider::disabled()),
        Arc::new(WebExecutor::new()),
    );

    registry.register(
        "sandbox_execute",
        "Sandbox Execute",
        "Execute a shell command in an Alpine Linux sandbox and return stdout, stderr, exit code, and current working directory. The environment is a full Alpine Linux system running via proot. Shell session is PERSISTENT across calls within THIS conversation: cwd, exported environment variables, and any in-shell state carry from one call to the next, just like a normal terminal. Pre-installed: bash, python3 (pip), nodejs, git, curl, wget, jq, plus remote-server tools — ssh, scp, sftp (openssh-client), lftp (FTP/FTPS), rsync. Limits: Output capped at 15000 characters per stream; default timeout 30s, max 60s. Fullscreen TUIs (top, htop, vim, less, nano) WILL NOT WORK — the sandbox has no PTY. Use non-interactive variants: \"top -bn1\", \"ps aux\", etc. Set background=true to run a long-lived process detached from the shell. Use sandbox_manage_process to check on it. Set fresh=true to run in a one-shot isolated shell that doesn't share state with the persistent session. Install extra packages with: apk add <package>",
        Arc::new(SandboxAndroidProvider::new()),
        Arc::new(SandboxAndroidExecutor::new()),
    );

    registry.register(
        "sandbox_manage_process",
        "Sandbox Manage Process",
        "Manage background shell processes started with sandbox_execute (background=true). Actions: list, log, kill, remove.",
        Arc::new(SandboxManageProcessProvider::new()),
        Arc::new(SandboxManageProcessExecutor::new()),
    );

    let registry = Arc::new(std::sync::RwLock::new(registry));
    let logger_clone = Arc::clone(registry.read().unwrap().logger());
    let resolver = resolver::CapabilityResolver::new(Arc::clone(&registry));

    (registry, resolver, logger_clone, runtime_state)
}
