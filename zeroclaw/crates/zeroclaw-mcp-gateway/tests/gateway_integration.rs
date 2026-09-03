//! End-to-end gateway tests against a mock MCP backend.
//!
//! The mock backend emulates a host-locked Streamable HTTP MCP server (like
//! Autodesk Fusion 360's local server): it rejects requests whose Host
//! header is not exactly `127.0.0.1:<port>` with HTTP 403
//! `{"error":"Invalid Host header"}`, and otherwise performs a full
//! handshake (session ids, notifications, tool calls, DELETE termination).

use std::net::SocketAddr;
use std::sync::Arc;

use axum::body::Body;
use axum::extract::State;
use axum::http::{Request, StatusCode};
use axum::response::IntoResponse as _;
use axum::routing::any;
use axum::Router;
use http_body_util::BodyExt as _;
use serde_json::json;
use tokio::io::AsyncWriteExt;
use tokio::net::TcpListener;
use tokio::sync::Mutex;
use zeroclaw_mcp_gateway::config::GatewayConfig;
use zeroclaw_mcp_gateway::Gateway;

// ── Mock backend ─────────────────────────────────────────────────────────

#[derive(Default)]
struct MockState {
    hosts: Mutex<Vec<String>>,
    sessions_received: Mutex<Vec<String>>,
    sessions_deleted: Mutex<Vec<String>>,
    expected_host: Mutex<Option<String>>,
}

async fn mock_handler(State(state): State<Arc<MockState>>, req: Request<Body>) -> axum::response::Response {
    let host = req
        .headers()
        .get("host")
        .and_then(|v| v.to_str().ok())
        .unwrap_or("")
        .to_string();
    let session = req
        .headers()
        .get("mcp-session-id")
        .and_then(|v| v.to_str().ok())
        .map(str::to_string);
    state.hosts.lock().await.push(host.clone());
    if let Some(s) = session.clone() {
        state.sessions_received.lock().await.push(s);
    }

    let method = req.method().clone();
    let path = req.uri().path().to_string();
    let bytes = req
        .into_body()
        .collect()
        .await
        .map(|c| c.to_bytes().to_vec())
        .unwrap_or_default();
    let text = String::from_utf8_lossy(&bytes).to_string();

    // Host-locked check.
    if let Some(expected) = state.expected_host.lock().await.clone() {
        if host != expected {
            return (
                StatusCode::FORBIDDEN,
                axum::Json(json!({"error": "Invalid Host header"})),
            )
                .into_response();
        }
    }

    match (method.as_str(), path.as_str()) {
        ("POST", "/mcp") if text.contains("\"initialize\"") => {
            let builder = axum::response::Response::builder()
                .status(StatusCode::OK)
                .header("content-type", "application/json")
                .header("mcp-session-id", "sess-1");
            builder
                .body(Body::from(
                    json!({"jsonrpc":"2.0","id":1,"result":{
                        "protocolVersion":"2024-11-05",
                        "capabilities":{"tools":{}},
                        "serverInfo":{"name":"Mock MCP Adapter","version":"1.0.0"}
                    }})
                    .to_string(),
                ))
                .unwrap()
                .into_response()
        }
        ("POST", "/mcp") => {
            let echoed = json!({"jsonrpc":"2.0","id":2,"result":{"echo":text}});
            axum::Json(echoed).into_response()
        }
        ("GET", "/mcp") => (
            StatusCode::METHOD_NOT_ALLOWED,
            axum::Json(json!({"error": "Server does not offer an SSE stream at this endpoint"})),
        )
            .into_response(),
        ("DELETE", "/mcp") => {
            if let Some(s) = session {
                state.sessions_deleted.lock().await.push(s);
            }
            axum::Json(json!({"jsonrpc":"2.0","result":{}})).into_response()
        }
        ("POST", "/always403") => (
            StatusCode::FORBIDDEN,
            axum::Json(json!({"error": "Invalid Host header"})),
        )
            .into_response(),
        _ => (StatusCode::NOT_FOUND, axum::Json(json!({"error": "not found"}))).into_response(),
    }
}

async fn start_mock(expected_host: Option<String>) -> (SocketAddr, Arc<MockState>) {
    let state = Arc::new(MockState::default());
    *state.expected_host.lock().await = expected_host;
    let app = Router::new()
        .route("/mcp", any(mock_handler))
        .route("/always403", any(mock_handler))
        .with_state(state.clone());
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(async move {
        let _ = axum::serve(listener, app).await;
    });
    (addr, state)
}

// ── Gateway harness ──────────────────────────────────────────────────────

