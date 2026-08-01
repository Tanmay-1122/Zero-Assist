use std::collections::HashMap;
use std::path::{Path, PathBuf};

/// Tracks persistent shell session state across commands.
///
/// Shared between ShellExecutor and ShellProvider via `Arc<Mutex<ShellRuntime>>`.
/// Each command execution updates cwd, env, exit code, jobs, and history.
pub struct ShellRuntime {
    /// Current working directory.
    pub cwd: PathBuf,
    /// Environment variables set via `export` for subsequent commands.
    pub env: HashMap<String, String>,
    /// Exit code of the last executed command.
    pub last_exit: i32,
    /// Background jobs (parsed from shell output).
    pub jobs: Vec<BackgroundJob>,
    /// Command history (most recent first, max 100).
    pub history: Vec<String>,
    /// Previous cwd (for `cd -`).
    prev_cwd: Option<PathBuf>,
}

pub struct BackgroundJob {
    pub pid: u32,
    pub command: String,
}

impl ShellRuntime {
    pub fn new(initial_cwd: PathBuf) -> Self {
        Self {
            cwd: initial_cwd,
            env: HashMap::new(),
            last_exit: 0,
            jobs: Vec::new(),
            history: Vec::new(),
            prev_cwd: None,
        }
    }

    /// Update runtime state based on a completed command execution.
    pub fn update_after_command(
        &mut self,
        command: &str,
        exit_code: i32,
        stdout: &str,
        _stderr: &str,
    ) {
        self.last_exit = exit_code;
        self.history.insert(0, command.to_string());
        if self.history.len() > 100 {
            self.history.truncate(100);
        }

        let trimmed = command.trim();

        // Detect `cd` commands
        if let Some(target) = self.parse_cd(trimmed) {
            let resolved = self.resolve_path(&target);
            if resolved.is_dir() {
                self.prev_cwd = Some(self.cwd.clone());
                self.cwd = resolved;
            }
        }

        // Detect `export` commands
        if let Some((key, value)) = self.parse_export(trimmed) {
            self.env.insert(key, value);
        }

        // Detect `unset` commands
        if let Some(key) = self.parse_unset(trimmed) {
            self.env.remove(&key);
        }

        // Detect background jobs (stdout: [1] 12345)
        if let Some(pid) = self.parse_background_pid(trimmed, stdout) {
            self.jobs.push(BackgroundJob {
                pid,
                command: trimmed.to_string(),
            });
        }
    }

    /// Get a human-readable context string for system prompt injection.
    pub fn context_string(&self) -> String {
        let mut ctx = format!("Current directory: {}", self.cwd.display());
        if self.last_exit != 0 {
            ctx.push_str(&format!("\nLast exit code: {}", self.last_exit));
        }
        if !self.jobs.is_empty() {
            let alive: Vec<&BackgroundJob> = self.jobs.iter().collect();
            ctx.push_str(&format!("\nBackground jobs: {}", alive.len()));
        }
        ctx
    }

    fn parse_cd(&self, command: &str) -> Option<String> {
        if command == "cd" {
            return Some("~".to_string());
        }
        if let Some(rest) = command.strip_prefix("cd ") {
            let target = rest.trim();
            if target.is_empty() || target.starts_with('#') {
                return None;
            }
            let target = target.split('#').next().unwrap_or(target).trim();
            let target = target.split('>').next().unwrap_or(target).trim();
            let target = target.split('|').next().unwrap_or(target).trim();
            let target = target.split(';').next().unwrap_or(target).trim();
            if target.is_empty() {
                return None;
            }
            Some(target.to_string())
        } else {
            None
        }
    }

    fn parse_export(&self, command: &str) -> Option<(String, String)> {
        let rest = command.strip_prefix("export ")?.trim().to_string();
        if rest.is_empty() {
            return None;
        }
        if let Some((key, value)) = rest.split_once('=') {
            let key = key.trim().to_string();
            let value = value.trim().to_string();
            Some((key, value))
        } else {
            Some((rest, String::new()))
        }
    }

    fn parse_unset(&self, command: &str) -> Option<String> {
        let key = command.strip_prefix("unset ")?.trim().to_string();
        if key.is_empty() { None } else { Some(key) }
    }

    fn parse_background_pid(&self, command: &str, stdout: &str) -> Option<u32> {
        if !command.trim().ends_with('&') {
            return None;
        }
        for line in stdout.lines() {
            let trimmed = line.trim();
            if let Some(rest) = trimmed.strip_prefix('[') {
                if rest.contains(']') {
                    let after_bracket = rest.split(']').nth(1).unwrap_or("").trim();
                    if let Ok(pid) = after_bracket.split_whitespace().next().unwrap_or("").parse::<u32>() {
                        return Some(pid);
                    }
                }
            }
        }
        None
    }

    fn resolve_path(&self, target: &str) -> PathBuf {
        let target = target.trim();
        if target == "~" {
            std::env::var("HOME")
                .map(PathBuf::from)
                .unwrap_or_else(|_| PathBuf::from("/root"))
        } else if target == "-" {
            self.prev_cwd.clone().unwrap_or_else(|| self.cwd.clone())
        } else if target.starts_with("~/") {
            let home = std::env::var("HOME").unwrap_or_else(|_| "/root".to_string());
            PathBuf::from(home).join(target.strip_prefix("~/").unwrap())
        } else if Path::new(target).is_absolute() {
            PathBuf::from(target)
        } else {
            self.cwd.join(target)
        }
    }
}
