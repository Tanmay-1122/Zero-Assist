/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! Internal client for Zero Assist's Termux bridge.
//!
//! The Android app owns Termux bootstrap, permissions, and bridge token
//! provisioning. The Rust session layer owns the model-visible tool registry,
//! so this module gives native tools a narrow localhost HTTP path into Termux.

use std::fmt::Write as _;
use std::path::Path;
use std::sync::Mutex;
use std::time::Duration;

use serde_json::{Value, json};
use sha2::{Digest, Sha256};

const DEFAULT_TERMUX_BRIDGE_BASE_URL: &str = "http://127.0.0.1:8787";
const FALLBACK_TERMUX_BRIDGE_BASE_URL: &str = "http://localhost:8787";
const TERMUX_BRIDGE_BASE_URL_ENV: &str = "ZERO_ASSIST_TERMUX_BRIDGE_BASE_URL";
const TERMUX_BRIDGE_TOKEN_ENV: &str = "ZERO_ASSIST_TERMUX_BRIDGE_TOKEN";
const TERMUX_BRIDGE_AUTH_HEADER: &str = "X-Zero-Assist-Termux-Token";
const HEALTH_TIMEOUT_SECS: u64 = 3;
const REQUEST_TIMEOUT_SECS: u64 = 35;
const CONNECT_TIMEOUT_SECS: u64 = 2;
const DEFAULT_COMMAND_TIMEOUT_SECS: u64 = 30;
const MAX_COMMAND_TIMEOUT_SECS: u64 = 120;
const DEFAULT_MAX_OUTPUT_BYTES: u64 = 64 * 1024;
const TERMUX_HOME_PREFIX: &str = "/data/data/com.termux/files/home";
const TERMUX_USR_PREFIX: &str = "/data/data/com.termux/files/usr";
const DEFAULT_TERMUX_WORKSPACE: &str = "/data/data/com.termux/files/home/.zero-assist/workspace";

static TERMUX_BRIDGE_AUTH_TOKEN: Mutex<Option<String>> = Mutex::new(None);

/// Sets the app-owned Termux bridge auth token used by native Termux tools.
pub(crate) fn set_bridge_auth_token(token: Option<String>) {
    *lock_bridge_auth_token() = normalize_bridge_auth_token(token.as_deref());
}

/// Returns whether the native client has a token for authenticated bridge calls.
pub(crate) fn has_configured_bridge_auth_token() -> bool {
    current_bridge_auth_token().is_some()
}

/// Fetches the Termux bridge capability map as JSON.
pub(crate) async fn capabilities() -> Result<Value, String> {
    let base_urls = bridge_base_urls();
    let (base_url, _reason) = probe_first_available_bridge(&base_urls).await?;
    let client = build_client(REQUEST_TIMEOUT_SECS)?;
    let response = with_bridge_auth(client.get(format!("{base_url}/capabilities")))
        .send()
        .await
        .map_err(|error| format!("Termux capability request failed: {error}"))?;
    parse_json_response(response, "Termux capability request").await
}

/// Executes one low-risk argv command through the Termux bridge.
pub(crate) async fn execute_low_risk(args: Value) -> Result<Value, String> {
    let request = prepare_termux_run_request(&args)?;
    let payload = match request {
        PreparedTermuxRun::Execute(payload) => payload,
        PreparedTermuxRun::ApprovalRequired(value) => return Ok(value),
    };
    let base_urls = bridge_base_urls();
    let (base_url, _reason) = probe_first_available_bridge(&base_urls).await?;
    let client = build_client(REQUEST_TIMEOUT_SECS)?;
    let response = with_bridge_auth(client.post(format!("{base_url}/execute")))
        .json(&payload)
        .send()
        .await
        .map_err(|error| format!("Termux command request failed: {error}"))?;
    parse_json_response(response, "Termux command request").await
}

