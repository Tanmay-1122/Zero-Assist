//! PRoot command execution wrapper for browser tools.
//!
//! This module provides functionality to execute browser automation commands
//! inside a PRoot Linux environment on Android. It supports on-demand ChromeDriver
//! lifecycle management for optimal resource usage.
//!
//! # Architecture
//!
//! ```text
//! Rust Code → PRoot Executor → proot-distro login → Command Execution
//!                                    ↓
//!                          On-Demand ChromeDriver
//!                          (only for rust_native backend)
//! ```
//!
//! # Features
//!
//! - **On-Demand ChromeDriver:** Start ChromeDriver only when rust_native backend is needed
//! - **PID Tracking:** Track ChromeDriver process for cleanup
//! - **Environment Setup:** Configure environment variables for browser tools
//! - **Error Recovery:** Handle PRoot-specific errors gracefully

use anyhow::{Context, Result};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::process::Stdio;
use tokio::process::Command;
use tracing::{debug, warn};

/// Configuration for PRoot execution environment.
///
/// This struct contains all settings needed to execute commands inside
/// a PRoot Linux distribution on Android.
#[derive(Clone, Debug, Serialize, Deserialize)]
pub struct ProotConfig {
    /// Whether PRoot execution is enabled.
    pub enabled: bool,
    
    /// PRoot distribution name (ubuntu, debian, alpine, etc.).
    pub distro: String,
    
    /// Path to proot-distro binary (typically /data/data/com.termux/files/usr/bin/proot-distro).
    pub proot_distro_bin: String,
    
    /// Environment variables to set inside PRoot.
    pub env: HashMap<String, String>,
    
    /// ChromeDriver port for rust_native backend.
    pub chromedriver_port: u16,
}

impl Default for ProotConfig {
    fn default() -> Self {
        let mut env = HashMap::new();
        env.insert("HOME".to_string(), "/root".to_string());
        env.insert("DISPLAY".to_string(), ":0".to_string());
        
        Self {
            enabled: false,
            distro: "alpine".to_string(),
            proot_distro_bin: "/data/data/com.termux/files/usr/bin/proot-distro".to_string(),
            env,
            chromedriver_port: 9515,
        }
    }
}

impl ProotConfig {
    /// Create a new PRoot configuration with custom distro.
    pub fn new(distro: impl Into<String>) -> Self {
        Self {
            distro: distro.into(),
            ..Default::default()
        }
    }
    
    /// Enable PRoot execution.
    pub fn enable(mut self) -> Self {
        self.enabled = true;
        self
    }
    
    /// Set environment variable.
    pub fn with_env(mut self, key: impl Into<String>, value: impl Into<String>) -> Self {
        self.env.insert(key.into(), value.into());
        self
    }
    
    /// Set ChromeDriver port.
    pub fn with_chromedriver_port(mut self, port: u16) -> Self {
        self.chromedriver_port = port;
        self
    }
    
    /// Detect if we're running inside a PRoot environment.
    ///
    /// This checks for common PRoot indicators:
    /// - Presence of /etc/proot marker file
    /// - TracerPid in /proc/self/status (PRoot uses ptrace)
    pub fn detect_proot() -> bool {
        // Check for PRoot marker file
        if std::path::Path::new("/etc/proot").exists() {
            return true;
        }
        
        // Check for TracerPid in /proc/self/status
        if let Ok(status) = std::fs::read_to_string("/proc/self/status") {
            if status.contains("TracerPid:") && !status.contains("TracerPid:\t0") {
                return true;
            }
        }
        
        false
    }
    
    /// Build a command that executes inside PRoot.
    ///
    /// This wraps the given command with `proot-distro login <distro> --`.
    ///
    /// # Arguments
    ///
    /// * `program` - The program to execute (e.g., "agent-browser", "lynx")
    /// * `args` - Arguments to pass to the program
    ///
    /// # Returns
    ///
    /// A `Command` configured to execute inside PRoot.
    pub fn build_command(&self, program: &str, args: &[&str]) -> Command {
        let mut cmd = Command::new(&self.proot_distro_bin);
        
        // proot-distro login <distro> -- <program> <args...>
        cmd.arg("login")
            .arg(&self.distro)
            .arg("--")
            .arg(program)
            .args(args);
        
        // Set environment variables
        for (key, value) in &self.env {
            cmd.env(key, value);
        }
        
        cmd
    }
    
