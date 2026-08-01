//! MCP transport abstraction — supports stdio, SSE, and HTTP transports.

use std::borrow::Cow;

use anyhow::{Context, Result, anyhow, bail};
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};
use tokio::process::{Child, Command};
use tokio::sync::{Mutex, Notify, oneshot};
use tokio::time::{Duration, timeout};
use tokio_stream::StreamExt;

use crate::mcp_protocol::{
    INTERNAL_ERROR, JSONRPC_VERSION, MCP_PROTOCOL_VERSION, JsonRpcError, JsonRpcRequest,
    JsonRpcResponse,
};
use zeroclaw_config::schema::{McpServerConfig, McpTransport};

/// Maximum bytes for a single JSON-RPC response.
const MAX_LINE_BYTES: usize = 4 * 1024 * 1024; // 4 MB

/// Timeout for init/list operations. Generous to accommodate slow remote
/// streamable-HTTP servers on first connection.
const RECV_TIMEOUT_SECS: u64 = 90;

/// Streamable HTTP Accept header required by MCP HTTP transport.
const MCP_STREAMABLE_ACCEPT: &str = "application/json, text/event-stream";

/// Default media type for MCP JSON-RPC request bodies.
const MCP_JSON_CONTENT_TYPE: &str = "application/json";
/// Streamable HTTP session header used to preserve MCP server state.
const MCP_SESSION_ID_HEADER: &str = "Mcp-Session-Id";

/// Streamable HTTP header declaring the negotiated protocol version.
/// Required on all requests after initialization (MCP spec 2025-06-18+).
const MCP_PROTOCOL_VERSION_HEADER: &str = "MCP-Protocol-Version";

// ── Transport Trait ──────────────────────────────────────────────────────

/// Abstract transport for MCP communication.
#[async_trait::async_trait]
pub trait McpTransportConn: Send + Sync {
    /// Send a JSON-RPC request and receive the response.
    async fn send_and_recv(&mut self, request: &JsonRpcRequest) -> Result<JsonRpcResponse>;

    /// Close the connection.
    async fn close(&mut self) -> Result<()>;
}

// ── Stdio Transport ──────────────────────────────────────────────────────

/// Stdio-based transport (spawn local process).
pub struct StdioTransport {
    _child: Child,
    stdin: tokio::process::ChildStdin,
    stdout_lines: tokio::io::Lines<BufReader<tokio::process::ChildStdout>>,
}

impl StdioTransport {
    pub fn new(config: &McpServerConfig) -> Result<Self> {
        let mut child = Command::new(&config.command)
            .args(&config.args)
            .envs(&config.env)
            .stdin(std::process::Stdio::piped())
            .stdout(std::process::Stdio::piped())
            .stderr(std::process::Stdio::inherit())
            .kill_on_drop(true)
            .spawn()
            .with_context(|| format!("failed to spawn MCP server `{}`", config.name))?;

        let stdin = child
            .stdin
            .take()
            .ok_or_else(|| anyhow!("no stdin on MCP server `{}`", config.name))?;
        let stdout = child
            .stdout
            .take()
            .ok_or_else(|| anyhow!("no stdout on MCP server `{}`", config.name))?;
        let stdout_lines = BufReader::new(stdout).lines();

        Ok(Self {
            _child: child,
            stdin,
            stdout_lines,
        })
    }

    async fn send_raw(&mut self, line: &str) -> Result<()> {
        self.stdin
            .write_all(line.as_bytes())
            .await
            .context("failed to write to MCP server stdin")?;
        self.stdin
            .write_all(b"\n")
            .await
            .context("failed to write newline to MCP server stdin")?;
        self.stdin.flush().await.context("failed to flush stdin")?;
        Ok(())
    }

    async fn recv_raw(&mut self) -> Result<String> {
        let line = self
            .stdout_lines
            .next_line()
            .await?
            .ok_or_else(|| anyhow!("MCP server closed stdout"))?;
        if line.len() > MAX_LINE_BYTES {
            bail!("MCP response too large: {} bytes", line.len());
        }
        Ok(line)
    }
}

#[async_trait::async_trait]
impl McpTransportConn for StdioTransport {
    async fn send_and_recv(&mut self, request: &JsonRpcRequest) -> Result<JsonRpcResponse> {
        let line = serde_json::to_string(request)?;
        tracing::trace!("MCP stdio >>> {}", line);
        self.send_raw(&line).await?;
        if request.id.is_none() {
            return Ok(JsonRpcResponse {
                jsonrpc: crate::mcp_protocol::JSONRPC_VERSION.to_string(),
                id: None,
                result: None,
                error: None,
            });
        }
        let deadline = std::time::Instant::now() + Duration::from_secs(RECV_TIMEOUT_SECS);
        loop {
            let remaining = deadline.saturating_duration_since(std::time::Instant::now());
            if remaining.is_zero() {
                tracing::error!("MCP stdio timeout waiting for response");
                bail!("timeout waiting for MCP response");
            }
            let resp_line = timeout(remaining, self.recv_raw())
                .await
                .map_err(|_| {
                    tracing::error!("MCP stdio timeout waiting for response");
                    anyhow::anyhow!("timeout waiting for MCP response")
                })?
                .map_err(|e| {
                    tracing::error!("MCP stdio read error: {e}");
                    e
                })?;
            let resp: JsonRpcResponse = serde_json::from_str(&resp_line)
                .with_context(|| format!("invalid JSON-RPC response: {}", resp_line))?;
            if resp.id.is_none() {
                tracing::debug!("MCP stdio: skipping server notification while waiting for response");
                continue;
            }
            tracing::trace!("MCP stdio <<< {}", serde_json::to_string(&resp).unwrap_or_default());
            return Ok(resp);
        }
    }

    async fn close(&mut self) -> Result<()> {
        let _ = self.stdin.shutdown().await;
        Ok(())
    }
}

// ── LocalhostStdio Transport ─────────────────────────────────────────────

/// Spawns a local process (stdio MCP server) and bridges JSON-RPC over a
/// localhost HTTP shim. The shim listens on `localhost:<port>`, accepts
/// JSON-RPC via POST, and relays each request/response over stdin/stdout.
/// This avoids the need for `npx`/`node` directly on Android — the shim
/// runs as an Android `Service` that can manage the child process lifecycle.
pub struct LocalhostStdioTransport {
    url: String,
    client: reqwest::Client,
}

impl LocalhostStdioTransport {
    pub fn new(config: &McpServerConfig) -> Result<Self> {
        let url = config
            .url
            .as_ref()
            .filter(|u| !u.is_empty())
            .ok_or_else(|| anyhow!("url (localhost:port) required for localhost_stdio transport"))?
            .clone();

        let client = reqwest::Client::builder()
            .timeout(Duration::from_secs(120))
            .build()
            .context("failed to build HTTP client for localhost_stdio transport")?;

        Ok(Self { url, client })
    }
}

