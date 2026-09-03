//! Temporary forensic test — verifies how the Android-emitted MCP TOML
//! deserializes into the runtime schema. NOT a permanent test.

use serde::Deserialize;
use zeroclaw_config::schema::{McpConfig, McpTransport};

/// Mirrors reload_config_inner's `McpPartial` wrapper.
#[derive(Deserialize)]
struct McpPartial {
    #[serde(default)]
    mcp: McpConfig,
}

#[test]
fn android_emitted_http_toml_roundtrip() {
    // Exactly what ConfigTomlBuilder.appendMcpSection/buildMcpToml emits
    // for an HTTP server with headers (nasa-mcp style).
    let toml = r#"
[mcp]
enabled = true
deferred_loading = false

[[mcp.servers]]
name = "nasa-mcp"
enabled = true
transport = "http"
url = "https://api.example.com/mcp"

[mcp.servers.headers]
Authorization = "Bearer abc"
"#;
    let cfg: McpPartial = toml::from_str(toml).expect("parses");
    assert_eq!(cfg.mcp.servers.len(), 1);
    let s = &cfg.mcp.servers[0];
    println!("name={} transport={:?} url={:?} command={:?} args={:?}", s.name, s.transport, s.url, s.command, s.args);
    assert_eq!(s.transport, McpTransport::Http, "HTTP must remain HTTP");
}

#[test]
fn android_emitted_stdio_toml_roundtrip() {
    let toml = r#"
[mcp]
enabled = true
deferred_loading = false

[[mcp.servers]]
name = "nasa-mcp"
enabled = true
transport = "stdio"
command = "npx"
args = ["-y", "@some/package"]
"#;
    let cfg: McpPartial = toml::from_str(toml).expect("parses");
    assert_eq!(cfg.mcp.servers.len(), 1);
    let s = &cfg.mcp.servers[0];
    println!("name={} transport={:?} command={:?} args={:?}", s.name, s.transport, s.command, s.args);
    assert_eq!(s.transport, McpTransport::Stdio);
}

#[test]
fn android_emitted_http_toml_without_transport_defaults_to_stdio() {
    let toml = r#"
[mcp]
enabled = true

[[mcp.servers]]
name = "nasa-mcp"
enabled = true
url = "https://api.example.com/mcp"
"#;
    let cfg: McpPartial = toml::from_str(toml).expect("parses");
    assert_eq!(cfg.mcp.servers.len(), 1);
    let s = &cfg.mcp.servers[0];
    println!("name={} transport={:?} url={:?} command={:?}", s.name, s.transport, s.url, s.command);
    assert_eq!(s.transport, McpTransport::Stdio, "omitted transport defaults to Stdio");
}
