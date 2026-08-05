//! Modular backend adapters.
//!
//! A [`BackendAdapter`] translates a backend's declared config into concrete
//! [`ForwardRules`]. Adapters are *transport-only* by contract: they may
//! rewrite the Host/Origin headers, decide session handling, and validate
//! the upstream — they must never touch the JSON-RPC payload.
//!
//! Built-in adapters:
//!
//! - [`HostRewriteAdapter`] — the generic default. Forwards with the Host
//!   header of the upstream URL, or an explicit `rewrite_host` override.
//! - [`FusionAdapter`] — `host_rewrite` with Fusion conventions pre-applied.
//!   Autodesk Fusion 360's local MCP server only accepts requests whose Host
//!   header is exactly the server's own address (`127.0.0.1:27182`). The
//!   adapter forces Host rewriting and requires a local HTTP upstream, but
//!   contains no Fusion-specific payload logic — it is "host-locked server"
//!   support made explicit.
//! - [`PassthroughAdapter`] — preserve the client's Host header verbatim.

use anyhow::{Result, bail};
use reqwest::Url;

use crate::config::AdapterKind;

/// Concrete forward behavior for a backend, resolved from its config.
#[derive(Debug, Clone)]
pub struct ForwardRules {
    pub upstream: Url,
    /// `Some(value)` → override Host; `None` → preserve the client's Host
    /// (passthrough mode).
    pub rewrite_host: Option<String>,
    /// Add an Origin header matching the upstream URL.
    pub rewrite_origin: bool,
    /// Pass Mcp-Session-Id through in both directions.
    pub preserve_session: bool,
    pub mode: HostMode,
}

/// How the Host header is forwarded to the upstream.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HostMode {
    /// Host header is rewritten (to the upstream authority or an explicit value).
    Rewrite,
    /// The client's Host header is preserved verbatim.
    Passthrough,
}

/// The modular adapter trait.
pub trait BackendAdapter: Send + Sync {
    fn kind(&self) -> AdapterKind;
    /// Resolve config into concrete forward rules; validates backend-specific
    /// constraints (e.g. fusion requires an HTTP upstream).
    fn resolve_rules(&self, config: &crate::config::BackendConfig) -> Result<ForwardRules>;
}

/// Generic default: forward with the upstream's own Host header.
pub struct HostRewriteAdapter;

/// Fusion host-locked MCP server support (transport only).
pub struct FusionAdapter;

/// Preserve the client's Host header.
pub struct PassthroughAdapter;

pub fn resolve_adapter(kind: AdapterKind) -> Box<dyn BackendAdapter> {
    match kind {
        AdapterKind::HostRewrite => Box::new(HostRewriteAdapter),
        AdapterKind::Fusion => Box::new(FusionAdapter),
        AdapterKind::Passthrough => Box::new(PassthroughAdapter),
    }
}

fn parse_upstream(config: &crate::config::BackendConfig) -> Result<Url> {
    Url::parse(&config.upstream)
        .map_err(|e| anyhow::anyhow!("upstream `{}` is not a valid URL: {e}", config.upstream))
}

impl BackendAdapter for HostRewriteAdapter {
    fn kind(&self) -> AdapterKind {
        AdapterKind::HostRewrite
    }

    fn resolve_rules(&self, config: &crate::config::BackendConfig) -> Result<ForwardRules> {
        let upstream = parse_upstream(config)?;
        let explicit = config
            .rewrite_host
            .as_deref()
            .map(str::trim)
            .filter(|h| !h.is_empty())
            .map(str::to_string);
        let host = explicit.unwrap_or_else(|| authority(&upstream));
        Ok(ForwardRules {
            upstream,
            rewrite_host: Some(host),
            rewrite_origin: config.rewrite_origin,
            preserve_session: config.preserve_session,
            mode: HostMode::Rewrite,
        })
    }
}

impl BackendAdapter for FusionAdapter {
    fn kind(&self) -> AdapterKind {
        AdapterKind::Fusion
    }

    fn resolve_rules(&self, config: &crate::config::BackendConfig) -> Result<ForwardRules> {
        let upstream = parse_upstream(config)?;
        if upstream.scheme() != "http" {
            bail!(
                "fusion adapter requires an http:// upstream (got {})",
                upstream.scheme()
            );
        }
        // Fusion's local MCP server binds 127.0.0.1:27182 and only accepts
        // that exact Host. Default to the upstream authority unless the user
        // explicitly overrides.
        let host = config
            .rewrite_host
            .as_deref()
            .map(str::trim)
            .filter(|h| !h.is_empty())
            .map(str::to_string)
            .unwrap_or_else(|| authority(&upstream));
        Ok(ForwardRules {
            upstream,
            rewrite_host: Some(host),
            rewrite_origin: config.rewrite_origin,
            preserve_session: config.preserve_session,
            mode: HostMode::Rewrite,
        })
    }
}