async fn probe_bridge_health(base_url: &str) -> Result<String, String> {
    let client = build_client(HEALTH_TIMEOUT_SECS)?;
    let response = with_bridge_auth(client.get(format!("{base_url}/health")))
        .send()
        .await
        .map_err(|error| format!("Termux bridge health probe failed: {error}"))?;
    let body = parse_json_response(response, "Termux bridge health probe").await?;
    let ready = body.get("ready").and_then(Value::as_bool).unwrap_or(false)
        || body
            .get("status")
            .and_then(Value::as_str)
            .is_some_and(|status| status.eq_ignore_ascii_case("ready"));
    let reason = body
        .get("reason")
        .and_then(Value::as_str)
        .unwrap_or("Termux bridge health endpoint is not ready.")
        .to_string();
    if ready { Ok(reason) } else { Err(reason) }
}

async fn probe_first_available_bridge(base_urls: &[String]) -> Result<(String, String), String> {
    let mut failures = Vec::new();
    for base_url in base_urls {
        match probe_bridge_health(base_url).await {
            Ok(reason) => return Ok((base_url.clone(), reason)),
            Err(reason) => failures.push(format!("{base_url}: {reason}")),
        }
    }

    Err(if failures.is_empty() {
        "Termux bridge is not configured.".to_string()
    } else {
        format!(
            "Termux bridge is not reachable. Attempted endpoints: {}. Failures: {}",
            base_urls.join(", "),
            failures.join("; ")
        )
    })
}

enum PreparedTermuxRun {
    Execute(Value),
    ApprovalRequired(Value),
}

fn prepare_termux_run_request(args: &Value) -> Result<PreparedTermuxRun, String> {
    let command = args
        .get("command")
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .ok_or_else(|| "termux_run requires a non-empty command.".to_string())?;
    let arguments = args
        .get("arguments")
        .map(|value| parse_string_arguments(command, value))
        .transpose()?
        .unwrap_or_default();
    let working_directory = args
        .get("working_directory")
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(normalize_working_directory_for_fingerprint);
    let effective_working_directory = working_directory
        .clone()
        .unwrap_or_else(|| DEFAULT_TERMUX_WORKSPACE.to_string());
    match classify_command(command, &arguments, &effective_working_directory) {
        TermuxRiskDecision::Low(_reason) => {
            validate_low_risk_command(command, &arguments)?;
            Ok(PreparedTermuxRun::Execute(build_execute_payload(
                args,
                command,
                arguments,
                working_directory,
            )))
        }
        TermuxRiskDecision::Medium(reason) => {
            Ok(PreparedTermuxRun::ApprovalRequired(approval_payload(
                command,
                &arguments,
                &effective_working_directory,
                "MEDIUM",
                &reason,
            )))
        }
        TermuxRiskDecision::High(reason) => {
            Ok(PreparedTermuxRun::ApprovalRequired(approval_payload(
                command,
                &arguments,
                &effective_working_directory,
                "HIGH",
                &reason,
            )))
        }
    }
}

fn build_execute_payload(
    args: &Value,
    command: &str,
    arguments: Vec<String>,
    working_directory: Option<String>,
) -> Value {
    let timeout_seconds = args
        .get("timeout_seconds")
        .and_then(Value::as_u64)
        .unwrap_or(DEFAULT_COMMAND_TIMEOUT_SECS)
        .clamp(1, MAX_COMMAND_TIMEOUT_SECS);

    let mut command_argv = vec![Value::String(command.to_string())];
    command_argv.extend(arguments.into_iter().map(Value::String));
    let mut payload = json!({
        "argv": command_argv,
        "timeout_seconds": timeout_seconds,
        "max_output_bytes": DEFAULT_MAX_OUTPUT_BYTES,
    });
    if let Some(working_directory) = working_directory {
        payload["working_directory"] = Value::String(working_directory);
    }
    payload
}

enum TermuxRiskDecision {
    Low(String),
    Medium(String),
    High(String),
}