    /// Execute a command inside PRoot.
    ///
    /// # Arguments
    ///
    /// * `program` - The program to execute
    /// * `args` - Arguments to pass to the program
    ///
    /// # Returns
    ///
    /// The output of the command (stdout, stderr, exit status).
    pub async fn execute_command(&self, program: &str, args: &[&str]) -> Result<std::process::Output> {
        if !self.enabled {
            // If PRoot is disabled, execute command directly
            return Command::new(program)
                .args(args)
                .stdout(Stdio::piped())
                .stderr(Stdio::piped())
                .output()
                .await
                .with_context(|| format!("Failed to execute command: {}", program));
        }
        
        debug!("Executing command in PRoot: {} {:?}", program, args);
        
        let mut cmd = self.build_command(program, args);
        cmd.stdout(Stdio::piped())
            .stderr(Stdio::piped());
        
        let output = cmd.output().await
            .with_context(|| format!("Failed to execute command in PRoot: {}", program))?;
        
        if !output.status.success() {
            debug!(
                "Command failed with exit code: {:?}, stderr: {}",
                output.status.code(),
                String::from_utf8_lossy(&output.stderr)
            );
        }
        
        Ok(output)
    }
    
    /// Start ChromeDriver on-demand.
    ///
    /// This executes the `chromedriver-start` wrapper script inside PRoot,
    /// which starts ChromeDriver in the background and saves the PID.
    ///
    /// # Returns
    ///
    /// The PID of the started ChromeDriver process.
    pub async fn start_chromedriver(&self) -> Result<u32> {
        debug!("Starting ChromeDriver on-demand (port {})", self.chromedriver_port);
        
        let output = self.execute_command("chromedriver-start", &[]).await
            .context("Failed to start ChromeDriver")?;
        
        if !output.status.success() {
            let stderr = String::from_utf8_lossy(&output.stderr);
            anyhow::bail!("ChromeDriver startup failed: {}", stderr);
        }
        
        // Read PID from /tmp/chromedriver.pid
        let pid = self.read_chromedriver_pid().await
            .context("Failed to read ChromeDriver PID after startup")?;
        
        debug!("ChromeDriver started with PID: {}", pid);
        Ok(pid)
    }
    
    /// Stop ChromeDriver.
    ///
    /// This executes the `chromedriver-stop` wrapper script inside PRoot,
    /// which terminates the ChromeDriver process and removes the PID file.
    pub async fn stop_chromedriver(&self) -> Result<()> {
        debug!("Stopping ChromeDriver");
        
        let output = self.execute_command("chromedriver-stop", &[]).await
            .context("Failed to stop ChromeDriver")?;
        
        if !output.status.success() {
            let stderr = String::from_utf8_lossy(&output.stderr);
            warn!("ChromeDriver stop failed (may not be running): {}", stderr);
        } else {
            debug!("ChromeDriver stopped successfully");
        }
        
        Ok(())
    }
    
    /// Read ChromeDriver PID from /tmp/chromedriver.pid inside PRoot.
    async fn read_chromedriver_pid(&self) -> Result<u32> {
        let output = self.execute_command("cat", &["/tmp/chromedriver.pid"]).await
            .context("Failed to read ChromeDriver PID file")?;
        
        if !output.status.success() {
            anyhow::bail!("ChromeDriver PID file not found");
        }
        
        let pid_str = String::from_utf8_lossy(&output.stdout);
        let pid = pid_str.trim().parse::<u32>()
            .context("Failed to parse ChromeDriver PID")?;
        
        Ok(pid)
    }
    
    /// Check if ChromeDriver is running.
    ///
    /// This reads the PID file and checks if the process exists.
    pub async fn is_chromedriver_running(&self) -> bool {
        if let Ok(pid) = self.read_chromedriver_pid().await {
            // Check if process exists
            let check = self.execute_command("ps", &["-p", &pid.to_string()]).await;
            if let Ok(output) = check {
                return output.status.success();
            }
        }
        false
    }
}

