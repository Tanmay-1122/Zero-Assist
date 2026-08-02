use async_trait::async_trait;
use serde_json::json;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use zeroclaw_api::tool::ToolResult;

use crate::capabilities::executor::{CapabilityExecutor, CapabilityRequest, ShellRequest};
use crate::capabilities::provider::{CapabilityProvider, CapabilityStatus};
use crate::capabilities::shell_runtime::ShellRuntime;
use crate::platform::RuntimeAdapter;
use crate::security::SecurityPolicy;
/// Environment variables safe to pass to shell commands.
/// Only functional variables are included — never API keys or secrets.
#[cfg(not(target_os = "windows"))]
pub(crate) const SAFE_ENV_VARS: &[&str] = &[
    "PATH", "HOME", "TERM", "LANG", "LC_ALL", "LC_CTYPE", "USER", "SHELL", "TMPDIR",
];

#[cfg(target_os = "windows")]
pub(crate) const SAFE_ENV_VARS: &[&str] = &[
    "PATH", "PATHEXT", "HOME", "USERPROFILE", "HOMEDRIVE", "HOMEPATH",
    "SYSTEMROOT", "SYSTEMDRIVE", "WINDIR", "COMSPEC", "TEMP", "TMP",
    "TERM", "LANG", "USERNAME",
];

/// Maximum output size in bytes (1 MB).
const MAX_OUTPUT_BYTES: usize = 1_048_576;

/// Default shell command timeout.
const DEFAULT_TIMEOUT_SECS: u64 = 60;

/// Shell capability provider.
pub struct ShellProvider {
    name: String,
    enabled: bool,
    priority: u32,
    runtime_state: Option<Arc<Mutex<ShellRuntime>>>,
}

impl ShellProvider {
    pub fn new() -> Self {
        Self {
            name: "sandbox".to_string(),
            enabled: true,
            priority: 10,
            runtime_state: None,
        }
    }

    pub fn with_runtime_state(runtime_state: Arc<Mutex<ShellRuntime>>) -> Self {
        Self {
            name: "sandbox".to_string(),
            enabled: true,
            priority: 10,
            runtime_state: Some(runtime_state),
        }
    }
}

#[async_trait]
impl CapabilityProvider for ShellProvider {
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
            healthy: true,
            degraded_reason: None,
            supports_network: false,
            supports_packages: true,
            supports_background: true,
            supports_pty: false,
            available_disk_bytes: 0,
            available_memory_bytes: 0,
            active_sessions: 1,
        }
    }

    async fn context(&self) -> serde_json::Value {
        let mut ctx = json!({
            "provider": "sandbox",
            "persistent_session": true,
            "status": "active"
        });
        if let Some(ref rt) = self.runtime_state {
            if let Ok(runtime) = rt.lock() {
                ctx["shell"] = json!({
                    "cwd": runtime.cwd.to_string_lossy(),
                    "last_exit": runtime.last_exit,
                    "env_count": runtime.env.len(),
                    "jobs": runtime.jobs.len(),
                });
            }
        }
        ctx
    }
}

/// Shell capability executor with persistent session state.
pub struct ShellExecutor {
    security: Arc<SecurityPolicy>,
    runtime: Arc<dyn RuntimeAdapter>,
    timeout_secs: u64,
    runtime_state: Arc<Mutex<ShellRuntime>>,
}

impl ShellExecutor {
    pub fn new(
        security: Arc<SecurityPolicy>,
        runtime: Arc<dyn RuntimeAdapter>,
        timeout_secs: u64,
        runtime_state: Arc<Mutex<ShellRuntime>>,
    ) -> Self {
        Self {
            security,
            runtime,
            timeout_secs: if timeout_secs > 0 {
                timeout_secs
            } else {
                DEFAULT_TIMEOUT_SECS
            },
            runtime_state,
        }
    }

    async fn execute_shell(&self, command: &str, approved: bool) -> anyhow::Result<ToolResult> {
        let result = self
            .execute_shell_inner(command, approved)
            .await;

        let exit_code = match &result {
            Ok(t) => {
                if t.success { 0 } else { 1 }
            }
            Err(_) => -1,
        };

        if let Ok(mut rt) = self.runtime_state.lock() {
            let (stdout, stderr) = match &result {
                Ok(t) => (t.output.as_str(), t.error.as_deref().unwrap_or("")),
                Err(_) => ("", ""),
            };
            rt.update_after_command(command, exit_code, stdout, stderr);
        }

        result
    }

