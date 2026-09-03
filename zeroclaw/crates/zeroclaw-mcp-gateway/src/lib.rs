//! Zero Engineering Gateway — a generic MCP reverse proxy.
//!
//! The gateway sits in front of MCP servers and adapts only the *transport*
//! layer (Host header, Origin header, session passthrough) — never the
//! JSON-RPC payloads. This is what makes host-locked MCP servers (e.g.
//! Autodesk Fusion 360's local server, which only accepts
//! `Host: 127.0.0.1:27182`) reachable from phones, containers, or any
//! client that cannot present the server's own address in the Host header.
//!
//! ```text
//! client --(Host: LAN-IP)--> gateway --(Host: 127.0.0.1:27182)--> fusion
//! ```
//!
//! Backends are declared in a TOML config and matched by path prefix. Each
//! backend is handled by a modular [`adapter::BackendAdapter`]:
//!
//! - `host_rewrite` (default): forward with the Host header of the upstream
//!   URL (or an explicit `rewrite_host` override)
//! - `fusion`: host_rewrite with Fusion conventions pre-applied
//! - `passthrough`: preserve the client's Host header verbatim
//!
//! Everything else — method, path, headers, and the request body — is
//! streamed through unmodified, so Streamable HTTP sessions,
//! `Mcp-Session-Id` state, SSE GET streams, and DELETE session termination
//! all work exactly as if the client were talking to the server directly.

pub mod adapter;
pub mod config;

use std::sync::Arc;

use anyhow::{Context, Result};
use axum::extract::State;
use axum::http::{Request, StatusCode, header};
use axum::response::{IntoResponse, Response};
use axum::routing::any;
use axum::{Router, body::Body};
use http_body_util::BodyExt as _;
use tokio::net::TcpListener;
use tracing::{info, warn};

use crate::adapter::ForwardRules;
use crate::config::GatewayConfig;

/// Headers that must never be forwarded to the upstream (RFC 7230 hop-by-hop).
const HOP_BY_HOP: &[&str] = &[
    "connection",
    "keep-alive",
    "proxy-authenticate",
    "proxy-authorization",
    "proxy-connection",
    "te",
    "trailer",
    "transfer-encoding",
    "upgrade",
];

/// A resolved backend: its declared config plus the adapter's forward rules.
pub struct ResolvedBackend {
    pub name: String,
    pub match_path: String,
    pub rules: ForwardRules,
}

impl ResolvedBackend {
    /// Whether this backend handles the given request path (longest prefix wins).
    pub fn matches(&self, path: &str) -> bool {
        let prefix = self.match_path.trim_end_matches('/');
        if prefix.is_empty() {
            return true;
        }
        path == prefix || path.starts_with(format!("{prefix}/").as_str())
    }
}

/// The gateway: a collection of resolved backends plus a shared HTTP client.
pub struct Gateway {
    pub listen: String,
    pub auth_token: Option<String>,
    pub backends: Vec<ResolvedBackend>,
    client: reqwest::Client,
}

impl Gateway {
    /// Build a gateway from parsed config. Validates backends and resolves
    /// each adapter's forward rules up front so misconfiguration fails fast.
    pub fn from_config(config: GatewayConfig) -> Result<Self> {
        if config.backends.is_empty() {
            anyhow::bail!("gateway config declares no backends");
        }
        let mut seen = std::collections::HashSet::new();
        let mut backends = Vec::with_capacity(config.backends.len());
        for backend in &config.backends {
            if !seen.insert(backend.name.clone()) {
                anyhow::bail!("duplicate backend name: {}", backend.name);
            }
            let rules = crate::adapter::resolve_adapter(crate::config::AdapterKind::parse(&backend.adapter)?)
                .resolve_rules(backend)
                .with_context(|| format!("backend `{}`", backend.name))?;
            backends.push(ResolvedBackend {
                name: backend.name.clone(),
                match_path: backend.match_path.clone(),
                rules,
            });
        }
        backends.sort_by_key(|b| std::cmp::Reverse(b.match_path.len()));

        let client = reqwest::Client::builder()
            .connect_timeout(std::time::Duration::from_secs(config.gateway.connect_timeout_secs))
            .build()
            .context("failed to build gateway HTTP client")?;

        Ok(Self {
            listen: config.gateway.listen.clone(),
            auth_token: config.gateway.auth_token.clone(),
            backends,
            client,
        })
    }

    /// Longest-prefix route lookup.
    fn route(&self, path: &str) -> Option<&ResolvedBackend> {
        self.backends
            .iter()
            .find(|backend| backend.matches(path))
    }

    /// Build the router and serve on a pre-bound listener (testable).
    pub async fn serve_on(self: Arc<Self>, listener: TcpListener) -> Result<()> {
        let app = Router::new().fallback(any(proxy_handler)).with_state(self);
        let addr = listener.local_addr().context("listener has no local address")?;
        info!("Zero Engineering Gateway listening on http://{addr}");
        axum::serve(listener, app)
            .with_graceful_shutdown(shutdown_signal())
            .await
            .context("gateway server error")?;
        Ok(())
    }