#[async_trait::async_trait]
impl McpTransportConn for LocalhostStdioTransport {
    async fn send_and_recv(&mut self, request: &JsonRpcRequest) -> Result<JsonRpcResponse> {
        let body = serde_json::to_string(request)?;
        tracing::debug!("localhost_stdio >>> {} {}", self.url, body);
        let resp = self
            .client
            .post(&self.url)
            .header("Content-Type", MCP_JSON_CONTENT_TYPE)
            .body(body)
            .send()
            .await
            .context("HTTP request to localhost_stdio MCP shim failed")?;

        let status = resp.status();
        if !status.is_success() {
            tracing::error!(url = %self.url, http_status = %status, "localhost_stdio MCP shim error");
            bail!("localhost_stdio MCP shim returned HTTP {}", status);
        }

        if request.id.is_none() {
            return Ok(JsonRpcResponse {
                jsonrpc: crate::mcp_protocol::JSONRPC_VERSION.to_string(),
                id: None,
                result: None,
                error: None,
            });
        }

        let resp_text = resp
            .text()
            .await
            .context("failed to read response from localhost_stdio MCP shim")?;
        tracing::debug!("localhost_stdio <<< {}", &resp_text[..resp_text.len().min(500)]);

        parse_jsonrpc_response_text(&resp_text)
    }

    async fn close(&mut self) -> Result<()> {
        Ok(())
    }
}

// ── HTTP Transport ───────────────────────────────────────────────────────

/// Streamable-HTTP transport (POST + optional background GET SSE stream).
///
/// Handles every response mode of the MCP streamable HTTP spec:
///   - 200 + `application/json` body
///   - 200 + `text/event-stream` on the POST connection
///   - 202 Accepted / empty body, with the JSON-RPC response delivered over
///     the background GET SSE stream
///
/// The GET SSE stream is opened lazily and only when the server appears to
/// need it; servers that answer every POST directly never see a GET.
pub struct HttpTransport {
    url: String,
    server_name: String,
    client: reqwest::Client,
    headers: std::collections::HashMap<String, String>,
    session_id: Option<String>,
    /// Pending JSON-RPC requests awaiting delivery over the GET SSE stream.
    shared: std::sync::Arc<Mutex<HttpStreamShared>>,
    shutdown_tx: Option<oneshot::Sender<()>>,
    reader_task: Option<tokio::task::JoinHandle<()>>,
    /// GET stream state; `Unsupported` disables all future GET attempts.
    stream_state: HttpStreamState,
    /// Rate-limits GET (re)connect attempts so a rejecting server isn't hammered.
    last_stream_attempt: Option<std::time::Instant>,
}

#[derive(Copy, Clone, Debug, Eq, PartialEq)]
enum HttpStreamState {
    Idle,
    Active,
    Unsupported,
}

#[derive(Default)]
struct HttpStreamShared {
    pending: std::collections::HashMap<u64, oneshot::Sender<JsonRpcResponse>>,
}

impl HttpTransport {
    pub fn new(config: &McpServerConfig) -> Result<Self> {
        let url = config
            .url
            .as_ref()
            .ok_or_else(|| anyhow!("URL required for HTTP transport"))?
            .clone();

        // No client-level timeout: the long-lived GET SSE stream must never be
        // cut off. Bounded timeouts are applied per-POST instead.
        let client = reqwest::Client::builder()
            .build()
            .context("failed to build HTTP client")?;

        Ok(Self {
            url: url.clone(),
            server_name: config.name.clone(),
            client,
            headers: config.headers.clone(),
            session_id: None,
            shared: std::sync::Arc::new(Mutex::new(HttpStreamShared::default())),
            shutdown_tx: None,
            reader_task: None,
            stream_state: HttpStreamState::Idle,
            last_stream_attempt: None,
        })
    }

    fn apply_session_header(&self, req: reqwest::RequestBuilder) -> reqwest::RequestBuilder {
        if let Some(session_id) = self.session_id.as_deref() {
            req.header(MCP_SESSION_ID_HEADER, session_id)
        } else {
            req
        }
    }

    fn update_session_id_from_headers(&mut self, headers: &reqwest::header::HeaderMap) {
        if let Some(session_id) = headers
            .get(MCP_SESSION_ID_HEADER)
            .and_then(|v| v.to_str().ok())
            .map(str::trim)
            .filter(|v| !v.is_empty())
        {
            self.session_id = Some(session_id.to_string());
        }
    }

    /// POST a JSON-RPC message. Notifications (id = null) must NOT have their
    /// response body read — some servers keep it open indefinitely and reading
    /// it would stall the handshake.
    async fn post(&self, body: &str) -> Result<reqwest::Response> {
        let has_accept = self
            .headers
            .keys()
            .any(|k| k.eq_ignore_ascii_case("Accept"));
        let has_content_type = self
            .headers
            .keys()
            .any(|k| k.eq_ignore_ascii_case("Content-Type"));

        let mut req = self
            .client
            .post(&self.url)
            .timeout(Duration::from_secs(120))
            .body(body.to_string());
        if !has_content_type {
            req = req.header("Content-Type", MCP_JSON_CONTENT_TYPE);
        }
        for (key, value) in &self.headers {
            req = req.header(key, value);
        }
        req = self.apply_session_header(req);
        req = req.header(MCP_PROTOCOL_VERSION_HEADER, MCP_PROTOCOL_VERSION);
        if !has_accept {
            req = req.header("Accept", MCP_STREAMABLE_ACCEPT);
        }

        req.send()
            .await
            .with_context(|| format!("HTTP request to MCP server `{}` failed", self.url))
    }

