use async_trait::async_trait;
use serde::{Deserialize, Serialize};

/// Result of a tool execution
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ToolResult {
    pub success: bool,
    pub output: String,
    pub error: Option<String>,
    /// Structured content blocks produced by this tool execution.
    #[serde(default)]
    pub blocks: Vec<crate::content::ContentBlock>,
    /// Optional structured metadata (e.g. canvas frames, file hashes).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub metadata: Option<serde_json::Value>,
}

impl ToolResult {
    /// Backward compatibility helper to extract concatenated text content from blocks or fallback to output.
    pub fn output_text(&self) -> &str {
        if !self.output.is_empty() {
            &self.output
        } else if let Some(first_block) = self.blocks.first() {
            first_block.text_content().unwrap_or("[Rich Content Block]")
        } else {
            ""
        }
    }
}

/// Description of a tool for the LLM
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct ToolSpec {
    pub name: String,
    pub description: String,
    pub parameters: serde_json::Value,
}

/// Core tool trait — implement for any capability
#[async_trait]
pub trait Tool: Send + Sync {
    /// Tool name (used in LLM function calling)
    fn name(&self) -> &str;

    /// Human-readable description
    fn description(&self) -> &str;

    /// JSON schema for parameters
    fn parameters_schema(&self) -> serde_json::Value;

    /// Execute the tool with given arguments
    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult>;

    /// Get the full spec for LLM registration
    fn spec(&self) -> ToolSpec {
        ToolSpec {
            name: self.name().to_string(),
            description: self.description().to_string(),
            parameters: self.parameters_schema(),
        }
    }
}
