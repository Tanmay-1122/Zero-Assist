//! Datasheet management tool — search, download, and manage device datasheets.

#![cfg(feature = "hardware")]

use crate::tool_result::{tool_err, tool_ok};
use async_trait::async_trait;
use serde_json::{Value, json};
use zeroclaw_api::tool::{Tool, ToolResult};

pub struct DatasheetTool;

#[async_trait]
impl Tool for DatasheetTool {
    fn name(&self) -> &str { "datasheet" }

    fn description(&self) -> &str {
        "Search for and retrieve technical datasheets for hardware components. \
         Provide a component name or part number to find relevant documentation."
    }

    fn parameters_schema(&self) -> Value {
        json!({
            "type": "object",
            "properties": {
                "query": {
                    "type": "string",
                    "description": "Component name or part number (e.g. 'STM32F401', 'SSD1306', 'AHT10')"
                },
                "action": {
                    "type": "string",
                    "enum": ["search", "list", "open"],
                    "description": "Action: search for datasheets, list downloaded datasheets, or open a saved one"
                },
                "filename": {
                    "type": "string",
                    "description": "Filename of a locally saved datasheet (for 'open' action)"
                }
            },
            "required": ["action"]
        })
    }

    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let action = args.get("action").and_then(|v| v.as_str()).unwrap_or("search");

        match action {
            "list" => {
                let dir = get_datasheet_dir();
                match list_datasheets(&dir) {
                    Ok(files) if files.is_empty() => {
                        tool_ok("No datasheets saved. Use action='search' to find datasheets.")
                    }
                    Ok(files) => tool_ok(format!("Saved datasheets:\n{}", files.join("\n"))),
                    Err(e) => tool_err(format!("Failed to list datasheets: {e}")),
                }
            }
            "open" => {
                let filename = match args.get("filename").and_then(|v| v.as_str()) {
                    Some(f) => f,
                    None => return tool_err("Parameter 'filename' required for action='open'"),
                };
                let path = get_datasheet_dir().join(filename);
                match std::fs::read_to_string(&path) {
                    Ok(content) => tool_ok(content),
                    Err(e) => tool_err(format!("Cannot open {filename}: {e}")),
                }
            }
            _ => {
                let query = match args.get("query").and_then(|v| v.as_str()) {
                    Some(q) => q,
                    None => return tool_err("Parameter 'query' required for action='search'"),
                };
                tool_ok(format!(
                    "Search for datasheet: '{}'\n\
                     Tip: Visit https://www.alldatasheet.com or https://datasheet.octopart.com \
                     and search for '{}' to find the official datasheet PDF.",
                    query, query
                ))
            }
        }
    }
}

fn get_datasheet_dir() -> std::path::PathBuf {
    directories::BaseDirs::new()
        .map(|d| d.home_dir().join(".zeroclaw").join("datasheets"))
        .unwrap_or_else(|| std::path::PathBuf::from(".zeroclaw/datasheets"))
}

fn list_datasheets(dir: &std::path::Path) -> anyhow::Result<Vec<String>> {
    if !dir.exists() {
        return Ok(Vec::new());
    }
    let mut files: Vec<String> = std::fs::read_dir(dir)?
        .filter_map(|e| e.ok())
        .filter(|e| e.path().is_file())
        .filter_map(|e| e.file_name().into_string().ok())
        .collect();
    files.sort();
    Ok(files)
}