    /// Bind `listen` and serve until shutdown.
    pub async fn serve(self: Arc<Self>) -> Result<()> {
        let listener = TcpListener::bind(&self.listen)
            .await
            .with_context(|| format!("cannot bind {}", self.listen))?;
        self.serve_on(listener).await
    }
}

/// The catch-all proxy handler. Methods, paths, query strings, and bodies
/// are streamed through untouched; only transport headers are adapted.
async fn proxy_handler(
    State(gateway): State<Arc<Gateway>>,
    request: Request<Body>,
) -> Response {
    let method = request.method().clone();
    let path = request.uri().path().to_string();
    let query = request.uri().query().map(str::to_string);
    let started = std::time::Instant::now();

    // 1. Route.
    let Some(backend) = gateway.route(&path) else {
        return (
            StatusCode::NOT_FOUND,
            json_error("no backend matches this path"),
        )
            .into_response();
    };

    // 2. Optional gateway-level bearer auth.
    if let Some(token) = &gateway.auth_token {
        let supplied = request
            .headers()
            .get(header::AUTHORIZATION)
            .and_then(|v| v.to_str().ok())
            .and_then(|v| v.strip_prefix("Bearer "))
            .map(str::trim);
        if supplied != Some(token.as_str()) {
            warn!(
                backend = %backend.name,
                method = %method,
                path = %path,
                "gateway auth rejected request"
            );
            return (
                StatusCode::UNAUTHORIZED,
                json_error("unauthorized - provide a valid Authorization: Bearer token"),
            )
                .into_response();
        }
    }

    // 3. Adapt + forward.
    let rules = &backend.rules;
    let upstream = match rules.build_upstream_url(&path, query.as_deref()) {
        Ok(url) => url,
        Err(error) => {
            warn!(backend = %backend.name, %error, "failed to build upstream URL");
            return (StatusCode::BAD_GATEWAY, json_error("invalid upstream URL")).into_response();
        }
    };

    let mut upstream_req = gateway
        .client
        .request(method.clone(), upstream.clone());

    // Copy through every header except hop-by-hop ones and those the rules
    // (or reqwest) manage: Host, Origin, and session handling. In
    // passthrough mode the client's Host is preserved verbatim.
    let passthrough_host = rules.mode == crate::adapter::HostMode::Passthrough;
    for (name, value) in request.headers() {
        let lower = name.as_str().to_ascii_lowercase();
        if HOP_BY_HOP.contains(&lower.as_str()) {
            continue;
        }
        if lower == "host" && !passthrough_host {
            continue;
        }
        if lower == "origin" || lower == "content-length" {
            continue;
        }
        if lower == "mcp-session-id" && !rules.preserve_session {
            continue;
        }
        upstream_req = upstream_req.header(name, value);
    }

    if let Some(host) = rules.forwarded_host() {
        upstream_req = upstream_req.header(header::HOST, host);
    }
    if rules.rewrite_origin {
        if let Some(origin) = rules.origin_for_upstream() {
            upstream_req = upstream_req.header(header::ORIGIN, origin);
        }
    }

    let body_bytes = request
        .into_body()
        .collect()
        .await
        .map(|collected| collected.to_bytes())
        .unwrap_or_default();
    upstream_req = upstream_req.body(body_bytes.to_vec());

    let upstream_resp = match upstream_req.send().await {
        Ok(resp) => resp,
        Err(error) => {
            warn!(backend = %backend.name, %error, "upstream request failed");
            return (
                StatusCode::BAD_GATEWAY,
                json_error(&format!("upstream request failed: {error}")),
            )
                .into_response();
        }
    };

    // 4. Stream the upstream response back, transport-adapted only.
    let status = upstream_resp.status();
    let mut builder = Response::builder().status(status);
    for (name, value) in upstream_resp.headers() {
        let lower = name.as_str().to_ascii_lowercase();
        if HOP_BY_HOP.contains(&lower.as_str()) {
            continue;
        }
        if lower == "content-length" {
            continue;
        }
        if lower == "mcp-session-id" && !rules.preserve_session {
            continue;
        }
        builder = builder.header(name, value);
    }

    let bytes = upstream_resp.bytes_stream();
    let body = Body::from_stream(bytes);

    let elapsed = started.elapsed();
    info!(
        backend = %backend.name,
        method = %method.as_str(),
        path = %path,
        status = %status,
        upstream = %upstream,
        rtt_ms = elapsed.as_millis() as u64,
        "gateway proxied request"
    );

    builder
        .body(body)
        .unwrap_or_else(|_| (StatusCode::INTERNAL_SERVER_ERROR, json_error("invalid response")).into_response())
}

fn json_error(message: &str) -> axum::Json<serde_json::Value> {
    axum::Json(serde_json::json!({ "error": message }))
}

async fn shutdown_signal() {
    let ctrl_c = async {
        tokio::signal::ctrl_c()
            .await
            .expect("failed to install Ctrl+C handler");
    };
    #[cfg(unix)]
    let terminate = async {
        tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())
            .expect("failed to install SIGTERM handler")
            .recv()
            .await;
    };
    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();
    tokio::select! {
        _ = ctrl_c => {},
        _ = terminate => {},
    }
    info!("shutdown signal received; draining");
}
