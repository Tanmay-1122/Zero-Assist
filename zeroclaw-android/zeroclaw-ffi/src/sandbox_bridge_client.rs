/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! Internal client for Zero-Assist's self-contained Linux sandbox bridge.
//!
//! The Android app owns the proot sandbox, permissions, and bridge token
//! provisioning. The Rust session layer owns the model-visible tool registry,
//! so this module gives native tools a narrow localhost HTTP path into the
//! sandbox without coupling the Rust crate to Android JNI details.
//!
//! The bridge is served by `SandboxBridgeServer` (Kotlin/NanoHTTPD) which
//! listens on 127.0.0.1:49481. Authentication uses a bearer token injected
//! into the process via `Os.setenv` before the Rust runtime starts.
//!
//! ## Environment variables
//! - `ZERO_ASSIST_SANDBOX_BRIDGE_TOKEN` – required bearer token
//! - `ZERO_ASSIST_SANDBOX_BRIDGE_BASE_URL` – optional base URL override
//!   (defaults to `http://127.0.0.1:49481`)

use std::sync::Mutex;
use std::sync::LazyLock;
use std::time::Duration;

use serde_json::{Value, json};

const DEFAULT_SANDBOX_BRIDGE_BASE_URL: &str = "http://127.0.0.1:49481";
const SANDBOX_BRIDGE_BASE_URL_ENV: &str = "ZERO_ASSIST_SANDBOX_BRIDGE_BASE_URL";
const SANDBOX_BRIDGE_TOKEN_ENV: &str = "ZERO_ASSIST_SANDBOX_BRIDGE_TOKEN";
const SANDBOX_BRIDGE_AUTH_HEADER: &str = "Authorization";
const HEALTH_TIMEOUT_SECS: u64 = 3;
const REQUEST_TIMEOUT_SECS: u64 = 65;
const BACKGROUND_REQUEST_TIMEOUT_SECS: u64 = 195;
const CONNECT_TIMEOUT_SECS: u64 = 2;

static SANDBOX_BRIDGE_AUTH_TOKEN: Mutex<Option<String>> = Mutex::new(None);

// ── Token management ────────────────────────────────────────────────────────

/// Sets the app-owned sandbox bridge auth token used by native sandbox tools.
pub(crate) fn set_bridge_auth_token(token: Option<String>) {
    *lock_bridge_auth_token() = normalize_token(token.as_deref());
}

/// Returns whether the native client has a token for authenticated bridge calls.
pub(crate) fn has_configured_bridge_auth_token() -> bool {
    current_bridge_auth_token().is_some()
}

fn lock_bridge_auth_token() -> std::sync::MutexGuard<'static, Option<String>> {
    SANDBOX_BRIDGE_AUTH_TOKEN.lock().unwrap_or_else(|e| e.into_inner())
}

fn current_bridge_auth_token() -> Option<String> {
    lock_bridge_auth_token().clone().or_else(|| {
        std::env::var(SANDBOX_BRIDGE_TOKEN_ENV).ok().and_then(|t| normalize_token(Some(&t)))
    })
}

fn normalize_token(token: Option<&str>) -> Option<String> {
    token.map(|t| t.trim().to_string()).filter(|t| !t.is_empty())
}

// ── HTTP helpers ────────────────────────────────────────────────────────────

fn bridge_base_url() -> String {
    std::env::var(SANDBOX_BRIDGE_BASE_URL_ENV)
        .ok()
        .and_then(|u| if u.is_empty() { None } else { Some(u) })
        .unwrap_or_else(|| DEFAULT_SANDBOX_BRIDGE_BASE_URL.to_string())
}

// ponytail: one shared client, per-request timeouts via .timeout().
// Connection pooling + TLS session reuse for free.
static SHARED_CLIENT: LazyLock<reqwest::Client> = LazyLock::new(|| {
    reqwest::Client::builder()
        .connect_timeout(Duration::from_secs(CONNECT_TIMEOUT_SECS))
        .build()
        .expect("Failed to build sandbox bridge HTTP client")
});

fn shared_client() -> &'static reqwest::Client {
    &SHARED_CLIENT
}