fn classify_command(
    command: &str,
    arguments: &[String],
    working_directory: &str,
) -> TermuxRiskDecision {
    let executable = executable_name(command);
    if std::iter::once(command)
        .chain(arguments.iter().map(String::as_str))
        .any(contains_shell_fragment)
    {
        return TermuxRiskDecision::High(
            "Command uses shell chaining, redirection, or command substitution and needs user approval.".to_string(),
        );
    }
    if matches!(
        executable.as_str(),
        "bash" | "fish" | "login" | "proot" | "sh" | "su" | "termux-chroot" | "tsu" | "zsh"
    ) {
        return TermuxRiskDecision::High(format!(
            "Interactive shells, privilege escalation, and package chroots need explicit user approval: {executable}."
        ));
    }
    if validate_working_directory(working_directory).is_err() {
        return TermuxRiskDecision::High(
            "Working directory is outside the standard Termux home or usr paths and needs explicit user approval.".to_string(),
        );
    }
    if validate_low_risk_command(command, arguments).is_ok() {
        return TermuxRiskDecision::Low(
            "Command is a bounded diagnostic or read-only inspection command.".to_string(),
        );
    }
    if is_high_risk_command(&executable)
        || arguments
            .iter()
            .any(|argument| is_high_risk_argument(argument))
    {
        return TermuxRiskDecision::High(
            "Command can alter files, processes, packages, networking, or device state."
                .to_string(),
        );
    }
    TermuxRiskDecision::Medium(
        "Command is not on the low-risk diagnostic list and needs approval before execution."
            .to_string(),
    )
}

fn approval_payload(
    command: &str,
    arguments: &[String],
    working_directory: &str,
    risk: &str,
    reason: &str,
) -> Value {
    let fingerprint = approval_fingerprint(command, arguments, working_directory);
    json!({
        "success": false,
        "approval_required": true,
        "blocked": false,
        "request_id": format!("zatap_{}", &fingerprint[..12]),
        "command": command,
        "arguments": arguments,
        "working_directory": working_directory,
        "risk": risk,
        "reason": reason,
        "fingerprint": fingerprint,
        "instruction": "Ask the user for Allow once or Try safer way before running this exact Termux command.",
    })
}

fn approval_fingerprint(command: &str, arguments: &[String], working_directory: &str) -> String {
    let mut argv = vec![command.trim().to_string()];
    argv.extend(arguments.iter().map(|argument| argument.trim().to_string()));
    let canonical = format!(
        "termux-v1\nargv={}\nworking_directory={}",
        serde_json::to_string(&argv).unwrap_or_else(|_| "[]".to_string()),
        serde_json::to_string(working_directory).unwrap_or_else(|_| "\"\"".to_string())
    );
    let digest = Sha256::digest(canonical.as_bytes());
    digest.iter().fold(String::new(), |mut output, byte| {
        let _ = write!(output, "{byte:02x}");
        output
    })
}

fn executable_name(command: &str) -> String {
    Path::new(command)
        .file_name()
        .and_then(|value| value.to_str())
        .unwrap_or(command)
        .to_lowercase()
}

fn is_high_risk_command(executable: &str) -> bool {
    matches!(
        executable,
        "apt"
            | "chmod"
            | "chown"
            | "curl"
            | "dd"
            | "git"
            | "kill"
            | "ln"
            | "mkfs"
            | "mv"
            | "nano"
            | "nc"
            | "node"
            | "npm"
            | "pkg"
            | "python"
            | "python3"
            | "rm"
            | "ssh"
            | "tar"
            | "termux-open"
            | "wget"
    )
}

fn is_high_risk_argument(argument: &str) -> bool {
    matches!(
        argument.trim(),
        "--force" | "-f" | "-rf" | "-fr" | "--recursive"
    )
}

fn parse_string_arguments(command: &str, value: &Value) -> Result<Vec<String>, String> {
    if let Some(raw) = value.as_str() {
        let trimmed = raw.trim();
        if trimmed.is_empty() {
            return Ok(Vec::new());
        }
        return if executable_name(command) == "touch" {
            Ok(trimmed
                .split_whitespace()
                .filter(|part| !part.is_empty())
                .map(str::to_string)
                .collect())
        } else {
            Ok(vec![trimmed.to_string()])
        };
    }

    let array = value
        .as_array()
        .ok_or_else(|| "arguments must be a string or an array of strings.".to_string())?;
    flatten_string_arguments(array, 0)
}

fn flatten_string_arguments(array: &[Value], depth: usize) -> Result<Vec<String>, String> {
    if depth > 1 {
        return Err("arguments must be a flat array of strings.".to_string());
    }

    let mut parsed = Vec::new();
    for item in array {
        if let Some(raw) = item.as_str() {
            let trimmed = raw.trim();
            if !trimmed.is_empty() {
                parsed.push(trimmed.to_string());
            }
        } else if let Some(nested) = item.as_array() {
            parsed.extend(flatten_string_arguments(nested, depth + 1)?);
        } else {
            return Err("arguments must contain only non-empty strings.".to_string());
        }
    }
    Ok(parsed)
}

