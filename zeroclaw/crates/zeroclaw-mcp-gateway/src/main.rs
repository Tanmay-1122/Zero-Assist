//! Zero Engineering Gateway binary.
//!
//! Usage:
//! ```text
//! zeroclaw-mcp-gateway --config gateway.toml
//! ```

use std::path::PathBuf;
use std::sync::Arc;

use anyhow::{Context, Result};
use tracing::info;
use zeroclaw_mcp_gateway::config::GatewayConfig;
use zeroclaw_mcp_gateway::Gateway;

fn main() -> Result<()> {
    let mut config_path = PathBuf::from("gateway.toml");
    let args: Vec<String> = std::env::args().collect();
    let mut i = 1;
    while i < args.len() {
        match args[i].as_str() {
            "--config" | "-c" => {
                i += 1;
                config_path = PathBuf::from(
                    args.get(i)
                        .context("--config requires a file path")?,
                );
            }
            "--help" | "-h" => {
                println!("Usage: zeroclaw-mcp-gateway [--config gateway.toml]");
                return Ok(());
            }
            other => anyhow::bail!("unknown argument: {other}"),
        }
        i += 1;
    }

    let text = std::fs::read_to_string(&config_path)
        .with_context(|| format!("failed to read config {}", config_path.display()))?;
    let config = GatewayConfig::parse_toml(&text)
        .with_context(|| format!("invalid config {}", config_path.display()))?;

    let filter = tracing_subscriber::EnvFilter::try_new(&config.gateway.log_level)
        .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info"));
    tracing_subscriber::fmt()
        .with_env_filter(filter)
        .init();

    let gateway = Arc::new(Gateway::from_config(config)?);
    for backend in &gateway.backends {
        info!(
            backend = %backend.name,
            match_path = %backend.match_path,
            adapter = ?backend.rules.mode,
            "routing: {} -> {}",
            backend.match_path,
            backend.rules.upstream
        );
    }

    let rt = tokio::runtime::Runtime::new().context("failed to start Tokio runtime")?;
    rt.block_on(gateway.serve())
}