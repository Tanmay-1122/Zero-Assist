//! Gateway TOML configuration.

use anyhow::{Context, Result, bail};
use serde::Deserialize;

/// Root of the gateway config file (e.g. `gateway.toml`).
#[derive(Debug, Clone, Deserialize)]
pub struct GatewayConfig {
    #[serde(default)]
    pub gateway: GatewaySettings,
    /// Backends in route order; matched by longest path prefix.
    #[serde(default)]
    pub backends: Vec<BackendConfig>,
}

impl Default for GatewayConfig {
    fn default() -> Self {
        Self {
            gateway: GatewaySettings::default(),
            backends: Vec::new(),
        }
    }
}

#[derive(Debug, Clone, Deserialize)]
pub struct GatewaySettings {
    /// Address the gateway binds (e.g. "0.0.0.0:8080").
    #[serde(default = "default_listen")]
    pub listen: String,
    /// tracing filter (e.g. "info", "debug").
    #[serde(default = "default_log_level")]
    pub log_level: String,
    /// If set, every request must carry `Authorization: Bearer <token>`.
    #[serde(default)]
    pub auth_token: Option<String>,
    /// Connect timeout toward upstreams, in seconds.
    #[serde(default = "default_connect_timeout_secs")]
    pub connect_timeout_secs: u64,
}

impl Default for GatewaySettings {
    fn default() -> Self {
        Self {
            listen: default_listen(),
            log_level: default_log_level(),
            auth_token: None,
            connect_timeout_secs: default_connect_timeout_secs(),
        }
    }
}

/// A single upstream backend.
///
/// ```toml
/// [[backends]]
/// name = "fusion"
/// match_path = "/mcp"
/// adapter = "fusion"
/// upstream = "http://127.0.0.1:27182"
/// ```
#[derive(Debug, Clone, Deserialize)]
pub struct BackendConfig {
    /// Unique backend name (logging / diagnostics only).
    pub name: String,
    /// Path prefix this backend handles ("/mcp" or "/" for catch-all).
    #[serde(default = "default_match_path")]
    pub match_path: String,
    /// Adapter kind: "host_rewrite" (default), "fusion", or "passthrough".
    #[serde(default = "default_adapter")]
    pub adapter: String,
    /// Upstream base URL (scheme://host[:port][/base-path]).
    pub upstream: String,
    /// Explicit Host header value. Defaults to the upstream authority
    /// (host:port) for host_rewrite/fusion, ignored for passthrough.
    #[serde(default)]
    pub rewrite_host: Option<String>,
    /// Add an Origin header matching the upstream URL.
    #[serde(default)]
    pub rewrite_origin: bool,
    /// Pass the Mcp-Session-Id header through in both directions.
    #[serde(default = "default_true")]
    pub preserve_session: bool,
}

fn default_listen() -> String {
    "0.0.0.0:8080".to_string()
}

fn default_log_level() -> String {
    "info".to_string()
}

fn default_connect_timeout_secs() -> u64 {
    10
}

fn default_match_path() -> String {
    "/".to_string()
}

fn default_adapter() -> String {
    "host_rewrite".to_string()
}

fn default_true() -> bool {
    true
}

/// Adapter kinds supported by the gateway. Each maps to a
/// [`BackendAdapter`][crate::adapter::BackendAdapter] implementation.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum AdapterKind {
    /// Forward with the Host header of the upstream URL (or an explicit
    /// `rewrite_host` override). The generic default.
    HostRewrite,
    /// `host_rewrite` with Fusion conventions pre-applied: upstream is
    /// local-only HTTP, Host is always rewritten, and only transport-level
    /// headers are touched. No Fusion-specific payload logic.
    Fusion,
    /// Preserve the client's Host header verbatim.
    Passthrough,
}

impl AdapterKind {
    pub fn parse(value: &str) -> Result<Self> {
        // Case- and separator-insensitive: "host_rewrite", "HostRewrite",
        // "hostrewrite" all resolve to HostRewrite.
        match value.trim().to_ascii_lowercase().replace('_', "").as_str() {
            "" | "hostrewrite" => Ok(Self::HostRewrite),
            "fusion" => Ok(Self::Fusion),
            "passthrough" => Ok(Self::Passthrough),
            other => bail!("unknown adapter `{other}` (expected host_rewrite, fusion, or passthrough)"),
        }
    }
}

impl GatewayConfig {
    /// Parse + validate a gateway config from TOML text.
    pub fn parse_toml(text: &str) -> Result<Self> {
        let config: Self = toml::from_str(text).context("failed to parse gateway config")?;
        config.validate()?;
        Ok(config)
    }