    async fn execute_shell_inner(
        &self,
        command: &str,
        approved: bool,
    ) -> anyhow::Result<ToolResult> {
        // Step 1: Security policy validation
        match self.security.validate_command_execution(command, approved) {
            Ok(_) => {}
            Err(reason) => {
                return Ok(ToolResult {
                    success: false,
                    output: String::new(),
                    error: Some(reason),
                metadata: None,
                });
            }
        }

        // Step 1b: Prefer the sandbox bridge when one is registered (Android).
        // The `"sandbox"` capability must mean the sandbox everywhere — on
        // Android the bridge routes into the PRoot Alpine environment, never
        // a raw device shell. Local host execution is only used when no
        // sandbox bridge exists (pure desktop).
        if let Some(bridge) = super::bridge::sandbox_bridge() {
            if bridge.has_token() {
                let args = serde_json::json!({ "command": command });
                return match bridge.execute(args).await {
                    Ok(value) => {
                        let success = value
                            .get("success")
                            .and_then(serde_json::Value::as_bool)
                            .unwrap_or(false);
                        let stdout = value
                            .get("stdout")
                            .and_then(serde_json::Value::as_str)
                            .unwrap_or("");
                        let stderr = value
                            .get("stderr")
                            .and_then(serde_json::Value::as_str)
                            .unwrap_or("");
                        Ok(ToolResult {
                            success,
                            output: stdout.to_string(),
                            error: if stderr.is_empty() && success {
                                None
                            } else {
                                Some(stderr.to_string())
                            },
                            metadata: None,
                        })
                    }
                    Err(e) => Ok(ToolResult {
                        success: false,
                        output: String::new(),
                        error: Some(format!("Sandbox bridge execution failed: {e}")),
                        metadata: None,
                    }),
                };
            }
        }

        // Step 2: Determine cwd from ShellRuntime state
        let cwd = {
            let rt = self.runtime_state.lock().unwrap();
            rt.cwd.clone()
        };

        // Step 3: Build the shell command with current cwd
        let mut cmd = match self.runtime.build_shell_command(command, &cwd) {
            Ok(cmd) => cmd,
            Err(e) => {
                return Ok(ToolResult {
                    success: false,
                    output: String::new(),
                    error: Some(format!("Failed to build runtime command: {e}")),
                metadata: None,
                });
            }
        };

        // Step 4: Safe environment (no secrets leaked)
        cmd.env_clear();
        let passthrough = self.security.shell_env_passthrough.clone();
        for var in SAFE_ENV_VARS.iter().copied().chain(passthrough.iter().map(|s| s.as_str())) {
            if let Ok(val) = std::env::var(var) {
                cmd.env(var, val);
            }
        }
        // Step 5b: Apply runtime-tracked env vars from `export` commands
        if let Ok(rt) = self.runtime_state.lock() {
            for (key, value) in rt.env.iter() {
                cmd.env(key, value);
            }
        }

        // Step 6: Execute with timeout
        let result = tokio::time::timeout(Duration::from_secs(self.timeout_secs), cmd.output()).await;

        match result {
            Ok(Ok(output)) => {
                let mut stdout = String::from_utf8_lossy(&output.stdout).to_string();
                let mut stderr = String::from_utf8_lossy(&output.stderr).to_string();

                if stdout.len() > MAX_OUTPUT_BYTES {
                    let mut b = MAX_OUTPUT_BYTES.min(stdout.len());
                    while b > 0 && !stdout.is_char_boundary(b) {
                        b -= 1;
                    }
                    stdout.truncate(b);
                    stdout.push_str("\n... [output truncated at 1MB]");
                }
                if stderr.len() > MAX_OUTPUT_BYTES {
                    let mut b = MAX_OUTPUT_BYTES.min(stderr.len());
                    while b > 0 && !stderr.is_char_boundary(b) {
                        b -= 1;
                    }
                    stderr.truncate(b);
                    stderr.push_str("\n... [stderr truncated at 1MB]");
                }

                Ok(ToolResult {
                    success: output.status.success(),
                    output: stdout,
                    error: if stderr.is_empty() { None } else { Some(stderr) },
                metadata: None,
                })
            }
            Ok(Err(e)) => Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some(format!("Failed to execute command: {e}")),
            metadata: None,
            }),
            Err(_) => Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some(format!(
                    "Command timed out after {}s and was killed",
                    self.timeout_secs
                )),
            metadata: None,
            }),
        }
    }
}

#[async_trait]
impl CapabilityExecutor for ShellExecutor {
    async fn execute(&self, request: CapabilityRequest) -> anyhow::Result<ToolResult> {
        match request {
            CapabilityRequest::Shell(ShellRequest { command, approved }) => {
                self.execute_shell(&command, approved).await
            }
            _ => Err(anyhow::anyhow!(
                "ShellExecutor received unexpected request type"
            )),
        }
    }
}