    /// Open the background GET SSE stream if it is not already running and the
    /// server is known to support it. Best-effort: never fatal.
    async fn ensure_stream(&mut self) {
        if let Some(task) = &self.reader_task
            && !task.is_finished()
        {
            return;
        }
        if self.stream_state == HttpStreamState::Unsupported {
            return;
        }
        if let Some(last) = self.last_stream_attempt
            && last.elapsed() < Duration::from_secs(5)
        {
            return;
        }
        self.last_stream_attempt = Some(std::time::Instant::now());

        let mut req = self
            .client
            .get(&self.url)
            .header("Accept", MCP_STREAMABLE_ACCEPT)
            .header("Cache-Control", "no-cache");
        for (key, value) in &self.headers {
            req = req.header(key, value);
        }
        req = self.apply_session_header(req);

        let resp = match req.send().await {
            Ok(r) => r,
            Err(e) => {
                tracing::debug!(url = %self.url, error = %e, "MCP HTTP: GET SSE stream unavailable (will retry)");
                return;
            }
        };

        let status = resp.status();
        if status == reqwest::StatusCode::NOT_FOUND
            || status == reqwest::StatusCode::METHOD_NOT_ALLOWED
            || status == reqwest::StatusCode::NOT_ACCEPTABLE
            || status == reqwest::StatusCode::NOT_IMPLEMENTED
        {
            self.stream_state = HttpStreamState::Unsupported;
            tracing::debug!(url = %self.url, http_status = %status, "MCP HTTP: server does not support GET SSE stream");
            return;
        }
        if !status.is_success() {
            tracing::debug!(url = %self.url, http_status = %status, "MCP HTTP: GET SSE stream rejected (will retry)");
            return;
        }

        let is_sse = resp
            .headers()
            .get(reqwest::header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .is_some_and(|v| v.to_ascii_lowercase().contains("text/event-stream"));
        if !is_sse {
            self.stream_state = HttpStreamState::Unsupported;
            tracing::debug!(url = %self.url, "MCP HTTP: GET response is not an event stream");
            return;
        }

        // The server may issue/refresh the session id on the GET response.
        self.update_session_id_from_headers(resp.headers());

        let (shutdown_tx, mut shutdown_rx) = oneshot::channel::<()>();
        self.shutdown_tx = Some(shutdown_tx);

        let shared = self.shared.clone();
        let url = self.url.clone();
        let server_name = self.server_name.clone();

        self.reader_task = Some(tokio::spawn(async move {
            let stream = resp
                .bytes_stream()
                .map(|item| item.map_err(std::io::Error::other));
            let reader = tokio_util::io::StreamReader::new(stream);
            let mut lines = BufReader::new(reader).lines();

            let mut cur_data: Vec<String> = Vec::new();

            loop {
                tokio::select! {
                    _ = &mut shutdown_rx => {
                        break;
                    }
                    line = lines.next_line() => {
                        let Ok(line_opt) = line else { break; };
                        let Some(mut line) = line_opt else { break; };
                        if line.ends_with('\r') {
                            line.pop();
                        }
                        if line.is_empty() {
                            if cur_data.is_empty() {
                                continue;
                            }
                            let data = cur_data.join("\n");
                            cur_data.clear();
                            dispatch_http_sse_message(&server_name, &url, &shared, &data).await;
                            continue;
                        }
                        if line.starts_with(':') {
                            continue;
                        }
                        if let Some(rest) = line.strip_prefix("data:") {
                            let rest = rest.strip_prefix(' ').unwrap_or(rest);
                            cur_data.push(rest.to_string());
                        }
                    }
                }
            }

            // Stream ended: fail every request still waiting on it.
            let pending = {
                let mut guard = shared.lock().await;
                std::mem::take(&mut guard.pending)
            };
            for (_, tx) in pending {
                let _ = tx.send(JsonRpcResponse {
                    jsonrpc: JSONRPC_VERSION.to_string(),
                    id: None,
                    result: None,
                    error: Some(JsonRpcError {
                        code: INTERNAL_ERROR,
                        message: "MCP HTTP SSE stream closed".to_string(),
                        data: None,
                    }),
                });
            }
        }));

        self.stream_state = HttpStreamState::Active;
        tracing::debug!(url = %self.url, "MCP HTTP: GET SSE stream opened");
    }
}

/// Dispatch one SSE `data:` payload from the GET stream to its waiting request.
async fn dispatch_http_sse_message(
    server_name: &str,
    url: &str,
    shared: &std::sync::Arc<Mutex<HttpStreamShared>>,
    data: &str,
) {
    let trimmed = data.trim();
    if trimmed.is_empty() {
        return;
    }
    let Ok(value) = serde_json::from_str::<serde_json::Value>(trimmed) else {
        return;
    };
    let Ok(resp) = serde_json::from_value::<JsonRpcResponse>(value) else {
        return;
    };
    // Skip server notifications/requests — only actionable responses carry a
    // result or error payload.
    if resp.result.is_none() && resp.error.is_none() {
        return;
    }
    let Some(id_val) = resp.id.clone() else {
        return;
    };
    let Some(id) = id_val.as_u64() else {
        return;
    };
    let tx = {
        let mut guard = shared.lock().await;
        guard.pending.remove(&id)
    };
    if let Some(tx) = tx {
        let _ = tx.send(resp);
    } else {
        tracing::debug!(
            server = server_name,
            url = url,
            id,
            "MCP HTTP SSE: response for unknown id"
        );
    }
}

#[async_trait::async_trait]
impl McpTransportConn for HttpTransport {
    async fn send_and_recv(&mut self, request: &JsonRpcRequest) -> Result<JsonRpcResponse> {
        let body = serde_json::to_string(request)?;
        let request_id: Option<u64> = request.id.as_ref().and_then(|v| v.as_u64());

        // Notifications are fire-and-forget: no response is expected, and some
        // servers keep the response stream open indefinitely. Send best-effort
        // and return immediately without reading the body.
        if request_id.is_none() {
            if let Ok(resp) = self.post(&body).await {
                self.update_session_id_from_headers(resp.headers());
            }
            return Ok(JsonRpcResponse {
                jsonrpc: JSONRPC_VERSION.to_string(),
                id: None,
                result: None,
                error: None,
            });
        }

        let id = request_id.expect("guarded above");
        let budget = Duration::from_secs(RECV_TIMEOUT_SECS);
        let deadline = std::time::Instant::now() + budget;

        // Register the pending slot BEFORE the POST so a response delivered
        // over the GET SSE stream is never missed.
        let (tx, mut rx) = oneshot::channel();
        {
            let mut guard = self.shared.lock().await;
            guard.pending.insert(id, tx);
        }

        // Open the GET SSE stream before POSTing: some servers hold the POST
        // open and only flush the response once the client's stream connects.
        self.ensure_stream().await;

        let post_outcome = match timeout(
            deadline.saturating_duration_since(std::time::Instant::now()),
            self.post(&body),
        )
        .await
        {
            Ok(Ok(resp)) => resp,
            Ok(Err(e)) => {
                self.shared.lock().await.pending.remove(&id);
                return Err(e);
            }
            Err(_) => {
                self.shared.lock().await.pending.remove(&id);
                bail!(
                    "MCP server `{}` timed out after {}s waiting for HTTP response",
                    self.server_name,
                    RECV_TIMEOUT_SECS
                );
            }
        };

        let status = post_outcome.status();
        if !status.is_success() {
            tracing::error!(url = %self.url, http_status = %status, "MCP HTTP transport error");
            self.shared.lock().await.pending.remove(&id);
            bail!("MCP server `{}` returned HTTP {}", self.url, status);
        }
        self.update_session_id_from_headers(post_outcome.headers());

        let is_sse = post_outcome
            .headers()
            .get(reqwest::header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .is_some_and(|v| v.to_ascii_lowercase().contains("text/event-stream"));

        if is_sse {
            // The response may arrive on this POST's SSE stream or over the
            // background GET stream — take whichever delivers first.
            let sse_fut = read_first_jsonrpc_from_sse_response(post_outcome, Some(id));
            tokio::select! {
                r = sse_fut => {
                    match r {
                        Ok(Some(resp)) => {
                            self.shared.lock().await.pending.remove(&id);
                            return Ok(resp);
                        }
                        Ok(None) => {
                            tracing::debug!(server = %self.server_name, id, "MCP HTTP: POST SSE stream ended without a matching response; waiting on GET stream");
                        }
                        Err(e) => {
                            tracing::warn!(server = %self.server_name, id, error = %e, "MCP HTTP: error reading POST SSE stream; waiting on GET stream");
                        }
                    }
                }
                r = &mut rx => {
                    match r {
                        Ok(resp) => {
                            self.shared.lock().await.pending.remove(&id);
                            return Ok(resp);
                        }
                        Err(_) => { /* channel closed; fall through to final wait */ }
                    }
                }
            }
        } else {
            let resp_text = post_outcome
                .text()
                .await
                .context("failed to read HTTP response")?;
            if !resp_text.trim().is_empty() {
                if let Ok(resp) = parse_jsonrpc_response_text(&resp_text) {
                    self.shared.lock().await.pending.remove(&id);
                    return Ok(resp);
                }
            }
            tracing::debug!(
                server = %self.server_name,
                id,
                bytes = resp_text.len(),
                "MCP HTTP: POST returned no parseable JSON-RPC response; waiting on GET stream"
            );
        }

        // Final fallback: wait for the GET SSE stream to deliver the response.
        let remaining = deadline.saturating_duration_since(std::time::Instant::now());
        match timeout(remaining, &mut rx).await {
            Ok(Ok(resp)) => {
                self.shared.lock().await.pending.remove(&id);
                Ok(resp)
            }
            Ok(Err(_)) => {
                self.shared.lock().await.pending.remove(&id);
                bail!(
                    "MCP server `{}` closed its stream before responding to `{}`",
                    self.server_name,
                    request.method
                );
            }
            Err(_) => {
                self.shared.lock().await.pending.remove(&id);
                bail!(
                    "MCP server `{}` timed out after {}s waiting for `{}` response",
                    self.server_name,
                    RECV_TIMEOUT_SECS,
                    request.method
                );
            }
        }
    }

    async fn close(&mut self) -> Result<()> {
        if let Some(tx) = self.shutdown_tx.take() {
            let _ = tx.send(());
        }
        if let Some(task) = self.reader_task.take() {
            task.abort();
        }
        let pending = {
            let mut guard = self.shared.lock().await;
            std::mem::take(&mut guard.pending)
        };
        for (_, tx) in pending {
            let _ = tx.send(JsonRpcResponse {
                jsonrpc: JSONRPC_VERSION.to_string(),
                id: None,
                result: None,
                error: Some(JsonRpcError {
                    code: INTERNAL_ERROR,
                    message: "MCP transport closed".to_string(),
                    data: None,
                }),
            });
        }
        Ok(())
    }
}

// ── SSE Transport ─────────────────────────────────────────────────────────

/// SSE-based transport (HTTP POST for requests, SSE for responses).
#[derive(Copy, Clone, Debug, Eq, PartialEq)]
enum SseStreamState {
    Unknown,
    Connected,
    Unsupported,
}

pub struct SseTransport {
    sse_url: String,
    server_name: String,
    client: reqwest::Client,
    headers: std::collections::HashMap<String, String>,
    stream_state: SseStreamState,
    shared: std::sync::Arc<Mutex<SseSharedState>>,
    notify: std::sync::Arc<Notify>,
    shutdown_tx: Option<oneshot::Sender<()>>,
    reader_task: Option<tokio::task::JoinHandle<()>>,
}

impl SseTransport {
    pub fn new(config: &McpServerConfig) -> Result<Self> {
        let sse_url = config
            .url
            .as_ref()
            .ok_or_else(|| anyhow!("URL required for SSE transport"))?
            .clone();

        let client = reqwest::Client::builder()
            .build()
            .context("failed to build HTTP client")?;

        Ok(Self {
            sse_url,
            server_name: config.name.clone(),
            client,
            headers: config.headers.clone(),
            stream_state: SseStreamState::Unknown,
            shared: std::sync::Arc::new(Mutex::new(SseSharedState::default())),
            notify: std::sync::Arc::new(Notify::new()),
            shutdown_tx: None,
            reader_task: None,
        })
    }