impl BackendAdapter for PassthroughAdapter {
    fn kind(&self) -> AdapterKind {
        AdapterKind::Passthrough
    }

    fn resolve_rules(&self, config: &crate::config::BackendConfig) -> Result<ForwardRules> {
        let upstream = parse_upstream(config)?;
        Ok(ForwardRules {
            upstream,
            rewrite_host: None,
            rewrite_origin: config.rewrite_origin,
            preserve_session: config.preserve_session,
            mode: HostMode::Passthrough,
        })
    }
}

impl ForwardRules {
    /// Build the full upstream URL for a request path + query. The client's
    /// path replaces the upstream's base path (match_path already includes it).
    pub fn build_upstream_url(&self, path: &str, query: Option<&str>) -> Result<Url> {
        let mut url = self.upstream.clone();
        url.set_path(path);
        url.set_query(query);
        Ok(url)
    }

    /// The Host header value to send upstream, if rewrites are in effect.
    pub fn forwarded_host(&self) -> Option<String> {
        match self.mode {
            HostMode::Rewrite => self.rewrite_host.clone(),
            HostMode::Passthrough => None,
        }
    }

    /// Origin header value derived from the upstream URL.
    pub fn origin_for_upstream(&self) -> Option<String> {
        let origin = self.upstream.origin().ascii_serialization();
        if !origin.is_empty() {
            Some(origin)
        } else {
            None
        }
    }
}

/// Host:port portion of a URL.
fn authority(url: &Url) -> String {
    let host = url.host_str().unwrap_or("");
    let port = url.port_or_known_default();
    match port {
        Some(port) => {
            if host.contains(':') {
                format!("[{host}]:{port}")
            } else {
                format!("{host}:{port}")
            }
        }
        None => host.to_string(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::config::BackendConfig;

    fn config(upstream: &str) -> BackendConfig {
        BackendConfig {
            name: "test".to_string(),
            match_path: "/mcp".to_string(),
            adapter: "host_rewrite".to_string(),
            upstream: upstream.to_string(),
            rewrite_host: None,
            rewrite_origin: false,
            preserve_session: true,
        }
    }

    #[test]
    fn host_rewrite_defaults_to_upstream_authority() {
        let rules = HostRewriteAdapter.resolve_rules(&config("http://127.0.0.1:27182")).unwrap();
        assert_eq!(rules.forwarded_host().as_deref(), Some("127.0.0.1:27182"));
        assert_eq!(rules.mode, HostMode::Rewrite);
    }

    #[test]
    fn explicit_rewrite_host_wins() {
        let mut cfg = config("http://10.0.0.5:8080");
        cfg.rewrite_host = Some("127.0.0.1:27182".into());
        let rules = HostRewriteAdapter.resolve_rules(&cfg).unwrap();
        assert_eq!(rules.forwarded_host().as_deref(), Some("127.0.0.1:27182"));
    }

    #[test]
    fn fusion_forces_rewrite() {
        let rules = FusionAdapter.resolve_rules(&config("http://127.0.0.1:27182")).unwrap();
        assert_eq!(rules.mode, HostMode::Rewrite);
        assert_eq!(rules.forwarded_host().as_deref(), Some("127.0.0.1:27182"));
    }

    #[test]
    fn fusion_rejects_non_http() {
        let err = FusionAdapter
            .resolve_rules(&config("https://127.0.0.1:27182"))
            .unwrap_err();
        assert!(err.to_string().contains("http://"));
    }

    #[test]
    fn passthrough_sends_no_host_override() {
        let rules = PassthroughAdapter.resolve_rules(&config("http://127.0.0.1:27182")).unwrap();
        assert_eq!(rules.mode, HostMode::Passthrough);
        assert!(rules.forwarded_host().is_none());
    }

    #[test]
    fn upstream_url_join_preserves_path() {
        let rules = PassthroughAdapter.resolve_rules(&config("http://127.0.0.1:27182")).unwrap();
        let url = rules.build_upstream_url("/mcp", None).unwrap();
        assert_eq!(url.as_str(), "http://127.0.0.1:27182/mcp");
        let url = rules.build_upstream_url("/mcp", Some("x=1")).unwrap();
        assert_eq!(url.as_str(), "http://127.0.0.1:27182/mcp?x=1");
    }

    #[test]
    fn upstream_url_join_uses_client_path() {
        let mut cfg = config("http://127.0.0.1:27182/base");
        cfg.upstream = "http://127.0.0.1:27182/base".into();
        let rules = PassthroughAdapter.resolve_rules(&cfg).unwrap();
        let url = rules.build_upstream_url("/mcp", None).unwrap();
        assert_eq!(url.as_str(), "http://127.0.0.1:27182/mcp");
    }
}