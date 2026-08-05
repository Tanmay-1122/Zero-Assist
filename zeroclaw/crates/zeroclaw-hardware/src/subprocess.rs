//! SubprocessTool — runs a user plugin binary in a subprocess.

use super::manifest::ToolManifest;
use async_trait::async_trait;
use serde_json::Value;
use std::path::PathBuf;
use tokio::process::Command;
use zeroclaw_api::tool::{Tool, ToolResult};

/// A tool that delegates execution to an external subprocess.
pub struct SubprocessTool {
    name: String,
    description: String,
    parameters_schema: Value,
    binary_path: PathBuf,
    cwd: PathBuf,
    env: std::collections::HashMap<String, String>,
}

impl SubprocessTool {
    pub fn new(manifest: ToolManifest, plugin_dir: PathBuf) -> Self {
        let binary_path = plugin_dir.join(&manifest.exec.binary);
        let cwd = manifest
            .exec
            .cwd
            .as_deref()
            .map(|c| plugin_dir.join(c))
            .unwrap_or_else(|| plugin_dir.clone());

        // Build JSON schema from parameter definitions
        let mut properties = serde_json::Map::new();
        let mut required = Vec::new();
        for param in &manifest.parameters {
            properties.insert(
                param.name.clone(),
                serde_json::json!({
                    "type": param.param_type,
                    "description": param.description,
                }),
            );
            if param.required {
                required.push(serde_json::Value::String(param.name.clone()));
            }
        }

        let parameters_schema = serde_json::json!({
            "type": "object",
            "properties": properties,
            "required": required,
        });

        Self {
            name: manifest.tool.name,
            description: manifest.tool.description,
            parameters_schema,
            binary_path,
            cwd,
            env: manifest.exec.env,
        }
    }
}

#[async_trait]
impl Tool for SubprocessTool {
    fn name(&self) -> &str {
        &self.name
    }

    fn description(&self) -> &str {
        &self.description
    }

    fn parameters_schema(&self) -> Value {
        self.parameters_schema.clone()
    }

    async fn execute(&self, params: Value) -> anyhow::Result<ToolResult> {
        let params_str = params.to_string();
        let mut cmd = Command::new(&self.binary_path);
        cmd.current_dir(&self.cwd)
            .arg(&params_str)
            .envs(&self.env);

        match cmd.output().await {
            Ok(output) if output.status.success() => {
                let stdout = String::from_utf8_lossy(&output.stdout).trim().to_string();
                Ok(ToolResult {
                    success: true,
                    output: stdout,
                    error: None,
                    blocks: Vec::new(),
                metadata: None,
                })
            }
            Ok(output) => {
                let stderr = String::from_utf8_lossy(&output.stderr).trim().to_string();
                Ok(ToolResult {
                    success: false,
                    output: String::new(),
                    error: Some(format!(
                        "Plugin exited with code {}: {stderr}",
                        output.status.code().unwrap_or(-1)
                    )),
                    blocks: Vec::new(),
                metadata: None,
                })
            }
            Err(e) => Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some(format!("Failed to spawn plugin: {e}")),
                blocks: Vec::new(),
            metadata: None,
            }),
        }
    }
}