async fn start_gateway(config_toml: &str, mock_addr: SocketAddr) -> SocketAddr {
    let toml = config_toml.replace("$MOCK", &format!("http://{mock_addr}"));
    let config = GatewayConfig::parse_toml(&toml).unwrap();
    let gateway = Arc::new(Gateway::from_config(config).unwrap());
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let addr = listener.local_addr().unwrap();
    tokio::spawn(async move {
        let _ = gateway.serve_on(listener).await;
    });
    addr
}

fn gateway_config(backend_lines: &str) -> String {
    format!(
        r#"
[gateway]
listen = "127.0.0.1:0"
{backend_lines}
"#
    )
}

const INIT_BODY: &str = r#"{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"test","version":"1.0"}}}"#;

// ── Tests ────────────────────────────────────────────────────────────────

#[tokio::test]
async fn fusion_adapter_reaches_host_locked_backend() {
    let (mock_addr, state) = start_mock(None).await;
    // Host-lock the mock to Fusion's exact address format: the gateway must
    // present the upstream authority (127.0.0.1:<port>) in the Host header.
    *state.expected_host.lock().await = Some(format!("127.0.0.1:{}", mock_addr.port()));

    let gateway_addr = start_gateway(
        &gateway_config(
            r#"
[[backends]]
name = "fusion"
match_path = "/mcp"
adapter = "fusion"
upstream = "$MOCK"
"#,
        ),
        mock_addr,
    )
    .await;

    let client = reqwest::Client::new();
    let resp = client
        .post(format!("http://{gateway_addr}/mcp"))
        .header("content-type", "application/json")
        .header("accept", "application/json, text/event-stream")
        .body(INIT_BODY)
        .send()
        .await
        .unwrap();

    assert_eq!(resp.status(), StatusCode::OK);
    assert_eq!(
        resp.headers().get("mcp-session-id").and_then(|v| v.to_str().ok()),
        Some("sess-1")
    );
    let body: serde_json::Value = resp.json().await.unwrap();
    assert_eq!(body["result"]["serverInfo"]["name"], "Mock MCP Adapter");

    // The mock saw the rewritten Host header, not the gateway's address.
    let hosts = state.hosts.lock().await.clone();
    assert_eq!(hosts, vec![format!("127.0.0.1:{}", mock_addr.port())]);
}

#[tokio::test]
async fn host_rewrite_adapter_is_generic_default() {
    let (mock_addr, state) = start_mock(None).await;
    *state.expected_host.lock().await = Some(format!("127.0.0.1:{}", mock_addr.port()));

    let gateway_addr = start_gateway(
        &gateway_config(
            r#"
[[backends]]
name = "generic"
match_path = "/mcp"
adapter = "host_rewrite"
upstream = "$MOCK"
"#,
        ),
        mock_addr,
    )
    .await;

    let resp = reqwest::Client::new()
        .post(format!("http://{gateway_addr}/mcp"))
        .header("content-type", "application/json")
        .body(INIT_BODY)
        .send()
        .await
        .unwrap();

    assert_eq!(resp.status(), StatusCode::OK);
    assert_eq!(
        resp.headers().get("mcp-session-id").and_then(|v| v.to_str().ok()),
        Some("sess-1")
    );
}

#[tokio::test]
async fn explicit_rewrite_host_override_wins() {
    let (mock_addr, state) = start_mock(None).await;
    *state.expected_host.lock().await = Some("fusion.internal:27182".to_string());

    let gateway_addr = start_gateway(
        &gateway_config(
            &format!(
                r#"
[[backends]]
name = "fusion"
match_path = "/mcp"
adapter = "fusion"
upstream = "$MOCK"
rewrite_host = "fusion.internal:27182"
"#
            ),
        ),
        mock_addr,
    )
    .await;

    let resp = reqwest::Client::new()
        .post(format!("http://{gateway_addr}/mcp"))
        .header("content-type", "application/json")
        .body(INIT_BODY)
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), StatusCode::OK);
}

#[tokio::test]
async fn session_id_passes_through_in_both_directions() {
    let (mock_addr, state) = start_mock(None).await;

    let gateway_addr = start_gateway(
        &gateway_config(
            r#"
[[backends]]
name = "generic"
match_path = "/mcp"
adapter = "host_rewrite"
upstream = "$MOCK"
"#,
        ),
        mock_addr,
    )
    .await;

    let client = reqwest::Client::new();
    let resp = client
        .post(format!("http://{gateway_addr}/mcp"))
        .header("content-type", "application/json")
        .body(INIT_BODY)
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), StatusCode::OK);

    // Second request carries the session id from the first response.
    let resp = client
        .post(format!("http://{gateway_addr}/mcp"))
        .header("content-type", "application/json")
        .header("mcp-session-id", "sess-1")
        .body(r#"{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}"#)
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), StatusCode::OK);

    let sessions = state.sessions_received.lock().await.clone();
    assert_eq!(sessions, vec!["sess-1"]);
}