    async fn ensure_connected(&mut self) -> Result<()> {
        if self.stream_state == SseStreamState::Unsupported {
            return Ok(());
        }
        if let Some(task) = &self.reader_task
            && !task.is_finished()
        {
            self.stream_state = SseStreamState::Connected;
            return Ok(());
        }

        let has_accept = self
            .headers
            .keys()
            .any(|k| k.eq_ignore_ascii_case("Accept"));

        let mut req = self
            .client
            .get(&self.sse_url)
            .header("Cache-Control", "no-cache");
        for (key, value) in &self.headers {
            req = req.header(key, value);
        }
        if !has_accept {
            req = req.header("Accept", MCP_STREAMABLE_ACCEPT);
        }

        let resp = req.send().await.context("SSE GET to MCP server failed")?;
        if resp.status() == reqwest::StatusCode::NOT_FOUND
            || resp.status() == reqwest::StatusCode::METHOD_NOT_ALLOWED
        {
            self.stream_state = SseStreamState::Unsupported;
            return Ok(());
        }
        if !resp.status().is_success() {
            return Err(anyhow!("MCP server returned HTTP {}", resp.status()));
        }
        let is_event_stream = resp
            .headers()
            .get(reqwest::header::CONTENT_TYPE)
            .and_then(|v| v.to_str().ok())
            .is_some_and(|v| v.to_ascii_lowercase().contains("text/event-stream"));
        if !is_event_stream {
            self.stream_state = SseStreamState::Unsupported;
            return Ok(());
        }

        let (shutdown_tx, mut shutdown_rx) = oneshot::channel::<()>();
        self.shutdown_tx = Some(shutdown_tx);

        let shared = self.shared.clone();
        let notify = self.notify.clone();
        let sse_url = self.sse_url.clone();
        let server_name = self.server_name.clone();

        self.reader_task = Some(tokio::spawn(async move {
            let stream = resp
                .bytes_stream()
                .map(|item| item.map_err(std::io::Error::other));
            let reader = tokio_util::io::StreamReader::new(stream);
            let mut lines = BufReader::new(reader).lines();

            let mut cur_event: Option<String> = None;
            let mut cur_id: Option<String> = None;
            let mut cur_data: Vec<String> = Vec::new();

            loop {
                tokio::select! {
                    _ = &mut shutdown_rx => {
                        break;
                    }
                    line = lines.next_line() => {
                        let Ok(line_opt) = line else { break; };
                        let Some(mut line) = line_opt else { break; };
                        if line.ends_with('\r') {
                            line.pop();
                        }
                        if line.is_empty() {
                            if cur_event.is_none() && cur_id.is_none() && cur_data.is_empty() {
                                continue;
                            }
                            let event = cur_event.take();
                            let data = cur_data.join("\n");
                            cur_data.clear();
                            let id = cur_id.take();
                            handle_sse_event(&server_name, &sse_url, &shared, &notify, event.as_deref(), id.as_deref(), data).await;
                            continue;
                        }

                        if line.starts_with(':') {
                            continue;
                        }

                        if let Some(rest) = line.strip_prefix("event:") {
                            cur_event = Some(rest.trim().to_string());
                        }
                        if let Some(rest) = line.strip_prefix("data:") {
                            let rest = rest.strip_prefix(' ').unwrap_or(rest);
                            cur_data.push(rest.to_string());
                        }
                        if let Some(rest) = line.strip_prefix("id:") {
                            cur_id = Some(rest.trim().to_string());
                        }
                    }
                }
            }

            let pending = {
                let mut guard = shared.lock().await;
                std::mem::take(&mut guard.pending)
            };
            for (_, tx) in pending {
                let _ = tx.send(JsonRpcResponse {
                    jsonrpc: crate::mcp_protocol::JSONRPC_VERSION.to_string(),
                    id: None,
                    result: None,
                    error: Some(JsonRpcError {
                        code: INTERNAL_ERROR,
                        message: "SSE connection closed".to_string(),
                        data: None,
                    }),
                });
            }
        }));
        self.stream_state = SseStreamState::Connected;

        Ok(())
    }

    async fn get_message_url(&self) -> Result<(String, bool)> {
        let guard = self.shared.lock().await;
        if let Some(url) = &guard.message_url {
            return Ok((url.clone(), guard.message_url_from_endpoint));
        }
        drop(guard);

        let derived = derive_message_url(&self.sse_url, "messages")
            .or_else(|| derive_message_url(&self.sse_url, "message"))
            .ok_or_else(|| anyhow!("invalid SSE URL"))?;
        let mut guard = self.shared.lock().await;
        if guard.message_url.is_none() {
            guard.message_url = Some(derived.clone());
            guard.message_url_from_endpoint = false;
        }
        Ok((derived, false))
    }

