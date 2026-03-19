use super::traits::{Tool, ToolResult};
use crate::runtime::RuntimeAdapter;
use crate::security::SecurityPolicy;
use async_trait::async_trait;
use serde_json::json;
use std::sync::Arc;
use std::time::Duration;

pub struct DroidRunTool {
    security: Arc<SecurityPolicy>,
    runtime: Arc<dyn RuntimeAdapter>,
}

impl DroidRunTool {
    pub fn new(security: Arc<SecurityPolicy>, runtime: Arc<dyn RuntimeAdapter>) -> Self {
        Self { security, runtime }
    }
}

#[async_trait]
impl Tool for DroidRunTool {
    fn name(&self) -> &str {
        "droidrun"
    }

    fn description(&self) -> &str {
        "Control a mobile phone using the droidrun CLI. Use natural language prompts to perform tasks on the device."
    }

    fn parameters_schema(&self) -> serde_json::Value {
        json!({
            "type": "object",
            "properties": {
                "prompt": {
                    "type": "string",
                    "description": "The natural language instruction for the device (e.g. 'open youtube and play a music video')"
                }
            },
            "required": ["prompt"]
        })
    }

    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let prompt = args
            .get("prompt")
            .and_then(|v| v.as_str())
            .ok_or_else(|| anyhow::anyhow!("Missing 'prompt' parameter"))?;

        if !self.security.record_action() {
            return Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some("Rate limit exceeded".into()),
            });
        }

        // Just run droidrun via the shell runner
        let command = format!("droidrun --prompt \"{}\" --no-ui", prompt.replace('"', "\\\""));
        
        let mut cmd = match self
            .runtime
            .build_shell_command(&command, &self.security.workspace_dir)
        {
            Ok(cmd) => cmd,
            Err(e) => {
                return Ok(ToolResult {
                    success: false,
                    output: String::new(),
                    error: Some(format!("Failed to build runtime command: {}", e)),
                });
            }
        };

        const DROIDRUN_TIMEOUT_SECS: u64 = 180;
        let result =
            tokio::time::timeout(Duration::from_secs(DROIDRUN_TIMEOUT_SECS), cmd.output()).await;

        match result {
            Ok(Ok(output)) => {
                let stdout = String::from_utf8_lossy(&output.stdout).to_string();
                let stderr = String::from_utf8_lossy(&output.stderr).to_string();

                Ok(ToolResult {
                    success: output.status.success(),
                    output: stdout,
                    error: if stderr.is_empty() {
                        None
                    } else {
                        Some(stderr)
                    },
                })
            }
            Ok(Err(e)) => Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some(format!("Failed to execute droidrun: {}", e)),
            }),
            Err(_) => Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some(format!("DroidRun timed out after {}s", DROIDRUN_TIMEOUT_SECS)),
            }),
        }
    }
}