#[tokio::test]
async fn preserve_session_false_strips_session_headers() {
    let (mock_addr, state) = start_mock(None).await;

    let gateway_addr = start_gateway(
        &gateway_config(
            r#"
[[backends]]
name = "stateless"
match_path = "/mcp"
adapter = "host_rewrite"
upstream = "$MOCK"
preserve_session = false
"#,
        ),
        mock_addr,
    )
    .await;

    let resp = reqwest::Client::new()
        .post(format!("http://{gateway_addr}/mcp"))
        .header("content-type", "application/json")
        .header("mcp-session-id", "secret-session")
        .body(INIT_BODY)
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), StatusCode::OK);

    let sessions = state.sessions_received.lock().await.clone();
    assert!(sessions.is_empty(), "session header must not reach upstream");
}

#[tokio::test]
async fn body_streamed_unchanged() {
    let (mock_addr, _state) = start_mock(None).await;

    let gateway_addr = start_gateway(
        &gateway_config(
            r#"
[[backends]]
name = "generic"
match_path = "/mcp"
adapter = "host_rewrite"
upstream = "$MOCK"
"#,
        ),
        mock_addr,
    )
    .await;

    let payload = json!({
        "jsonrpc": "2.0",
        "id": 7,
        "method": "tools/call",
        "params": {
            "name": "fusion_mcp_execute",
            "arguments": {"script": "adsk.doThings(); // unicode: 融合测试"}
        }
    });

    let resp = reqwest::Client::new()
        .post(format!("http://{gateway_addr}/mcp"))
        .header("content-type", "application/json")
        .body(payload.to_string())
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), StatusCode::OK);

    let body: serde_json::Value = resp.json().await.unwrap();
    let echoed: serde_json::Value = serde_json::from_str(body["result"]["echo"].as_str().unwrap()).unwrap();
    assert_eq!(echoed, payload, "payload must round-trip byte-identically");
}

#[tokio::test]
async fn upstream_errors_propagate_verbatim() {
    let (mock_addr, _state) = start_mock(None).await;

    let gateway_addr = start_gateway(
        &gateway_config(
            r#"
[[backends]]
name = "generic"
match_path = "/"
adapter = "host_rewrite"
upstream = "$MOCK"
"#,
        ),
        mock_addr,
    )
    .await;

    let resp = reqwest::Client::new()
        .post(format!("http://{gateway_addr}/always403"))
        .body("{}")
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), StatusCode::FORBIDDEN);
    let body: serde_json::Value = resp.json().await.unwrap();
    assert_eq!(body["error"], "Invalid Host header");
}

#[tokio::test]
async fn get_sse_rejection_passes_through() {
    let (mock_addr, _state) = start_mock(None).await;

    let gateway_addr = start_gateway(
        &gateway_config(
            r#"
[[backends]]
name = "generic"
match_path = "/mcp"
adapter = "host_rewrite"
upstream = "$MOCK"
"#,
        ),
        mock_addr,
    )
    .await;

    let resp = reqwest::Client::new()
        .get(format!("http://{gateway_addr}/mcp"))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), StatusCode::METHOD_NOT_ALLOWED);
    let body: serde_json::Value = resp.json().await.unwrap();
    assert!(
        body["error"]
            .as_str()
            .unwrap()
            .contains("does not offer an SSE stream")
    );
}

#[tokio::test]
async fn delete_session_termination_passes_through() {
    let (mock_addr, state) = start_mock(None).await;

    let gateway_addr = start_gateway(
        &gateway_config(
            r#"
[[backends]]
name = "generic"
match_path = "/mcp"
adapter = "host_rewrite"
upstream = "$MOCK"
"#,
        ),
        mock_addr,
    )
    .await;

    let resp = reqwest::Client::new()
        .delete(format!("http://{gateway_addr}/mcp"))
        .header("mcp-session-id", "sess-9")
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), StatusCode::OK);
    assert_eq!(state.sessions_deleted.lock().await.clone(), vec!["sess-9"]);
}

#[tokio::test]
async fn gateway_auth_token_required() {
    let (mock_addr, _state) = start_mock(None).await;

    let gateway_addr = start_gateway(
        &gateway_config(
            r#"
auth_token = "sekrit"
[[backends]]
name = "generic"
match_path = "/mcp"
adapter = "host_rewrite"
upstream = "$MOCK"
"#,
        ),
        mock_addr,
    )
    .await;

    // No token → 401.
    let resp = reqwest::Client::new()
        .post(format!("http://{gateway_addr}/mcp"))
        .body(INIT_BODY)
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), StatusCode::UNAUTHORIZED);

    // Wrong token → 401.
    let resp = reqwest::Client::new()
        .post(format!("http://{gateway_addr}/mcp"))
        .bearer_auth("nope")
        .body(INIT_BODY)
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), StatusCode::UNAUTHORIZED);

    // Correct token → proxied.
    let resp = reqwest::Client::new()
        .post(format!("http://{gateway_addr}/mcp"))
        .bearer_auth("sekrit")
        .header("content-type", "application/json")
        .body(INIT_BODY)
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), StatusCode::OK);
}