fn validate_working_directory(raw: &str) -> Result<String, String> {
    let value = raw.trim().trim_end_matches('/');
    if is_at_or_inside(value, TERMUX_HOME_PREFIX) || is_at_or_inside(value, TERMUX_USR_PREFIX) {
        Ok(value.to_string())
    } else {
        Err("working_directory must stay inside the Termux home or usr directories.".to_string())
    }
}

fn normalize_working_directory_for_fingerprint(raw: &str) -> String {
    let trimmed = raw.trim();
    let without_trailing_slashes = trimmed.trim_end_matches('/');
    if without_trailing_slashes.is_empty() {
        trimmed.to_string()
    } else {
        without_trailing_slashes.to_string()
    }
}

fn is_at_or_inside(path: &str, prefix: &str) -> bool {
    path == prefix || path.starts_with(&format!("{prefix}/"))
}

fn validate_low_risk_command(command: &str, arguments: &[String]) -> Result<(), String> {
    let executable = executable_name(command);
    if std::iter::once(command)
        .chain(arguments.iter().map(String::as_str))
        .any(contains_shell_fragment)
    {
        return Err(
            "shell chaining, redirection, and command substitution require user approval."
                .to_string(),
        );
    }

    let args: Vec<&str> = arguments.iter().map(String::as_str).collect();
    let allowed = match executable.as_str() {
        "python" | "python3" => matches!(args.as_slice(), ["--version" | "-V"]),
        "touch" => is_low_risk_touch_arguments(arguments),
        "uname" => matches!(args.as_slice(), [] | ["-a" | "-m" | "-r" | "-s"]),
        "date" | "false" | "id" | "pwd" | "true" | "whoami" => args.is_empty(),
        _ => false,
    };
    if allowed {
        Ok(())
    } else {
        Err(
            "Only bounded low-risk diagnostic Termux commands run without approval; other commands require user approval."
                .to_string(),
        )
    }
}

fn is_low_risk_touch_arguments(arguments: &[String]) -> bool {
    !arguments.is_empty()
        && arguments.iter().all(|argument| {
            let trimmed = argument.trim();
            !trimmed.is_empty()
                && !trimmed.starts_with('-')
                && !trimmed.starts_with('/')
                && !trimmed.contains("..")
                && !trimmed.contains('\\')
        })
}

fn contains_shell_fragment(value: &str) -> bool {
    let lowered = value.to_lowercase();
    [
        "&&", "||", ";", "`", "$(", "<<", ">>", ">/", "</", "| sh", "| bash",
    ]
    .iter()
    .any(|fragment| lowered.contains(fragment))
}

async fn parse_json_response(response: reqwest::Response, label: &str) -> Result<Value, String> {
    let status = response.status();
    let text = response
        .text()
        .await
        .map_err(|error| format!("{label} failed to read response body: {error}"))?;
    if !status.is_success() {
        let detail = extract_error_message(&text).unwrap_or_else(|| text.clone());
        return Err(if detail.is_empty() {
            format!("{label} returned HTTP {status}.")
        } else {
            format!("{label} returned HTTP {status}: {detail}")
        });
    }
    serde_json::from_str(&text).map_err(|error| format!("{label} returned invalid JSON: {error}"))
}

fn extract_error_message(body_text: &str) -> Option<String> {
    let body: Value = serde_json::from_str(body_text).ok()?;
    body.get("error")
        .and_then(|error| {
            error
                .get("message")
                .or_else(|| error.get("reason"))
                .and_then(Value::as_str)
        })
        .or_else(|| body.get("message").and_then(Value::as_str))
        .or_else(|| body.get("reason").and_then(Value::as_str))
        .map(str::to_string)
}

fn build_client(timeout_secs: u64) -> Result<reqwest::Client, String> {
    reqwest::Client::builder()
        .timeout(Duration::from_secs(timeout_secs))
        .connect_timeout(Duration::from_secs(CONNECT_TIMEOUT_SECS))
        .build()
        .map_err(|error| format!("Failed to build Termux bridge HTTP client: {error}"))
}