    #[allow(dead_code)] // WIP: alternate message URL fallback
    fn maybe_try_alternate_message_url(
        &self,
        current_url: &str,
        from_endpoint: bool,
    ) -> Option<String> {
        if from_endpoint {
            return None;
        }
        let alt = if current_url.ends_with("/messages") {
            derive_message_url(&self.sse_url, "message")
        } else {
            derive_message_url(&self.sse_url, "messages")
        }?;
        if alt == current_url {
            return None;
        }
        Some(alt)
    }
}

#[derive(Default)]
struct SseSharedState {
    message_url: Option<String>,
    message_url_from_endpoint: bool,
    pending: std::collections::HashMap<u64, oneshot::Sender<JsonRpcResponse>>,
}

fn derive_message_url(sse_url: &str, message_path: &str) -> Option<String> {
    let url = reqwest::Url::parse(sse_url).ok()?;
    let mut segments: Vec<&str> = url.path_segments()?.collect();
    if segments.is_empty() {
        return None;
    }
    if segments.last().copied() == Some("sse") {
        segments.pop();
        segments.push(message_path);
        let mut new_url = url.clone();
        new_url.set_path(&format!("/{}", segments.join("/")));
        return Some(new_url.to_string());
    }
    let mut new_url = url.clone();
    let mut path = url.path().trim_end_matches('/').to_string();
    path.push('/');
    path.push_str(message_path);
    new_url.set_path(&path);
    Some(new_url.to_string())
}

async fn handle_sse_event(
    server_name: &str,
    sse_url: &str,
    shared: &std::sync::Arc<Mutex<SseSharedState>>,
    notify: &std::sync::Arc<Notify>,
    event: Option<&str>,
    _id: Option<&str>,
    data: String,
) {
    let event = event.unwrap_or("message");
    let trimmed = data.trim();
    if trimmed.is_empty() {
        return;
    }

    if event.eq_ignore_ascii_case("endpoint") || event.eq_ignore_ascii_case("mcp-endpoint") {
        if let Some(url) = parse_endpoint_from_data(sse_url, trimmed) {
            let mut guard = shared.lock().await;
            guard.message_url = Some(url);
            guard.message_url_from_endpoint = true;
            drop(guard);
            notify.notify_waiters();
        }
        return;
    }

    if !event.eq_ignore_ascii_case("message") {
        return;
    }

    let Ok(value) = serde_json::from_str::<serde_json::Value>(trimmed) else {
        return;
    };

    let Ok(resp) = serde_json::from_value::<JsonRpcResponse>(value.clone()) else {
        let _ = serde_json::from_value::<JsonRpcRequest>(value);
        return;
    };

    let Some(id_val) = resp.id.clone() else {
        return;
    };
    let id = match id_val.as_u64() {
        Some(v) => v,
        None => return,
    };

    let tx = {
        let mut guard = shared.lock().await;
        guard.pending.remove(&id)
    };
    if let Some(tx) = tx {
        let _ = tx.send(resp);
    } else {
        tracing::debug!(
            "MCP SSE `{}` received response for unknown id {}",
            server_name,
            id
        );
    }
}

fn parse_endpoint_from_data(sse_url: &str, data: &str) -> Option<String> {
    if data.starts_with('{') {
        let v: serde_json::Value = serde_json::from_str(data).ok()?;
        let endpoint = v.get("endpoint")?.as_str()?;
        return parse_endpoint_from_data(sse_url, endpoint);
    }
    if data.starts_with("http://") || data.starts_with("https://") {
        return Some(data.to_string());
    }
    let base = reqwest::Url::parse(sse_url).ok()?;
    base.join(data).ok().map(|u| u.to_string())
}

fn extract_json_from_sse_text(resp_text: &str) -> Cow<'_, str> {
    let text = resp_text.trim_start_matches('\u{feff}');
    let mut current_data_lines: Vec<&str> = Vec::new();
    let mut last_event_data_lines: Vec<&str> = Vec::new();

    for raw_line in text.lines() {
        let line = raw_line.trim_end_matches('\r').trim_start();
        if line.is_empty() {
            if !current_data_lines.is_empty() {
                last_event_data_lines = std::mem::take(&mut current_data_lines);
            }
            continue;
        }

        if line.starts_with(':') {
            continue;
        }

        if let Some(rest) = line.strip_prefix("data:") {
            let rest = rest.strip_prefix(' ').unwrap_or(rest);
            current_data_lines.push(rest);
        }
    }

    if !current_data_lines.is_empty() {
        last_event_data_lines = current_data_lines;
    }

    if last_event_data_lines.is_empty() {
        return Cow::Borrowed(text.trim());
    }

    if last_event_data_lines.len() == 1 {
        return Cow::Borrowed(last_event_data_lines[0].trim());
    }

