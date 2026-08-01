//! Plugin loader — scans `~/.zeroclaw/tools/` for user tool manifests.

use super::manifest::ToolManifest;
use super::subprocess::SubprocessTool;
use std::path::Path;
use zeroclaw_api::tool::Tool;

/// Scan a plugin directory for `tool.toml` manifests and build SubprocessTools.
pub fn scan_plugin_dir(dir: &Path) -> Vec<Box<dyn Tool>> {
    let mut tools: Vec<Box<dyn Tool>> = Vec::new();

    let entries = match std::fs::read_dir(dir) {
        Ok(e) => e,
        Err(_) => return tools,
    };

    for entry in entries.filter_map(|e| e.ok()) {
        let manifest_path = entry.path().join("tool.toml");
        if !manifest_path.exists() {
            continue;
        }

        let contents = match std::fs::read_to_string(&manifest_path) {
            Ok(c) => c,
            Err(e) => {
                tracing::warn!("Failed to read {:?}: {e}", manifest_path);
                continue;
            }
        };

        let manifest: ToolManifest = match toml::from_str(&contents) {
            Ok(m) => m,
            Err(e) => {
                tracing::warn!("Failed to parse {:?}: {e}", manifest_path);
                continue;
            }
        };

        let plugin_dir = entry.path();
        let binary_path = plugin_dir.join(&manifest.exec.binary);

        if !binary_path.exists() {
            tracing::warn!(
                "Plugin '{}': binary {:?} not found, skipping",
                manifest.tool.name,
                binary_path
            );
            continue;
        }

        tracing::info!("Loaded plugin tool: {}", manifest.tool.name);
        tools.push(Box::new(SubprocessTool::new(manifest, plugin_dir)));
    }

    tools
}