fn with_bridge_auth(request: reqwest::RequestBuilder) -> reqwest::RequestBuilder {
    match current_bridge_auth_token() {
        Some(token) => request.header(TERMUX_BRIDGE_AUTH_HEADER, token),
        None => request,
    }
}

fn bridge_base_urls() -> Vec<String> {
    if let Some(explicit_base_url) = std::env::var(TERMUX_BRIDGE_BASE_URL_ENV)
        .ok()
        .and_then(|value| normalize_bridge_base_url(&value))
    {
        return vec![explicit_base_url];
    }
    [
        DEFAULT_TERMUX_BRIDGE_BASE_URL,
        FALLBACK_TERMUX_BRIDGE_BASE_URL,
    ]
    .into_iter()
    .filter_map(normalize_bridge_base_url)
    .collect()
}

fn normalize_bridge_base_url(raw_base_url: &str) -> Option<String> {
    let normalized = raw_base_url.trim().trim_end_matches('/');
    if normalized.is_empty() {
        return None;
    }
    let url = reqwest::Url::parse(normalized).ok()?;
    if url.scheme() != "http" {
        return None;
    }
    if !url.username().is_empty() || url.password().is_some() {
        return None;
    }
    if url.query().is_some() || url.fragment().is_some() {
        return None;
    }
    if !matches!(url.path(), "" | "/") {
        return None;
    }
    if !matches!(url.host_str()?, "127.0.0.1" | "localhost" | "::1" | "[::1]") {
        return None;
    }
    Some(normalized.to_string())
}

fn bridge_auth_token_from_env() -> Option<String> {
    normalize_bridge_auth_token(std::env::var(TERMUX_BRIDGE_TOKEN_ENV).ok().as_deref())
}

fn current_bridge_auth_token() -> Option<String> {
    configured_bridge_auth_token().or_else(bridge_auth_token_from_env)
}

fn configured_bridge_auth_token() -> Option<String> {
    lock_bridge_auth_token().clone()
}

fn lock_bridge_auth_token() -> std::sync::MutexGuard<'static, Option<String>> {
    TERMUX_BRIDGE_AUTH_TOKEN.lock().unwrap_or_else(|error| {
        tracing::warn!("Termux bridge auth token mutex was poisoned; recovering: {error}");
        error.into_inner()
    })
}