    let joined = last_event_data_lines.join("\n");
    Cow::Owned(joined.trim().to_string())
}

fn parse_jsonrpc_response_text(resp_text: &str) -> Result<JsonRpcResponse> {
    let trimmed = resp_text.trim();
    if trimmed.is_empty() {
        bail!("MCP server returned no response");
    }

    let json_text = if looks_like_sse_text(trimmed) {
        extract_json_from_sse_text(trimmed)
    } else {
        Cow::Borrowed(trimmed)
    };

    let mcp_resp: JsonRpcResponse = serde_json::from_str(json_text.as_ref())
        .with_context(|| format!("invalid JSON-RPC response: {}", resp_text))?;
    Ok(mcp_resp)
}

fn looks_like_sse_text(text: &str) -> bool {
    text.starts_with("data:")
        || text.starts_with("event:")
        || text.contains("\ndata:")
        || text.contains("\nevent:")
}

/// Read the first JSON-RPC response matching `expected_id` from an SSE
/// response body. Server notifications (no result/error payload) and responses
/// for other request ids are skipped. `None` means the stream ended without a
/// matching response.
async fn read_first_jsonrpc_from_sse_response(
    resp: reqwest::Response,
    expected_id: Option<u64>,
) -> Result<Option<JsonRpcResponse>> {
    let stream = resp
        .bytes_stream()
        .map(|item| item.map_err(std::io::Error::other));
    let reader = tokio_util::io::StreamReader::new(stream);
    let mut lines = BufReader::new(reader).lines();

    let mut cur_event: Option<String> = None;
    let mut cur_data: Vec<String> = Vec::new();

    while let Ok(line_opt) = lines.next_line().await {
        let Some(mut line) = line_opt else { break };
        if line.ends_with('\r') {
            line.pop();
        }
        if line.is_empty() {
            if cur_event.is_none() && cur_data.is_empty() {
                continue;
            }
            let event = cur_event.take();
            let data = cur_data.join("\n");
            cur_data.clear();

            let event = event.unwrap_or_else(|| "message".to_string());
            if event.eq_ignore_ascii_case("endpoint") || event.eq_ignore_ascii_case("mcp-endpoint")
            {
                continue;
            }
            if !event.eq_ignore_ascii_case("message") {
                continue;
            }

            let trimmed = data.trim();
            if trimmed.is_empty() {
                continue;
            }
            let json_str = extract_json_from_sse_text(trimmed);
            if let Ok(resp) = serde_json::from_str::<JsonRpcResponse>(json_str.as_ref()) {
                // Skip server notifications/requests — only actionable
                // responses carry a result or error payload.
                if resp.result.is_none() && resp.error.is_none() {
                    continue;
                }
                if let Some(expected) = expected_id
                    && resp.id.as_ref().and_then(|v| v.as_u64()) != Some(expected)
                {
                    continue;
                }
                return Ok(Some(resp));
            }
            continue;
        }

        if line.starts_with(':') {
            continue;
        }
        if let Some(rest) = line.strip_prefix("event:") {
            cur_event = Some(rest.trim().to_string());
        }
        if let Some(rest) = line.strip_prefix("data:") {
            let rest = rest.strip_prefix(' ').unwrap_or(rest);
            cur_data.push(rest.to_string());
        }
    }

    Ok(None)
}

#[async_trait::async_trait]
impl McpTransportConn for SseTransport {
    async fn send_and_recv(&mut self, request: &JsonRpcRequest) -> Result<JsonRpcResponse> {
        self.ensure_connected().await?;

        // Notifications (id = null) get no response from the server. Send
        // best-effort and return immediately — never wait on a response that
        // will not arrive. Some servers keep the SSE stream open indefinitely,
        // which would stall the caller for the full connection lifetime.
        if request.id.is_none() {
            let body = serde_json::to_string(request)?;
            let (message_url, _) = self.get_message_url().await?;
            let url = if self.stream_state == SseStreamState::Connected {
                message_url
            } else {
                self.sse_url.clone()
            };

            let has_accept = self
                .headers
                .keys()
                .any(|k| k.eq_ignore_ascii_case("Accept"));
            let has_content_type = self
                .headers
                .keys()
                .any(|k| k.eq_ignore_ascii_case("Content-Type"));

            let mut req = self
                .client
                .post(&url)
                .timeout(Duration::from_secs(120))
                .body(body);
            if !has_content_type {
                req = req.header("Content-Type", MCP_JSON_CONTENT_TYPE);
            }
            for (key, value) in &self.headers {
                req = req.header(key, value);
            }
            if !has_accept {
                req = req.header("Accept", MCP_STREAMABLE_ACCEPT);
            }
            let _ = req.send().await;

            return Ok(JsonRpcResponse {
                jsonrpc: crate::mcp_protocol::JSONRPC_VERSION.to_string(),
                id: None,
                result: None,
                error: None,
            });
        }

        let id = request.id.as_ref().and_then(|v| v.as_u64());
        let body = serde_json::to_string(request)?;

        let (mut message_url, mut from_endpoint) = self.get_message_url().await?;
        if self.stream_state == SseStreamState::Connected && !from_endpoint {
            for _ in 0..3 {
                {
                    let guard = self.shared.lock().await;
                    if guard.message_url_from_endpoint
                        && let Some(url) = &guard.message_url
                    {
                        message_url = url.clone();
                        from_endpoint = true;
                        break;
                    }
                }
                let _ = timeout(Duration::from_millis(300), self.notify.notified()).await;
            }
        }
        let primary_url = if from_endpoint {
            message_url.clone()
        } else {
            self.sse_url.clone()
        };
        let secondary_url = if message_url == self.sse_url {
            None
        } else if primary_url == message_url {
            Some(self.sse_url.clone())
        } else {
            Some(message_url.clone())
        };
        let has_secondary = secondary_url.is_some();

        let mut rx = None;
        if let Some(id) = id
            && self.stream_state == SseStreamState::Connected
        {
            let (tx, ch) = oneshot::channel();
            {
                let mut guard = self.shared.lock().await;
                guard.pending.insert(id, tx);
            }
            rx = Some((id, ch));
        }

        let mut got_direct = None;
        let mut last_status = None;

        for (i, url) in std::iter::once(primary_url)
            .chain(secondary_url)
            .enumerate()
        {
            let has_accept = self
                .headers
                .keys()
                .any(|k| k.eq_ignore_ascii_case("Accept"));
            let has_content_type = self
                .headers
                .keys()
                .any(|k| k.eq_ignore_ascii_case("Content-Type"));
            let mut req = self
                .client
                .post(&url)
                .timeout(Duration::from_secs(120))
                .body(body.clone());
            if !has_content_type {
                req = req.header("Content-Type", MCP_JSON_CONTENT_TYPE);
            }
            for (key, value) in &self.headers {
                req = req.header(key, value);
            }
            if !has_accept {
                req = req.header("Accept", MCP_STREAMABLE_ACCEPT);
            }

            let resp = req.send().await.with_context(|| {
                format!("SSE POST to MCP server `{}` failed", self.sse_url)
            })?;
            let status = resp.status();
            last_status = Some(status);

            if i == 0 && (status == reqwest::StatusCode::NOT_FOUND
                || status == reqwest::StatusCode::METHOD_NOT_ALLOWED)
            {
                tracing::debug!(url = %self.sse_url, http_status = %status, "SSE transport: primary URL returned non-fatal status, trying secondary");
                continue;
            }

            if !status.is_success() {
                tracing::error!(url = %self.sse_url, http_status = %status, "SSE transport: HTTP error");
                break;
            }

            if request.id.is_none() {
                got_direct = Some(JsonRpcResponse {
                    jsonrpc: crate::mcp_protocol::JSONRPC_VERSION.to_string(),
                    id: None,
                    result: None,
                    error: None,
                });
                break;
            }

            let is_sse = resp
                .headers()
                .get(reqwest::header::CONTENT_TYPE)
                .and_then(|v| v.to_str().ok())
                .is_some_and(|v| v.to_ascii_lowercase().contains("text/event-stream"));

            if is_sse {
                if i == 0 && has_secondary {
                    match timeout(
                        Duration::from_secs(3),
                        read_first_jsonrpc_from_sse_response(resp, None),
                    )
                    .await
                    {
                        Ok(res) => {
                            if let Some(resp) = res? {
                                got_direct = Some(resp);
                            }
                            break;
                        }
                        Err(_) => continue,
                    }
                }
                if let Some(resp) = read_first_jsonrpc_from_sse_response(resp, None).await? {
                    got_direct = Some(resp);
                }
                break;
            }

            let text = if i == 0 && has_secondary {
                match timeout(Duration::from_secs(3), resp.text()).await {
                    Ok(Ok(t)) => t,
                    Ok(Err(_)) => String::new(),
                    Err(_) => continue,
                }
            } else {
                resp.text().await.unwrap_or_default()
            };
            let trimmed = text.trim();
            if !trimmed.is_empty() {
                let json_str = if trimmed.contains("\ndata:") || trimmed.starts_with("data:") {
                    extract_json_from_sse_text(trimmed)
                } else {
                    Cow::Borrowed(trimmed)
                };
                if let Ok(mcp_resp) = serde_json::from_str::<JsonRpcResponse>(json_str.as_ref()) {
                    got_direct = Some(mcp_resp);
                }
            }
            break;
        }

        if let Some((id, _)) = rx.as_ref() {
            if got_direct.is_some() {
                let mut guard = self.shared.lock().await;
                guard.pending.remove(id);
            } else if let Some(status) = last_status
                && !status.is_success()
            {
                let mut guard = self.shared.lock().await;
                guard.pending.remove(id);
            }
        }

        if let Some(resp) = got_direct {
            return Ok(resp);
        }

        if let Some(status) = last_status {
            if !status.is_success() {
                tracing::error!(url = %self.sse_url, http_status = %status, "SSE transport: all URLs failed");
                bail!("MCP server returned HTTP {}", status);
            }
        } else {
            tracing::error!(url = %self.sse_url, "SSE transport: request not sent");
            bail!("MCP request not sent");
        }

        let Some((_id, rx)) = rx else {
            bail!("MCP server returned no response");
        };

        rx.await.map_err(|_| anyhow!("SSE response channel closed"))
    }