/// ChromeDriver lifecycle manager.
///
/// This struct manages the on-demand lifecycle of ChromeDriver for the
/// rust_native browser backend. It ensures ChromeDriver is started when
/// needed and stopped when no longer in use.
#[derive(Debug)]
pub struct ChromeDriverManager {
    config: ProotConfig,
    pid: Option<u32>,
}

impl ChromeDriverManager {
    /// Create a new ChromeDriver manager.
    pub fn new(config: ProotConfig) -> Self {
        Self {
            config,
            pid: None,
        }
    }
    
    /// Ensure ChromeDriver is running.
    ///
    /// If ChromeDriver is not running, start it. If it's already running,
    /// do nothing.
    ///
    /// # Returns
    ///
    /// The PID of the ChromeDriver process.
    pub async fn ensure_running(&mut self) -> Result<u32> {
        // Check if already running
        if let Some(pid) = self.pid {
            if self.config.is_chromedriver_running().await {
                debug!("ChromeDriver already running (PID: {})", pid);
                return Ok(pid);
            } else {
                warn!("ChromeDriver PID {} no longer valid, restarting", pid);
                self.pid = None;
            }
        }
        
        // Start ChromeDriver
        let pid = self.config.start_chromedriver().await
            .context("Failed to start ChromeDriver")?;
        
        self.pid = Some(pid);
        Ok(pid)
    }
    
    /// Stop ChromeDriver if running.
    pub async fn stop(&mut self) -> Result<()> {
        if self.pid.is_some() {
            self.config.stop_chromedriver().await?;
            self.pid = None;
        }
        Ok(())
    }
    
    /// Get ChromeDriver endpoint URL.
    pub fn endpoint_url(&self) -> String {
        format!("http://127.0.0.1:{}", self.config.chromedriver_port)
    }
}

impl Drop for ChromeDriverManager {
    fn drop(&mut self) {
        if self.pid.is_some() {
            warn!("ChromeDriverManager dropped with active ChromeDriver, attempting cleanup");
            // Note: We can't await in Drop, so this is best-effort
            // The chromedriver-stop script will handle cleanup on next run
        }
    }
}

/// Execute a command with optional PRoot wrapping.
///
/// This is a convenience function that wraps a command for PRoot execution
/// if the config is enabled, or executes directly if disabled.
///
/// # Arguments
///
/// * `config` - PRoot configuration (if enabled, wraps command)
/// * `program` - Program to execute
/// * `args` - Arguments to pass
///
/// # Returns
///
/// The command output (stdout, stderr, exit status).
pub async fn execute_with_proot(
    config: &ProotConfig,
    program: &str,
    args: &[&str],
) -> Result<std::process::Output> {
    config.execute_command(program, args).await
}

#[cfg(test)]
mod tests {
    use super::*;
    
    #[test]
    fn test_proot_config_default() {
        let config = ProotConfig::default();
        assert!(!config.enabled);
        assert_eq!(config.distro, "alpine");
        assert_eq!(config.chromedriver_port, 9515);
        assert!(config.env.contains_key("HOME"));
        assert!(config.env.contains_key("DISPLAY"));
    }
    
    #[test]
    fn test_proot_config_builder() {
        let config = ProotConfig::new("debian")
            .enable()
            .with_env("CUSTOM_VAR", "value")
            .with_chromedriver_port(9516);
        
        assert!(config.enabled);
        assert_eq!(config.distro, "debian");
        assert_eq!(config.chromedriver_port, 9516);
        assert_eq!(config.env.get("CUSTOM_VAR"), Some(&"value".to_string()));
    }
    
    #[test]
    fn test_build_command() {
        let config = ProotConfig::new("alpine").enable();
        let cmd = config.build_command("agent-browser", &["--version"]);
        
        // Command should be: proot-distro login alpine -- agent-browser --version
        let program = cmd.as_std().get_program().to_str().unwrap();
        assert!(program.contains("proot-distro"));
    }
    
    #[test]
    fn test_detect_proot() {
        // This will return false on most systems (only true inside actual PRoot)
        let is_proot = ProotConfig::detect_proot();
        assert!(!is_proot); // Expected on normal systems
    }
}