fn normalize_bridge_auth_token(raw_token: Option<&str>) -> Option<String> {
    raw_token
        .map(str::trim)
        .filter(|token| !token.is_empty())
        .map(str::to_string)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn auth_token_trims_blank_values() {
        assert_eq!(normalize_bridge_auth_token(None), None);
        assert_eq!(normalize_bridge_auth_token(Some("   ")), None);
        assert_eq!(
            normalize_bridge_auth_token(Some(" token ")).as_deref(),
            Some("token")
        );
    }

    #[test]
    fn bridge_base_url_allows_loopback_http_only() {
        assert_eq!(
            normalize_bridge_base_url(" http://127.0.0.1:8787/ ").as_deref(),
            Some("http://127.0.0.1:8787")
        );
        assert_eq!(
            normalize_bridge_base_url("http://localhost:8787").as_deref(),
            Some("http://localhost:8787")
        );
        assert_eq!(
            normalize_bridge_base_url("http://[::1]:8787").as_deref(),
            Some("http://[::1]:8787")
        );
    }

    #[test]
    fn bridge_base_url_rejects_non_local_or_augmented_urls() {
        for raw_base_url in [
            "",
            "https://127.0.0.1:8787",
            "http://192.168.1.50:8787",
            "http://127.0.0.1:8787/bridge",
            "http://user@127.0.0.1:8787",
            "http://127.0.0.1:8787?token=secret",
            "http://127.0.0.1:8787#fragment",
        ] {
            assert_eq!(normalize_bridge_base_url(raw_base_url), None);
        }
    }

    #[test]
    fn working_directory_rejects_prefix_lookalikes() {
        assert!(validate_working_directory("/data/data/com.termux/files/home/project").is_ok());
        assert!(
            validate_working_directory("/data/data/com.termux/files/homeevil").is_err(),
            "prefix lookalikes must not pass"
        );
    }

    #[test]
    fn low_risk_policy_allows_diagnostics_only() {
        assert!(validate_low_risk_command("uname", &["-a".to_string()]).is_ok());
        assert!(validate_low_risk_command("python3", &["--version".to_string()]).is_ok());
        assert!(
            validate_low_risk_command("touch", &["test1.txt".to_string(), "test2.txt".to_string()])
                .is_ok()
        );
        assert!(
            validate_low_risk_command("python3", &["-c".to_string(), "print(1)".to_string()])
                .is_err()
        );
        assert!(validate_low_risk_command("rm", &["-rf".to_string(), "x".to_string()]).is_err());
        assert!(validate_low_risk_command("uname", &["-a;whoami".to_string()]).is_err());
        assert!(validate_low_risk_command("touch", &["../outside.txt".to_string()]).is_err());
    }

    #[test]
    fn touch_with_multiple_files_executes_directly() {
        let PreparedTermuxRun::Execute(payload) = prepare_termux_run_request(&json!({
            "command": "touch",
            "arguments": ["test1.txt", "test2.txt"],
            "working_directory": DEFAULT_TERMUX_WORKSPACE,
        }))
        .expect("touch payload should build") else {
            panic!("simple touch command should execute directly");
        };

        assert_eq!(payload["argv"], json!(["touch", "test1.txt", "test2.txt"]));
    }

    #[test]
    fn touch_string_arguments_are_split_for_recovery() {
        let PreparedTermuxRun::Execute(payload) = prepare_termux_run_request(&json!({
            "command": "touch",
            "arguments": "test1.txt test2.txt",
            "working_directory": DEFAULT_TERMUX_WORKSPACE,
        }))
        .expect("touch payload should build") else {
            panic!("simple touch command should execute directly");
        };

        assert_eq!(payload["argv"], json!(["touch", "test1.txt", "test2.txt"]));
    }

    #[test]
    fn heredoc_like_arguments_request_approval_instead_of_execution() {
        let PreparedTermuxRun::ApprovalRequired(payload) = prepare_termux_run_request(&json!({
            "command": "python3",
            "arguments": ["- <<'PY'\nprint('hi')\nPY"],
        }))
        .expect("request should classify") else {
            panic!("heredoc-like command should request approval");
        };

        assert_eq!(payload["approval_required"], true);
        assert_eq!(payload["risk"], "HIGH");
    }

    #[test]
    fn execute_payload_uses_argv_contract() {
        let PreparedTermuxRun::Execute(payload) = prepare_termux_run_request(&json!({
            "command": "python3",
            "arguments": ["--version"],
            "working_directory": "/data/data/com.termux/files/home",
            "timeout_seconds": 999,
        }))
        .expect("payload should build") else {
            panic!("low-risk command should execute directly");
        };

        assert_eq!(payload["argv"], json!(["python3", "--version"]));
        assert_eq!(payload["timeout_seconds"], MAX_COMMAND_TIMEOUT_SECS);
        assert_eq!(
            payload["working_directory"],
            "/data/data/com.termux/files/home"
        );
    }

    #[test]
    fn medium_risk_command_returns_approval_request() {
        let PreparedTermuxRun::ApprovalRequired(payload) = prepare_termux_run_request(&json!({
            "command": "zero-assist-helper",
            "arguments": ["--status"],
        }))
        .expect("request should classify") else {
            panic!("medium-risk command should request approval");
        };

        assert_eq!(payload["approval_required"], true);
        assert_eq!(payload["risk"], "MEDIUM");
        assert_eq!(payload["working_directory"], DEFAULT_TERMUX_WORKSPACE);
        assert!(payload["fingerprint"].as_str().unwrap_or_default().len() == 64);
    }

    #[test]
    fn shell_command_returns_approval_request() {
        let PreparedTermuxRun::ApprovalRequired(payload) = prepare_termux_run_request(&json!({
            "command": "sh",
            "arguments": ["-c", "echo unsafe"],
        }))
        .expect("request should classify") else {
            panic!("shell command should request approval");
        };

        assert_eq!(payload["blocked"], false);
        assert_eq!(payload["approval_required"], true);
        assert_eq!(payload["risk"], "HIGH");
    }
}