    async fn close(&mut self) -> Result<()> {
        if let Some(tx) = self.shutdown_tx.take() {
            let _ = tx.send(());
        }
        if let Some(task) = self.reader_task.take() {
            task.abort();
        }
        Ok(())
    }
}

// ── Factory ──────────────────────────────────────────────────────────────

/// Create a transport based on config.
pub fn create_transport(config: &McpServerConfig) -> Result<Box<dyn McpTransportConn>> {
    match config.transport {
        McpTransport::Stdio => Ok(Box::new(StdioTransport::new(config)?)),
        McpTransport::LocalhostStdio => Ok(Box::new(LocalhostStdioTransport::new(config)?)),
        McpTransport::Http => Ok(Box::new(HttpTransport::new(config)?)),
        McpTransport::Sse => Ok(Box::new(SseTransport::new(config)?)),
    }
}

// ── Tests ─────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_transport_default_is_stdio() {
        let config = McpServerConfig::default();
        assert_eq!(config.transport, McpTransport::Stdio);
    }

    #[test]
    fn test_http_transport_requires_url() {
        let config = McpServerConfig {
            name: "test".into(),
            transport: McpTransport::Http,
            ..Default::default()
        };
        assert!(HttpTransport::new(&config).is_err());
    }

    #[test]
    fn test_sse_transport_requires_url() {
        let config = McpServerConfig {
            name: "test".into(),
            transport: McpTransport::Sse,
            ..Default::default()
        };
        assert!(SseTransport::new(&config).is_err());
    }

    #[test]
    fn test_extract_json_from_sse_data_no_space() {
        let input = "data:{\"jsonrpc\":\"2.0\",\"result\":{}}\n\n";
        let extracted = extract_json_from_sse_text(input);
        let _: JsonRpcResponse = serde_json::from_str(extracted.as_ref()).unwrap();
    }

    #[test]
    fn test_extract_json_from_sse_with_event_and_id() {
        let input = "id: 1\nevent: message\ndata: {\"jsonrpc\":\"2.0\",\"result\":{}}\n\n";
        let extracted = extract_json_from_sse_text(input);
        let _: JsonRpcResponse = serde_json::from_str(extracted.as_ref()).unwrap();
    }

    #[test]
    fn test_extract_json_from_sse_multiline_data() {
        let input = "event: message\ndata: {\ndata:   \"jsonrpc\": \"2.0\",\ndata:   \"result\": {}\ndata: }\n\n";
        let extracted = extract_json_from_sse_text(input);
        let _: JsonRpcResponse = serde_json::from_str(extracted.as_ref()).unwrap();
    }

    #[test]
    fn test_extract_json_from_sse_skips_bom_and_leading_whitespace() {
        let input = "\u{feff}\n\n  data: {\"jsonrpc\":\"2.0\",\"result\":{}}\n\n";
        let extracted = extract_json_from_sse_text(input);
        let _: JsonRpcResponse = serde_json::from_str(extracted.as_ref()).unwrap();
    }

    #[test]
    fn test_extract_json_from_sse_uses_last_event_with_data() {
        let input =
            ": keep-alive\n\nid: 1\nevent: message\ndata: {\"jsonrpc\":\"2.0\",\"result\":{}}\n\n";
        let extracted = extract_json_from_sse_text(input);
        let _: JsonRpcResponse = serde_json::from_str(extracted.as_ref()).unwrap();
    }

    #[test]
    fn test_parse_jsonrpc_response_text_handles_plain_json() {
        let parsed = parse_jsonrpc_response_text("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}")
            .expect("plain JSON response should parse");
        assert_eq!(parsed.id, Some(serde_json::json!(1)));
        assert!(parsed.error.is_none());
    }

    #[test]
    fn test_parse_jsonrpc_response_text_handles_sse_framed_json() {
        let sse =
            "event: message\ndata: {\"jsonrpc\":\"2.0\",\"id\":2,\"result\":{\"ok\":true}}\n\n";
        let parsed =
            parse_jsonrpc_response_text(sse).expect("SSE-framed JSON response should parse");
        assert_eq!(parsed.id, Some(serde_json::json!(2)));
        assert_eq!(
            parsed
                .result
                .as_ref()
                .and_then(|v| v.get("ok"))
                .and_then(|v| v.as_bool()),
            Some(true)
        );
    }

    #[test]
    fn test_parse_jsonrpc_response_text_rejects_empty_payload() {
        assert!(parse_jsonrpc_response_text(" \n\t ").is_err());
    }

    #[test]
    fn http_transport_updates_session_id_from_response_headers() {
        let config = McpServerConfig {
            name: "test-http".into(),
            transport: McpTransport::Http,
            url: Some("http://localhost/mcp".into()),
            ..Default::default()
        };
        let mut transport = HttpTransport::new(&config).expect("build transport");

        let mut headers = reqwest::header::HeaderMap::new();
        headers.insert(
            reqwest::header::HeaderName::from_static("mcp-session-id"),
            reqwest::header::HeaderValue::from_static("session-abc"),
        );
        transport.update_session_id_from_headers(&headers);
        assert_eq!(transport.session_id.as_deref(), Some("session-abc"));
    }

    #[test]
    fn http_transport_injects_session_id_header_when_available() {
        let config = McpServerConfig {
            name: "test-http".into(),
            transport: McpTransport::Http,
            url: Some("http://localhost/mcp".into()),
            ..Default::default()
        };
        let mut transport = HttpTransport::new(&config).expect("build transport");
        transport.session_id = Some("session-xyz".to_string());

        let req = transport
            .apply_session_header(reqwest::Client::new().post("http://localhost/mcp"))
            .build()
            .expect("build request");
        assert_eq!(
            req.headers()
                .get(MCP_SESSION_ID_HEADER)
                .and_then(|v| v.to_str().ok()),
            Some("session-xyz")
        );
    }

    // ── derive_message_url tests ──────────────────────────────────────────────

    #[test]
    fn derive_message_url_replaces_sse_segment_with_messages() {
        let url = derive_message_url("http://localhost:3000/mcp/sse", "messages");
        assert_eq!(url, Some("http://localhost:3000/mcp/messages".to_string()));
    }

    #[test]
    fn derive_message_url_appends_when_no_sse_segment() {
        let url = derive_message_url("http://localhost:3000/mcp", "messages");
        assert_eq!(url, Some("http://localhost:3000/mcp/messages".to_string()));
    }

    #[test]
    fn derive_message_url_returns_none_for_invalid_url() {
        let url = derive_message_url("not-a-url", "messages");
        assert!(url.is_none());
    }

    #[test]
    fn derive_message_url_message_path_variant() {
        let url = derive_message_url("http://localhost:3000/mcp/sse", "message");
        assert_eq!(url, Some("http://localhost:3000/mcp/message".to_string()));
    }

    // ── parse_endpoint_from_data tests ───────────────────────────────────────

    #[test]
    fn parse_endpoint_absolute_http_url_returned_as_is() {
        let result = parse_endpoint_from_data("http://base/sse", "http://other/messages");
        assert_eq!(result, Some("http://other/messages".to_string()));
    }

    #[test]
    fn parse_endpoint_absolute_https_url_returned_as_is() {
        let result = parse_endpoint_from_data("https://base/sse", "https://other/messages");
        assert_eq!(result, Some("https://other/messages".to_string()));
    }

    #[test]
    fn parse_endpoint_relative_path_resolved_against_base() {
        let result = parse_endpoint_from_data("http://localhost:3000/sse", "/messages");
        assert_eq!(result, Some("http://localhost:3000/messages".to_string()));
    }

    #[test]
    fn parse_endpoint_json_object_with_endpoint_key() {
        let json_data = r#"{"endpoint":"/messages"}"#;
        let result = parse_endpoint_from_data("http://localhost:3000/sse", json_data);
        assert_eq!(result, Some("http://localhost:3000/messages".to_string()));
    }

    // ── looks_like_sse_text tests ─────────────────────────────────────────────

    #[test]
    fn looks_like_sse_text_detects_data_prefix() {
        assert!(looks_like_sse_text("data:{\"jsonrpc\":\"2.0\"}"));
    }

    #[test]
    fn looks_like_sse_text_detects_event_prefix() {
        assert!(looks_like_sse_text("event: message\ndata: {}"));
    }

    #[test]
    fn looks_like_sse_text_detects_embedded_data_line() {
        assert!(looks_like_sse_text("id: 1\ndata:{\"x\":1}"));
    }

    #[test]
    fn looks_like_sse_text_plain_json_is_not_sse() {
        assert!(!looks_like_sse_text(
            "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"
        ));
    }

    // ── extract_json_from_sse_text edge cases ─────────────────────────────────

    #[test]
    fn extract_json_skips_comment_lines() {
        let input = ": keep-alive\ndata: {\"jsonrpc\":\"2.0\",\"result\":{}}\n\n";
        let extracted = extract_json_from_sse_text(input);
        let v: serde_json::Value = serde_json::from_str(extracted.as_ref()).unwrap();
        assert_eq!(v["jsonrpc"], "2.0");
    }

    #[test]
    fn extract_json_empty_input_returns_empty_trimmed() {
        let result = extract_json_from_sse_text("   ");
        assert!(result.as_ref().trim().is_empty());
    }

    #[test]
    fn extract_json_plain_json_returned_unchanged() {
        let input = "{\"jsonrpc\":\"2.0\",\"result\":{}}";
        let extracted = extract_json_from_sse_text(input);
        // No SSE framing, extracted as-is (trimmed)
        assert_eq!(extracted.as_ref(), input);
    }

    // ── parse_jsonrpc_response_text edge cases ────────────────────────────────

    #[test]
    fn parse_jsonrpc_response_rejects_whitespace_only() {
        assert!(parse_jsonrpc_response_text("   \n\t  ").is_err());
    }

    #[test]
    fn parse_jsonrpc_response_with_error_result() {
        let json = r#"{"jsonrpc":"2.0","id":1,"error":{"code":-32601,"message":"not found"}}"#;
        let resp = parse_jsonrpc_response_text(json).unwrap();
        assert!(resp.error.is_some());
        assert_eq!(resp.error.unwrap().code, -32601);
    }

    // ── create_transport factory ──────────────────────────────────────────────

    #[test]
    fn create_transport_stdio_fails_without_valid_command() {
        // Spawning a non-existent binary should fail
        let config = McpServerConfig {
            name: "test-stdio".into(),
            transport: McpTransport::Stdio,
            command: "/usr/bin/zeroclaw_nonexistent_binary_abc123".into(),
            ..Default::default()
        };
        let result = create_transport(&config);
        assert!(result.is_err());
    }

    #[test]
    fn create_transport_http_without_url_fails() {
        let config = McpServerConfig {
            name: "test-http".into(),
            transport: McpTransport::Http,
            ..Default::default()
        };
        assert!(create_transport(&config).is_err());
    }

    #[test]
    fn create_transport_sse_without_url_fails() {
        let config = McpServerConfig {
            name: "test-sse".into(),
            transport: McpTransport::Sse,
            ..Default::default()
        };
        assert!(create_transport(&config).is_err());
    }

    #[test]
    fn create_transport_http_with_url_succeeds() {
        let config = McpServerConfig {
            name: "test-http".into(),
            transport: McpTransport::Http,
            url: Some("http://localhost:9999/mcp".into()),
            ..Default::default()
        };
        // Build should succeed even if server isn't running
        assert!(create_transport(&config).is_ok());
    }

    #[test]
    fn create_transport_sse_with_url_succeeds() {
        let config = McpServerConfig {
            name: "test-sse".into(),
            transport: McpTransport::Sse,
            url: Some("http://localhost:9999/sse".into()),
            ..Default::default()
        };
        assert!(create_transport(&config).is_ok());
    }

    #[test]
    fn create_transport_localhost_stdio_without_url_fails() {
        let config = McpServerConfig {
            name: "test-local-stdio".into(),
            transport: McpTransport::LocalhostStdio,
            ..Default::default()
        };
        assert!(create_transport(&config).is_err());
    }

    #[test]
    fn create_transport_localhost_stdio_with_url_succeeds() {
        let config = McpServerConfig {
            name: "test-local-stdio".into(),
            transport: McpTransport::LocalhostStdio,
            url: Some("http://localhost:9735/".into()),
            ..Default::default()
        };
        assert!(create_transport(&config).is_ok());
    }

    // ── HTTP session id whitespace handling ───────────────────────────────────

    #[test]
    fn http_transport_ignores_empty_session_id_header() {
        let config = McpServerConfig {
            name: "test-http".into(),
            transport: McpTransport::Http,
            url: Some("http://localhost/mcp".into()),
            ..Default::default()
        };
        let mut transport = HttpTransport::new(&config).expect("build transport");
        let mut headers = reqwest::header::HeaderMap::new();
        headers.insert(
            reqwest::header::HeaderName::from_static("mcp-session-id"),
            reqwest::header::HeaderValue::from_static("   "),
        );
        transport.update_session_id_from_headers(&headers);
        // Whitespace-only session id should not be stored
        assert!(transport.session_id.is_none());
    }

    #[test]
    fn http_transport_no_session_header_leaves_none() {
        let config = McpServerConfig {
            name: "test-http".into(),
            transport: McpTransport::Http,
            url: Some("http://localhost/mcp".into()),
            ..Default::default()
        };
        let transport = HttpTransport::new(&config).expect("build transport");
        assert!(transport.session_id.is_none());
    }

    #[test]
    fn http_transport_apply_session_header_noop_when_no_session() {
        let config = McpServerConfig {
            name: "test-http".into(),
            transport: McpTransport::Http,
            url: Some("http://localhost/mcp".into()),
            ..Default::default()
        };
        let transport = HttpTransport::new(&config).expect("build transport");
        let req = transport
            .apply_session_header(reqwest::Client::new().post("http://localhost/mcp"))
            .build()
            .expect("build request");
        assert!(req.headers().get(MCP_SESSION_ID_HEADER).is_none());
    }
}