    /// Structural validation independent of adapters.
    pub fn validate(&self) -> Result<()> {
        if self.backends.is_empty() {
            bail!("gateway config declares no [[backends]]");
        }
        for backend in &self.backends {
            let name = backend.name.trim();
            if name.is_empty() {
                bail!("[[backends]] entry has an empty name");
            }
            if !backend.match_path.starts_with('/') {
                bail!("backend `{name}`: match_path must start with '/'");
            }
            let parsed = reqwest::Url::parse(&backend.upstream)
                .with_context(|| format!("backend `{name}`: upstream is not a valid URL"))?;
            if !matches!(parsed.scheme(), "http" | "https") {
                bail!("backend `{name}`: upstream must use http:// or https://");
            }
            if let Some(host) = backend.rewrite_host.as_deref() {
                if host.trim().is_empty() {
                    bail!("backend `{name}`: rewrite_host must not be empty");
                }
            }
            AdapterKind::parse(&backend.adapter)
                .with_context(|| format!("backend `{name}`"))?;
        }
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// anyhow 2.x Display shows only the top context; this flattens the chain.
    fn full_message(error: &anyhow::Error) -> String {
        error
            .chain()
            .map(|cause| cause.to_string())
            .collect::<Vec<_>>()
            .join(": ")
    }

    #[test]
    fn parses_minimal_config() {
        let config = GatewayConfig::parse_toml(
            r#"
[gateway]
listen = "0.0.0.0:9000"

[[backends]]
name = "fusion"
match_path = "/mcp"
adapter = "fusion"
upstream = "http://127.0.0.1:27182"
"#,
        )
        .unwrap();
        assert_eq!(config.gateway.listen, "0.0.0.0:9000");
        assert_eq!(config.backends.len(), 1);
        assert_eq!(config.backends[0].upstream, "http://127.0.0.1:27182");
        assert_eq!(config.backends[0].adapter, "fusion");
        assert!(config.backends[0].preserve_session);
    }

    #[test]
    fn parses_full_config() {
        let config = GatewayConfig::parse_toml(
            r#"
[gateway]
listen = "127.0.0.1:8080"
log_level = "debug"
auth_token = "sekrit"
connect_timeout_secs = 5

[[backends]]
name = "fusion"
adapter = "host_rewrite"
upstream = "http://127.0.0.1:27182"
rewrite_host = "127.0.0.1:27182"
rewrite_origin = true
preserve_session = true

[[backends]]
name = "generic"
match_path = "/"
adapter = "passthrough"
upstream = "http://localhost:31337"
"#,
        )
        .unwrap();
        assert_eq!(config.gateway.auth_token.as_deref(), Some("sekrit"));
        assert_eq!(config.backends.len(), 2);
        assert!(config.backends[0].rewrite_origin);
        assert!(!config.backends[0].preserve_session == false);
        assert_eq!(config.backends[1].match_path, "/");
    }

    #[test]
    fn rejects_missing_backends() {
        let error = GatewayConfig::parse_toml("[gateway]\nlisten = \"127.0.0.1:1\"\n").unwrap_err();
        assert!(full_message(&error).contains("no [[backends]]"));
    }

    #[test]
    fn rejects_bad_upstream_scheme() {
        let error = GatewayConfig::parse_toml(
            r#"
[[backends]]
name = "bad"
upstream = "ftp://127.0.0.1:21"
"#,
        )
        .unwrap_err();
        assert!(full_message(&error).contains("http:// or https://"));
    }

    #[test]
    fn rejects_unknown_adapter() {
        let error = GatewayConfig::parse_toml(
            r#"
[[backends]]
name = "bad"
adapter = "magic"
upstream = "http://127.0.0.1:1"
"#,
        )
        .unwrap_err();
        assert!(full_message(&error).contains("unknown adapter"));
    }

    #[test]
    fn rejects_empty_rewrite_host() {
        let error = GatewayConfig::parse_toml(
            r#"
[[backends]]
name = "bad"
rewrite_host = "  "
upstream = "http://127.0.0.1:1"
"#,
        )
        .unwrap_err();
        assert!(full_message(&error).contains("rewrite_host"));
    }

    #[test]
    fn adapter_kind_parsing() {
        assert_eq!(AdapterKind::parse("host_rewrite").unwrap(), AdapterKind::HostRewrite);
        assert_eq!(AdapterKind::parse("HostRewrite").unwrap(), AdapterKind::HostRewrite);
        assert_eq!(AdapterKind::parse("fusion").unwrap(), AdapterKind::Fusion);
        assert_eq!(AdapterKind::parse("passthrough").unwrap(), AdapterKind::Passthrough);
        assert!(AdapterKind::parse("nope").is_err());
    }
}