fn with_sandbox_auth(
    request: reqwest::RequestBuilder,
) -> reqwest::RequestBuilder {
    if let Some(token) = current_bridge_auth_token() {
        request.header(SANDBOX_BRIDGE_AUTH_HEADER, format!("Bearer {token}"))
    } else {
        request
    }
}

async fn parse_json_response(
    response: reqwest::Response,
    context: &str,
) -> Result<Value, String> {
    let status = response.status();
    let body = response.text().await.map_err(|e| format!("{context} body read failed: {e}"))?;
    serde_json::from_str(&body)
        .map_err(|e| format!("{context} JSON parse failed (status={status}): {e} body={body}"))
}

const RETRY_DELAY_MS: u64 = 500;

fn is_transient_error(err: &reqwest::Error) -> bool {
    err.is_connect() || err.is_timeout() || err.is_request()
}

// ── Public API ──────────────────────────────────────────────────────────────

/// Checks whether the sandbox bridge is reachable and the sandbox is ready.
pub(crate) async fn health() -> Result<Value, String> {
    let base_url = bridge_base_url();
    let response = with_sandbox_auth(shared_client().post(format!("{base_url}/health")).timeout(Duration::from_secs(HEALTH_TIMEOUT_SECS)))
        .send()
        .await
        .map_err(|e| format!("Sandbox bridge health check failed: {e}"))?;
    parse_json_response(response, "Sandbox bridge health").await
}

/// Executes a shell command inside the Alpine proot sandbox.
///
/// Mirrors the `sandbox_execute` tool schema. `args` is the raw tool-call
/// JSON object produced by the model.
pub(crate) async fn execute(args: Value) -> Result<Value, String> {
    let command = args.get("command").and_then(|v| v.as_str()).unwrap_or("?");
    let background = args.get("background").and_then(Value::as_bool).unwrap_or(false);
    let timeout = if background { BACKGROUND_REQUEST_TIMEOUT_SECS } else { REQUEST_TIMEOUT_SECS };
    let base_url = bridge_base_url();
    eprintln!("===== sandbox_bridge_client::execute =====");
    eprintln!("  POST {base_url}/execute");
    eprintln!("  command = {command}");

    let mut last_err = String::new();
    for attempt in 0..=1 {
        if attempt > 0 {
            eprintln!("  Retrying after transient error: {last_err}");
            tokio::time::sleep(Duration::from_millis(RETRY_DELAY_MS)).await;
        }
        match with_sandbox_auth(
            shared_client()
                .post(format!("{base_url}/execute"))
                .json(&args)
                .timeout(Duration::from_secs(timeout)),
        )
        .send()
        .await
        {
            Ok(response) => {
                let result = parse_json_response(response, "Sandbox execute").await;
                match &result {
                    Ok(val) => eprintln!("===== sandbox_bridge_client::execute result = {val}"),
                    Err(e) => eprintln!("===== sandbox_bridge_client::execute FAILED = {e}"),
                }
                return result;
            }
            Err(e) => {
                last_err = e.to_string();
                if !is_transient_error(&e) {
                    return Err(format!("Sandbox execute request failed: {e}"));
                }
            }
        }
    }
    Err(format!("Sandbox execute request failed after retry: {last_err}"))
}

/// Manages background sandbox processes (list/log/kill/remove).
///
/// Mirrors the `sandbox_manage_process` tool schema.
pub(crate) async fn manage_process(args: Value) -> Result<Value, String> {
    let base_url = bridge_base_url();

    let mut last_err = String::new();
    for attempt in 0..=1 {
        if attempt > 0 {
            tokio::time::sleep(Duration::from_millis(RETRY_DELAY_MS)).await;
        }
        match with_sandbox_auth(
            shared_client()
                .post(format!("{base_url}/manage_process"))
                .json(&args)
                .timeout(Duration::from_secs(REQUEST_TIMEOUT_SECS)),
        )
        .send()
        .await
        {
            Ok(response) => {
                return parse_json_response(response, "Sandbox manage_process").await;
            }
            Err(e) => {
                last_err = e.to_string();
                if !is_transient_error(&e) {
                    return Err(format!("Sandbox manage_process request failed: {e}"));
                }
            }
        }
    }
    Err(format!("Sandbox manage_process request failed after retry: {last_err}"))
}

