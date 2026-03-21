use super::traits::{Tool, ToolResult};
use crate::security::SecurityPolicy;
use async_trait::async_trait;
use serde_json::json;
use std::path::PathBuf;
use std::sync::Arc;

/// Tool to retrieve DroidRun configuration (API keys, etc.)
pub struct DroidRunConfigGetTool {
    security: Arc<SecurityPolicy>,
}

impl DroidRunConfigGetTool {
    pub fn new(security: Arc<SecurityPolicy>) -> Self {
        Self { security }
    }

    fn get_droidrun_config_dir(&self) -> Result<PathBuf, String> {
        if cfg!(windows) {
            let local_app_data = std::env::var("LOCALAPPDATA")
                .map_err(|_| "Could not find LOCALAPPDATA environment variable".to_string())?;
            Ok(PathBuf::from(local_app_data).join("droidrun").join("droidrun"))
        } else if cfg!(target_os = "macos") {
            let home = std::env::var("HOME")
                .map_err(|_| "Could not find HOME environment variable".to_string())?;
            Ok(PathBuf::from(home)
                .join("Library")
                .join("Application Support")
                .join("droidrun"))
        } else {
            let home = std::env::var("HOME")
                .map_err(|_| "Could not find HOME environment variable".to_string())?;
            Ok(PathBuf::from(home).join(".config").join("droidrun"))
        }
    }
}

#[async_trait]
impl Tool for DroidRunConfigGetTool {
    fn name(&self) -> &str {
        "droidrun_config_get"
    }

    fn description(&self) -> &str {
        "Retrieves DroidRun configuration (API keys, server URL, etc.)"
    }

    fn parameters_schema(&self) -> serde_json::Value {
        json!({
            "type": "object",
            "properties": {},
            "additionalProperties": false
        })
    }

    async fn execute(&self, _args: serde_json::Value) -> anyhow::Result<ToolResult> {
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
            return Ok(ToolResult {
                success: true,
                output: json!({
                "google_api_key": null,
                "api_key": null,
                "server_url": null
                }).to_string(),
                error: None,
            });
        }

        let env_file = config_dir.join(".env");
        if !env_file.exists() {
            return Ok(ToolResult {
                success: true,
                output: json!({
                    "google_api_key": null,
                    "api_key": null,
                    "server_url": null
                }).to_string(),
                error: None,
            });
        }

        let content = tokio::fs::read_to_string(&env_file).await?;
        let mut google_api_key = None;
        let mut api_key = None;
        let mut server_url = None;

        for line in content.lines() {
            if let Some(val) = line.strip_prefix("GOOGLE_API_KEY=") {
                google_api_key = Some(val.to_string());
            } else if let Some(val) = line.strip_prefix("DROIDRUN_API_KEY=") {
                api_key = Some(val.to_string());
            } else if let Some(val) = line.strip_prefix("DROIDRUN_SERVER_URL=") {
                server_url = Some(val.to_string());
            }
        }

        Ok(ToolResult {
            success: true,
            output: json!({
                "google_api_key": google_api_key,
                "api_key": api_key,
                "server_url": server_url
            }).to_string(),
            error: None,
        })
    }
}
