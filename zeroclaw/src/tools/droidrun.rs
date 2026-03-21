use super::traits::{Tool, ToolResult};
use crate::config::{Config, DroidRunConfig, DroidRunLlmConfig};
use crate::runtime::RuntimeAdapter;
use crate::security::SecurityPolicy;
use async_trait::async_trait;
use serde_json::{json, Value};
use std::sync::Arc;
use std::time::Duration;

const DROIDRUN_TIMEOUT_SECS: u64 = 180;

pub struct DroidRunTool {
    droidrun: DroidRunConfig,
    security: Arc<SecurityPolicy>,
    runtime: Arc<dyn RuntimeAdapter>,
}

impl DroidRunTool {
    pub fn new(
        config: Arc<Config>,
        security: Arc<SecurityPolicy>,
        runtime: Arc<dyn RuntimeAdapter>,
    ) -> Self {
        Self::with_config(config.droidrun.clone(), security, runtime)
    }

    pub fn with_config(
        droidrun: DroidRunConfig,
        security: Arc<SecurityPolicy>,
        runtime: Arc<dyn RuntimeAdapter>,
    ) -> Self {
        Self {
            droidrun,
            security,
            runtime,
        }
    }

    /// Safely escape a string for shell usage
    /// Uses single quotes which preserve literal values, escaping any internal single quotes
    fn escape_shell_arg(s: &str) -> String {
        format!("'{}'", s.replace('\'', "'\\''"))
    }

    fn should_use_api(&self) -> bool {
        self.droidrun.use_api || self.droidrun.api_key.is_some()
    }

    fn has_llm_override(config: &DroidRunLlmConfig) -> bool {
        config.provider.is_some()
            || config.model.is_some()
            || config.api_key.is_some()
            || config.base_url.is_some()
    }

    fn merge_string_field(slot: &mut Option<String>, value: Option<&Value>) {
        if let Some(text) = value
            .and_then(Value::as_str)
            .map(str::trim)
            .filter(|text| !text.is_empty())
        {
            *slot = Some(text.to_string());
        }
    }

    fn effective_llm_config(&self, args: &Value) -> DroidRunLlmConfig {
        let mut effective = self.droidrun.llm.clone();
        if let Some(overrides) = args.get("droidrun_override").and_then(Value::as_object) {
            Self::merge_string_field(&mut effective.provider, overrides.get("provider"));
            Self::merge_string_field(&mut effective.model, overrides.get("model"));
            Self::merge_string_field(&mut effective.api_key, overrides.get("api_key"));
            Self::merge_string_field(&mut effective.base_url, overrides.get("base_url"));
        }
        effective
    }

    fn build_api_payload(prompt: &str, llm: &DroidRunLlmConfig) -> Value {
        let mut payload = serde_json::Map::from_iter([
            ("prompt".to_string(), json!(prompt)),
            ("instruction".to_string(), json!(prompt)),
            ("no_ui".to_string(), json!(true)),
        ]);

        let mut config = serde_json::Map::new();
        if let Some(provider) = llm.provider.as_deref() {
            config.insert("llm_provider".to_string(), json!(provider));
        }
        if let Some(model) = llm.model.as_deref() {
            config.insert("llm_model".to_string(), json!(model));
        }
        if let Some(api_key) = llm.api_key.as_deref() {
            config.insert("llm_api_key".to_string(), json!(api_key));
        }
        if let Some(base_url) = llm.base_url.as_deref() {
            config.insert("llm_base_url".to_string(), json!(base_url));
        }
        if !config.is_empty() {
            payload.insert("config".to_string(), Value::Object(config));
        }

        Value::Object(payload)
    }
}

#[async_trait]
impl Tool for DroidRunTool {
    fn name(&self) -> &str {
        "droidrun"
    }

    fn description(&self) -> &str {
        "Control a mobile phone using the DroidRun API or CLI. Performs tasks like 'open YouTube' or 'search for pizza' on the device."
    }

    fn parameters_schema(&self) -> serde_json::Value {
        json!({
            "type": "object",
            "properties": {
                "prompt": {
                    "type": "string",
                    "description": "The natural language instruction for the device."
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

        if prompt.trim().is_empty() {
            return Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some("Prompt cannot be empty".into()),
            });
        }

        if !self.security.record_action() {
            return Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some("Rate limit exceeded".into()),
            });
        }

        let llm_override = self.effective_llm_config(&args);

        if self.should_use_api() {
            if self.droidrun.url.trim().is_empty() {
                return Ok(ToolResult {
                    success: false,
                    output: String::new(),
                    error: Some(
                        "DroidRun API mode is enabled but no server URL is configured".into(),
                    ),
                });
            }

            let client = reqwest::Client::builder()
                .timeout(Duration::from_secs(DROIDRUN_TIMEOUT_SECS))
                .build()?;

            let mut request = client
                .post(&format!(
                    "{}/execute",
                    self.droidrun.url.trim_end_matches('/')
                ))
                .json(&Self::build_api_payload(prompt, &llm_override));
            if let Some(api_key) = self.droidrun.api_key.as_deref() {
                request = request.header("Authorization", format!("Bearer {}", api_key));
            }

            let response = request.send().await;

            match response {
                Ok(resp) => {
                    let status = resp.status();
                    let body = match resp.text().await {
                        Ok(text) => text,
                        Err(e) => {
                            return Ok(ToolResult {
                                success: false,
                                output: String::new(),
                                error: Some(format!("Failed to read API response body: {}", e)),
                            });
                        }
                    };
                    if status.is_success() {
                        return Ok(ToolResult {
                            success: true,
                            output: body,
                            error: None,
                        });
                    } else {
                        return Ok(ToolResult {
                            success: false,
                            output: body,
                            error: Some(format!("DroidRun API error: {}", status)),
                        });
                    }
                }
                Err(e) => {
                    return Ok(ToolResult {
                        success: false,
                        output: String::new(),
                        error: Some(format!("Failed to connect to DroidRun API: {}", e)),
                    });
                }
            }
        }

        if Self::has_llm_override(&llm_override) {
            return Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some("DroidRun LLM overrides require DroidRun API mode".into()),
            });
        }

        // Fallback to CLI if no API key
        let escaped_prompt = Self::escape_shell_arg(prompt);
        let command = format!("droidrun --prompt {} --no-ui", escaped_prompt);
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
                error: Some(format!("Failed to execute droidrun CLI: {}", e)),
            }),
            Err(_) => Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some(format!(
                    "DroidRun CLI timed out after {}s",
                    DROIDRUN_TIMEOUT_SECS
                )),
            }),
        }
    }
}