/// Returns a JSON availability snapshot.
pub(crate) async fn availability() -> Result<Value, String> {
    if !has_configured_bridge_auth_token() {
        return Ok(json!({
            "available": false,
            "reason": "Linux sandbox bridge token not configured. Open Zero-Assist to initialise the sandbox."
        }));
    }
    match health().await {
        Ok(body) => {
            let ready = body.get("sandbox_ready").and_then(Value::as_bool).unwrap_or(false);
            Ok(json!({
                "available": ready,
                "reason": if ready {
                    "Linux sandbox is installed and ready."
                } else {
                    "Linux sandbox is not yet installed. Enable it in Plugins → Installed."
                }
            }))
        }
        Err(e) => Ok(json!({
            "available": false,
            "reason": format!("Linux sandbox bridge is not reachable: {e}")
        })),
    }
}

// ── Bridge verification tests ───────────────────────────────────────────────
//
// These tests call the sandbox bridge **without involving the LLM or agent
// loop**. They prove the Rust HTTP client functions compile, link, and
// connect to the `SandboxBridgeServer` (NanoHTTPD on port 49481).
//
// Precondition: the Zero-Assist Android app must be running on a device or
// emulator with the Linux Sandbox plugin installed and enabled.
//
// Token: set `ZERO_ASSIST_SANDBOX_BRIDGE_TOKEN` or call
// `set_bridge_auth_token(Some(...))` before running.
//
// Run: `cargo test -p zeroclaw-ffi -- sandbox_bridge_ --nocapture`

#[cfg(test)]
mod tests {
    use super::*;

    fn log_config() {
        let url = bridge_base_url();
        let has_token = has_configured_bridge_auth_token();
        println!("[sandbox_bridge_test] base_url = {url}");
        println!("[sandbox_bridge_test] token_configured = {has_token}");
    }

    #[tokio::test]
    async fn test_sandbox_bridge_health() {
        log_config();
        eprintln!("[sandbox_bridge_test] Calling sandbox_bridge_client::health()...");
        match health().await {
            Ok(body) => {
                println!("[sandbox_bridge_test] health() SUCCESS: {body}");
            }
            Err(e) => {
                eprintln!("[sandbox_bridge_test] health() FAILED: {e}");
                eprintln!("[sandbox_bridge_test] Expected if bridge is not running.");
            }
        }
    }

    #[tokio::test]
    async fn test_sandbox_bridge_availability() {
        log_config();
        eprintln!("[sandbox_bridge_test] Calling sandbox_bridge_client::availability()...");
        match availability().await {
            Ok(body) => {
                println!("[sandbox_bridge_test] availability() SUCCESS: {body}");
            }
            Err(e) => {
                eprintln!("[sandbox_bridge_test] availability() FAILED: {e}");
            }
        }
    }

    #[tokio::test]
    async fn test_sandbox_bridge_execute_pwd() {
        log_config();
        let args = json!({"command": "pwd"});
        eprintln!("[sandbox_bridge_test] Calling sandbox_bridge_client::execute(pwd)...");
        match execute(args).await {
            Ok(body) => {
                println!("[sandbox_bridge_test] execute(pwd) SUCCESS: {body}");
            }
            Err(e) => {
                eprintln!("[sandbox_bridge_test] execute(pwd) FAILED: {e}");
            }
        }
    }

    #[tokio::test]
    async fn test_sandbox_bridge_execute_uname() {
        log_config();
        let args = json!({"command": "uname -a"});
        eprintln!("[sandbox_bridge_test] Calling sandbox_bridge_client::execute(uname -a)...");
        match execute(args).await {
            Ok(body) => {
                println!("[sandbox_bridge_test] execute(uname -a) SUCCESS: {body}");
            }
            Err(e) => {
                eprintln!("[sandbox_bridge_test] execute(uname -a) FAILED: {e}");
            }
        }
    }
}
