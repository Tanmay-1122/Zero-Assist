use super::traits::{Tool, ToolResult};
use crate::security::SecurityPolicy;
use async_trait::async_trait;
use serde_json::json;
use std::path::PathBuf;
use std::sync::Arc;

/// Tool to update DroidRun configuration (API keys, etc.)
pub struct DroidRunConfigSetTool {
    security: Arc<SecurityPolicy>,
}

impl DroidRunConfigSetTool {
    pub fn new(security: Arc<SecurityPolicy>) -> Self {
        Self { security }
    }

    fn get_droidrun_config_dir(&self) -> Result<PathBuf, String> {
        if cfg!(windows) {
            // Match platformdirs behavior for "droidrun": AppData\Local\droidrun\droidrun
            let local_app_data = std::env::var("LOCALAPPDATA")
                .map_err(|_| "Could not find LOCALAPPDATA environment variable".to_string())?;
            Ok(PathBuf::from(local_app_data)
                .join("droidrun")
                .join("droidrun"))
        } else if cfg!(target_os = "macos") {
            // Match platformdirs: ~/Library/Application Support/droidrun
            let home = std::env::var("HOME")
                .map_err(|_| "Could not find HOME environment variable".to_string())?;
            Ok(PathBuf::from(home)
                .join("Library")
                .join("Application Support")
                .join("droidrun"))
        } else {
            // Linux/Other: ~/.config/droidrun
            let home = std::env::var("HOME")
                .map_err(|_| "Could not find HOME environment variable".to_string())?;
            Ok(PathBuf::from(home).join(".config").join("droidrun"))
        }
    }
}

#[async_trait]
impl Tool for DroidRunConfigSetTool {
    fn name(&self) -> &str {
        "droidrun_config_set"
    }

    fn description(&self) -> &str {
        "Updates DroidRun configuration (e.g., Google API Key for engine)"
    }

    fn parameters_schema(&self) -> serde_json::Value {
        json!({
            "type": "object",
            "properties": {
                "key_type": {
                    "type": "string",
                    "enum": ["google", "api_key", "server_url"],
                    "description": "The type of configuration to update ('google' for Google API key, 'api_key' for general DroidRun API key, 'server_url' for DroidRun server URL)"
                },
                "value": {
                    "type": "string",
                    "description": "The value to set"
                }
            },
            "required": ["key_type", "value"]
        })
    }

    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let key_type = args
            .get("key_type")
            .and_then(|v| v.as_str())
            .ok_or_else(|| anyhow::anyhow!("Missing 'key_type' parameter"))?;

        let value = args
            .get("value")
            .and_then(|v| v.as_str())
            .ok_or_else(|| anyhow::anyhow!("Missing 'value' parameter"))?;

        if !self.security.can_act() {
            return Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some("Action blocked: autonomy is read-only".into()),
            });
        }

        let config_dir = match self.get_droidrun_config_dir() {
            Ok(d) => d,
            Err(e) => {
                return Ok(ToolResult {
                    success: false,
                    output: String::new(),
                    error: Some(e),
                });
            }
        };

        if !config_dir.exists() {
            if let Err(e) = tokio::fs::create_dir_all(&config_dir).await {
                return Ok(ToolResult {
                    success: false,
                    output: String::new(),
                    error: Some(format!("Failed to create config directory: {e}")),
                });
            }
        }

        let env_file = config_dir.join(".env");
        let var_name = match key_type {
            "google" => "GOOGLE_API_KEY",
            "api_key" => "DROIDRUN_API_KEY",
            "server_url" => "DROIDRUN_SERVER_URL",
            _ => {
                return Ok(ToolResult {
                    success: false,
                    output: String::new(),
                    error: Some(format!("Unsupported key type: {key_type}")),
                });
            }
        };

        // Read existing content to avoid overwriting other keys
        let mut lines = Vec::new();
        if env_file.exists() {
            let content = tokio::fs::read_to_string(&env_file).await?;
            lines = content.lines().map(|s| s.to_string()).collect();
        }

        let mut updated = false;
        for line in lines.iter_mut() {
            if line.starts_with(&format!("{var_name}=")) {
                *line = format!("{var_name}={value}");
                updated = true;
                break;
            }
        }

        if !updated {
            lines.push(format!("{var_name}={value}"));
        }

        let new_content = lines.join("\n") + "\n";

        if let Err(e) = tokio::fs::write(&env_file, new_content).await {
            return Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some(format!("Failed to write env file: {e}")),
            });
        }

        Ok(ToolResult {
            success: true,
            output: format!("Successfully updated {var_name} in {}", env_file.display()),
            error: None,
        })
    }
}