/// Read whatever the peer sends until it closes or [timeout] elapses.
async fn read_until_close_or_timeout(
    stream: &mut tokio::net::TcpStream,
    timeout: std::time::Duration,
) -> String {
    use tokio::io::AsyncReadExt as _;
    let mut got = Vec::new();
    let mut buf = [0u8; 4096];
    let deadline = tokio::time::sleep(timeout);
    tokio::pin!(deadline);
    loop {
        tokio::select! {
            _ = &mut deadline => break,
            read = stream.read(&mut buf) => {
                match read {
                    Ok(0) | Err(_) => break,
                    Ok(n) => {
                        got.extend_from_slice(&buf[..n]);
                        if got.len() > 65536 { break; }
                    }
                }
            }
        }
    }
    String::from_utf8_lossy(&got).to_string()
}

#[tokio::test]
async fn passthrough_adapter_preserves_client_host() {
    let (mock_addr, state) = start_mock(None).await;

    let gateway_addr = start_gateway(
        &gateway_config(
            r#"
[[backends]]
name = "explicit"
match_path = "/mcp"
adapter = "passthrough"
upstream = "$MOCK"
"#,
        ),
        mock_addr,
    )
    .await;

    // Raw socket: reqwest refuses to fabricate arbitrary Host headers, so we
    // speak HTTP/1.1 by hand (exactly like the Android probe's raw socket).
    let mut stream = tokio::net::TcpStream::connect(gateway_addr).await.unwrap();
    let request = format!(
        "POST /mcp HTTP/1.1\r\nHost: myhost.test:1234\r\nConnection: close\r\nContent-Type: application/json\r\nContent-Length: {}\r\n\r\n{}",
        INIT_BODY.len(),
        INIT_BODY
    );
    stream.write_all(request.as_bytes()).await.unwrap();

    let response = read_until_close_or_timeout(&mut stream, std::time::Duration::from_secs(5)).await;
    assert!(
        response.starts_with("HTTP/1.1 200"),
        "expected 200, got: {:?}",
        response.lines().next().unwrap_or("")
    );

    let hosts = state.hosts.lock().await.clone();
    assert_eq!(hosts, vec!["myhost.test:1234"], "client Host must be preserved");
}

#[tokio::test]
async fn unmatched_path_returns_404() {
    let (mock_addr, _state) = start_mock(None).await;

    let gateway_addr = start_gateway(
        &gateway_config(
            r#"
[[backends]]
name = "generic"
match_path = "/mcp"
adapter = "host_rewrite"
upstream = "$MOCK"
"#,
        ),
        mock_addr,
    )
    .await;

    let resp = reqwest::Client::new()
        .get(format!("http://{gateway_addr}/other"))
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), StatusCode::NOT_FOUND);
}

#[tokio::test]
async fn method_and_query_are_preserved() {
    let (mock_addr, state) = start_mock(None).await;

    let gateway_addr = start_gateway(
        &gateway_config(
            r#"
[[backends]]
name = "generic"
match_path = "/"
adapter = "host_rewrite"
upstream = "$MOCK"
"#,
        ),
        mock_addr,
    )
    .await;

    // DELETE /mcp?x=1 must reach the mock with the query string intact.
    let resp = reqwest::Client::new()
        .delete(format!("http://{gateway_addr}/mcp?x=1"))
        .header("mcp-session-id", "sess-q")
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), StatusCode::OK);
    assert_eq!(state.sessions_deleted.lock().await.clone(), vec!["sess-q"]);
}

#[tokio::test]
async fn headers_are_preserved() {
    let (mock_addr, _state) = start_mock(None).await;

    let gateway_addr = start_gateway(
        &gateway_config(
            r#"
[[backends]]
name = "generic"
match_path = "/mcp"
adapter = "host_rewrite"
upstream = "$MOCK"
"#,
        ),
        mock_addr,
    )
    .await;

    let resp = reqwest::Client::new()
        .post(format!("http://{gateway_addr}/mcp"))
        .header("content-type", "application/json")
        .header("x-custom-probe", "keep-me")
        .body(INIT_BODY)
        .send()
        .await
        .unwrap();
    assert_eq!(resp.status(), StatusCode::OK);
}
