/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! Live agent session management with streaming tool-call loop integration.
//!
//! A session represents a single multi-turn conversation with the `ZeroClaw`
//! agent loop. The lifecycle follows a strict state machine:
//!
//! 1. **Start** -- [`session_start`](crate::session_start) creates a new
//!    session, parsing daemon config and building the system prompt.
//! 2. **Seed** -- optional: inject prior context via
//!    [`session_seed_history`](crate::session_seed_history).
//! 3. **Send** -- [`session_send`](crate::session_send) runs the full
//!    tool-call loop, streaming progress deltas through an
//!    [`FfiSessionListener`] callback.
//! 4. **Cancel / Clear** -- abort the current send or wipe history.
//! 5. **History** -- [`session_history`](crate::session_history) returns
//!    the conversation transcript.
//! 6. **Destroy** -- [`session_destroy`](crate::session_destroy) tears
//!    down the session and releases all resources.
//!
//! Only one session exists at a time (guarded by the [`SESSION`] mutex).

// Remove these allows once all session functions are wired in lib.rs.
#![allow(dead_code)]

mod google_workspace_tools;
mod http_tools;
mod mcp_status_tool;
mod memory_tools;
mod sandbox_tools;
mod streaming;
mod termux_tools;

use std::collections::HashMap;
use std::fmt::Write;
use std::hash::{Hash, Hasher};
use std::sync::{Arc, Mutex};

use tokio_util::sync::CancellationToken;
use zeroclaw::agent::dispatcher::ToolDispatcher;
use zeroclaw::agent::thinking::{
    apply_thinking_level, clamp_temperature, parse_thinking_directive, resolve_thinking_level,
};
use zeroclaw::memory::Memory;
use zeroclaw::providers::traits::build_tool_instructions_text;
use zeroclaw::providers::{ChatMessage, ChatRequest, ChatResponse, Provider, ToolCall};
use zeroclaw::tools::{Tool, ToolResult, ToolSpec};
use zeroclaw_config::scattered_types::ThinkingLevel;

use crate::config_compat;
use crate::error::FfiError;
use crate::runtime::{clone_daemon_config, clone_daemon_memory};
use crate::url_helpers;

use google_workspace_tools::FfiGoogleWorkspaceTool;
use http_tools::{FfiHttpRequestTool, FfiWebFetchTool};
use mcp_status_tool::FfiMcpStatusTool;
use memory_tools::{FfiMemoryForgetTool, FfiMemoryStoreTool};
use sandbox_tools::{FfiSandboxExecuteTool, FfiSandboxManageProcessTool};
use zeroclaw_runtime::capabilities::shell_runtime::ShellRuntime;
use streaming::{stream_response_text, truncate_tool_args_hint};
use termux_tools::{FfiTermuxCapabilitiesTool, FfiTermuxRunTool};

/// Env var published by the Kotlin app when the user opts into Termux.
///
/// The sandbox is the canonical shell backend: unless the user explicitly
/// enables Termux, the `termux_run` / `termux_get_capabilities` tools are
/// NOT registered, so the model can only route shell work through
/// `sandbox_execute` inside the isolated Alpine environment.
const TERMUX_ENABLED_ENV: &str = "ZERO_ASSIST_TERMUX_ENABLED";

/// Maximum user message size in bytes (1 MiB).
const MAX_MESSAGE_BYTES: usize = 1_048_576;

/// Whether the Termux bridge tools should be registered for this session.
///
/// Reads `ZERO_ASSIST_TERMUX_ENABLED` published by the Kotlin layer
/// (`AppSettings.termuxEnabled`). Absent/empty values count as disabled.
fn termux_enabled() -> bool {
    std::env::var(TERMUX_ENABLED_ENV)
        .map(|value| parse_termux_enabled_flag(&value))
        .unwrap_or(false)
}

/// Parses the `ZERO_ASSIST_TERMUX_ENABLED` env value.
fn parse_termux_enabled_flag(value: &str) -> bool {
    let trimmed = value.trim();
    !trimmed.is_empty()
        && !matches!(
            trimmed.to_ascii_lowercase().as_str(),
            "0" | "false" | "no"
        )
}

/// Default maximum agentic tool-use iterations per user message.
const DEFAULT_MAX_TOOL_ITERATIONS: usize = 10;

/// Non-system message count threshold that triggers auto-compaction.
const DEFAULT_MAX_HISTORY_MESSAGES: usize = 50;

/// Number of most-recent non-system messages to keep after compaction.
const COMPACTION_KEEP_RECENT: usize = 20;

/// Safety cap for the compaction source transcript sent to the summariser.
const COMPACTION_MAX_SOURCE_CHARS: usize = 12_000;

/// Maximum characters retained in the stored compaction summary.
const COMPACTION_MAX_SUMMARY_CHARS: usize = 2_000;

/// Minimum characters per chunk when streaming the final response text.
const STREAM_CHUNK_MIN_CHARS: usize = 80;

/// Maximum number of seed messages accepted by [`session_seed_inner`].
const MAX_SEED_MESSAGES: usize = 200;

/// Monotonically increasing request ID generator.
/// Each session_send_inner call gets a unique ID.
static NEXT_REQUEST_ID: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(1);

/// The request_id of the session_send_inner call that currently owns the
/// session history (i.e. is between take and put_back). 0 = none.
static HISTORY_OWNER_REQ_ID: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

/// The request_id of the session_send_inner call currently executing
/// inside provider.chat() / run_agent_loop. 0 = none.
static PROVIDER_OWNER_REQ_ID: std::sync::atomic::AtomicU64 = std::sync::atomic::AtomicU64::new(0);

/// Concurrent send counter for detecting overlapping session_send_inner calls.
/// Incremented on entry, decremented on exit. If >1 at any point, concurrent
/// access is occurring.
static CONCURRENT_SEND_COUNT: std::sync::atomic::AtomicU32 = std::sync::atomic::AtomicU32::new(0);

/// Microsecond timestamp for instrumentation logging.
fn ts_us() -> u128 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_micros()
}

/// Compute a hash of conversation history for change detection.
fn history_hash(history: &[ChatMessage]) -> u64 {
    let mut hasher = std::collections::hash_map::DefaultHasher::new();
    for msg in history {
        msg.role.hash(&mut hasher);
        msg.content.hash(&mut hasher);
    }
    hasher.finish()
}

/// The global singleton session slot.
///
/// At most one [`Session`] is active at any time. Operations that require
/// a running session acquire this mutex and return
/// [`FfiError::StateError`] when the slot is `None`.
static SESSION: Mutex<Option<Session>> = Mutex::new(None);

const COMPOSIO_SESSIONS_TOOL_GUIDANCE: &str = r"## Composio Sessions Tool Use

The configured Composio key is a Sessions/MCP consumer key. Use the MCP tools exposed through `tool_search`; do not call the legacy `composio` or `run_composio_tool` tool names.

For Gmail, YouTube, and other Composio actions:
- First call `tool_search` with the app and action words, or `select:<exact_tool_name>` when you already know the exact MCP tool name.
- For broad capability questions, connected-tool listing, or vague app requests, use broader queries such as `youtube`, `youtube comments`, `youtube captions`, `youtube channel statistics`, or `gmail send email`, and set `max_results` high enough to inspect the app surface.
- After `tool_search` returns schemas, call only the exact returned tool name and match its schema exactly.
- `tool_search` only loads tool schemas. It does not execute the action, send an email, post a comment, or change external state.
- Do not tell the user that an email/message/post was sent unless a concrete `composio__...` action tool was called and returned success.
- If one `tool_search` query returns no match, do not conclude Composio lacks the capability until you retry once with broader app/action wording.
- Do not use `COMPOSIO_MANAGE_CONNECTIONS` unless the user asks to inspect or manage connections.
- Prefer the specific app/action tool over broad remote workbench or multi-execute tools. Use those broad tools only when the returned schema says they are required.
- If a Composio tool fails, read the tool output and fix the arguments before retrying. Do not repeat the same failed call unchanged.
- If a Composio legacy name returns guidance, do not retry that name. Use `tool_search` once, then use the exact returned tool name or answer directly if no suitable tool is available.
";

const COMPOSIO_SESSIONS_UNAVAILABLE_GUIDANCE: &str = r"## Composio Sessions Unavailable

Composio Sessions is configured, but the Composio MCP catalog was not loaded for this session. Do not call `tool_search`, `composio`, `run_composio_tool`, or guessed `composio__...` tool names. If the user asks for a Composio action, explain that Composio is not available in this session and ask them to verify the MCP consumer key, network access, and Composio connection status before retrying.
";

const COMPOSIO_SESSIONS_ENDPOINT: &str = "https://connect.composio.dev/mcp";
const COMPOSIO_REST_ENDPOINT: &str = "https://backend.composio.dev/api/v3";

/// The cancellation token for the currently active [`session_send_inner`] call.
///
/// Set at the start of `session_send_inner`, cleared on exit. Calling
/// [`session_cancel_inner`] cancels the token, causing the agent loop
/// to abort at the next check point.
static CANCEL_TOKEN: Mutex<Option<CancellationToken>> = Mutex::new(None);

/// Locks the [`SESSION`] mutex, recovering from poison if a prior holder panicked.
///
/// See [`crate::runtime::lock_daemon`] for the rationale behind poison recovery.
fn lock_session() -> std::sync::MutexGuard<'static, Option<Session>> {
    SESSION.lock().unwrap_or_else(|e| {
        tracing::warn!("Session mutex was poisoned; recovering: {e}");
        e.into_inner()
    })
}

/// Locks the [`CANCEL_TOKEN`] mutex, recovering from poison.
fn lock_cancel_token() -> std::sync::MutexGuard<'static, Option<CancellationToken>> {
    CANCEL_TOKEN.lock().unwrap_or_else(|e| {
        tracing::warn!("Cancel token mutex was poisoned; recovering: {e}");
        e.into_inner()
    })
}

fn configured_composio_key(config: &zeroclaw::Config) -> Option<&str> {
    config
        .composio
        .api_key
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
}

fn composio_key_kind(config: &zeroclaw::Config) -> &'static str {
    let Some(key) = configured_composio_key(config) else {
        return "none";
    };

    if config_compat::is_composio_sessions_key(key) {
        "sessions"
    } else if config_compat::is_composio_cli_user_key(key) {
        "cli_user"
    } else {
        "project"
    }
}

fn composio_route_label(config: &zeroclaw::Config) -> &'static str {
    if !config.composio.enabled {
        return "disabled";
    }

    let Some(key) = configured_composio_key(config) else {
        return "unconfigured";
    };

    if config_compat::is_composio_sessions_key(key) {
        "sessions_mcp_tool_search"
    } else if config_compat::is_composio_cli_user_key(key) {
        "unsupported_cli_user_key"
    } else {
        "legacy_rest_composio"
    }
}

fn composio_route_endpoint(config: &zeroclaw::Config) -> &'static str {
    match composio_route_label(config) {
        "sessions_mcp_tool_search" => COMPOSIO_SESSIONS_ENDPOINT,
        "legacy_rest_composio" => COMPOSIO_REST_ENDPOINT,
        _ => "none",
    }
}

fn composio_key_fingerprint(key: Option<&str>) -> String {
    let Some(key) = key.map(str::trim).filter(|value| !value.is_empty()) else {
        return "none".to_string();
    };

    let mut hash = 0xcbf2_9ce4_8422_2325u64;
    for byte in key.bytes() {
        hash ^= u64::from(byte);
        hash = hash.wrapping_mul(0x0000_0100_0000_01b3);
    }
    format!("fnv64:{hash:016x}")
}

fn log_composio_route(config: &zeroclaw::Config) {
    tracing::info!(
        target: "zeroclaw_android::composio",
        enabled = config.composio.enabled,
        key_kind = composio_key_kind(config),
        route = composio_route_label(config),
        endpoint = composio_route_endpoint(config),
        entity_id = %config.composio.entity_id,
        key_fingerprint = %composio_key_fingerprint(config.composio.api_key.as_deref()),
        "Composio route resolved"
    );
}

// ── FFI tool implementations ────────────────────────────────────────────
/// Constructs a failed [`ToolResult`] with the given error message.
fn fail_result(error: String) -> ToolResult {
    ToolResult {
        success: false,
        output: String::new(),
        error: Some(error),
    metadata: None,
    }
}

/// Builds the tools registry for the Android agent session.
///
/// Constructs tools that are available without upstream's `SecurityPolicy`:
/// - Memory tools (store, recall, forget) via FFI wrappers and upstream
/// - Cron listing tools via upstream constructors
/// - Web search via upstream constructor (when enabled in config)
/// - Web fetch via FFI wrapper (when enabled in config)
/// - HTTP request via FFI wrapper (when enabled in config)
///
/// Tools that require `SecurityPolicy` (shell, file I/O, git, browser) are
/// excluded because the upstream security module is `pub(crate)`. These
/// tools are also less relevant on Android where the OS sandbox provides
/// security boundaries.
/// Maximum number of tools registered in a single session.
///
/// Prevents excessive token consumption when many plugins are enabled.
/// The LLM receives tool specs as part of the system prompt; each tool
/// costs 200-500 tokens. Beyond this limit, lower-priority tools are
/// silently dropped and a warning is logged.
const MAX_SESSION_TOOLS: usize = 20;

type ActivatedMcpToolsHandle = Arc<Mutex<zeroclaw::tools::ActivatedToolSet>>;

/// Snapshot of per-server MCP connection results from the last session
/// start. Populated by [`append_mcp_tools_from_config`] and read by the
/// `mcp_status` tool so the model can report configured-but-unreachable
/// servers.
static MCP_STATUS_SNAPSHOT: std::sync::OnceLock<tokio::sync::Mutex<Vec<zeroclaw::tools::McpServerStatus>>> =
    std::sync::OnceLock::new();
static MCP_REGISTRY_HANDLE: std::sync::OnceLock<tokio::sync::Mutex<Option<Arc<zeroclaw::tools::McpRegistry>>>> =
    std::sync::OnceLock::new();

async fn set_mcp_status_snapshot(statuses: Vec<zeroclaw::tools::McpServerStatus>) {
    let lock = MCP_STATUS_SNAPSHOT.get_or_init(|| tokio::sync::Mutex::new(Vec::new()));
    let mut guard = lock.lock().await;
    *guard = statuses;
}

async fn set_mcp_registry_handle(registry: Arc<zeroclaw::tools::McpRegistry>) {
    let lock = MCP_REGISTRY_HANDLE.get_or_init(|| tokio::sync::Mutex::new(None));
    let mut guard = lock.lock().await;
    *guard = Some(registry);
}

pub(super) async fn get_live_mcp_statuses() -> Vec<zeroclaw::tools::McpServerStatus> {
    if let Some(lock) = MCP_REGISTRY_HANDLE.get() {
        let guard = lock.lock().await;
        if let Some(ref registry) = *guard {
            return registry.statuses();
        }
    }
    let snapshot_lock = MCP_STATUS_SNAPSHOT.get_or_init(|| tokio::sync::Mutex::new(Vec::new()));
    snapshot_lock.lock().await.clone()
}

/// Builds a system-prompt section listing configured MCP servers and
/// their connection status, so the model can answer connectivity
/// questions accurately.
fn build_mcp_status_section(statuses: &[zeroclaw::tools::McpServerStatus]) -> String {
    let mut section = String::new();
    section.push_str("\n\nConfigured MCP servers:\n");
    for s in statuses {
        let sanitized_name = zeroclaw::tools::sanitize_identifier(&s.name);
        if s.connected {
            let _ = writeln!(
                section,
                "- {}: CONNECTED ({} tool(s), invoke via sub-agent tool mcp_agent_{})",
                s.name,
                s.tool_count,
                sanitized_name
            );
        } else if s.error.is_none() {
            let _ = writeln!(
                section,
                "- {}: READY / STANDBY (Configured as sub-agent tool mcp_agent_{}; connects on demand when a task is delegated).",
                s.name,
                sanitized_name
            );
        } else {
            let _ = writeln!(
                section,
                "- {}: UNREACHABLE ({}).",
                s.name,
                s.error.as_deref().unwrap_or("connection failed")
            );
        }
    }
    section
}

fn build_tools_registry(
    config: &zeroclaw::Config,
    memory: Option<Arc<dyn Memory>>,
    shared_folder_enabled: bool,
    workflow_folder_enabled: bool,
) -> (Vec<Box<dyn Tool>>, Arc<Mutex<ShellRuntime>>) {
    let config_arc = Arc::new(config.clone());
    let mut tools: Vec<Box<dyn Tool>> = vec![
        Box::new(zeroclaw::tools::CronListTool::new(config_arc.clone())),
        Box::new(zeroclaw::tools::CronRunsTool::new(config_arc)),
    ];

    // Create a shared ShellRuntime that mirrors the sandbox's persistent shell
    // state (cwd, env, exit code, jobs, history) into the system prompt.
    let shell_runtime = Arc::new(Mutex::new(ShellRuntime::new(
        config.workspace_dir.clone(),
    )));

    if let Some(memory) = memory {
        tools.push(Box::new(FfiMemoryStoreTool::new(memory.clone())));
        tools.push(Box::new(zeroclaw::tools::MemoryRecallTool::new(
            memory.clone(),
        )));
        tools.push(Box::new(FfiMemoryForgetTool::new(memory)));
    }

    if config.web_search.enabled {
        tools.push(Box::new(zeroclaw::tools::WebSearchTool::new(
            config.web_search.provider.clone(),
            config.web_search.brave_api_key.clone(),
            config.web_search.max_results,
            config.web_search.timeout_secs,
        )));
    }

    if config.web_fetch.enabled {
        tools.push(Box::new(FfiWebFetchTool::new(
            url_helpers::normalize_allowed_domains(config.web_fetch.allowed_domains.clone()),
            url_helpers::normalize_allowed_domains(config.web_fetch.blocked_domains.clone()),
            config.web_fetch.max_response_size,
            config.web_fetch.timeout_secs,
        )));
    }

    if config.http_request.enabled {
        tools.push(Box::new(FfiHttpRequestTool::new(
            url_helpers::normalize_allowed_domains(config.http_request.allowed_domains.clone()),
            config.http_request.max_response_size,
            config.http_request.timeout_secs,
        )));
    }

    // Termux tools are OPT-IN. The sandbox is the only shell backend by
    // default, so the LLM only ever sees sandbox_execute / sandbox_manage_process
    // for shell work. When the user explicitly enables Termux (Kotlin publishes
    // ZERO_ASSIST_TERMUX_ENABLED), the Termux tools are added back for explicit
    // Termux-only interactions. Bridge readiness depends on Android-owned Termux
    // permissions/bootstrap and can change after session creation, so execution
    // performs live health/token checks.
    if termux_enabled() {
        tools.push(Box::new(FfiTermuxCapabilitiesTool));
        tools.push(Box::new(FfiTermuxRunTool));
    }

    // Register the first-party Linux sandbox tools unconditionally. The
    // sandbox bridge authentication token must be set via FFI before the
    // session starts; if it's missing, tools return a clear "not configured"
    // error rather than crashing.
    tools.push(Box::new(FfiSandboxExecuteTool::new(Arc::clone(&shell_runtime))));
    tools.push(Box::new(FfiSandboxManageProcessTool));

    if config.google_workspace.enabled {
        tools.push(Box::new(FfiGoogleWorkspaceTool::new(
            config.google_workspace.clone(),
        )));
    }

    if config.composio.enabled
        && let Some(key) = configured_composio_key(config)
        && !config_compat::is_composio_sessions_key(key)
        && !config_compat::is_composio_cli_user_key(key)
    {
        let security = Arc::new(zeroclaw_config::policy::SecurityPolicy::from_config(
            &config.autonomy,
            &config.workspace_dir,
        ));
        tools.push(Box::new(zeroclaw::tools::ComposioTool::new(
            key,
            Some(&config.composio.entity_id),
            security,
        )));
    }

    if shared_folder_enabled {
        tools.extend(crate::shared_folder::create_shared_folder_tools());
    }
    if workflow_folder_enabled {
        tools.extend(crate::shared_folder::create_workflow_folder_tools());
    }

    // Device control tool — backed by the Kotlin accessibility-service handler.
    // Registered unconditionally; execution returns a clear error if the
    // Kotlin handler has not been set up, so the LLM can learn the constraint.
    tools.push(Box::new(zeroclaw::tools::DeviceControlTool));

    // MCP server status tool — registered whenever MCP is enabled so the
    // model can see configured servers (and their unreachable status) even
    // when the live connection failed.
    if config.mcp.enabled {
        tools.push(Box::new(FfiMcpStatusTool::new()));
    }

    // ── STAGE 7 (PART 1): SESSION BUILD — TOOL REGISTRY TRUNCATION ───────
    {
        let total = tools.len();
        if total > MAX_SESSION_TOOLS {
            eprintln!("[SESSION BUILD] Tool count {} exceeds MAX_SESSION_TOOLS={} — TRUNCATING", total, MAX_SESSION_TOOLS);
            let removed: Vec<String> = tools[MAX_SESSION_TOOLS..].iter().map(|t| t.name().to_string()).collect();
            for r in &removed {
                eprintln!("[SESSION BUILD]   REMOVED by truncation: {}", r);
            }
            tracing::warn!(
                total = total,
                limit = MAX_SESSION_TOOLS,
                removed = ?removed,
                "Session tool count exceeds budget; truncating to {MAX_SESSION_TOOLS} tools",
            );
            tools.truncate(MAX_SESSION_TOOLS);
        } else {
            eprintln!("[SESSION BUILD] Tool count {} within MAX_SESSION_TOOLS={} — no truncation", total, MAX_SESSION_TOOLS);
        }
    }

    // ── STAGE 7 (PART 2): SESSION BUILD — TOOL LIST DUMP ─────────────────
    {
        let tool_count = tools.len();
        let dc_present = tools.iter().any(|t| t.name() == "device_control");
        eprintln!("===== REGISTERED_RUST_TOOLS [count={tool_count}, device_control_present={dc_present}] =====");
        for (i, tool) in tools.iter().enumerate() {
            eprintln!("  [{:3}] {}", i + 1, tool.name());
        }
        eprintln!("=================================================================");
    }

    (tools, shell_runtime)
}

async fn append_mcp_tools_from_config(
    config: &zeroclaw::Config,
    tools: &mut Vec<Box<dyn Tool>>,
) -> (Option<ActivatedMcpToolsHandle>, String) {
    let mcp_servers = config_compat::effective_mcp_servers_from_config(config);
    if mcp_servers.is_empty() {
        eprintln!("[MCP SESSION] No MCP servers in config — skipping");
        return (None, String::new());
    }

    eprintln!("[MCP SESSION] Starting MCP initialization — {} server(s)", mcp_servers.len());
    for srv in &mcp_servers {
        eprintln!("[MCP SESSION]   server: name={}, transport={:?}, url={:?}, enabled={}",
            srv.name, srv.transport, srv.url, srv.enabled);
    }

    tracing::info!(
        "Android session initializing MCP client: {} server(s) configured",
        mcp_servers.len()
    );

    let registry = match zeroclaw::tools::McpRegistry::connect_all_from_config(&config).await {
        Ok(registry) => Arc::new(registry),
        Err(error) => {
            eprintln!("[MCP SESSION] Registry connect_all FAILED: {:#}", error);
            tracing::error!("Android session MCP registry failed to initialize: {error:#}");
            return (None, String::new());
        }
    };

    // Snapshot per-server results (including failures) for the mcp_status tool.
    set_mcp_registry_handle(Arc::clone(&registry)).await;
    let statuses = registry.statuses();
    set_mcp_status_snapshot(statuses.clone()).await;
    let status_section = build_mcp_status_section(&statuses);

    if registry.is_empty() {
        eprintln!("[MCP SESSION] Registry is empty — no usable servers connected");
        tracing::warn!("Android session MCP registry connected no usable servers");
        // Still inform the model about the configured-but-unreachable servers.
        return (None, status_section);
    }

    let server_count = registry.server_count();
    let all_tool_names = registry.tool_names();
    eprintln!("[MCP SESSION] Registry connected — {} server(s), {} tool name(s) in index",
        server_count, all_tool_names.len());
    for tn in &all_tool_names {
        eprintln!("[MCP SESSION]   indexed_tool={}", tn);
    }

    let before = tools.len();
    eprintln!("[MCP SESSION] Registry size BEFORE: {} tools", before);

    if config.mcp.deferred_loading {
        eprintln!("[MCP SESSION] Deferred loading enabled — creating DeferredMcpToolSet");
        let deferred_set =
            zeroclaw::tools::DeferredMcpToolSet::from_registry(Arc::clone(&registry)).await;
        if deferred_set.is_empty() {
            eprintln!("[MCP SESSION] Deferred set is empty — skipping");
            return (None, String::new());
        }

        let deferred_section = zeroclaw::tools::build_deferred_tools_section(&deferred_set);
        let activated = Arc::new(Mutex::new(zeroclaw::tools::ActivatedToolSet::new()));
        tools.push(Box::new(zeroclaw::tools::ToolSearchTool::new(
            deferred_set,
            Arc::clone(&activated),
        )));
        eprintln!("[MCP SESSION] Deferred mode — returning activated handle");
        return (Some(activated), format!("{deferred_section}\n\n{status_section}"));
    }

    eprintln!("[MCP SESSION] Deferred loading disabled — registering tools directly");
    let mut registered = 0usize;
    for name in registry.tool_names() {
        eprintln!("[MCP SESSION] Looking up tool def: {}", name);
        if let Some(def) = registry.get_tool_def(&name).await {
            let wrapper: Arc<dyn Tool> = Arc::new(zeroclaw::tools::McpToolWrapper::new(
                name.clone(),
                def,
                Arc::clone(&registry),
            ));
            let tool_name = wrapper.name().to_string();
            tools.push(Box::new(zeroclaw::tools::ArcToolRef(wrapper)));
            registered += 1;
            eprintln!("[MCP SESSION]   REGISTERED tool #{}: {} (total tools now: {})", registered, tool_name, tools.len());
        } else {
            eprintln!("[MCP SESSION]   REJECTED: get_tool_def returned None for '{}'", name);
        }
    }

    let after = tools.len();
    eprintln!("[MCP SESSION] Registry size AFTER: {} tools (added {})", after, after - before);
    eprintln!("[MCP SESSION] All tools now in registry:");
    for (i, t) in tools.iter().enumerate() {
        eprintln!("  [{:3}] {}", i + 1, t.name());
    }

    tracing::info!(
        "Android session MCP registered {} tool(s) from {} server(s)",
        registered,
        registry.server_count()
    );
    (None, status_section)
}

fn tool_registry_contains(tools: &[Box<dyn Tool>], tool_name: &str) -> bool {
    tools.iter().any(|tool| tool.name() == tool_name)
}

fn tool_registry_contains_composio_mcp_surface(tools: &[Box<dyn Tool>]) -> bool {
    tools.iter().any(|tool| {
        let name = tool.name();
        name == "tool_search" || name.starts_with("composio__") || name.starts_with("COMPOSIO_")
    })
}

/// Generates [`ToolSpec`] metadata from the tools registry.
///
/// Uses each tool's [`Tool::spec`] method to produce the name, description,
/// and JSON parameter schema that the provider uses for native tool calling.
fn tool_specs_from_registry(tools: &[Box<dyn Tool>]) -> Vec<ToolSpec> {
    tools.iter().map(|t| t.spec()).collect()
}

/// Internal session state holding conversation history and provider config.
///
/// Not exposed across the FFI boundary -- Kotlin interacts exclusively
/// through exported free functions and the [`FfiSessionListener`] callback.
struct Session {
    /// Accumulated conversation messages (user + assistant turns).
    history: Vec<ChatMessage>,
    /// Parsed daemon configuration snapshot taken at session creation.
    config: zeroclaw::Config,
    /// Assembled system prompt (identity + workspace files).
    system_prompt: String,
    /// Model identifier passed to the provider (e.g. `"gpt-4o"`).
    model: String,
    /// Sampling temperature for the provider.
    temperature: f64,
    /// Optional Android-selected thinking level applied per turn.
    thinking_level_override: Option<ThinkingLevel>,
    /// Provider name used to create the provider instance (e.g. `"openai"`).
    provider_name: String,
    /// Tools registry built from available upstream tools and FFI wrappers.
    tools_registry: Vec<Box<dyn Tool>>,
    /// Deferred MCP tools activated during this conversation, if MCP is active.
    activated_mcp_tools: Option<ActivatedMcpToolsHandle>,
    /// Whether Android shared-folder tools are enabled for this session.
    shared_folder_enabled: bool,
    /// Whether Android workflow-folder tools are enabled for this session.
    workflow_folder_enabled: bool,
    /// Shared shell runtime for sandbox state injection into system prompt.
    shell_runtime: Arc<Mutex<ShellRuntime>>,
}

/// Optional overrides applied when creating a live agent session.
///
/// This lets Android rehydrate the singleton native session for a specific
/// agent's provider, model, credentials, and system prompt before each send.
struct SessionStartOverrides {
    /// Provider name override (for example `"openai"` or `"anthropic"`).
    provider_name: String,
    /// Model identifier override.
    model: String,
    /// Optional API key override for this session.
    api_key: Option<String>,
    /// Optional base URL override for self-hosted or custom providers.
    base_url: Option<String>,
    /// Optional temperature override.
    temperature: Option<f64>,
    /// Optional Android-selected thinking level.
    thinking_level: Option<ThinkingLevel>,
    /// Optional system prompt override.
    system_prompt: Option<String>,
}

/// RAII guard that ensures taken-out session state (history + tools) is
/// always restored, even if a panic occurs during processing.
///
/// When [`session_send_inner`] takes history and tools out of the
/// [`SESSION`] mutex for processing, a panic between take and put-back
/// would leave the session in a zombified state (active but empty).
/// This guard's [`Drop`] implementation puts the state back
/// automatically during stack unwinding, preventing permanent data loss.
///
/// Call [`SessionStateGuard::defuse`] after a successful put-back to
/// prevent a redundant restore.
struct SessionStateGuard {
    /// Conversation history taken from the session. `None` once defused.
    history: Option<Vec<ChatMessage>>,
    /// Tools registry taken from the session. `None` once defused.
    tools: Option<Vec<Box<dyn Tool>>>,
}

impl SessionStateGuard {
    /// Creates a new guard holding the taken-out session state.
    fn new(history: Vec<ChatMessage>, tools: Vec<Box<dyn Tool>>) -> Self {
        Self {
            history: Some(history),
            tools: Some(tools),
        }
    }

    /// Returns mutable references to the held history and tools.
    fn state_mut(&mut self) -> (&mut Vec<ChatMessage>, &[Box<dyn Tool>]) {
        (
            self.history.as_mut().expect("guard already defused"),
            self.tools.as_deref().expect("guard already defused"),
        )
    }

    /// Consumes the held state, returning ownership to the caller.
    ///
    /// After this call the guard's [`Drop`] is a no-op.
    fn take(mut self) -> (Vec<ChatMessage>, Vec<Box<dyn Tool>>) {
        (
            self.history.take().expect("guard already defused"),
            self.tools.take().expect("guard already defused"),
        )
    }
}

impl Drop for SessionStateGuard {
    fn drop(&mut self) {
        let Some(history) = self.history.take() else {
            return;
        };
        let Some(tools) = self.tools.take() else {
            return;
        };

        let restored_len = history.len();
        let restored_hash = history_hash(&history);
        let session_addr = &SESSION as *const _ as usize;
        tracing::warn!(
            target: "zeroclaw::concurrency",
            request_id = 0,
            ts_us = ts_us(),
            thread_id = %format!("{:?}", std::thread::current().id()),
            session_addr = session_addr,
            restored_len = restored_len,
            restored_hash = restored_hash,
            "SessionStateGuard::drop restoring state after panic"
        );
        let mut guard = lock_session();
        if let Some(session) = guard.as_mut() {
            session.history = history;
            session.tools_registry = tools;
        }
        *lock_cancel_token() = None;
    }
}

/// A single conversation message exchanged over the FFI boundary.
///
/// Mirrors [`zeroclaw::providers::ChatMessage`] but uses UniFFI-compatible
/// types. The `role` field is one of `"system"`, `"user"`, or `"assistant"`.
#[derive(uniffi::Record, Clone, Debug)]
pub struct SessionMessage {
    /// The message role: `"system"`, `"user"`, or `"assistant"`.
    pub role: String,
    /// The text content of the message.
    pub content: String,
}

/// Callback interface that Kotlin implements to receive live agent session events.
///
/// Events are dispatched from the tokio runtime thread during
/// [`session_send`](crate::session_send). Implementations must be
/// thread-safe (`Send + Sync`). Each callback corresponds to a distinct
/// phase of the agent's tool-call loop execution.
#[uniffi::export(callback_interface)]
pub trait FfiSessionListener: Send + Sync {
    /// The agent is producing internal reasoning (thinking/planning).
    ///
    /// Called with progressive text chunks as the agent reasons about
    /// which tools to invoke or how to answer.
    fn on_thinking(&self, text: String);

    /// A chunk of the agent's final response text has arrived.
    ///
    /// Called incrementally as the provider streams response tokens.
    /// Concatenating all chunks yields the full response.
    fn on_response_chunk(&self, text: String);

    /// The agent is about to invoke a tool.
    ///
    /// `name` is the tool identifier (e.g. `"read_file"`).
    /// `arguments_hint` is a short summary of the arguments, which may
    /// be empty if no hint is available.
    fn on_tool_start(&self, name: String, arguments_hint: String);

    /// A tool invocation has completed.
    ///
    /// `name` is the tool identifier, `success` indicates whether the
    /// tool returned a result or an error, and `duration_secs` is the
    /// wall-clock execution time rounded to whole seconds.
    fn on_tool_result(&self, name: String, success: bool, duration_secs: u64);

    /// Raw tool output text for display in a collapsible detail section.
    ///
    /// Called after [`on_tool_result`](FfiSessionListener::on_tool_result)
    /// with the full stdout/stderr captured from the tool execution.
    fn on_tool_output(&self, name: String, output: String);

    /// A progress status line from the agent loop.
    ///
    /// Used for miscellaneous status updates that do not fit the other
    /// callback categories (e.g. `"Searching memory..."`).
    fn on_progress(&self, message: String);

    /// The conversation history was compacted to fit the context window.
    ///
    /// `summary` contains the AI-generated summary that replaced older
    /// messages. The UI should display this as a fold/expansion point.
    fn on_compaction(&self, summary: String);

    /// The agent loop has finished and the full response is available.
    ///
    /// `full_response` contains the concatenated final answer. This is
    /// always the last callback for a successful send.
    fn on_complete(&self, full_response: String);

    /// An unrecoverable error occurred during the agent loop.
    ///
    /// `error` contains a human-readable description. The session
    /// remains valid and the caller may retry with a new send.
    fn on_error(&self, error: String);

    /// The current send was cancelled by the user.
    ///
    /// The session remains valid; the caller may issue a new send.
    fn on_cancelled(&self);
}

// ── Session lifecycle ───────────────────────────────────────────────────

/// Creates a new live agent session from the running daemon's configuration.
///
/// Mirrors the setup phase of upstream `zeroclaw::agent::run()`:
///
/// 1. Clones the daemon config snapshot.
/// 2. Resolves provider name, model, and temperature.
/// 3. Loads workspace and community skills.
/// 4. Builds tool description metadata for the system prompt.
/// 5. Creates a temporary provider to query native tool support.
/// 6. Builds the full system prompt via
///    [`zeroclaw::channels::build_system_prompt_with_mode`].
/// 7. Seeds the conversation history with the system prompt.
/// 8. Stores the [`Session`] in the global [`SESSION`] mutex.
///
/// Only one session may exist at a time. Calling this while a session is
/// already active returns [`FfiError::StateError`].
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if a session is already active or
/// the daemon is not running, [`FfiError::StateCorrupted`] if the session
/// mutex is poisoned, or [`FfiError::SpawnError`] if provider creation fails.
pub(crate) fn session_start_inner() -> Result<(), FfiError> {
    session_start_with_overrides(None)
}

/// Creates a new live agent session using agent-specific overrides.
///
/// Android uses this to rehydrate the singleton native session for a specific
/// agent before sending a message from the group chat.
pub(crate) fn session_start_custom_inner(
    provider_name: String,
    model: String,
    api_key: Option<String>,
    base_url: Option<String>,
    temperature: Option<f64>,
    thinking_level: Option<String>,
    system_prompt: String,
) -> Result<(), FfiError> {
    let trimmed_system_prompt = system_prompt.trim();
    let thinking_level = parse_thinking_level_override(thinking_level)?;
    session_start_with_overrides(Some(SessionStartOverrides {
        provider_name,
        model,
        api_key,
        base_url,
        temperature,
        thinking_level,
        system_prompt: if trimmed_system_prompt.is_empty() {
            None
        } else {
            Some(trimmed_system_prompt.to_string())
        },
    }))
}

fn parse_thinking_level_override(
    thinking_level: Option<String>,
) -> Result<Option<ThinkingLevel>, FfiError> {
    let Some(raw) = thinking_level.map(|value| value.trim().to_string()) else {
        return Ok(None);
    };
    if raw.is_empty() {
        return Ok(None);
    }
    ThinkingLevel::from_str_insensitive(&raw)
        .map(Some)
        .ok_or_else(|| FfiError::ConfigError {
            detail: format!("invalid thinking level override: {raw}"),
        })
}

#[allow(clippy::too_many_lines)]
fn session_start_with_overrides(overrides: Option<SessionStartOverrides>) -> Result<(), FfiError> {
    let mut config = clone_daemon_config()?;
    let shared_folder_enabled = crate::runtime::is_shared_folder_enabled().unwrap_or(false);
    let workflow_folder_enabled = crate::runtime::is_workflow_folder_enabled().unwrap_or(false);

    let override_provider_name = overrides
        .as_ref()
        .map(|value| value.provider_name.trim())
        .filter(|value| !value.is_empty())
        .map(str::to_string);

    let override_model = overrides
        .as_ref()
        .map(|value| value.model.trim())
        .filter(|value| !value.is_empty())
        .map(str::to_string);

    let override_api_key = overrides.as_ref().and_then(|value| value.api_key.clone());
    let override_base_url = overrides.as_ref().and_then(|value| value.base_url.clone());
    let override_temperature = overrides.as_ref().and_then(|value| value.temperature);
    let thinking_level_override = overrides.as_ref().and_then(|value| value.thinking_level);

    if override_provider_name.is_some()
        || override_model.is_some()
        || override_api_key.is_some()
        || override_base_url.is_some()
        || override_temperature.is_some()
    {
        let provider = override_provider_name.clone().unwrap_or_else(|| {
            config_compat::active_provider_name_or_default(&config, "openrouter")
        });
        let model = override_model.clone().unwrap_or_else(|| {
            config_compat::active_model_or_default(&config, "anthropic/claude-sonnet-4")
        });
        config_compat::apply_provider_override(
            &mut config,
            provider,
            model,
            override_api_key,
            override_base_url,
            override_temperature,
        );
    }

    let provider_name = config_compat::active_provider_name_or_default(&config, "openrouter");
    let model = config_compat::active_model_or_default(&config, "anthropic/claude-sonnet-4");
    let temperature = config_compat::active_temperature_or_default(&config, 0.7);

    log_composio_route(&config);

    // Build tools registry from daemon memory + config.
    let daemon_memory = clone_daemon_memory().ok();
    if daemon_memory.is_none() {
        tracing::warn!("Memory backend unavailable; memory tools will be skipped");
    }
    let (mut tools_registry, shell_runtime) = build_tools_registry(
        &config,
        daemon_memory,
        shared_folder_enabled,
        workflow_folder_enabled,
    );
    let (activated_mcp_tools, deferred_mcp_section) =
        if config_compat::effective_mcp_servers_from_config(&config).is_empty() {
            (None, String::new())
        } else {
            let handle = crate::runtime::get_or_create_runtime()?;
            handle.block_on(append_mcp_tools_from_config(&config, &mut tools_registry))
        };

    // ── STAGE 7 (PART 3): SESSION BUILD — VERIFY MCP TOOLS IN REGISTRY ──
    {
        let count = tools_registry.len();
        let mcp_tools: Vec<String> = tools_registry.iter()
            .filter(|t| {
                let n = t.name();
                n.starts_with("mcp_agent_") || n.contains("__") || n.contains("Levi") || n == "mcp_status" || n == "tool_search"
            })
            .map(|t| t.name().to_string())
            .collect();
        eprintln!("[SESSION BUILD] After MCP append — total tools in registry: {}", count);
        eprintln!("[SESSION BUILD] MCP-related tools found: {:?}", mcp_tools);
        if mcp_tools.is_empty() {
            eprintln!("[SESSION BUILD] *** WARNING: No MCP tools found in registry! ***");
            eprintln!("[SESSION BUILD] *** Check CONNECT_ALL logs above for failures ***");
        }
        for (i, t) in tools_registry.iter().enumerate() {
            eprintln!("[SESSION BUILD]   [{:3}] {}", i + 1, t.name());
        }
    }

    // Generate tool descriptions from the real tools registry, plus
    // static descriptions for tools the LLM should know about but that
    // cannot be constructed from the FFI crate.
    let mut tool_descs =
        build_android_tool_descs(&config, shared_folder_enabled, workflow_folder_enabled);
    for tool in &tools_registry {
        let name = tool.name().to_string();
        if !tool_descs.iter().any(|(n, _)| n == &name) {
            tool_descs.push((name, tool.description().to_string()));
        }
    }

    let tool_desc_refs: Vec<(&str, &str)> = tool_descs
        .iter()
        .map(|(name, desc)| (name.as_str(), desc.as_str()))
        .collect();

    let bootstrap_max_chars = if config.agent.compact_context {
        Some(6000)
    } else {
        None
    };

    let native_tools = {
        let provider_runtime_options =
            zeroclaw::providers::provider_runtime_options_from_config(&config);
        let provider = zeroclaw::providers::create_routed_provider_with_options(
            &provider_name,
            config_compat::active_api_key(&config),
            config_compat::active_base_url(&config),
            &config.reliability,
            &config.providers.model_routes,
            &model,
            &provider_runtime_options,
        )
        .map_err(|e| FfiError::SpawnError {
            detail: format!("failed to create provider for native-tools check: {e}"),
        })?;
        provider.supports_native_tools()
    };

    // Upstream `skills` module is `pub(crate)`, so we cannot call
    // `load_skills_with_config` or name the `Skill` type directly.
    // Pass an empty slice -- skill prompt injection still works via
    // workspace file scanning inside `build_system_prompt_with_mode`.
    let mut system_prompt = zeroclaw::channels::build_system_prompt_with_mode(
        &config.workspace_dir,
        &model,
        &tool_desc_refs,
        &[],
        Some(&config.identity),
        bootstrap_max_chars,
        native_tools,
        config.skills.prompt_injection_mode,
        config.autonomy.level,
    );

    if !deferred_mcp_section.is_empty() {
        system_prompt.push_str("\n\n");
        system_prompt.push_str(&deferred_mcp_section);
    }

    if should_add_composio_sessions_guidance(&config, &tools_registry) {
        system_prompt.push_str("\n\n");
        system_prompt.push_str(COMPOSIO_SESSIONS_TOOL_GUIDANCE);
    } else if should_add_composio_sessions_unavailable_guidance(&config, &tools_registry) {
        system_prompt.push_str("\n\n");
        system_prompt.push_str(COMPOSIO_SESSIONS_UNAVAILABLE_GUIDANCE);
    }

    if !native_tools && !tools_registry.is_empty() {
        system_prompt.push_str("\n\n");
        system_prompt.push_str(&build_tool_instructions_text(&tool_specs_from_registry(
            &tools_registry,
        )));
    }

    // Upstream AIEOS only renders agent identity fields; Android onboarding
    // also stores user_name, timezone, and communication_style inside the
    // identity JSON object. Extract and append them so the model knows who
    // it is talking to.
    append_android_identity_extras(&mut system_prompt, &config.identity);

    if shared_folder_enabled {
        system_prompt.push_str(
            "\n\nShared folder access is enabled for this session. You can use \
shared_folder_list, shared_folder_read, and shared_folder_write to inspect \
and modify files inside the user-selected shared folder. Do not claim you only \
have workspace access when these shared-folder tools are available.",
        );
    }
    if workflow_folder_enabled {
        system_prompt.push_str(
            "\n\nWorkflow Folder access is enabled by default for this session. Use \
workflow_folder_list, workflow_folder_read, and workflow_folder_write for workflow \
files. Shared Folder remains separate and should only be used through shared_folder_* \
tools when the user specifically asks for the shared folder.",
        );
    }

    // Device-control routing: instruct the LLM that ANY Android UI task must
    // go through device_control, not web_search or sandbox tools.
    system_prompt.push_str(
        "\n\n## Device Control Routing (CRITICAL)\n\n\
         When the user asks you to do something ON their Android phone screen, \
         you MUST use the device_control tool. This is the ONLY tool that can \
         interact with Android apps, launch apps, tap buttons, type text, scroll, \
         and play content on the device.\n\n\
         Routes to device_control:\n\
         - Opening an app (YouTube, Instagram, WhatsApp, Settings, Brave, etc.)\n\
         - Navigating Android UI (settings, menus, tabs)\n\
         - Typing text into an app field\n\
         - Tapping buttons or links inside an app\n\
         - Scrolling through content in an app\n\
         - Playing or selecting video/audio inside an app\n\
         - Sharing content through an app\n\
         - Changing device settings through the UI\n\n\
         Do NOT use web_search_tool for these — web_search searches the internet, \
         it cannot control the phone.\n\
         Do NOT use sandbox_execute for these — the sandbox runs Linux commands, \
         it cannot control the Android UI.\n\
         Do NOT use workflow_folder_list for these — that lists files, it cannot \
         control the phone.\n\n\
         Only use web_search_tool when the user wants to search the internet.\n\n\
         ## Shell Environment Routing (CRITICAL)\n\n\
         ALL shell command execution goes through sandbox_execute inside the \
         isolated Alpine Linux sandbox. There is no other shell. Commands like \
         `ls`, `cd`, `pwd`, `cat`, `grep`, `find`, `echo`, `apk`, `pip`, `npm`, \
         `git`, `python3`, and `node` MUST be run with sandbox_execute.\n\n\
         The sandbox shell session is PERSISTENT within this conversation: cwd, \
         exported variables, and in-shell state carry from one call to the next. \
         Read the ## Shell Environment section below for the current state.\n",
    );

    if termux_enabled() {
        system_prompt.push_str(
            "\n\n\
             NOTE: The user has explicitly enabled the Termux bridge, so the \
             termux_run / termux_get_capabilities tools are available. Use them \
             ONLY when the user explicitly asks to interact with their Termux \
             installation, its files, or Android host tools. For general Linux \
             commands, packages, and scripting, ALWAYS prefer sandbox_execute — \
             it provides the full Alpine Linux environment without affecting Termux.",
        );
    }
    system_prompt.push_str(
        "\n\n\
         ## Device Control Error Handling (IMPORTANT)\n\n\
         When device_control returns a failure, it includes a structured error_code. \
         You MUST inspect it and respond appropriately:\n\n\
         ACCESSIBILITY_DISABLED / ACCESSIBILITY_NOT_CONNECTED:\n\
         → Tell the user: \"The accessibility service is not enabled. Please enable \
         Zero-Assist in Android Settings → Accessibility.\"\n\
         → Do NOT substitute web_search or sandbox_execute.\n\
         → When the user later says \"I enabled it, try again\", retry the original goal.\n\n\
         NO_ACTIVE_WINDOW:\n\
         → Tell the user the screen may be off or locked.\n\
         → Ask them to unlock their screen and retry.\n\n\
         APP_NOT_FOUND:\n\
         → Tell the user the app is not installed.\n\
         → Do NOT try to launch it through Termux or any other method.\n\n\
         APP_LAUNCH_FAILED / APP_LAUNCH_FOREGROUND_MISMATCH:\n\
         → The app may have restrictions on background launching.\n\
         → Ask the user to open the app manually, then retry.\n\n\
         PLANNER_FAILED / STUCK / MAX_STEPS_REACHED:\n\
         → The task could not be completed automatically.\n\
         → Explain what was attempted and what failed.\n\
         → Do NOT silently replace with web_search unless the user explicitly asks.\n\n\
         For ALL device control failures:\n\
         → NEVER claim the task was completed if it was not.\n\
         → NEVER silently substitute a different tool's output as if it were the \
         device control result.\n\
         → NEVER say \"I opened X\" if the device control reported failure.\n\
         → ALWAYS report the actual blocking condition to the user.",
    );

    if let Some(extra_prompt) = overrides
        .as_ref()
        .and_then(|value| value.system_prompt.as_ref())
        .map(|value| value.trim())
        .filter(|value| !value.is_empty())
    {
        system_prompt.push_str("\n\n## Agent Role\n");
        system_prompt.push_str(extra_prompt);
    }

    // Inject initial sandbox shell context into the system prompt.
    {
        let rt = shell_runtime.lock().unwrap_or_else(|e| e.into_inner());
        let ctx = rt.context_string();
        if !ctx.is_empty() {
            system_prompt.push_str(&format!("\n\n## Shell Environment\n\n{ctx}\n"));
        }
    }

    let history = vec![ChatMessage::system(&system_prompt)];

    let session = Session {
        history,
        config,
        system_prompt,
        model,
        temperature,
        thinking_level_override,
        provider_name,
        tools_registry,
        activated_mcp_tools,
        shared_folder_enabled,
        workflow_folder_enabled,
        shell_runtime,
    };

    let mut guard = lock_session();

    if guard.is_some() {
        return Err(FfiError::StateError {
            detail: "a session is already active; destroy it first".into(),
        });
    }

    *guard = Some(session);

    tracing::info!("Live agent session started");
    Ok(())
}

fn is_composio_sessions_configured(config: &zeroclaw::Config) -> bool {
    config.composio.enabled
        && config
            .composio
            .api_key
            .as_deref()
            .is_some_and(config_compat::is_composio_sessions_key)
}

fn should_add_composio_sessions_guidance(
    config: &zeroclaw::Config,
    tools: &[Box<dyn Tool>],
) -> bool {
    is_composio_sessions_configured(config) && tool_registry_contains(tools, "tool_search")
}

fn should_add_composio_sessions_unavailable_guidance(
    config: &zeroclaw::Config,
    tools: &[Box<dyn Tool>],
) -> bool {
    is_composio_sessions_configured(config) && !tool_registry_contains_composio_mcp_surface(tools)
}

/// Maximum number of images per session send request.
const MAX_SESSION_IMAGES: usize = 5;

/// Sends a message through the live agent session's tool-call loop.
///
/// This is the core function that drives multi-turn agent interaction.
/// The flow is:
///
/// 1. Validate message size (max 1 MiB) and image arrays.
/// 2. Compose multimodal message with `[IMAGE:...]` markers if images
///    are present.
/// 3. Create a [`CancellationToken`] and store it in [`CANCEL_TOKEN`].
/// 4. Take the session's history out of the [`SESSION`] mutex.
/// 5. Build memory context by recalling relevant memories.
/// 6. Enrich the user message with memory context and a timestamp.
/// 7. Create a fresh provider and tools registry.
/// 8. Run the agent loop ([`run_agent_loop`]).
/// 9. On success: run compaction, put history back, fire `on_complete`.
/// 10. On cancel: keep partial history, put history back, fire
///     `on_cancelled`.
/// 11. On error: truncate history to pre-send state, put history back,
///     fire `on_error`.
/// 12. Clear [`CANCEL_TOKEN`].
///
/// # Errors
///
/// Returns [`FfiError::ConfigError`] for oversized messages or
/// mismatched image arrays, [`FfiError::StateError`] if no session is
/// active, [`FfiError::StateCorrupted`] if the session mutex is
/// poisoned, or [`FfiError::SpawnError`] if the agent loop or provider
/// creation fails.
#[allow(clippy::too_many_lines)]
pub(crate) fn session_send_inner(
    message: String,
    image_data: Vec<String>,
    mime_types: Vec<String>,
    listener: Arc<dyn FfiSessionListener>,
) -> Result<(), FfiError> {
    // Validate image arrays before composing the message.
    if image_data.len() != mime_types.len() {
        return Err(FfiError::ConfigError {
            detail: format!(
                "image_data length ({}) != mime_types length ({})",
                image_data.len(),
                mime_types.len()
            ),
        });
    }
    if image_data.len() > MAX_SESSION_IMAGES {
        return Err(FfiError::ConfigError {
            detail: format!(
                "too many images ({}, max {MAX_SESSION_IMAGES})",
                image_data.len()
            ),
        });
    }

    // Compose the final message text, embedding image markers if present.
    let composed_message = compose_multimodal_message(&message, &image_data, &mime_types);

    if composed_message.len() > MAX_MESSAGE_BYTES {
        return Err(FfiError::ConfigError {
            detail: format!(
                "message too large ({} bytes, max {MAX_MESSAGE_BYTES})",
                composed_message.len()
            ),
        });
    }

    let (thinking_directive, message) = match parse_thinking_directive(&composed_message) {
        Some((level, remaining)) => {
            tracing::info!(thinking_level = ?level, "Thinking directive parsed from FFI session message");
            (Some(level), remaining)
        }
        None => (None, composed_message),
    };

    let cancel_token = CancellationToken::new();
    {
        let mut ct_guard = lock_cancel_token();
        *ct_guard = Some(cancel_token.clone());
    }

    let request_id = NEXT_REQUEST_ID.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
    let started_at = std::time::Instant::now();
    let _thread_name = std::thread::current().name().unwrap_or("?").to_string();
    let os_thread_id = format!("{:?}", std::thread::current().id());
    let session_addr = &SESSION as *const _ as usize;

    tracing::info!(
        target: "zeroclaw::concurrency",
        request_id = request_id,
        ts_us = ts_us(),
        thread_id = %os_thread_id,
        session_addr = session_addr,
        "SESSION_SEND_ENTRY"
    );

    let call_count = CONCURRENT_SEND_COUNT.fetch_add(1, std::sync::atomic::Ordering::SeqCst) + 1;
    if call_count > 1 {
        tracing::warn!(
            target: "zeroclaw::concurrency",
            request_id = request_id,
            ts_us = ts_us(),
            thread_id = %os_thread_id,
            session_addr = session_addr,
            call_count = call_count,
            "CONCURRENT_SESSION_SEND overlapping calls detected"
        );
    }

    let (
        mut state_guard,
        config,
        model,
        temperature,
        thinking_level_override,
        provider_name,
        system_prompt,
        activated_mcp_tools,
        shared_folder_enabled,
        workflow_folder_enabled,
        _history_len_before_take,
    ) = {
        let lock_start = std::time::Instant::now();
        let mut guard = lock_session();
        let lock_wait_us = lock_start.elapsed().as_micros();
        let session = guard.as_mut().ok_or_else(|| FfiError::StateError {
            detail: "no active session; call session_start first".into(),
        })?;
        let history_len_before_take = session.history.len();
        let model = session.model.clone();
        let provider_name = session.provider_name.clone();
        let session_ptr = &SESSION as *const _ as usize;

        if lock_wait_us > 1000 {
            tracing::warn!(
                target: "zeroclaw::concurrency",
                request_id = request_id,
                ts_us = ts_us(),
                thread_id = %os_thread_id,
                session_addr = session_ptr,
                lock_wait_us = lock_wait_us,
                "SESSION_LOCK_CONTENTION mutex wait exceeded 1ms"
            );
        }

        let active_provider_req = PROVIDER_OWNER_REQ_ID.load(std::sync::atomic::Ordering::SeqCst);
        if active_provider_req != 0 {
            tracing::warn!(
                target: "zeroclaw::concurrency",
                request_id = request_id,
                ts_us = ts_us(),
                thread_id = %os_thread_id,
                session_addr = session_ptr,
                active_request_id = active_provider_req,
                history_len = history_len_before_take,
                "OVERLAP_AT_TAKE another request is inside provider.chat() while this request takes history"
            );
        }

        if history_len_before_take == 0 {
            tracing::warn!(
                target: "zeroclaw::concurrency",
                request_id = request_id,
                ts_us = ts_us(),
                thread_id = %os_thread_id,
                session_addr = session_ptr,
                "HISTORY_EMPTY_AT_TAKE session history is empty - another request may have taken it"
            );
        }

        let history_owner_before = HISTORY_OWNER_REQ_ID.swap(request_id, std::sync::atomic::Ordering::SeqCst);
        if history_owner_before != 0 {
            tracing::warn!(
                target: "zeroclaw::concurrency",
                request_id = request_id,
                ts_us = ts_us(),
                thread_id = %os_thread_id,
                session_addr = session_ptr,
                history_owner_before = history_owner_before,
                "OVERLAP_HISTORY_OWNER another request already owns the session history"
            );
        }

        let history_hash_before = history_hash(&session.history);
        let tool_count = session.tools_registry.len();

        tracing::info!(
            target: "zeroclaw::concurrency",
            request_id = request_id,
            ts_us = ts_us(),
            thread_id = %os_thread_id,
            session_addr = session_ptr,
            history_len = history_len_before_take,
            history_hash = history_hash_before,
            tool_count = tool_count,
            history_owner_before = history_owner_before,
            history_owner_after = request_id,
            model = %model,
            provider = %provider_name,
            "HISTORY_BEFORE_TAKE"
        );

        let taken_history = std::mem::take(&mut session.history);
        let taken_tools = std::mem::take(&mut session.tools_registry);
        let history_taken_len = taken_history.len();
        let history_remaining = session.history.len();

        tracing::info!(
            target: "zeroclaw::concurrency",
            request_id = request_id,
            ts_us = ts_us(),
            thread_id = %os_thread_id,
            session_addr = session_ptr,
            history_taken_len = history_taken_len,
            history_remaining = history_remaining,
            tool_count = taken_tools.len(),
            "HISTORY_AFTER_TAKE"
        );

        (
            SessionStateGuard::new(taken_history, taken_tools),
            session.config.clone(),
            model,
            session.temperature,
            session.thinking_level_override,
            provider_name,
            session.system_prompt.clone(),
            session.activated_mcp_tools.clone(),
            session.shared_folder_enabled,
            session.workflow_folder_enabled,
            history_len_before_take,
        )
    };

    tracing::info!(
        target: "zeroclaw::concurrency",
        request_id = request_id,
        ts_us = ts_us(),
        thread_id = %os_thread_id,
        session_addr = session_addr,
        history_owner_after = HISTORY_OWNER_REQ_ID.load(std::sync::atomic::Ordering::SeqCst),
        "HISTORY_GUARD_CREATED"
    );

    let (history, tools) = state_guard.state_mut();
    let history_len_before = history.len();

    // Strip stale [IMAGE:] markers from older history turns so that old
    // image attachments (e.g. Telegram downloads saved as local paths)
    // are not repeatedly sent with unrelated later text requests.
    // This mirrors the orchestrator's logic in loop_.rs for non-vision
    // providers, but applies unconditionally in the FFI session path
    // since the Android assistant does not need historical images re-sent.
    for turn in history.iter_mut() {
        if turn.role == "user" && turn.content.contains("[IMAGE:") {
            let (cleaned, _refs) =
                zeroclaw::providers::multimodal::parse_image_markers(&turn.content);
            turn.content = cleaned;
        }
    }
    // Drop turns that became empty after marker removal (image-only messages).
    history.retain(|turn| !turn.content.trim().is_empty());

    tracing::info!(
        target: "zeroclaw::concurrency",
        request_id = request_id,
        ts_us = ts_us(),
        thread_id = %os_thread_id,
        session_addr = session_addr,
        history_len_local = history_len_before,
        tool_count_local = tools.len(),
        "HISTORY_TAKE_VERIFIED"
    );

    let handle = crate::runtime::get_or_create_runtime()?;

    // Clone the memory backend *before* entering block_on to avoid holding
    // the DAEMON mutex inside the async block, which could deadlock with a
    // concurrent stop_daemon call.
    let daemon_memory = clone_daemon_memory().ok();

    let result: Result<String, AgentLoopOutcome> = handle.block_on(async {
        let thinking_level = resolve_thinking_level(
            thinking_directive,
            thinking_level_override,
            &config.agent.thinking,
        );
        let thinking_params = apply_thinking_level(thinking_level);
        let effective_temperature =
            clamp_temperature(temperature + thinking_params.temperature_adjustment);
        let turn_system_prompt = thinking_params
            .system_prompt_prefix
            .as_ref()
            .map(|prefix| format!("{prefix}\n\n{system_prompt}"));
        if let Some(prompt) = turn_system_prompt.as_ref() {
            replace_session_system_prompt(history, prompt);
        }

        // Build memory context (best-effort; skip if memory unavailable).
        let mem_context = match daemon_memory {
            Some(ref mem) => {
                listener.on_progress("Searching memory...".into());
                build_memory_context(mem.as_ref(), &message).await
            }
            None => String::new(),
        };

        // Enrich the user message with memory context and timestamp.
        let timestamp = chrono::Utc::now().format("%Y-%m-%d %H:%M UTC");
        let enriched = if mem_context.is_empty() {
            format!("[{timestamp}] {message}")
        } else {
            format!("{mem_context}[{timestamp}] {message}")
        };

        history.push(ChatMessage::user(enriched));

        // Create provider.
        let provider_runtime_options =
            zeroclaw::providers::provider_runtime_options_from_config(&config);

        let provider = zeroclaw::providers::create_routed_provider_with_options(
            &provider_name,
            config_compat::active_api_key(&config),
            config_compat::active_base_url(&config),
            &config.reliability,
            &config.providers.model_routes,
            &model,
            &provider_runtime_options,
        )
        .map_err(|e| AgentLoopOutcome::Error(format!("failed to create provider: {e}")))?;

        // Build tool specs from the real tools registry plus static
        // descriptions for tools the LLM should know about.
        let mut tool_specs = tool_specs_from_registry(tools);

        // ── STAGE 9 (PART 1): VERIFY MCP TOOLS IN SPECS BEFORE MERGE ─────
        {
            let mcp_specs: Vec<String> = tool_specs.iter()
                .filter(|s| s.name.contains("__") || s.name.contains("Levi") || s.name.starts_with("mcp_"))
                .map(|s| s.name.to_string())
                .collect();
            eprintln!("[STAGE 9] tool_specs_from_registry returned {} specs", tool_specs.len());
            eprintln!("[STAGE 9] MCP tools in specs: {:?}", mcp_specs);
            if mcp_specs.is_empty() {
                eprintln!("[STAGE 9] *** WARNING: No MCP tools in tool_specs from registry! ***");
                eprintln!("[STAGE 9] *** Check STAGE 7 logs to see if MCP tools were registered ***");
            }
        }

        for spec in
            build_android_tool_specs(&config, shared_folder_enabled, workflow_folder_enabled)
        {
            if !tool_specs.iter().any(|s| s.name == spec.name) {
                tool_specs.push(spec);
            }
        }

        // ── STAGE 9 (PART 2): VERIFY MCP TOOLS IN FINAL SPECS ────────────
        {
            let all_names: Vec<&str> = tool_specs.iter().map(|s| s.name.as_str()).collect();
            let mcp_specs: Vec<String> = tool_specs.iter()
                .filter(|s| s.name.contains("__") || s.name.contains("Levi") || s.name.starts_with("mcp_"))
                .map(|s| s.name.to_string())
                .collect();
            eprintln!("[STAGE 9] Final tool_specs count: {} (after android_specs merge)", tool_specs.len());
            eprintln!("[STAGE 9] MCP tools in final specs: {:?}", mcp_specs);
            eprintln!("[STAGE 9] All tool names: {:?}", all_names);
        }

        // ── DEBUG: log provider/model and user message ────────────────────
        {
            let dc_in_specs = tool_specs.iter().any(|s| s.name == "device_control");
            let user_msg_preview = message.chars().take(120).collect::<String>();
            eprintln!("===== REQUEST_START [request_id={request_id}] =====");
            eprintln!("  provider={provider_name} model={model}");
            eprintln!("  tool_specs_count={} device_control_in_specs={dc_in_specs}", tool_specs.len());
            eprintln!("  user_message: {user_msg_preview}");
            eprintln!("===========================================");
        }

        // Run the agent loop with real tool execution.
        let prev_provider_owner = PROVIDER_OWNER_REQ_ID.swap(request_id, std::sync::atomic::Ordering::SeqCst);
        if prev_provider_owner != 0 {
            tracing::warn!(
                target: "zeroclaw::concurrency",
                request_id = request_id,
                ts_us = ts_us(),
                thread_id = %format!("{:?}", std::thread::current().id()),
                session_addr = session_addr,
                previous_owner = prev_provider_owner,
                "OVERLAP_IN_PROVIDER starting run_agent_loop while another request is already inside"
            );
        }
        tracing::info!(
            target: "zeroclaw::concurrency",
            request_id = request_id,
            ts_us = ts_us(),
            thread_id = %format!("{:?}", std::thread::current().id()),
            session_addr = session_addr,
            history_len = history.len(),
            "RUN_AGENT_LOOP_START"
        );
        let loop_result = run_agent_loop(
            provider.as_ref(),
            history,
            tools,
            &tool_specs,
            activated_mcp_tools.clone(),
            &model,
            effective_temperature,
            &cancel_token,
            &listener,
            request_id,
            &config.multimodal,
        )
        .await;
        tracing::info!(
            target: "zeroclaw::concurrency",
            request_id = request_id,
            ts_us = ts_us(),
            thread_id = %format!("{:?}", std::thread::current().id()),
            session_addr = session_addr,
            history_len = history.len(),
            "RUN_AGENT_LOOP_END"
        );
        PROVIDER_OWNER_REQ_ID.store(0, std::sync::atomic::Ordering::SeqCst);
        if turn_system_prompt.is_some() {
            replace_session_system_prompt(history, &system_prompt);
        }
        loop_result
    });

    let duration_ms = started_at.elapsed().as_millis() as u64;

    // Consume the guard (disarms Drop) and put state back explicitly.
    // If we reach this point, no panic occurred, so we handle all
    // three outcomes and restore state ourselves.
    let (mut history, tools) = state_guard.take();

    let history_len_after_loop = history.len();
    let history_hash_after_loop = history_hash(&history);
    let outcome_label = match &result {
        Ok(_) => "Ok",
        Err(AgentLoopOutcome::Cancelled) => "Cancelled",
        Err(AgentLoopOutcome::Error(_)) => "Error",
    };

    tracing::info!(
        target: "zeroclaw::concurrency",
        request_id = request_id,
        ts_us = ts_us(),
        thread_id = %os_thread_id,
        session_addr = session_addr,
        duration_ms = duration_ms,
        history_len_before = history_len_before,
        history_len_after = history_len_after_loop,
        history_hash_after = history_hash_after_loop,
        outcome = outcome_label,
        "AGENT_LOOP_COMPLETE"
    );

    tracing::info!(
        target: "zeroclaw::concurrency",
        request_id = request_id,
        ts_us = ts_us(),
        thread_id = %os_thread_id,
        session_addr = session_addr,
        history_owner_before = HISTORY_OWNER_REQ_ID.load(std::sync::atomic::Ordering::SeqCst),
        "HISTORY_GUARD_DROPPED"
    );

    match result {
        Ok(full_response) => {
            // Run compaction on the history (best-effort, 30s timeout).
            if let Ok(true) = handle.block_on(async {
                let provider_runtime_options =
                    zeroclaw::providers::provider_runtime_options_from_config(&config);
                let provider = zeroclaw::providers::create_provider_with_options(
                    &provider_name,
                    config_compat::active_api_key(&config),
                    &provider_runtime_options,
                )
                .ok();
                if let Some(provider) = provider {
                    match tokio::time::timeout(
                        std::time::Duration::from_secs(30),
                        auto_compact_history(
                            &mut history,
                            provider.as_ref(),
                            &model,
                            DEFAULT_MAX_HISTORY_MESSAGES,
                        ),
                    )
                    .await
                    {
                        Ok(result) => result,
                        Err(_) => {
                            tracing::warn!(
                                target: "zeroclaw::concurrency",
                                request_id = request_id,
                                "COMPACTION_TIMED_OUT after 30s, skipping"
                            );
                            Ok(false)
                        }
                    }
                } else {
                    Ok(false)
                }
            }) {
                // Find the compaction summary (most recent assistant message
                // that starts with "[Compaction summary]").
                if let Some(summary_msg) = history.iter().rev().find(|m| {
                    m.role == "assistant" && m.content.starts_with("[Compaction summary]")
                }) {
                    listener.on_compaction(summary_msg.content.clone());
                }
            }

            CONCURRENT_SEND_COUNT.fetch_sub(1, std::sync::atomic::Ordering::SeqCst);
            tracing::info!(
                target: "zeroclaw::concurrency",
                request_id = request_id,
                ts_us = ts_us(),
                thread_id = %os_thread_id,
                session_addr = session_addr,
                duration_ms = duration_ms,
                history_len_before_restore = history.len(),
                outcome = "Ok",
                "HISTORY_RESTORE"
            );
            put_session_state_back(history, tools, &system_prompt, request_id);
            HISTORY_OWNER_REQ_ID.store(0, std::sync::atomic::Ordering::SeqCst);
            clear_cancel_token();
            listener.on_complete(full_response);
            Ok(())
        }
        Err(AgentLoopOutcome::Cancelled) => {
            CONCURRENT_SEND_COUNT.fetch_sub(1, std::sync::atomic::Ordering::SeqCst);
            tracing::info!(
                target: "zeroclaw::concurrency",
                request_id = request_id,
                ts_us = ts_us(),
                thread_id = %os_thread_id,
                session_addr = session_addr,
                duration_ms = duration_ms,
                history_len_before_restore = history.len(),
                outcome = "Cancelled",
                "HISTORY_RESTORE"
            );
            put_session_state_back(history, tools, &system_prompt, request_id);
            HISTORY_OWNER_REQ_ID.store(0, std::sync::atomic::Ordering::SeqCst);
            clear_cancel_token();
            listener.on_cancelled();
            Ok(())
        }
        Err(AgentLoopOutcome::Error(msg)) => {
            history.truncate(history_len_before);
            CONCURRENT_SEND_COUNT.fetch_sub(1, std::sync::atomic::Ordering::SeqCst);
            tracing::info!(
                target: "zeroclaw::concurrency",
                request_id = request_id,
                ts_us = ts_us(),
                thread_id = %os_thread_id,
                session_addr = session_addr,
                duration_ms = duration_ms,
                history_len_before_restore = history.len(),
                outcome = "Error",
                error = %msg,
                "HISTORY_RESTORE"
            );
            put_session_state_back(history, tools, &system_prompt, request_id);
            HISTORY_OWNER_REQ_ID.store(0, std::sync::atomic::Ordering::SeqCst);
            clear_cancel_token();
            listener.on_error(msg.clone());
            Err(FfiError::SpawnError { detail: msg })
        }
    }
}

/// Injects seed messages into the active session's conversation history.
///
/// Used to restore prior context (e.g. from Room persistence) before the
/// first [`session_send_inner`] call. Messages are appended after the
/// system prompt in the order provided.
///
/// At most [`MAX_SEED_MESSAGES`] entries are accepted. The `role` field of
/// each [`SessionMessage`] must be `"user"` or `"assistant"`; system
/// messages are silently skipped to prevent system prompt corruption.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no session is active, or
/// [`FfiError::StateCorrupted`] if the session mutex is poisoned.
pub(crate) fn session_seed_inner(messages: Vec<SessionMessage>) -> Result<(), FfiError> {
    let mut guard = lock_session();
    let session = guard.as_mut().ok_or_else(|| FfiError::StateError {
        detail: "no active session; call session_start first".into(),
    })?;

    let capped = if messages.len() > MAX_SEED_MESSAGES {
        tracing::warn!(
            count = messages.len(),
            max = MAX_SEED_MESSAGES,
            "Seed messages capped"
        );
        &messages[..MAX_SEED_MESSAGES]
    } else {
        &messages
    };

    for msg in capped {
        match msg.role.as_str() {
            "user" => session.history.push(ChatMessage::user(&msg.content)),
            "assistant" => session.history.push(ChatMessage::assistant(&msg.content)),
            "tool" => {
                tracing::debug!("Skipping seed tool message without paired assistant tool call");
            }
            _ => {
                // Skip system messages to protect the system prompt.
                tracing::debug!(role = %msg.role, "Skipping seed message with reserved role");
            }
        }
    }

    tracing::info!(count = capped.len(), "Seeded session history");
    Ok(())
}

/// Cancels the currently running [`session_send_inner`] call.
///
/// Sets the [`CANCEL_TOKEN`] to cancelled state. The agent loop checks
/// this token between iterations and tool executions, aborting with an
/// [`AgentLoopOutcome::Cancelled`] result. If no send is in progress,
/// this is a no-op.
///
/// # Errors
///
pub(crate) fn session_cancel_inner() {
    let guard = lock_cancel_token();
    if let Some(token) = guard.as_ref() {
        token.cancel();
        // NOTE: tasks are now fire-and-forget from the session
        // perspective. The Rust poll loop will wait for the worker task to reach
        // a terminal state (completed/failed/canceled-by-timeout). Canceling the
        // worker task here was killing in-flight automation that the user
        // had already triggered (e.g. "open Instagram and send a message").
        tracing::info!("Session send cancelled");
    }
}

/// Clears the active session's conversation history, retaining only the
/// system prompt.
///
/// After this call the session behaves as if freshly started -- the
/// system prompt is preserved but all user/assistant/tool messages are
/// discarded.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no session is active, or
/// [`FfiError::StateCorrupted`] if the session mutex is poisoned.
pub(crate) fn session_clear_inner() -> Result<(), FfiError> {
    let mut guard = lock_session();
    let session = guard.as_mut().ok_or_else(|| FfiError::StateError {
        detail: "no active session; call session_start first".into(),
    })?;

    let system_prompt = session.system_prompt.clone();
    session.history = vec![ChatMessage::system(&system_prompt)];

    tracing::info!("Session history cleared");
    Ok(())
}

/// Returns the current conversation history as a list of [`SessionMessage`]
/// records suitable for transfer across the FFI boundary.
///
/// The returned list includes the system prompt (role `"system"`) as the
/// first entry, followed by user, assistant, and tool messages in
/// chronological order.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no session is active, or
/// [`FfiError::StateCorrupted`] if the session mutex is poisoned.
pub(crate) fn session_history_inner() -> Result<Vec<SessionMessage>, FfiError> {
    let guard = lock_session();
    let session = guard.as_ref().ok_or_else(|| FfiError::StateError {
        detail: "no active session; call session_start first".into(),
    })?;

    let messages = session
        .history
        .iter()
        .map(|m| SessionMessage {
            role: m.role.clone(),
            content: m.content.clone(),
        })
        .collect();

    Ok(messages)
}

/// Destroys the active session and releases all associated resources.
///
/// After this call, a new session may be created with
/// [`session_start_inner`]. Any in-flight [`session_send_inner`] call is
/// cancelled first via the [`CANCEL_TOKEN`].
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no session is active, or
/// [`FfiError::StateCorrupted`] if the session mutex is poisoned.
pub(crate) fn session_destroy_inner() -> Result<(), FfiError> {
    // Cancel any in-flight send.
    session_cancel_inner();

    let mut guard = lock_session();
    if guard.take().is_none() {
        return Err(FfiError::StateError {
            detail: "no active session to destroy".into(),
        });
    }

    tracing::info!("Live agent session destroyed");
    Ok(())
}

// ── Agent loop ──────────────────────────────────────────────────────────

/// Outcome categories for the agent loop, used internally to distinguish
/// success, cancellation, and errors without mixing them into `FfiError`.
#[derive(Debug)]
enum AgentLoopOutcome {
    /// The send was cancelled via [`CANCEL_TOKEN`].
    Cancelled,
    /// An unrecoverable error occurred during the loop.
    Error(String),
}

/// Runs the agent tool-call loop until the LLM produces a final text
/// response, the maximum iteration count is reached, or cancellation
/// is signalled.
///
/// For each iteration:
/// 1. Check the cancellation token.
/// 2. Fire `on_thinking` / `on_progress` via the listener.
/// 3. Call `provider.chat(...)` with the current history and tool specs.
/// 4. If no tool calls: stream the final response, append to history, return.
/// 5. If tool calls: execute tools that exist in the registry and report
///    results; tools not in the registry get a fallback "unavailable" message.
///
/// Tools with real implementations (memory, cron, web search) are executed
/// directly. Tools that require upstream's `pub(crate)` `SecurityPolicy`
/// (shell, file I/O, git, browser) are not in the registry and receive
/// an unavailability response so the LLM can answer without them.
///
/// The function returns the full response text on success, or an
/// [`AgentLoopOutcome`] on failure/cancellation.
#[allow(clippy::too_many_arguments, clippy::too_many_lines)]
async fn run_agent_loop(
    provider: &dyn Provider,
    history: &mut Vec<ChatMessage>,
    tools: &[Box<dyn Tool>],
    tool_specs: &[ToolSpec],
    activated_mcp_tools: Option<ActivatedMcpToolsHandle>,
    model: &str,
    temperature: f64,
    cancel_token: &CancellationToken,
    listener: &Arc<dyn FfiSessionListener>,
    request_id: u64,
    multimodal_config: &zeroclaw_config::schema::MultimodalConfig,
) -> Result<String, AgentLoopOutcome> {
    let session_addr = &SESSION as *const _ as usize;
    let mut repeated_shared_folder_failures: HashMap<(String, String), usize> = HashMap::new();

    for iteration in 0..DEFAULT_MAX_TOOL_ITERATIONS {
        // Check cancellation before each iteration.
        if cancel_token.is_cancelled() {
            return Err(AgentLoopOutcome::Cancelled);
        }

        let mut effective_tool_specs = tool_specs.to_vec();
        if let Some(activated) = activated_mcp_tools.as_ref() {
            effective_tool_specs.extend(
                activated
                    .lock()
                    .expect("activated MCP tool registry mutex poisoned")
                    .tool_specs(),
            );
        }
        let use_native_tools = provider.supports_native_tools() && !effective_tool_specs.is_empty();
        let request_tools = if effective_tool_specs.is_empty() {
            None
        } else {
            Some(effective_tool_specs.as_slice())
        };

        // ── DEBUG: dump tool list sent to LLM ────────────────────────────
        {
            let count = effective_tool_specs.len();
            let dc_present = effective_tool_specs.iter().any(|s| s.name == "device_control");
            let tool_names: Vec<&str> = effective_tool_specs.iter().map(|s| s.name.as_str()).collect();
            eprintln!("===== TOOLS_SENT_TO_PROVIDER [request_id={request_id}, iter={iteration}] =====");
            eprintln!("  count={count} device_control_present={dc_present} native_tools={use_native_tools}");
            eprintln!("  tool_names={tool_names:?}");
            for (i, spec) in effective_tool_specs.iter().enumerate() {
                eprintln!("  {}: {} — {}", i + 1, spec.name, spec.description.chars().take(80).collect::<String>());
            }
            eprintln!("============================================");
        }

        // Progress: thinking.
        let phase = if iteration == 0 {
            "Thinking...".to_string()
        } else {
            format!("Thinking (round {})...", iteration + 1)
        };
        listener.on_thinking(phase);

        // Call the provider.
        let msg_count = history.len();
        let sys_prompt_len = history.iter().find(|m| m.role == "system").map_or(0, |m| m.content.len());
        let caps = provider.capabilities();
        let chat_start = std::time::Instant::now();

        tracing::info!(
            target: "zeroclaw::concurrency",
            request_id = request_id,
            ts_us = ts_us(),
            thread_id = %format!("{:?}", std::thread::current().id()),
            session_addr = session_addr,
            iteration = iteration,
            tool_count = effective_tool_specs.len(),
            msg_count = msg_count,
            sys_prompt_len = sys_prompt_len,
            caps_native = caps.native_tool_calling,
            caps_vision = caps.vision,
            caps_cache = caps.prompt_caching,
            model = %model,
            "PROVIDER_CHAT_START"
        );

        // Normalize [IMAGE:] markers: convert local file paths to base64
        // data URIs and trim excess images from older turns. This mirrors
        // the canonical pipeline in zeroclaw-runtime's agent loop.
        let prepared = zeroclaw::providers::multimodal::prepare_messages_for_provider(
            history,
            multimodal_config,
        )
        .await
        .unwrap_or_else(|e| {
            tracing::warn!(
                target: "zeroclaw::concurrency",
                request_id = request_id,
                error = %e,
                "multimodal normalization failed; sending raw history"
            );
            zeroclaw::providers::multimodal::PreparedMessages {
                messages: history.clone(),
                contains_images: false,
            }
        });

        let chat_future = provider.chat(
            ChatRequest {
                messages: &prepared.messages,
                tools: request_tools,
            },
            model,
            Some(temperature),
        );

        let chat_result = tokio::select! {
            () = cancel_token.cancelled() => return Err(AgentLoopOutcome::Cancelled),
            result = chat_future => result,
        };

        let chat_duration_us = chat_start.elapsed().as_micros();

        let response = match chat_result {
            Ok(r) => {
                tracing::info!(
                    target: "zeroclaw::concurrency",
                    request_id = request_id,
                    ts_us = ts_us(),
                    thread_id = %format!("{:?}", std::thread::current().id()),
                    session_addr = session_addr,
                    iteration = iteration,
                    duration_us = chat_duration_us,
                    has_tool_calls = r.has_tool_calls(),
                    response_text_len = r.text.as_ref().map_or(0, |t| t.len()),
                    provider_request_id = 0,
                    "PROVIDER_CHAT_END_SUCCESS"
                );
                r
            }
            Err(e) => {
                tracing::warn!(
                    target: "zeroclaw::concurrency",
                    request_id = request_id,
                    ts_us = ts_us(),
                    thread_id = %format!("{:?}", std::thread::current().id()),
                    session_addr = session_addr,
                    iteration = iteration,
                    duration_us = chat_duration_us,
                    error = %e,
                    provider_request_id = 0,
                    "PROVIDER_CHAT_END_FAILURE"
                );
                return Err(AgentLoopOutcome::Error(format!("provider chat failed: {e}")));
            }
        };

        let (assistant_text, response_tool_calls) =
            extract_response_tool_calls(&response, use_native_tools, iteration);

        // ── DEBUG: log tool selection ────────────────────────────────────
        if response_tool_calls.is_empty() {
            eprintln!("===== TOOL_SELECTION [request_id={request_id}, iter={iteration}]: no tool (final response) =====");
        } else {
            for tc in &response_tool_calls {
                eprintln!("===== TOOL_SELECTION [request_id={request_id}, iter={iteration}]: {} =====", tc.name);
                eprintln!("       arguments: {}", tc.arguments);
            }
        }

        // No tool calls -- this is the final response.
        if response_tool_calls.is_empty() {
            let text = assistant_text;

            // Stream response in chunks.
            stream_response_text(&text, listener, cancel_token)?;

            history.push(ChatMessage::assistant(&text));
            return Ok(text);
        }

        // Has tool calls -- execute those we have and report unavailable for the rest.
        let tool_call_count = response_tool_calls.len();
        listener.on_progress(format!("Got {tool_call_count} tool call(s)"));

        // Push assistant message with tool calls context.
        if use_native_tools {
            let native_history = build_native_assistant_history(
                &assistant_text,
                &response_tool_calls,
                response.reasoning_content.as_deref(),
            );
            history.push(ChatMessage::assistant(native_history));
        } else {
            history.push(ChatMessage::assistant(&assistant_text));
        }

        // Execute or respond to each tool call.
        let mut tool_results_text = String::new();

        for call in &response_tool_calls {
            if cancel_token.is_cancelled() {
                return Err(AgentLoopOutcome::Cancelled);
            }

            let args: serde_json::Value =
                serde_json::from_str(&call.arguments).unwrap_or(serde_json::json!({}));
            let tool = tools.iter().find(|t| t.name() == call.name);
            let activated_tool = if tool.is_none() {
                activated_mcp_tools.as_ref().and_then(|activated| {
                    activated
                        .lock()
                        .expect("activated MCP tool registry mutex poisoned")
                        .get_resolved(&call.name)
                })
            } else {
                None
            };
            let composio_tool_search = if tool.is_none()
                && activated_tool.is_none()
                && should_route_composio_name_to_tool_search(&call.name)
            {
                tools
                    .iter()
                    .find(|t| t.name() == "tool_search")
                    .and_then(|tool_search| {
                        composio_tool_search_query(&call.name, &call.arguments)
                            .map(|query| (tool_search, serde_json::json!({"query": query})))
                    })
            } else {
                None
            };

            let (display_tool_name, display_arguments) =
                if let Some((_, search_args)) = composio_tool_search.as_ref() {
                    ("tool_search", search_args.to_string())
                } else {
                    (call.name.as_str(), call.arguments.clone())
                };
            let args_hint = truncate_tool_args_hint(display_tool_name, &display_arguments);
            listener.on_tool_start(display_tool_name.to_string(), args_hint);

            let start_time = std::time::Instant::now();

            let exec_result = if let Some(tool) = tool {
                Some(tokio::select! {
                    () = cancel_token.cancelled() => {
                        return Err(AgentLoopOutcome::Cancelled);
                    }
                    result = tool.execute(args) => result,
                })
            } else if let Some(tool) = activated_tool {
                Some(tokio::select! {
                    () = cancel_token.cancelled() => {
                        return Err(AgentLoopOutcome::Cancelled);
                    }
                    result = tool.execute(args) => result,
                })
            } else if let Some((tool_search, search_args)) = composio_tool_search {
                Some(tokio::select! {
                    () = cancel_token.cancelled() => {
                        return Err(AgentLoopOutcome::Cancelled);
                    }
                    result = tool_search.execute(search_args) => result,
                })
            } else {
                None
            };

            let (success, output) = match exec_result {
                Some(Ok(result)) if result.success => (true, result.output),
                Some(Ok(result)) => (
                    false,
                    result
                        .error
                        .unwrap_or_else(|| "Tool failed without error message".into()),
                ),
                Some(Err(e)) => (false, format!("Tool execution error: {e}")),
                None => unavailable_tool_result(&call.name, activated_mcp_tools.as_ref()),
            };

            let duration_secs = start_time.elapsed().as_secs();
            listener.on_tool_result(call.name.clone(), success, duration_secs);
            listener.on_tool_output(call.name.clone(), output.clone());

            let repeated_shared_folder_failure = repeated_shared_folder_failure_response(
                call,
                success,
                &output,
                &mut repeated_shared_folder_failures,
            );
            let terminal_tool_failure = terminal_tool_failure_response(call, success, &output);

            if use_native_tools {
                let tool_msg = serde_json::json!({
                    "tool_call_id": call.id,
                    "content": output,
                });
                history.push(ChatMessage::tool(tool_msg.to_string()));
            } else {
                let _ = writeln!(
                    tool_results_text,
                    "<tool_result name=\"{}\">\n{output}\n</tool_result>",
                    call.name
                );
            }

            if let Some(text) = repeated_shared_folder_failure.or(terminal_tool_failure) {
                if !use_native_tools && !tool_results_text.is_empty() {
                    history.push(ChatMessage::user(format!(
                        "[Tool results]\n{tool_results_text}"
                    )));
                }
                stream_response_text(&text, listener, cancel_token)?;
                history.push(ChatMessage::assistant(&text));
                return Ok(text);
            }
        }

        // For prompt-guided mode, append collected tool results as a user message.
        if !use_native_tools && !tool_results_text.is_empty() {
            history.push(ChatMessage::user(format!(
                "[Tool results]\n{tool_results_text}"
            )));
        }
    }

    Err(AgentLoopOutcome::Error(format!(
        "Agent exceeded maximum tool iterations ({DEFAULT_MAX_TOOL_ITERATIONS})"
    )))
}

// ── Prompt-guided tool-call extraction ──────────────────────────────────

fn repeated_shared_folder_failure_response(
    call: &ToolCall,
    success: bool,
    output: &str,
    repeated_failures: &mut HashMap<(String, String), usize>,
) -> Option<String> {
    if success || call.name != "shared_folder_read" {
        return None;
    }

    let path = shared_folder_read_path(&call.arguments);
    let error = output.trim();
    if error.is_empty() {
        return None;
    }

    let key = (path.clone(), error.to_string());
    let count = repeated_failures.entry(key).or_insert(0);
    *count += 1;

    if *count < 2 {
        return None;
    }

    Some(format!(
        "I couldn't read `{path}` from the shared folder after the same read failed twice: {error}. I stopped retrying that file so this turn doesn't stall. Please choose another file, reconnect/select the shared folder again, or continue without that attachment."
    ))
}

fn shared_folder_read_path(arguments: &str) -> String {
    serde_json::from_str::<serde_json::Value>(arguments)
        .ok()
        .and_then(|value| {
            value
                .get("path")
                .and_then(|path| path.as_str())
                .map(str::to_string)
        })
        .unwrap_or_else(|| "<unknown>".to_string())
}

fn terminal_tool_failure_response(call: &ToolCall, success: bool, output: &str) -> Option<String> {
    if is_composio_tool_not_found_result(call, success, output) {
        let tool_name = composio_missing_tool_name(call, output)
            .unwrap_or_else(|| "the requested Composio/Gmail tool".to_string());
        return Some(format!(
            "I can't complete that action because `{tool_name}` is not available in the current Composio tool catalog. I stopped retrying so this turn doesn't run out of tool attempts. Use `tool_search` to choose an exact available Gmail/Composio tool, refresh the Composio connection/catalog, or try again with a supported action."
        ));
    }

    if success || !is_composio_account_pending_error(output) {
        return None;
    }

    let app = composio_call_app(&call.arguments).unwrap_or_else(|| "the requested app".to_string());
    Some(format!(
        "I can't complete that {app} action yet because the Composio connected account is not ready for execution, and Composio only authorizes actions after the account becomes ACTIVE. I stopped retrying so this turn doesn't run out of tool attempts. Please wait for the connection to show ACTIVE in Connections, or reconnect the account, then try the send again."
    ))
}

fn is_composio_account_pending_error(output: &str) -> bool {
    let lower = output.to_ascii_lowercase();
    lower.contains("connected account is not in an active state")
        || (lower.contains("connected account") && lower.contains("not active"))
        || lower.contains("not active/initializing")
        || lower.contains("not active or initializing")
        || (lower.contains("current status is \"initializing\"")
            && lower.contains("active status is required"))
        || lower.contains("is not active yet")
}

fn is_composio_tool_not_found_result(call: &ToolCall, success: bool, output: &str) -> bool {
    if output.trim().is_empty() || !is_tool_not_found_error(output) {
        return false;
    }

    if success && call.name != "tool_search" {
        return false;
    }

    is_composio_or_gmail_context(call, output)
}

fn is_tool_not_found_error(output: &str) -> bool {
    let lower = output.to_ascii_lowercase();
    lower.contains("not found")
        && (lower.contains("tool")
            || lower.contains("not found:")
            || lower.contains("composio")
            || lower.contains("gmail_")
            || lower.contains("gmail-")
            || lower.contains("composio__"))
}

fn is_composio_or_gmail_context(call: &ToolCall, output: &str) -> bool {
    let name = call.name.to_ascii_lowercase();
    let output = output.to_ascii_lowercase();
    let tool_search_query = composio_tool_search_query_arg(&call.arguments)
        .unwrap_or_default()
        .to_ascii_lowercase();

    is_legacy_composio_tool_name(&call.name)
        || name.starts_with("composio__")
        || name.starts_with("composio_")
        || name.starts_with("gmail_")
        || name.starts_with("gmail-")
        || (name == "tool_search"
            && (tool_search_query.contains("composio")
                || tool_search_query.contains("gmail")
                || output.contains("composio")
                || output.contains("gmail")))
        || composio_call_app(&call.arguments).is_some()
        || output.contains("composio")
        || output.contains("gmail")
}

fn composio_missing_tool_name(call: &ToolCall, output: &str) -> Option<String> {
    if call.name != "tool_search" && !is_legacy_composio_tool_name(&call.name) {
        return Some(call.name.clone());
    }

    extract_not_found_tool_name(output)
        .or_else(|| composio_call_action_name(&call.arguments))
        .or_else(|| composio_tool_search_query_arg(&call.arguments))
}

fn extract_not_found_tool_name(output: &str) -> Option<String> {
    if let Some((_, after)) = output.split_once("Not found:") {
        return clean_tool_name_candidate(after.split([',', '\n', '\r']).next().unwrap_or(after));
    }

    let lower = output.to_ascii_lowercase();
    let index = lower.find(" not found")?;
    let before = &output[..index];
    let candidate = before
        .rsplit([' ', '`', '\'', '"'])
        .find(|part| !part.trim().is_empty())
        .unwrap_or(before);
    clean_tool_name_candidate(candidate.strip_prefix("Tool ").unwrap_or(candidate))
}

fn clean_tool_name_candidate(candidate: &str) -> Option<String> {
    let cleaned = candidate
        .trim()
        .trim_matches(['`', '\'', '"', '.', ':', ';'])
        .trim();
    (!cleaned.is_empty()).then(|| cleaned.to_string())
}

fn composio_call_action_name(arguments: &str) -> Option<String> {
    let value = serde_json::from_str::<serde_json::Value>(arguments).ok()?;

    for key in ["tool_slug", "action_name", "action"] {
        if let Some(name) = value
            .get(key)
            .and_then(|v| v.as_str())
            .map(str::trim)
            .filter(|name| !name.is_empty())
        {
            return Some(name.to_string());
        }
    }

    value.get("params").and_then(|params| {
        ["tool_slug", "action_name", "action"]
            .into_iter()
            .find_map(|key| {
                params
                    .get(key)
                    .and_then(|v| v.as_str())
                    .map(str::trim)
                    .filter(|name| !name.is_empty())
                    .map(str::to_string)
            })
    })
}

fn composio_tool_search_query_arg(arguments: &str) -> Option<String> {
    serde_json::from_str::<serde_json::Value>(arguments)
        .ok()
        .and_then(|value| {
            value
                .get("query")
                .and_then(|query| query.as_str())
                .map(str::trim)
                .filter(|query| !query.is_empty())
                .map(|query| query.strip_prefix("select:").unwrap_or(query).to_string())
        })
}

fn composio_call_app(arguments: &str) -> Option<String> {
    serde_json::from_str::<serde_json::Value>(arguments)
        .ok()
        .and_then(|value| {
            value
                .get("app")
                .or_else(|| value.get("toolkit"))
                .or_else(|| value.get("toolkit_slug"))
                .and_then(|app| app.as_str())
                .map(str::to_string)
        })
}

fn is_legacy_composio_tool_name(tool_name: &str) -> bool {
    matches!(tool_name, "composio" | "run_composio_tool")
}

fn should_route_composio_name_to_tool_search(tool_name: &str) -> bool {
    is_legacy_composio_tool_name(tool_name)
        || tool_name.starts_with("composio__")
        || tool_name.starts_with("COMPOSIO_")
}

fn composio_tool_search_query(tool_name: &str, arguments: &str) -> Option<String> {
    if tool_name.starts_with("composio__") {
        return Some(format!("select:{tool_name}"));
    }

    if is_legacy_composio_tool_name(tool_name) {
        return legacy_composio_tool_search_query(arguments);
    }

    let remote_name_query = tool_name.replace(['_', '-'], " ");
    let arguments_query = legacy_composio_tool_search_query(arguments);
    Some(match arguments_query {
        Some(query) if !query.is_empty() => format!("{remote_name_query} {query}"),
        _ => remote_name_query,
    })
}

fn legacy_composio_tool_search_query(arguments: &str) -> Option<String> {
    let value = serde_json::from_str::<serde_json::Value>(arguments).ok()?;
    let mut parts = Vec::new();

    for key in [
        "app",
        "toolkit",
        "toolkit_slug",
        "action_name",
        "tool_slug",
        "action",
    ] {
        if let Some(part) = value
            .get(key)
            .and_then(|v| v.as_str())
            .map(str::trim)
            .filter(|part| !part.is_empty())
        {
            parts.push(part.to_string());
        }
    }

    if let Some(params) = value.get("params").and_then(|v| v.as_object()) {
        for key in ["action", "action_name", "tool_slug"] {
            if let Some(part) = params
                .get(key)
                .and_then(|v| v.as_str())
                .map(str::trim)
                .filter(|part| !part.is_empty())
            {
                parts.push(part.to_string());
            }
        }
    }

    if let Some(text) = value
        .get("text")
        .and_then(|v| v.as_str())
        .map(str::trim)
        .filter(|text| !text.is_empty())
    {
        parts.push(text.to_string());
    }

    (!parts.is_empty()).then(|| parts.join(" "))
}

fn unavailable_tool_result(
    tool_name: &str,
    activated_mcp_tools: Option<&ActivatedMcpToolsHandle>,
) -> (bool, String) {
    if tool_name == "composio" || tool_name == "run_composio_tool" {
        let mut message = format!(
            "Tool '{tool_name}' is a legacy Composio tool name and is not the active tool surface for a Composio Sessions/MCP key."
        );
        message.push_str(
            " Call `tool_search` first and then use the exact `composio__...` tool name it returns.",
        );
        message.push_str(" Do not retry this legacy tool name.");
        return (true, message);
    }

    let mut message = format!(
        "Tool '{tool_name}' is not available in this session. Please answer directly without this tool."
    );

    if tool_name.starts_with("composio__") || tool_name.starts_with("COMPOSIO_") {
        message.push_str(
            " This Composio MCP tool is not activated or the name is misspelled. Call `tool_search` with `select:<exact_tool_name>` or search by app/action keywords, then use the exact returned tool name.",
        );
    }

    let Some(activated) = activated_mcp_tools else {
        return (false, message);
    };
    let names: Vec<String> = activated
        .lock()
        .expect("activated MCP tool registry mutex poisoned")
        .tool_names()
        .into_iter()
        .map(str::to_string)
        .collect();
    if names.is_empty() {
        return (false, message);
    }

    let preview: Vec<String> = names.into_iter().take(8).collect();
    message.push_str(" Activated MCP tools include: ");
    message.push_str(&preview.join(", "));
    (false, message)
}

fn extract_response_tool_calls(
    response: &ChatResponse,
    use_native_tools: bool,
    iteration: usize,
) -> (String, Vec<ToolCall>) {
    let mut tool_calls = response.tool_calls.clone();
    let mut text = response.text_or_empty().to_string();

    if !use_native_tools {
        let dispatcher = zeroclaw::agent::dispatcher::XmlToolDispatcher;
        let (xml_text, calls) = dispatcher.parse_response(response);
        text = xml_text;
        tool_calls.extend(calls.into_iter().enumerate().map(|(index, call)| {
            ToolCall {
                id: call
                    .tool_call_id
                    .unwrap_or_else(|| format!("prompt_call_{iteration}_{index}")),
                name: call.name,
                arguments: serde_json::to_string(&call.arguments)
                    .unwrap_or_else(|_| "{}".to_string()),
            }
        }));
    }

    if tool_calls.is_empty()
        && let Some((json_text, json_tool_calls)) =
            parse_json_tool_call_text(response.text_or_empty(), iteration)
    {
        text = json_text;
        tool_calls = json_tool_calls;
    } else if tool_calls.is_empty() && looks_like_json_tool_call_text(response.text_or_empty()) {
        text = TOOL_CALL_PARSE_ERROR_MESSAGE.to_string();
    }

    (text, tool_calls)
}

// ── JSON tool-call envelope parsing ─────────────────────────────────────

const TOOL_CALL_PARSE_ERROR_MESSAGE: &str =
    "The model returned a malformed tool call, so I could not execute it. Please try again.";

fn looks_like_json_tool_call_text(text: &str) -> bool {
    let trimmed = trim_json_tool_call_fence(text);
    trimmed.contains("\"tool_calls\"")
        || (trimmed.contains("\"function\"") && trimmed.contains("\"arguments\""))
}

fn parse_json_tool_call_text(text: &str, iteration: usize) -> Option<(String, Vec<ToolCall>)> {
    let trimmed = trim_json_tool_call_fence(text);
    for candidate in json_tool_call_candidates(trimmed) {
        let Ok(parsed) = serde_json::from_str::<serde_json::Value>(&candidate) else {
            continue;
        };

        if let Some(tool_calls) = json_tool_call_value_to_provider_calls(&parsed, iteration) {
            return Some(tool_calls);
        }
    }

    None
}

fn json_tool_call_candidates(text: &str) -> Vec<String> {
    let mut candidates = Vec::new();
    push_unique_json_tool_call_candidate(&mut candidates, text.trim().to_string());

    if let Some(object) = first_balanced_json_object(text) {
        push_unique_json_tool_call_candidate(&mut candidates, object.to_string());
    }

    if let Some(fragment) = tool_calls_fragment_json_object(text) {
        push_unique_json_tool_call_candidate(&mut candidates, fragment);
    }

    candidates
}

fn push_unique_json_tool_call_candidate(candidates: &mut Vec<String>, candidate: String) {
    if candidate.is_empty() || candidates.iter().any(|existing| existing == &candidate) {
        return;
    }

    candidates.push(candidate);
}

fn first_balanced_json_object(text: &str) -> Option<&str> {
    let start = text.find('{')?;
    let end = balanced_json_end(text, start, '{', '}')?;
    Some(&text[start..end])
}

fn tool_calls_fragment_json_object(text: &str) -> Option<String> {
    let key_start = text.find("\"tool_calls\"")?;
    let array_start = text[key_start..].find('[')? + key_start;
    let array_end = balanced_json_end(text, array_start, '[', ']')?;
    Some(format!("{{{}}}", &text[key_start..array_end]))
}

fn balanced_json_end(text: &str, start: usize, open: char, close: char) -> Option<usize> {
    let mut depth = 0usize;
    let mut in_string = false;
    let mut escaped = false;

    for (offset, ch) in text[start..].char_indices() {
        if in_string {
            if escaped {
                escaped = false;
            } else if ch == '\\' {
                escaped = true;
            } else if ch == '"' {
                in_string = false;
            }
            continue;
        }

        if ch == '"' {
            in_string = true;
            continue;
        }

        if ch == open {
            depth += 1;
        } else if ch == close {
            depth = depth.checked_sub(1)?;
            if depth == 0 {
                return Some(start + offset + ch.len_utf8());
            }
        }
    }

    None
}

fn json_tool_call_value_to_provider_calls(
    parsed: &serde_json::Value,
    iteration: usize,
) -> Option<(String, Vec<ToolCall>)> {
    let content = parsed
        .get("content")
        .and_then(serde_json::Value::as_str)
        .unwrap_or("")
        .to_string();

    let tool_calls: Vec<ToolCall> = if let Some(calls) = parsed
        .get("tool_calls")
        .and_then(serde_json::Value::as_array)
    {
        calls
            .iter()
            .enumerate()
            .filter_map(|(index, call)| json_tool_call_to_provider_call(call, iteration, index))
            .collect()
    } else if let Some(calls) = parsed.as_array() {
        calls
            .iter()
            .enumerate()
            .filter_map(|(index, call)| json_tool_call_to_provider_call(call, iteration, index))
            .collect()
    } else {
        json_tool_call_to_provider_call(parsed, iteration, 0)
            .into_iter()
            .collect()
    };

    (!tool_calls.is_empty()).then_some((content, tool_calls))
}

fn trim_json_tool_call_fence(text: &str) -> &str {
    let trimmed = text.trim();
    let Some(without_opening) = trimmed.strip_prefix("```") else {
        return trimmed;
    };

    let without_opening = without_opening
        .strip_prefix("json")
        .or_else(|| without_opening.strip_prefix("JSON"))
        .unwrap_or(without_opening)
        .trim_start();

    without_opening
        .strip_suffix("```")
        .map_or(trimmed, str::trim)
}

fn json_tool_call_to_provider_call(
    call: &serde_json::Value,
    iteration: usize,
    index: usize,
) -> Option<ToolCall> {
    let name = call
        .get("name")
        .and_then(serde_json::Value::as_str)
        .or_else(|| {
            call.get("function")
                .and_then(|function| function.get("name"))
                .and_then(serde_json::Value::as_str)
        })?
        .to_string();

    let arguments = json_tool_call_arguments(call)?;
    let id = call
        .get("id")
        .and_then(serde_json::Value::as_str)
        .or_else(|| call.get("tool_call_id").and_then(serde_json::Value::as_str))
        .map_or_else(
            || format!("json_call_{iteration}_{index}"),
            ToString::to_string,
        );

    Some(ToolCall {
        id,
        name,
        arguments: serde_json::to_string(&arguments).unwrap_or_else(|_| "{}".to_string()),
    })
}

fn json_tool_call_arguments(call: &serde_json::Value) -> Option<serde_json::Value> {
    if let Some(arguments) = call.get("arguments") {
        return normalize_json_tool_call_arguments(arguments);
    }

    let function = call.get("function")?;
    if let Some(arguments) = function.get("arguments") {
        return normalize_json_tool_call_arguments(arguments);
    }

    function.as_object().map(|object| {
        let mut args = object.clone();
        args.remove("name");
        serde_json::Value::Object(args)
    })
}

fn normalize_json_tool_call_arguments(value: &serde_json::Value) -> Option<serde_json::Value> {
    if let Some(arguments) = value.as_str() {
        serde_json::from_str(arguments).ok()
    } else {
        Some(value.clone())
    }
}

// ── Multimodal message composition ──────────────────────────────────────

/// Composes a user message with embedded `[IMAGE:...]` markers.
///
/// When `image_data` is empty the original `text` is returned unchanged.
/// Otherwise each base64-encoded image is appended as an `[IMAGE:data:
/// <mime>;base64,<payload>]` marker. The upstream provider's
/// `to_message_content` parser (in `compatible.rs`) recognises these
/// markers and converts them to multimodal content parts.
fn compose_multimodal_message(text: &str, image_data: &[String], mime_types: &[String]) -> String {
    if image_data.is_empty() {
        return text.to_string();
    }

    let mut buf =
        String::with_capacity(text.len() + image_data.iter().map(String::len).sum::<usize>() + 256);
    buf.push_str(text);

    for (data, mime) in image_data.iter().zip(mime_types.iter()) {
        buf.push_str("\n\n[IMAGE:data:");
        buf.push_str(mime);
        buf.push_str(";base64,");
        buf.push_str(data);
        buf.push(']');
    }

    buf
}

// ── Android identity extras ─────────────────────────────────────────────

/// Appends Android-specific identity fields to the system prompt.
///
/// The upstream AIEOS renderer only outputs agent identity (name, bio,
/// personality). Android onboarding also stores `user_name`, `timezone`,
/// and `communication_style` inside the `identity` JSON object. These
/// fields are silently dropped by serde because they don't exist in the
/// upstream `IdentitySection` struct.
///
/// This function parses the raw `aieos_inline` JSON, extracts those
/// extra fields, and appends a "## User Context" section to the prompt.
fn append_android_identity_extras(
    prompt: &mut String,
    identity_config: &zeroclaw::config::IdentityConfig,
) {
    use std::fmt::Write;

    let Some(ref inline) = identity_config.aieos_inline else {
        return;
    };

    let Ok(root) = serde_json::from_str::<serde_json::Value>(inline) else {
        return;
    };

    let identity_obj = match root.get("identity") {
        Some(v) => v,
        None => &root,
    };

    let user_name = identity_obj
        .get("user_name")
        .and_then(|v| v.as_str())
        .unwrap_or("");
    let timezone = identity_obj
        .get("timezone")
        .and_then(|v| v.as_str())
        .unwrap_or("");
    let comm_style = identity_obj
        .get("communication_style")
        .and_then(|v| v.as_str())
        .unwrap_or("");

    if user_name.is_empty() && timezone.is_empty() && comm_style.is_empty() {
        return;
    }

    prompt.push_str("\n## User Context\n\n");
    if !user_name.is_empty() {
        let _ = writeln!(prompt, "**User's name:** {user_name}");
    }
    if !timezone.is_empty() {
        let _ = writeln!(prompt, "**Timezone:** {timezone}");
    }
    if !comm_style.is_empty() {
        let _ = writeln!(prompt, "**Preferred communication style:** {comm_style}");
    }
}

// ── Memory context ──────────────────────────────────────────────────────

/// Queries the memory backend for entries relevant to the user message
/// and formats them as a context preamble string.
///
/// Mirrors upstream `build_context()` but simplified for the FFI session.
/// Entries whose key matches the assistant autosave pattern are skipped
/// to avoid injecting raw LLM output back as context.
///
/// Returns an empty string if no relevant memories are found or the
/// memory query fails.
async fn build_memory_context(mem: &dyn Memory, query: &str) -> String {
    let Ok(entries) = mem.recall(query, 5, None, None, None).await else {
        return String::new();
    };

    // Filter out autosave entries and low-relevance results.
    let relevant: Vec<_> = entries
        .iter()
        .filter(|e| !zeroclaw::memory::is_assistant_autosave_key(&e.key))
        .filter(|e| match e.score {
            Some(score) => score >= 0.3,
            None => true,
        })
        .collect();

    if relevant.is_empty() {
        return String::new();
    }

    let mut context = String::from("[Memory context]\n");
    for entry in &relevant {
        let _ = writeln!(context, "- {}: {}", entry.key, entry.content);
    }
    context.push('\n');

    context
}

// ── Compaction ───────────────────────────────────────────────────────────

/// Automatically compacts conversation history when it exceeds the
/// `max_history` threshold.
///
/// Mirrors upstream `auto_compact_history()`:
/// 1. Counts non-system messages.
/// 2. If count exceeds `max_history`, takes the oldest messages
///    (keeping [`COMPACTION_KEEP_RECENT`] recent ones).
/// 3. Builds a transcript of the compactable messages.
/// 4. Asks the provider to summarise the transcript.
/// 5. Replaces the compacted messages with a single
///    `[Compaction summary]` assistant message.
///
/// Returns `true` if compaction occurred, `false` if history was within
/// limits.
async fn auto_compact_history(
    history: &mut Vec<ChatMessage>,
    provider: &dyn Provider,
    model: &str,
    max_history: usize,
) -> Result<bool, AgentLoopOutcome> {
    let has_system = history.first().is_some_and(|m| m.role == "system");
    let non_system_count = if has_system {
        history.len().saturating_sub(1)
    } else {
        history.len()
    };

    if non_system_count <= max_history {
        return Ok(false);
    }

    let start = usize::from(has_system);
    let keep_recent = COMPACTION_KEEP_RECENT.min(non_system_count);
    let compact_count = non_system_count.saturating_sub(keep_recent);
    if compact_count == 0 {
        return Ok(false);
    }

    let compact_end = start + compact_count;
    let to_compact: Vec<ChatMessage> = history[start..compact_end].to_vec();
    let transcript = build_compaction_transcript(&to_compact);

    let summariser_system = "You are a conversation compaction engine. Summarize older chat \
        history into concise context for future turns. Preserve: user preferences, commitments, \
        decisions, unresolved tasks, key facts. Omit: filler, repeated chit-chat, verbose tool \
        logs. Output plain text bullet points only.";

    let summariser_user = format!(
        "Summarize the following conversation history for context preservation. \
         Keep it short (max 12 bullet points).\n\n{transcript}"
    );

    let summary_raw = provider
        .chat_with_system(Some(summariser_system), &summariser_user, model, Some(0.2))
        .await
        .unwrap_or_else(|_| {
            // Fallback to deterministic local truncation.
            truncate_chars(&transcript, COMPACTION_MAX_SUMMARY_CHARS)
        });

    let summary = truncate_chars(&summary_raw, COMPACTION_MAX_SUMMARY_CHARS);

    let summary_msg = ChatMessage::assistant(format!("[Compaction summary]\n{}", summary.trim()));
    history.splice(start..compact_end, std::iter::once(summary_msg));

    Ok(true)
}

/// Trims conversation history to prevent unbounded growth.
///
/// Preserves the system prompt (first message if role=system) and the most
/// recent `max_history` non-system messages, draining the oldest entries.
fn trim_history(history: &mut Vec<ChatMessage>, max_history: usize) {
    let has_system = history.first().is_some_and(|m| m.role == "system");
    let non_system_count = if has_system {
        history.len().saturating_sub(1)
    } else {
        history.len()
    };

    if non_system_count <= max_history {
        return;
    }

    let start = usize::from(has_system);
    let to_remove = non_system_count - max_history;
    history.drain(start..start + to_remove);
}

fn replace_session_system_prompt(history: &mut Vec<ChatMessage>, system_prompt: &str) {
    if let Some(message) = history.first_mut()
        && message.role == "system"
    {
        message.content = system_prompt.to_string();
        return;
    }
    history.insert(0, ChatMessage::system(system_prompt));
}

/// Updates the `## Shell Environment` section in the session's system prompt
/// with the latest sandbox shell state (cwd, last exit, jobs).
///
/// Called after each `session_send` turn so the LLM always sees current context.
fn update_shell_context_in_session(session: &mut Session) {
    let ctx = {
        let rt = session.shell_runtime.lock().unwrap_or_else(|e| e.into_inner());
        rt.context_string()
    };
    let prompt = &mut session.system_prompt;
    let marker = "## Shell Environment\n\n";
    match prompt.find(marker) {
        Some(start) => {
            // Find the end of the section (next "\n## " or end of string).
            let content_start = start + marker.len();
            let end = prompt[content_start..]
                .find("\n## ")
                .map(|i| content_start + i)
                .unwrap_or(prompt.len());
            prompt.replace_range(start..end, &format!("{marker}{ctx}\n"));
        }
        None => {
            if !ctx.is_empty() {
                prompt.push_str(&format!("\n\n{marker}{ctx}\n"));
            }
        }
    }
    // Sync the updated prompt into the history's system message.
    if let Some(msg) = session.history.first_mut()
        && msg.role == "system"
    {
        msg.content = prompt.clone();
    }
}

/// Builds a transcript of messages for the compaction summariser.
///
/// Each message is formatted as `"ROLE: content"` on its own line.
/// The output is capped at [`COMPACTION_MAX_SOURCE_CHARS`] characters.
fn build_compaction_transcript(messages: &[ChatMessage]) -> String {
    let mut transcript = String::new();
    for msg in messages {
        let role = msg.role.to_uppercase();
        let _ = writeln!(transcript, "{role}: {}", msg.content.trim());
    }

    if transcript.chars().count() > COMPACTION_MAX_SOURCE_CHARS {
        truncate_chars(&transcript, COMPACTION_MAX_SOURCE_CHARS)
    } else {
        transcript
    }
}

// ── Helpers ─────────────────────────────────────────────────────────────

/// Truncates a string to `max_chars` characters, appending `"..."` if truncated.
fn truncate_chars(s: &str, max_chars: usize) -> String {
    match s.char_indices().nth(max_chars) {
        Some((idx, _)) => {
            let truncated = &s[..idx];
            format!("{}...", truncated.trim_end())
        }
        None => s.to_string(),
    }
}

/// Builds tool specifications for the Android-appropriate tool set.
///
/// These specs are passed to `provider.chat()` so the LLM is aware of
/// available tools. Real session tools provide their own full schemas through
/// the registry; this helper supplies fallback metadata for static tools that
/// may still be surfaced in the prompt.
fn build_android_tool_specs(
    config: &zeroclaw::Config,
    shared_folder_enabled: bool,
    workflow_folder_enabled: bool,
) -> Vec<ToolSpec> {
    let descs = build_android_tool_descs(config, shared_folder_enabled, workflow_folder_enabled);
    descs
        .into_iter()
        .map(|(name, description)| ToolSpec {
            name,
            description,
            parameters: serde_json::json!({
                "type": "object",
                "properties": {},
            }),
        })
        .collect()
}

/// Builds a JSON-structured assistant history entry for native tool calling mode.
///
/// Preserves tool call IDs so subsequent `role=tool` messages can reference
/// the correct call. Also preserves `reasoning_content` from thinking models.
fn build_native_assistant_history(
    text: &str,
    tool_calls: &[zeroclaw::providers::ToolCall],
    reasoning_content: Option<&str>,
) -> String {
    let calls_json: Vec<serde_json::Value> = tool_calls
        .iter()
        .map(|tc| {
            serde_json::json!({
                "id": tc.id,
                "name": tc.name,
                "arguments": tc.arguments,
            })
        })
        .collect();

    let mut msg = serde_json::json!({
        "content": text,
        "tool_calls": calls_json,
    });

    if let Some(rc) = reasoning_content {
        msg["reasoning_content"] = serde_json::Value::String(rc.to_string());
    }

    msg.to_string()
}

/// Puts the working history and tools registry back into the [`SESSION`] mutex.
///
/// If the session was destroyed while the send was in progress, the
/// state is silently dropped (the session slot will be `None`).
fn put_session_state_back(
    history: Vec<ChatMessage>,
    tools: Vec<Box<dyn Tool>>,
    _system_prompt: &str,
    request_id: u64,
) {
    let restored_len = history.len();
    let restored_hash = history_hash(&history);
    let session_addr = &SESSION as *const _ as usize;

    tracing::info!(
        target: "zeroclaw::concurrency",
        request_id = request_id,
        ts_us = ts_us(),
        thread_id = %format!("{:?}", std::thread::current().id()),
        session_addr = session_addr,
        restored_len = restored_len,
        restored_hash = restored_hash,
        "SESSION_STATE_RESTORE_BEFORE"
    );

    let restore_start = std::time::Instant::now();
    let mut guard = lock_session();
    let verify = if let Some(session) = guard.as_mut() {
        let prev_history_len = session.history.len();
        let prev_history_hash = history_hash(&session.history);

        session.history = history;
        session.tools_registry = tools;

        // Update the system prompt's shell context with the latest sandbox state.
        update_shell_context_in_session(session);

        let final_history_len = session.history.len();
        let final_history_hash = history_hash(&session.history);
        let restore_duration_us = restore_start.elapsed().as_micros();

        tracing::info!(
            target: "zeroclaw::concurrency",
            request_id = request_id,
            ts_us = ts_us(),
            thread_id = %format!("{:?}", std::thread::current().id()),
            session_addr = session_addr,
            prev_history_len = prev_history_len,
            prev_history_hash = prev_history_hash,
            restored_len = restored_len,
            restored_hash = restored_hash,
            final_history_len = final_history_len,
            final_history_hash = final_history_hash,
            restore_duration_us = restore_duration_us,
            "SESSION_STATE_RESTORE_AFTER"
        );

        if prev_history_len != 0 {
            tracing::warn!(
                target: "zeroclaw::concurrency",
                request_id = request_id,
                ts_us = ts_us(),
                thread_id = %format!("{:?}", std::thread::current().id()),
                session_addr = session_addr,
                prev_history_len = prev_history_len,
                restored_len = restored_len,
                "SESSION_STATE_RESTORE_OVERWRITE session had non-empty history before restore - concurrent access detected"
            );
        }
        Some((final_history_len, final_history_hash))
    } else {
        tracing::warn!(
            target: "zeroclaw::concurrency",
            request_id = request_id,
            ts_us = ts_us(),
            thread_id = %format!("{:?}", std::thread::current().id()),
            session_addr = session_addr,
            restored_len = restored_len,
            "SESSION_STATE_RESTORE_NO_SESSION session was destroyed while send was in progress"
        );
        None
    };

    drop(guard);

    if let Some((verify_len, verify_hash)) = verify {
        if verify_len != restored_len || verify_hash != restored_hash {
            tracing::error!(
                target: "zeroclaw::concurrency",
                request_id = request_id,
                ts_us = ts_us(),
                thread_id = %format!("{:?}", std::thread::current().id()),
                session_addr = session_addr,
                expected_len = restored_len,
                actual_len = verify_len,
                expected_hash = restored_hash,
                actual_hash = verify_hash,
                "SESSION_STATE_RESTORE_MISMATCH restored history does not match what was written"
            );
        }
    }

    tracing::info!(
        target: "zeroclaw::concurrency",
        request_id = request_id,
        ts_us = ts_us(),
        thread_id = %format!("{:?}", std::thread::current().id()),
        session_addr = session_addr,
        history_owner_after_restore = HISTORY_OWNER_REQ_ID.load(std::sync::atomic::Ordering::SeqCst),
        "SESSION_STATE_RESTORE_FINAL"
    );
}

/// Clears the global [`CANCEL_TOKEN`].
fn clear_cancel_token() {
    *lock_cancel_token() = None;
}

/// Builds the Android-appropriate tool description list for the system prompt.
///
/// Returns `(tool_name, description)` pairs matching the subset of tools
/// available in an Android agent session. This is a strict subset of the
/// tools available via daemon channel routing.
///
/// Session tools include: memory (store/recall/forget), cron (add/list/remove),
/// and optionally web_fetch, http_request, browser_open, Composio, and delegate.
///
/// Shell and file I/O tools (`shell`, `file_read`, `file_write`) are NOT
/// included here — those tools are only available via daemon channel tools
/// and cannot be executed within a session context.
///
/// Hardware peripherals and screenshot tools are excluded because they require
/// desktop-only capabilities.
///
/// Conditional tools (`web_fetch`, `http_request`, `browser_open`, `composio`,
/// `delegate`) are included only when their corresponding config sections are
/// enabled/non-empty.
#[allow(clippy::too_many_lines)]
fn build_android_tool_descs(
    config: &zeroclaw::Config,
    shared_folder_enabled: bool,
    workflow_folder_enabled: bool,
) -> Vec<(String, String)> {
    let mut descs: Vec<(String, String)> = vec![
        (
            "device_control".into(),
            "Control the Android device screen via accessibility gestures. \
             Provide a natural-language goal and this tool will automate the UI \
             using click, type, scroll, swipe, app launch, and other actions.\n\n\
             Use device_control for ALL tasks that involve interacting with the \
             Android device UI: opening apps, navigating settings, typing into \
             fields, playing videos inside apps, sharing content, scrolling, etc.\n\n\
             Do NOT use web_search_tool or sandbox_execute to control Android \
             applications. Those tools cannot interact with the device screen.\n\n\
             Example goals:\n\
             - \"Open YouTube and play cat videos\"\n\
             - \"Open Settings and enable Wi-Fi\"\n\
             - \"Open WhatsApp and send 'Hello' to Mom\"\n\n\
             The accessibility service must be enabled in Android Settings → \
             Accessibility → Zero-Assist.\n\n\
             On failure, the response includes an error_code field. Possible codes:\n\
             - ACCESSIBILITY_DISABLED: Service not enabled; tell user to enable it.\n\
             - APP_NOT_FOUND: App not installed on device.\n\
             - APP_LAUNCH_FAILED: App exists but could not be launched.\n\
             - STUCK / MAX_STEPS_REACHED: Multi-step goal could not be completed.\n\
             When retryable=true, the user may be able to fix the issue and retry."
                .into(),
        ),
        (
            "memory_store".into(),
            "Save to memory. Use when: preserving durable preferences, \
             decisions, key context. Don't use when: information is \
             transient/noisy/sensitive without need."
                .into(),
        ),
        (
            "memory_recall".into(),
            "Search memory. Use when: retrieving prior decisions, user \
             preferences, historical context. Don't use when: answer \
             is already in current context."
                .into(),
        ),
        (
            "memory_forget".into(),
            "Delete a memory entry. Use when: memory is incorrect/stale \
             or explicitly requested for removal. Don't use when: \
             impact is uncertain."
                .into(),
        ),
        (
            "cron_list".into(),
            "List all cron jobs with schedule, status, and metadata.".into(),
        ),
        (
            "cron_runs".into(),
            "List recent run records and statuses for one cron job by job_id.".into(),
        ),
    ];

    if config.browser.enabled {
        descs.push((
            "browser_open".into(),
            "Open approved HTTPS URLs in system browser \
             (allowlist-only, no scraping)"
                .into(),
        ));
    }

    if !config.agents.is_empty() {
        descs.push((
            "delegate".into(),
            "Delegate a sub-task to a specialized agent. Use when: task \
             needs different model/capability, or to parallelize work."
                .into(),
        ));
    }

    if config.web_fetch.enabled {
        descs.push((
            "web_fetch".into(),
            "Fetch a web page and return its content as clean text. \
             Use when: gathering web content, reading documentation, \
             checking APIs. Don't use when: making API calls with custom \
             headers (use http_request instead)."
                .into(),
        ));
    }

    if config.http_request.enabled {
        descs.push((
            "http_request".into(),
            "Make HTTP requests to external APIs with custom methods and \
             headers. Supports GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS. \
             Use when: calling REST APIs, webhooks, external services."
                .into(),
        ));
    }

    if shared_folder_enabled {
        descs.push((
            "shared_folder_list".into(),
            "List files and directories in the user-selected shared folder. \
             Use when: inspecting what is available outside the app workspace."
                .into(),
        ));
        descs.push((
            "shared_folder_read".into(),
            "Read a file from the user-selected shared folder. \
             Use when: opening documents, configs, or source files that live \
             outside the app workspace. It returns binary/base64 for PDFs and \
             does not extract PDF text."
                .into(),
        ));
        descs.push((
            "shared_folder_write".into(),
            "Write a file or create a directory in the user-selected shared \
             folder. Use when: updating shared files outside the app workspace. \
             This does not generate PDFs; .pdf writes must be real PDF bytes, \
             normally base64-encoded."
                .into(),
        ));
    }

    if workflow_folder_enabled {
        descs.push((
            "workflow_folder_list".into(),
            "List files and directories in the Workflow Folder. \
             Use when: inspecting workflow files, generated artifacts, or \
             the app's default working folder."
                .into(),
        ));
        descs.push((
            "workflow_folder_read".into(),
            "Read a file from the Workflow Folder. \
             Use when: opening workflow documents, configs, or artifacts. \
             It returns binary/base64 for PDFs and does not extract PDF text."
                .into(),
        ));
        descs.push((
            "workflow_folder_write".into(),
            "Write a file or create a directory in the Workflow Folder. \
             Use when: saving workflow outputs or editing workflow files. \
             This does not generate PDFs; .pdf writes must be real PDF bytes, \
             normally base64-encoded."
                .into(),
        ));
    }

    descs
}

// ── Delta string parser ─────────────────────────────────────────────────
//
// Upstream `ZeroClaw`'s `run_tool_call_loop()` emits progress as plain
// strings with emoji prefixes. The parser below converts these strings
// into typed [`FfiSessionListener`] callbacks.

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use std::sync::Arc;
    use std::sync::Mutex as StdMutex;
    use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};

    /// A test listener that records all callback invocations as strings.
    ///
    /// Each event is formatted as `"callback_name:payload"` and pushed
    /// onto the internal vector for later assertion.
    struct RecordingListener {
        /// Accumulated event strings.
        events: StdMutex<Vec<String>>,
    }

    impl RecordingListener {
        /// Creates a new empty recording listener.
        fn new() -> Self {
            Self {
                events: StdMutex::new(Vec::new()),
            }
        }

        /// Returns a snapshot of all recorded events.
        fn events(&self) -> Vec<String> {
            self.events.lock().expect("events lock poisoned").clone()
        }
    }

    impl FfiSessionListener for RecordingListener {
        fn on_thinking(&self, text: String) {
            self.events
                .lock()
                .expect("events lock poisoned")
                .push(format!("thinking:{text}"));
        }

        fn on_response_chunk(&self, text: String) {
            self.events
                .lock()
                .expect("events lock poisoned")
                .push(format!("response_chunk:{text}"));
        }

        fn on_tool_start(&self, name: String, arguments_hint: String) {
            self.events
                .lock()
                .expect("events lock poisoned")
                .push(format!("tool_start:{name}:{arguments_hint}"));
        }

        fn on_tool_result(&self, name: String, success: bool, duration_secs: u64) {
            self.events
                .lock()
                .expect("events lock poisoned")
                .push(format!("tool_result:{name}:{success}:{duration_secs}"));
        }

        fn on_tool_output(&self, name: String, output: String) {
            self.events
                .lock()
                .expect("events lock poisoned")
                .push(format!("tool_output:{name}:{output}"));
        }

        fn on_progress(&self, message: String) {
            self.events
                .lock()
                .expect("events lock poisoned")
                .push(format!("progress:{message}"));
        }

        fn on_compaction(&self, summary: String) {
            self.events
                .lock()
                .expect("events lock poisoned")
                .push(format!("compaction:{summary}"));
        }

        fn on_complete(&self, full_response: String) {
            self.events
                .lock()
                .expect("events lock poisoned")
                .push(format!("complete:{full_response}"));
        }

        fn on_error(&self, error: String) {
            self.events
                .lock()
                .expect("events lock poisoned")
                .push(format!("error:{error}"));
        }

        fn on_cancelled(&self) {
            self.events
                .lock()
                .expect("events lock poisoned")
                .push("cancelled".to_string());
        }
    }

    // ── dispatch_delta tests ────────────────────────────────────────

    #[test]
    fn test_termux_enabled_flag_parsing() {
        assert!(!parse_termux_enabled_flag(""));
        assert!(!parse_termux_enabled_flag("   "));
        assert!(!parse_termux_enabled_flag("0"));
        assert!(!parse_termux_enabled_flag("false"));
        assert!(!parse_termux_enabled_flag("no"));
        assert!(!parse_termux_enabled_flag(" FALSE "));
        assert!(parse_termux_enabled_flag("1"));
        assert!(parse_termux_enabled_flag("true"));
        assert!(parse_termux_enabled_flag("yes"));
    }

    #[test]
    fn test_build_tools_registry_excludes_termux_tools_by_default() {
        let config = zeroclaw::Config::default();

        let (tools, _shell_runtime) = build_tools_registry(&config, None, false, false);

        assert!(!tools.iter().any(|tool| tool.name() == "termux_run"));
        assert!(!tools
            .iter()
            .any(|tool| tool.name() == "termux_get_capabilities"));
        assert!(tools
            .iter()
            .any(|tool| tool.name() == "sandbox_execute"));
        assert!(tools
            .iter()
            .any(|tool| tool.name() == "sandbox_manage_process"));
    }

    #[test]
    fn test_build_tools_registry_includes_composio_when_configured() {
        let mut config = zeroclaw::Config::default();
        config.composio.enabled = true;
        config.composio.api_key = Some("test-key".into());

        let (tools, _shell_runtime) = build_tools_registry(&config, None, false, false);

        assert!(tools.iter().any(|tool| tool.name() == "composio"));
    }

    #[test]
    fn test_build_tools_registry_excludes_composio_without_enabled_key() {
        let mut config = zeroclaw::Config::default();
        config.composio.api_key = Some("test-key".into());

        let (tools, _shell_runtime) = build_tools_registry(&config, None, false, false);

        assert!(!tools.iter().any(|tool| tool.name() == "composio"));
    }

    #[test]
    fn test_build_tools_registry_excludes_cli_user_key() {
        let mut config = zeroclaw::Config::default();
        config.composio.enabled = true;
        config.composio.api_key = Some("uak_test_user_key".into());

        let (tools, _shell_runtime) = build_tools_registry(&config, None, false, false);

        assert!(!tools.iter().any(|tool| tool.name() == "composio"));
        assert_eq!(composio_key_kind(&config), "cli_user");
        assert_eq!(composio_route_label(&config), "unsupported_cli_user_key");
        assert_eq!(composio_route_endpoint(&config), "none");
    }

    struct TestToolSearch;

    #[async_trait::async_trait]
    impl Tool for TestToolSearch {
        fn name(&self) -> &'static str {
            "tool_search"
        }

        fn description(&self) -> &'static str {
            "Test tool search"
        }

        fn parameters_schema(&self) -> serde_json::Value {
            serde_json::json!({"type": "object"})
        }

        async fn execute(&self, _args: serde_json::Value) -> anyhow::Result<ToolResult> {
            Ok(ToolResult {
                success: true,
                output: String::new(),
                error: None,
            metadata: None,
            })
        }
    }

    #[test]
    fn test_sessions_key_uses_tool_search_without_legacy_composio_helper() {
        let mut config = zeroclaw::Config::default();
        config.composio.enabled = true;
        config.composio.api_key = Some("ck_test_sessions_key".into());

        let (mut tools, _shell_runtime) = build_tools_registry(&config, None, false, false);

        assert!(!tool_registry_contains(&tools, "composio"));
        assert!(!should_add_composio_sessions_guidance(&config, &tools));
        assert!(should_add_composio_sessions_unavailable_guidance(
            &config, &tools
        ));

        tools.push(Box::new(TestToolSearch));

        assert!(!tool_registry_contains(&tools, "composio"));
        assert!(should_add_composio_sessions_guidance(&config, &tools));
        assert!(!should_add_composio_sessions_unavailable_guidance(
            &config, &tools
        ));
    }

    struct PromptGuidedProvider {
        calls: AtomicUsize,
        saw_tools: AtomicBool,
    }

    impl PromptGuidedProvider {
        fn new() -> Self {
            Self {
                calls: AtomicUsize::new(0),
                saw_tools: AtomicBool::new(false),
            }
        }
    }

    #[async_trait::async_trait]
    impl Provider for PromptGuidedProvider {
        async fn chat_with_system(
            &self,
            _system_prompt: Option<&str>,
            _message: &str,
            _model: &str,
            _temperature: Option<f64>,
        ) -> anyhow::Result<String> {
            Ok("unused".to_string())
        }

        async fn chat(
            &self,
            request: ChatRequest<'_>,
            _model: &str,
            _temperature: Option<f64>,
        ) -> anyhow::Result<ChatResponse> {
            self.saw_tools.store(
                request.tools.is_some_and(|tools| !tools.is_empty()),
                Ordering::SeqCst,
            );
            let call_index = self.calls.fetch_add(1, Ordering::SeqCst);

            if call_index == 0 {
                return Ok(ChatResponse {
                    text: Some(
                        r#"<tool_call>{"name":"echo_tool","arguments":{"message":"hello"}}</tool_call>"#
                            .to_string(),
                    ),
                    tool_calls: Vec::new(),
                    usage: None,
                    reasoning_content: None,
                });
            }

            assert!(
                request
                    .messages
                    .iter()
                    .any(|message| message.content.contains("<tool_result name=\"echo_tool\"")),
                "prompt-guided tool results should be returned to the provider"
            );
            Ok(ChatResponse {
                text: Some("final".to_string()),
                tool_calls: Vec::new(),
                usage: None,
                reasoning_content: None,
            })
        }
    }

    struct EchoTool;

    #[async_trait::async_trait]
    impl Tool for EchoTool {
        fn name(&self) -> &'static str {
            "echo_tool"
        }

        fn description(&self) -> &'static str {
            "Echoes a message"
        }

        fn parameters_schema(&self) -> serde_json::Value {
            serde_json::json!({
                "type": "object",
                "properties": {
                    "message": { "type": "string" }
                },
                "required": ["message"]
            })
        }

        async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
            Ok(ToolResult {
                success: true,
                output: args
                    .get("message")
                    .and_then(serde_json::Value::as_str)
                    .unwrap_or_default()
                    .to_string(),
                error: None,
            metadata: None,
            })
        }
    }

    struct MissingGmailToolProvider {
        calls: AtomicUsize,
    }

    #[async_trait::async_trait]
    impl Provider for MissingGmailToolProvider {
        async fn chat_with_system(
            &self,
            _system_prompt: Option<&str>,
            _message: &str,
            _model: &str,
            _temperature: Option<f64>,
        ) -> anyhow::Result<String> {
            Ok("unused".to_string())
        }

        async fn chat(
            &self,
            _request: ChatRequest<'_>,
            _model: &str,
            _temperature: Option<f64>,
        ) -> anyhow::Result<ChatResponse> {
            self.calls.fetch_add(1, Ordering::SeqCst);
            Ok(ChatResponse {
                text: Some(String::new()),
                tool_calls: vec![ToolCall {
                    id: "call_1".to_string(),
                    name: "composio__GMAIL_UPLOAD_ATTACHMENT".to_string(),
                    arguments: r#"{"app":"gmail","filename":"invoice.pdf"}"#.to_string(),
                }],
                usage: None,
                reasoning_content: None,
            })
        }
    }

    struct MissingGmailTool;

    #[async_trait::async_trait]
    impl Tool for MissingGmailTool {
        fn name(&self) -> &'static str {
            "composio__GMAIL_UPLOAD_ATTACHMENT"
        }

        fn description(&self) -> &'static str {
            "Missing Composio Gmail attachment tool"
        }

        fn parameters_schema(&self) -> serde_json::Value {
            serde_json::json!({
                "type": "object",
                "properties": {
                    "filename": { "type": "string" }
                }
            })
        }

        async fn execute(&self, _args: serde_json::Value) -> anyhow::Result<ToolResult> {
            Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some(
                    "MCP tool `composio__GMAIL_UPLOAD_ATTACHMENT` error -32602: Tool GMAIL_UPLOAD_ATTACHMENT not found"
                        .to_string(),
                ),
            metadata: None,
            })
        }
    }

    struct ActivatedGmailSendProvider {
        calls: AtomicUsize,
    }

    #[async_trait::async_trait]
    impl Provider for ActivatedGmailSendProvider {
        async fn chat_with_system(
            &self,
            _system_prompt: Option<&str>,
            _message: &str,
            _model: &str,
            _temperature: Option<f64>,
        ) -> anyhow::Result<String> {
            Ok("unused".to_string())
        }

        async fn chat(
            &self,
            request: ChatRequest<'_>,
            _model: &str,
            _temperature: Option<f64>,
        ) -> anyhow::Result<ChatResponse> {
            let call_index = self.calls.fetch_add(1, Ordering::SeqCst);
            if call_index == 0 {
                assert!(
                    request.tools.is_some_and(|tools| {
                        tools
                            .iter()
                            .any(|tool| tool.name == "composio__GMAIL_SEND_EMAIL")
                    }),
                    "activated Composio tool spec should be included in provider request"
                );
                return Ok(ChatResponse {
                    text: Some(String::new()),
                    tool_calls: vec![ToolCall {
                        id: "call_1".to_string(),
                        name: "composio__GMAIL_SEND_EMAIL".to_string(),
                        arguments: r#"{"recipient_email":"droidmaster989@gmail.com","body":"hi"}"#
                            .to_string(),
                    }],
                    usage: None,
                    reasoning_content: None,
                });
            }

            assert!(
                request.messages.iter().any(|message| {
                    message
                        .content
                        .contains("<tool_result name=\"composio__GMAIL_SEND_EMAIL\"")
                        && message.content.contains("sent to droidmaster989@gmail.com")
                }),
                "activated Gmail tool result should be returned to the provider"
            );
            Ok(ChatResponse {
                text: Some("final".to_string()),
                tool_calls: Vec::new(),
                usage: None,
                reasoning_content: None,
            })
        }
    }

    struct ActivatedGmailSendTool;

    #[async_trait::async_trait]
    impl Tool for ActivatedGmailSendTool {
        fn name(&self) -> &'static str {
            "composio__GMAIL_SEND_EMAIL"
        }

        fn description(&self) -> &'static str {
            "Send email through Gmail"
        }

        fn parameters_schema(&self) -> serde_json::Value {
            serde_json::json!({
                "type": "object",
                "properties": {
                    "recipient_email": { "type": "string" },
                    "body": { "type": "string" }
                },
                "required": ["recipient_email", "body"]
            })
        }

        async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
            let recipient = args
                .get("recipient_email")
                .and_then(serde_json::Value::as_str)
                .unwrap_or_default();
            Ok(ToolResult {
                success: true,
                output: format!("sent to {recipient}"),
                error: None,
            metadata: None,
            })
        }
    }

    #[tokio::test]
    async fn test_run_agent_loop_executes_prompt_guided_tool_calls() {
        let provider = PromptGuidedProvider::new();
        let mut history = vec![ChatMessage::system("system")];
        let tools: Vec<Box<dyn Tool>> = vec![Box::new(EchoTool)];
        let tool_specs = tool_specs_from_registry(&tools);
        let recording = Arc::new(RecordingListener::new());
        let listener: Arc<dyn FfiSessionListener> = recording.clone();
        let cancel_token = CancellationToken::new();

        let result = run_agent_loop(
            &provider,
            &mut history,
            &tools,
            &tool_specs,
            None,
            "test-model",
            0.0,
            &cancel_token,
            &listener,
            0,
            &zeroclaw_config::schema::MultimodalConfig::default(),
        )
        .await
        .expect("prompt-guided tool call should complete");

        assert_eq!(result, "final");
        assert!(provider.saw_tools.load(Ordering::SeqCst));
        assert!(
            recording
                .events()
                .iter()
                .any(|event| event == "tool_start:echo_tool:"),
        );
    }

    #[tokio::test]
    async fn test_run_agent_loop_stops_on_composio_tool_not_found() {
        let provider = MissingGmailToolProvider {
            calls: AtomicUsize::new(0),
        };
        let mut history = vec![ChatMessage::system("system")];
        let tools: Vec<Box<dyn Tool>> = vec![Box::new(MissingGmailTool)];
        let tool_specs = tool_specs_from_registry(&tools);
        let recording = Arc::new(RecordingListener::new());
        let listener: Arc<dyn FfiSessionListener> = recording.clone();
        let cancel_token = CancellationToken::new();

        let result = run_agent_loop(
            &provider,
            &mut history,
            &tools,
            &tool_specs,
            None,
            "test-model",
            0.0,
            &cancel_token,
            &listener,
            0,
            &zeroclaw_config::schema::MultimodalConfig::default(),
        )
        .await
        .expect("non-retryable Composio tool errors should end the turn cleanly");

        assert!(result.contains("GMAIL_UPLOAD_ATTACHMENT"));
        assert!(result.contains("stopped retrying"));
        assert!(!result.contains("maximum tool iterations"));
        assert_eq!(provider.calls.load(Ordering::SeqCst), 1);
    }

    #[tokio::test]
    async fn test_run_agent_loop_executes_activated_composio_tool_before_search_fallback() {
        let provider = ActivatedGmailSendProvider {
            calls: AtomicUsize::new(0),
        };
        let mut history = vec![ChatMessage::system("system")];
        let tools: Vec<Box<dyn Tool>> = vec![Box::new(TestToolSearch)];
        let tool_specs = tool_specs_from_registry(&tools);
        let activated = Arc::new(Mutex::new(zeroclaw::tools::ActivatedToolSet::new()));
        let gmail_tool: Arc<dyn Tool> = Arc::new(ActivatedGmailSendTool);
        activated
            .lock()
            .unwrap()
            .activate("composio__GMAIL_SEND_EMAIL".to_string(), gmail_tool);
        let recording = Arc::new(RecordingListener::new());
        let listener: Arc<dyn FfiSessionListener> = recording.clone();
        let cancel_token = CancellationToken::new();

        let result = run_agent_loop(
            &provider,
            &mut history,
            &tools,
            &tool_specs,
            Some(activated),
            "test-model",
            0.0,
            &cancel_token,
            &listener,
            0,
            &zeroclaw_config::schema::MultimodalConfig::default(),
        )
        .await
        .expect("activated Composio tool should execute");

        let events = recording.events();
        assert_eq!(result, "final");
        assert!(
            events
                .iter()
                .any(|event| event.starts_with("tool_start:composio__GMAIL_SEND_EMAIL:"))
        );
        assert!(
            !events
                .iter()
                .any(|event| event.starts_with("tool_start:tool_search:")),
            "activated Composio tool call must not be routed back to tool_search"
        );
        assert_eq!(provider.calls.load(Ordering::SeqCst), 2);
    }

    struct StructuredNonNativeProvider {
        calls: AtomicUsize,
    }

    #[async_trait::async_trait]
    impl Provider for StructuredNonNativeProvider {
        async fn chat_with_system(
            &self,
            _system_prompt: Option<&str>,
            _message: &str,
            _model: &str,
            _temperature: Option<f64>,
        ) -> anyhow::Result<String> {
            Ok("unused".to_string())
        }

        async fn chat(
            &self,
            request: ChatRequest<'_>,
            _model: &str,
            _temperature: Option<f64>,
        ) -> anyhow::Result<ChatResponse> {
            let call_index = self.calls.fetch_add(1, Ordering::SeqCst);
            if call_index == 0 {
                return Ok(ChatResponse {
                    text: Some("checking".to_string()),
                    tool_calls: vec![ToolCall {
                        id: "call_1".to_string(),
                        name: "echo_tool".to_string(),
                        arguments: r#"{"message":"structured"}"#.to_string(),
                    }],
                    usage: None,
                    reasoning_content: None,
                });
            }

            assert!(
                request
                    .messages
                    .iter()
                    .any(|message| message.content.contains("<tool_result name=\"echo_tool\"")),
                "structured non-native tool results should be returned to the provider"
            );
            Ok(ChatResponse {
                text: Some("final".to_string()),
                tool_calls: Vec::new(),
                usage: None,
                reasoning_content: None,
            })
        }
    }

    #[tokio::test]
    async fn test_run_agent_loop_preserves_structured_calls_from_non_native_provider() {
        let provider = StructuredNonNativeProvider {
            calls: AtomicUsize::new(0),
        };
        let mut history = vec![ChatMessage::system("system")];
        let tools: Vec<Box<dyn Tool>> = vec![Box::new(EchoTool)];
        let tool_specs = tool_specs_from_registry(&tools);
        let recording = Arc::new(RecordingListener::new());
        let listener: Arc<dyn FfiSessionListener> = recording.clone();
        let cancel_token = CancellationToken::new();

        let result = run_agent_loop(
            &provider,
            &mut history,
            &tools,
            &tool_specs,
            None,
            "test-model",
            0.0,
            &cancel_token,
            &listener,
            0,
            &zeroclaw_config::schema::MultimodalConfig::default(),
        )
        .await
        .expect("structured non-native tool call should complete");

        assert_eq!(result, "final");
        assert!(
            recording
                .events()
                .iter()
                .any(|event| event == "tool_output:echo_tool:structured"),
        );
    }

    #[test]
    fn test_composio_route_label_uses_sessions_for_ck_key() {
        let mut config = zeroclaw::Config::default();
        config.composio.enabled = true;
        config.composio.api_key = Some("ck_test_sessions_key".into());

        assert_eq!(composio_key_kind(&config), "sessions");
        assert_eq!(composio_route_label(&config), "sessions_mcp_tool_search");
        assert_eq!(composio_route_endpoint(&config), COMPOSIO_SESSIONS_ENDPOINT);
    }

    #[test]
    fn test_composio_route_label_uses_rest_for_project_key() {
        let mut config = zeroclaw::Config::default();
        config.composio.enabled = true;
        config.composio.api_key = Some("ak_test_project_key".into());

        assert_eq!(composio_key_kind(&config), "project");
        assert_eq!(composio_route_label(&config), "legacy_rest_composio");
        assert_eq!(composio_route_endpoint(&config), COMPOSIO_REST_ENDPOINT);
    }

    #[test]
    fn test_composio_key_fingerprint_redacts_secret() {
        let fingerprint = composio_key_fingerprint(Some("ck_secret_value"));

        assert!(fingerprint.starts_with("fnv64:"));
        assert!(!fingerprint.contains("ck_secret_value"));
        assert_eq!(composio_key_fingerprint(Some("   ")), "none");
    }

    #[test]
    fn test_terminal_tool_failure_response_stops_composio_initializing_account() {
        let call = ToolCall {
            id: "call_1".to_string(),
            name: "composio".to_string(),
            arguments: r#"{"app":"gmail","action":"execute"}"#.to_string(),
        };
        let output = "Action execution failed: Composio v3 action execution failed: HTTP 422: Connected account is not in an ACTIVE state. Current status is \"INITIALIZING\" but ACTIVE status is required for authorization.";

        let response = terminal_tool_failure_response(&call, false, output)
            .expect("pending Composio account should produce terminal response");

        assert!(response.contains("gmail"));
        assert!(response.contains("ACTIVE"));
        assert!(response.contains("stopped retrying"));
    }

    #[test]
    fn test_terminal_tool_failure_response_stops_composio_not_active_account() {
        let call = ToolCall {
            id: "call_1".to_string(),
            name: "composio__GMAIL_SEND_EMAIL".to_string(),
            arguments: r#"{"app":"gmail"}"#.to_string(),
        };
        let output = "Connected account is not ACTIVE/INITIALIZING for toolkit gmail.";

        let response = terminal_tool_failure_response(&call, false, output)
            .expect("non-active Composio account should produce terminal response");

        assert!(response.contains("gmail"));
        assert!(response.contains("ACTIVE"));
        assert!(response.contains("stopped retrying"));
    }

    #[test]
    fn test_terminal_tool_failure_response_stops_composio_tool_not_found() {
        let call = ToolCall {
            id: "call_1".to_string(),
            name: "composio__GMAIL_UPLOAD_ATTACHMENT".to_string(),
            arguments: r#"{"app":"gmail"}"#.to_string(),
        };
        let output = "MCP tool `composio__GMAIL_UPLOAD_ATTACHMENT` error -32602: Tool GMAIL_UPLOAD_ATTACHMENT not found";

        let response = terminal_tool_failure_response(&call, false, output)
            .expect("missing Composio tool should produce terminal response");

        assert!(response.contains("composio__GMAIL_UPLOAD_ATTACHMENT"));
        assert!(response.contains("tool catalog"));
        assert!(response.contains("stopped retrying"));
    }

    #[test]
    fn test_terminal_tool_failure_response_stops_tool_search_not_found() {
        let call = ToolCall {
            id: "call_1".to_string(),
            name: "tool_search".to_string(),
            arguments: r#"{"query":"select:GMAIL_UPLOAD_ATTACHMENT"}"#.to_string(),
        };
        let output = "Not found: GMAIL_UPLOAD_ATTACHMENT";

        let response = terminal_tool_failure_response(&call, true, output)
            .expect("tool_search not-found for Gmail should produce terminal response");

        assert!(response.contains("GMAIL_UPLOAD_ATTACHMENT"));
        assert!(response.contains("stopped retrying"));
    }

    #[test]
    fn test_terminal_tool_failure_response_ignores_successful_tool_result() {
        let call = ToolCall {
            id: "call_1".to_string(),
            name: "composio".to_string(),
            arguments: r#"{"app":"gmail"}"#.to_string(),
        };
        let output = "Connected account is not in an ACTIVE state";

        assert!(terminal_tool_failure_response(&call, true, output).is_none());
    }

    #[test]
    fn test_terminal_tool_failure_response_ignores_unrelated_tool_search_not_found() {
        let call = ToolCall {
            id: "call_1".to_string(),
            name: "tool_search".to_string(),
            arguments: r#"{"query":"select:fs__read_file"}"#.to_string(),
        };

        assert!(terminal_tool_failure_response(&call, true, "Not found: fs__read_file").is_none());
    }

    #[test]
    fn test_legacy_composio_tool_search_query_uses_app_and_action() {
        let query = legacy_composio_tool_search_query(
            r#"{"app":"gmail","action":"execute","params":{"action":"send email"}}"#,
        )
        .expect("legacy Composio arguments should produce a search query");

        assert!(query.contains("gmail"));
        assert!(query.contains("send email"));
    }

    #[test]
    fn test_legacy_composio_tool_search_query_uses_text_fallback() {
        let query = legacy_composio_tool_search_query(
            r#"{"app":"gmail","text":"send the PDF attachment"}"#,
        )
        .expect("text Composio arguments should produce a search query");

        assert_eq!(query, "gmail send the PDF attachment");
    }

    #[test]
    fn test_composio_tool_search_query_selects_prefixed_tool() {
        let query = composio_tool_search_query("composio__GMAIL_SEND_EMAIL", "{}")
            .expect("prefixed Composio tool should produce exact selection");

        assert_eq!(query, "select:composio__GMAIL_SEND_EMAIL");
    }

    #[test]
    fn test_composio_tool_search_query_searches_remote_name() {
        let query = composio_tool_search_query(
            "COMPOSIO_MULTI_EXECUTE_TOOL",
            r#"{"app":"gmail","text":"send attachment"}"#,
        )
        .expect("remote Composio tool name should produce a search query");

        assert!(query.contains("COMPOSIO MULTI EXECUTE TOOL"));
        assert!(query.contains("gmail"));
        assert!(query.contains("send attachment"));
    }

    struct JsonEnvelopeProvider {
        calls: AtomicUsize,
    }

    #[async_trait::async_trait]
    impl Provider for JsonEnvelopeProvider {
        async fn chat_with_system(
            &self,
            _system_prompt: Option<&str>,
            _message: &str,
            _model: &str,
            _temperature: Option<f64>,
        ) -> anyhow::Result<String> {
            Ok("unused".to_string())
        }

        async fn chat(
            &self,
            request: ChatRequest<'_>,
            _model: &str,
            _temperature: Option<f64>,
        ) -> anyhow::Result<ChatResponse> {
            let call_index = self.calls.fetch_add(1, Ordering::SeqCst);
            if call_index == 0 {
                return Ok(ChatResponse {
                    text: Some(
                        serde_json::json!({
                            "content": "",
                            "tool_calls": [{
                                "function": {
                                    "name": "echo_tool",
                                    "arguments": { "message": "json-envelope" }
                                }
                            }]
                        })
                        .to_string(),
                    ),
                    tool_calls: Vec::new(),
                    usage: None,
                    reasoning_content: None,
                });
            }

            assert!(
                request
                    .messages
                    .iter()
                    .any(|message| message.content.contains("<tool_result name=\"echo_tool\"")),
                "JSON-envelope tool results should be returned to the provider"
            );
            Ok(ChatResponse {
                text: Some("final".to_string()),
                tool_calls: Vec::new(),
                usage: None,
                reasoning_content: None,
            })
        }
    }

    #[tokio::test]
    async fn test_run_agent_loop_executes_json_tool_call_envelope() {
        let provider = JsonEnvelopeProvider {
            calls: AtomicUsize::new(0),
        };
        let mut history = vec![ChatMessage::system("system")];
        let tools: Vec<Box<dyn Tool>> = vec![Box::new(EchoTool)];
        let tool_specs = tool_specs_from_registry(&tools);
        let recording = Arc::new(RecordingListener::new());
        let listener: Arc<dyn FfiSessionListener> = recording.clone();
        let cancel_token = CancellationToken::new();

        let result = run_agent_loop(
            &provider,
            &mut history,
            &tools,
            &tool_specs,
            None,
            "test-model",
            0.0,
            &cancel_token,
            &listener,
            0,
            &zeroclaw_config::schema::MultimodalConfig::default(),
        )
        .await
        .expect("JSON-envelope tool call should complete");

        assert_eq!(result, "final");
        assert!(
            recording
                .events()
                .iter()
                .any(|event| event == "tool_output:echo_tool:json-envelope"),
        );
    }

    #[test]
    fn test_extract_response_tool_calls_parses_composio_json_envelope() {
        let response = ChatResponse {
            text: Some(
                serde_json::json!({
                    "content": "",
                    "tool_calls": [{
                        "name": "composio",
                        "function": {
                            "action": "execute",
                            "app": "youtube",
                            "params": {
                                "channel_name": "MrBeast",
                                "action": "subscribe"
                            }
                        }
                    }]
                })
                .to_string(),
            ),
            tool_calls: Vec::new(),
            usage: None,
            reasoning_content: None,
        };

        let (text, calls) = extract_response_tool_calls(&response, false, 2);

        assert_eq!(text, "");
        assert_eq!(calls.len(), 1);
        assert_eq!(calls[0].name, "composio");
        let arguments: serde_json::Value = serde_json::from_str(&calls[0].arguments).unwrap();
        assert_eq!(arguments["action"], "execute");
        assert_eq!(arguments["app"], "youtube");
        assert_eq!(arguments["params"]["channel_name"], "MrBeast");
    }

    #[test]
    fn test_extract_response_tool_calls_parses_function_arguments_string() {
        let response = ChatResponse {
            text: Some(
                r#"{"content":"","tool_calls":[{"function":{"arguments":"{\"action\":\"list_accounts\",\"app\":\"youtube\"}","name":"composio"}}]}"#
                    .to_string(),
            ),
            tool_calls: Vec::new(),
            usage: None,
            reasoning_content: None,
        };

        let (text, calls) = extract_response_tool_calls(&response, false, 3);

        assert_eq!(text, "");
        assert_eq!(calls.len(), 1);
        assert_eq!(calls[0].name, "composio");
        let arguments: serde_json::Value = serde_json::from_str(&calls[0].arguments).unwrap();
        assert_eq!(arguments["action"], "list_accounts");
        assert_eq!(arguments["app"], "youtube");
    }

    #[test]
    fn test_extract_response_tool_calls_parses_noisy_tool_call_fragment() {
        let response = ChatResponse {
            text: Some(
                r#"assistant text ;"tool_calls":[{"function":{"arguments":"{\"action_name\":\"execute\",\"app\":\"youtube\",\"params\":{\"channel_name\":\"MrBeast\",\"action\":\"subscribe\"}}","name":"composio"}}]"#
                    .to_string(),
            ),
            tool_calls: Vec::new(),
            usage: None,
            reasoning_content: None,
        };

        let (text, calls) = extract_response_tool_calls(&response, false, 4);

        assert_eq!(text, "");
        assert_eq!(calls.len(), 1);
        assert_eq!(calls[0].name, "composio");
        let arguments: serde_json::Value = serde_json::from_str(&calls[0].arguments).unwrap();
        assert_eq!(arguments["action_name"], "execute");
        assert_eq!(arguments["app"], "youtube");
        assert_eq!(arguments["params"]["channel_name"], "MrBeast");
        assert_eq!(arguments["params"]["action"], "subscribe");
    }

    #[test]
    fn test_extract_response_tool_calls_hides_malformed_tool_call_json() {
        let response = ChatResponse {
            text: Some(
                r#"{"content":"","tool_calls":[{"function":{"arguments":"{\"action\":\"list_accounts\",\"app\":\"youtube\"}","name":"composio"}}"#
                    .to_string(),
            ),
            tool_calls: Vec::new(),
            usage: None,
            reasoning_content: None,
        };

        let (text, calls) = extract_response_tool_calls(&response, false, 5);

        assert!(calls.is_empty());
        assert_eq!(text, TOOL_CALL_PARSE_ERROR_MESSAGE);
    }

    // ── parse_tool_completion tests ─────────────────────────────────

    // ── truncate_chars tests ────────────────────────────────────────

    #[test]
    fn test_truncate_chars_short_string() {
        let result = truncate_chars("hello", 10);
        assert_eq!(result, "hello");
    }

    #[test]
    fn test_truncate_chars_long_string() {
        let input = "a".repeat(100);
        let result = truncate_chars(&input, 10);
        assert!(result.ends_with("..."));
        assert!(result.len() <= 14); // 10 chars + "..."
    }

    // ── trim_history tests ──────────────────────────────────────────

    #[test]
    fn test_trim_history_within_limit() {
        let mut history = vec![
            ChatMessage::system("system"),
            ChatMessage::user("hello"),
            ChatMessage::assistant("hi"),
        ];
        trim_history(&mut history, 10);
        assert_eq!(history.len(), 3);
    }

    #[test]
    fn test_trim_history_exceeds_limit() {
        let mut history = vec![ChatMessage::system("system")];
        for i in 0..10 {
            history.push(ChatMessage::user(format!("msg {i}")));
        }
        assert_eq!(history.len(), 11); // 1 system + 10 user

        trim_history(&mut history, 5);
        assert_eq!(history.len(), 6); // 1 system + 5 user
        assert_eq!(history[0].role, "system");
        assert_eq!(history[1].content, "msg 5");
    }

    #[test]
    fn test_trim_history_no_system_prompt() {
        let mut history: Vec<ChatMessage> = (0..10)
            .map(|i| ChatMessage::user(format!("msg {i}")))
            .collect();

        trim_history(&mut history, 3);
        assert_eq!(history.len(), 3);
        assert_eq!(history[0].content, "msg 7");
    }

    // ── build_compaction_transcript tests ────────────────────────────

    #[test]
    fn test_build_compaction_transcript_basic() {
        let messages = vec![
            ChatMessage::user("What is Rust?"),
            ChatMessage::assistant("Rust is a systems programming language."),
        ];
        let transcript = build_compaction_transcript(&messages);
        assert!(transcript.contains("USER: What is Rust?"));
        assert!(transcript.contains("ASSISTANT: Rust is a systems programming language."));
    }

    // ── truncate_tool_args_hint tests ───────────────────────────────

    // ── build_native_assistant_history tests ─────────────────────────

    #[test]
    fn test_build_native_assistant_history_basic() {
        let calls = vec![zeroclaw::providers::ToolCall {
            id: "call_123".into(),
            name: "shell".into(),
            arguments: r#"{"command":"ls"}"#.into(),
        }];

        let result = build_native_assistant_history("Let me check", &calls, None);
        let parsed: serde_json::Value =
            serde_json::from_str(&result).expect("failed to parse JSON result");

        assert_eq!(parsed["content"], "Let me check");
        assert_eq!(parsed["tool_calls"][0]["id"], "call_123");
        assert_eq!(parsed["tool_calls"][0]["name"], "shell");
        assert!(parsed.get("reasoning_content").is_none());
    }

    #[test]
    fn test_build_native_assistant_history_with_reasoning() {
        let calls = vec![zeroclaw::providers::ToolCall {
            id: "call_456".into(),
            name: "file_read".into(),
            arguments: r#"{"path":"test.rs"}"#.into(),
        }];

        let result =
            build_native_assistant_history("Reading file", &calls, Some("thinking about it"));
        let parsed: serde_json::Value =
            serde_json::from_str(&result).expect("failed to parse JSON result");

        assert_eq!(parsed["reasoning_content"], "thinking about it");
    }

    // ── stream_response_text tests ──────────────────────────────────

    // ── session lifecycle unit tests (no daemon) ────────────────────

    #[test]
    fn test_session_send_no_session() {
        let listener = Arc::new(RecordingListener::new());
        let result = session_send_inner("hello".into(), vec![], vec![], listener);
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::StateError { detail } => {
                assert!(detail.contains("no active session"));
            }
            other => panic!("expected StateError, got {other:?}"),
        }
    }

    #[test]
    fn test_session_send_oversized_message() {
        let listener = Arc::new(RecordingListener::new());
        let big_message = "x".repeat(MAX_MESSAGE_BYTES + 1);
        let result = session_send_inner(big_message, vec![], vec![], listener);
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::ConfigError { detail } => {
                assert!(detail.contains("too large"));
            }
            other => panic!("expected ConfigError, got {other:?}"),
        }
    }

    #[test]
    fn test_session_send_mismatched_image_arrays() {
        let listener = Arc::new(RecordingListener::new());
        let result = session_send_inner("hi".into(), vec!["base64data".into()], vec![], listener);
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::ConfigError { detail } => {
                assert!(detail.contains("image_data length"));
            }
            other => panic!("expected ConfigError, got {other:?}"),
        }
    }

    #[test]
    fn test_session_send_too_many_images() {
        let listener = Arc::new(RecordingListener::new());
        let images = vec!["img".to_string(); MAX_SESSION_IMAGES + 1];
        let mimes = vec!["image/png".to_string(); MAX_SESSION_IMAGES + 1];
        let result = session_send_inner("hi".into(), images, mimes, listener);
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::ConfigError { detail } => {
                assert!(detail.contains("too many images"));
            }
            other => panic!("expected ConfigError, got {other:?}"),
        }
    }

    #[test]
    fn test_compose_multimodal_message_no_images() {
        let result = compose_multimodal_message("hello world", &[], &[]);
        assert_eq!(result, "hello world");
    }

    #[test]
    fn test_compose_multimodal_message_with_images() {
        let result =
            compose_multimodal_message("describe this", &["abc123".into()], &["image/png".into()]);
        assert!(result.starts_with("describe this"));
        assert!(result.contains("[IMAGE:data:image/png;base64,abc123]"));
    }

    #[test]
    fn test_session_cancel_no_send() {
        session_cancel_inner();
    }

    #[test]
    fn test_session_clear_no_session() {
        let result = session_clear_inner();
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::StateError { detail } => {
                assert!(detail.contains("no active session"));
            }
            other => panic!("expected StateError, got {other:?}"),
        }
    }

    #[test]
    fn test_session_history_no_session() {
        let result = session_history_inner();
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::StateError { detail } => {
                assert!(detail.contains("no active session"));
            }
            other => panic!("expected StateError, got {other:?}"),
        }
    }

    #[test]
    fn test_session_destroy_no_session() {
        let result = session_destroy_inner();
        assert!(result.is_err());
        match result.unwrap_err() {
            FfiError::StateError { detail } => {
                assert!(detail.contains("no active session"));
            }
            other => panic!("expected StateError, got {other:?}"),
        }
    }

    // ── append_android_identity_extras tests ────────────────────────

    #[test]
    fn test_android_identity_extras_user_name() {
        let config = zeroclaw::config::IdentityConfig {
            format: "aieos".into(),
            aieos_path: None,
            aieos_inline: Some(
                r#"{"identity":{"names":{"first":"Nova"},"user_name":"Alice","timezone":"US/Eastern","communication_style":"casual"}}"#.into(),
            ),
        };
        let mut prompt = String::from("## Identity\n\n**Name:** Nova\n");
        append_android_identity_extras(&mut prompt, &config);
        assert!(prompt.contains("**User's name:** Alice"));
        assert!(prompt.contains("**Timezone:** US/Eastern"));
        assert!(prompt.contains("**Preferred communication style:** casual"));
    }

    #[test]
    fn test_android_identity_extras_empty_inline() {
        let config = zeroclaw::config::IdentityConfig {
            format: "aieos".into(),
            aieos_path: None,
            aieos_inline: None,
        };
        let mut prompt = String::from("base prompt");
        append_android_identity_extras(&mut prompt, &config);
        assert_eq!(prompt, "base prompt");
    }

    #[test]
    fn test_android_identity_extras_no_extra_fields() {
        let config = zeroclaw::config::IdentityConfig {
            format: "aieos".into(),
            aieos_path: None,
            aieos_inline: Some(r#"{"identity":{"names":{"first":"Nova"}}}"#.into()),
        };
        let mut prompt = String::from("base prompt");
        append_android_identity_extras(&mut prompt, &config);
        assert_eq!(prompt, "base prompt");
    }

    // ── SessionStateGuard tests ────────────────────────────────────

    #[test]
    fn test_guard_take_disarms_drop() {
        let history = vec![ChatMessage::user("hello")];
        let guard = SessionStateGuard::new(history, vec![]);

        let (h, t) = guard.take();
        assert_eq!(h.len(), 1);
        assert!(t.is_empty());
        // Drop runs here but is a no-op (defused).
    }

    #[test]
    fn test_guard_state_mut_provides_references() {
        let history = vec![ChatMessage::user("one")];
        let mut guard = SessionStateGuard::new(history, vec![]);

        let (h, _t) = guard.state_mut();
        h.push(ChatMessage::assistant("two"));
        assert_eq!(h.len(), 2);

        let (taken_h, _) = guard.take();
        assert_eq!(taken_h.len(), 2);
        assert_eq!(taken_h[1].content, "two");
    }

    #[test]
    fn test_guard_drop_without_take_keeps_state() {
        // Verify that dropping a guard without calling take() does NOT
        // consume the state (it's available for the Drop impl to use).
        // The actual SESSION restoration is tested implicitly through
        // session_send_inner's panic-safety.
        let history = vec![ChatMessage::user("preserved")];
        let guard = SessionStateGuard::new(history, vec![]);
        // Drop fires here — without a live SESSION it's a no-op,
        // but critically it does NOT panic.
        drop(guard);
    }

    // ── Concurrency stress test ──────────────────────────────────────

    // Requires a live daemon (provider API key + running runtime) and mutates
    // the process-global SESSION slot, which corrupts other session tests when
    // run in the same binary. Disabled by default; run explicitly in an
    // environment with a fully configured daemon.
    #[ignore = "requires live daemon with configured provider"]
    #[test]
    fn test_concurrent_session_send_race_condition() {
        // Use TryInit to avoid panicking in CI if another test already set
        // up a subscriber.
        let _ = tracing_subscriber::fmt()
            .with_test_writer()
            .with_env_filter("zeroclaw::concurrency")
            .try_init();

        // ── 1. Initialise minimal daemon ──
        let config = zeroclaw::Config::default();
        crate::runtime::test_init_minimal_daemon(config);

        // This guard restores global state on drop (whether test passes or
        // panics) so we don't poison globals for subsequent tests. Uses
        // try_lock to avoid hanging if the deadlocked threads still hold
        // the session mutex.
        struct Cleanup;
        impl Drop for Cleanup {
            fn drop(&mut self) {
                crate::runtime::test_teardown_daemon();
                if let Ok(mut guard) = SESSION.try_lock() {
                    *guard = None;
                } else {
                    eprintln!("Cleanup: session lock held by deadlocked threads - skipping");
                }
                HISTORY_OWNER_REQ_ID.store(0, Ordering::SeqCst);
                CONCURRENT_SEND_COUNT.store(0, Ordering::SeqCst);
                PROVIDER_OWNER_REQ_ID.store(0, Ordering::SeqCst);
            }
        }
        let _cleanup = Cleanup;

        // ── 2. Set up SESSION with deterministic history ──
        let history_original = vec![
            ChatMessage::system("You are a helpful assistant."),
            ChatMessage::user("Hello, who are you?"),
            ChatMessage::assistant("I am ZeroClaw, your AI assistant."),
        ];
        let hash_before = history_hash(&history_original);
        let len_before = history_original.len();

        let daemon_config = crate::runtime::clone_daemon_config()
            .expect("daemon config must be clonable");
        let provider_name =
            config_compat::active_provider_name_or_default(&daemon_config, "openrouter");
        let model =
            config_compat::active_model_or_default(&daemon_config, "anthropic/claude-sonnet-4");

        *lock_session() = Some(Session {
            history: history_original.clone(),
            config: daemon_config,
            system_prompt: "You are a helpful assistant.".into(),
            model,
            temperature: 0.7,
            thinking_level_override: None,
            provider_name,
            tools_registry: vec![],
            activated_mcp_tools: None,
            shared_folder_enabled: false,
            workflow_folder_enabled: false,
            shell_runtime: Arc::new(StdMutex::new(ShellRuntime::new(
                std::path::PathBuf::from("/tmp"),
            ))),
        });

        eprintln!();
        eprintln!("╔══════════════════════════════════════════════════╗");
        eprintln!("║  CONCURRENT SESSION SEND RACE TEST              ║");
        eprintln!("╚══════════════════════════════════════════════════╝");
        eprintln!("Initial history: {len_before} messages, hash = {hash_before:#x}");
        for (i, m) in history_original.iter().enumerate() {
            eprintln!("  [{i}] {}: {}", m.role, truncate_debug(&m.content, 80));
        }

        // ── 3. Spawn concurrent session_send_inner calls ──
        // Use a channel with timeout to avoid hanging forever if the
        // reentrant-lock deadlock in put_session_state_back is triggered.
        use std::sync::mpsc;

        let barrier = Arc::new(std::sync::Barrier::new(2));
        let b1 = Arc::clone(&barrier);
        let b2 = Arc::clone(&barrier);

        let msg1 = "First concurrent message".to_string();
        let msg2 = "Second concurrent message".to_string();

        let listener1: Arc<dyn FfiSessionListener> = Arc::new(RecordingListener::new());
        let listener2: Arc<dyn FfiSessionListener> = Arc::new(RecordingListener::new());

        let (tx, rx) = mpsc::channel::<(&'static str, Result<(), FfiError>)>();
        let tx2 = tx.clone();
        let timeout_secs = 5;

        let t_start = std::time::Instant::now();
        std::thread::spawn(move || {
            b1.wait();
            let result = session_send_inner(msg1, vec![], vec![], listener1);
            tx.send(("thread1", result)).ok();
        });
        std::thread::spawn(move || {
            b2.wait();
            let result = session_send_inner(msg2, vec![], vec![], listener2);
            tx2.send(("thread2", result)).ok();
        });

        let deadline = std::time::Duration::from_secs(timeout_secs);
        let mut results: Vec<(&'static str, Result<(), FfiError>)> = Vec::new();
        let mut deadlock_detected = false;
        let mut disconnected = false;
        loop {
            match rx.recv_timeout(deadline) {
                Ok((name, result)) => {
                    results.push((name, result));
                    if results.len() == 2 {
                        break;
                    }
                }
                Err(mpsc::RecvTimeoutError::Timeout) => {
                    deadlock_detected = true;
                    eprintln!(
                        "WARNING: Concurrent threads deadlocked within {timeout_secs}s timeout"
                    );
                    break;
                }
                Err(mpsc::RecvTimeoutError::Disconnected) => {
                    deadlock_detected = true;
                    disconnected = true;
                    eprintln!("WARNING: Channel disconnected (threads may have panicked)");
                    break;
                }
            }
        }
        let elapsed = t_start.elapsed();

        // ── 4. Collect evidence ──
        // Use try_lock to avoid hanging if the deadlocked threads still hold
        // the session lock (which happens when both get stuck in the
        // put_session_state_back reentrant-lock path).
        let (final_hash, final_len, session_available) = match SESSION.try_lock() {
            Ok(guard) => {
                let (h, l) = match guard.as_ref() {
                    Some(session) => (history_hash(&session.history), session.history.len()),
                    None => (0u64, 0),
                };
                drop(guard);
                (h, l, true)
            }
            Err(_) => {
                eprintln!("  (session lock not available - threads deadlocked)");
                (0u64, 0, false)
            }
        };
        let owner_after = HISTORY_OWNER_REQ_ID.load(Ordering::SeqCst);
        let concurrent_after = CONCURRENT_SEND_COUNT.load(Ordering::SeqCst);
        let provider_after = PROVIDER_OWNER_REQ_ID.load(Ordering::SeqCst);

        // ── 5. Print structured report ──
        eprintln!();
        eprintln!("══ CONCURRENCY STRESS TEST REPORT ════════════════════");
        eprintln!("  Elapsed:     {elapsed:.1?}");
        eprintln!("  Timeout:     {timeout_secs}s");
        eprintln!("  Deadlocked:  {deadlock_detected}");
        if disconnected {
            eprintln!("  Disconnected: yes (threads may have panicked)");
        }
        eprintln!();

        eprintln!("── Thread results ({}) ──", results.len());
        for (name, result) in &results {
            eprintln!("  {name}: {result:?}");
        }
        if deadlock_detected && results.is_empty() {
            eprintln!("  * No threads completed - both deadlocked");
            eprintln!("  * Threads stuck in put_session_state_back");
            eprintln!("  * Site: line 2959 reentrant lock_session()");
            eprintln!("  *   while line 2906 already holds the lock");
        } else if deadlock_detected {
            eprintln!("  * One thread completed before deadlock");
            eprintln!("  * Other thread stuck in put_session_state_back");
        }
        eprintln!();

        eprintln!("── Ownership state after test ──");
        eprintln!("  HISTORY_OWNER_REQ_ID:  {owner_after}");
        eprintln!("  CONCURRENT_SEND_COUNT: {concurrent_after}");
        eprintln!("  PROVIDER_OWNER_REQ_ID: {provider_after}");
        eprintln!();

        eprintln!("── Session state after test ──");
        if session_available {
            let summary = match SESSION.try_lock() {
                Ok(guard) => match guard.as_ref() {
                    Some(session) => session
                        .history
                        .iter()
                        .map(|m| format!("[{}] {}", m.role, truncate_debug(&m.content, 60)))
                        .collect::<Vec<_>>()
                        .join("\n  "),
                    None => "(no session)".into(),
                },
                Err(_) => "(unavailable)".into(),
            };
            eprintln!("  History messages: {final_len}");
            eprintln!("  History hash:     {final_hash:#x}");
            eprintln!("  Messages:\n  {summary}");
        } else {
            eprintln!("  (unavailable - deadlocked threads hold the lock)");
        }
        eprintln!();

        // Detect evidence of the race.
        let history_lost = session_available && final_len == 0;
        let history_corrupted = session_available && final_hash != hash_before;

        let race_detected = deadlock_detected || history_lost || history_corrupted;

        eprintln!("── Race analysis ──");
        if deadlock_detected {
            eprintln!("  ⚠ DEADLOCK at put_session_state_back line 2959");
            eprintln!("    Reentrant lock_session() while line 2906");
            eprintln!("    already holds the session mutex.");
            eprintln!("    This is a pre-existing self-deadlock that");
            eprintln!("    triggers when >=2 threads call send concurrently.");
            eprintln!();
            eprintln!("  Trace evidence visible above:");
            eprintln!("    - CONCURRENT_SESSION_SEND overlapping calls");
            eprintln!("    - HISTORY_EMPTY_AT_TAKE (second thread sees 0)");
            eprintln!("    - OVERLAP_HISTORY_OWNER");
            eprintln!("    - OVERLAP_IN_PROVIDER");
            eprintln!("    - SESSION_STATE_RESTORE_OVERWRITE (if present)");
        }
        if history_lost {
            eprintln!("  ⚠ HISTORY LOST: {len_before} -> {final_len} messages");
            eprintln!("    Session was emptied by concurrent restore.");
        }
        if history_corrupted {
            eprintln!("  ⚠ HISTORY CORRUPTED: hash changed");
            eprintln!("    Before: {hash_before:#x}");
            eprintln!("    After:  {final_hash:#x}");
        }
        if !race_detected {
            eprintln!("  ✓ No race detected.");
            eprintln!();
            eprintln!("── Why the race may not have reproduced ──");
            eprintln!("  Both threads completed without incident.");
            eprintln!("  Possible reasons:");
            eprintln!("    1. Provider creation serialized the threads");
            eprintln!("       (get_or_create_runtime / tokio runtime)");
            eprintln!("    2. Lock acquisition order was coincidentally");
            eprintln!("       sequential despite the Barrier");
            eprintln!("    3. The OS scheduler did not interleave them");
            eprintln!();
            eprintln!("── Suggestions to make reproduction deterministic ──");
            eprintln!("    1. Add a configurable micro-sleep (spin or");
            eprintln!("       thread::yield_now) after the lock in");
            eprintln!("       session_send_inner (behind #[cfg(test)])");
            eprintln!("    2. Add a test-only provider that holds the");
            eprintln!("       session during a controlled delay");
            eprintln!("    3. Use a scope thread (crossbeam) to guarantee");
            eprintln!("       deterministic interleaving");
            eprintln!("    4. Increase to 3+ concurrent threads");
            eprintln!();
            eprintln!("  The reentrant-lock at line 2959 is a latent bug");
            eprintln!("  even if this run did not trigger it. The verify");
            eprintln!("  block should use the existing guard instead of");
            eprintln!("  calling lock_session() again.");
        }
        eprintln!();

        // ── 6. Verification ──
        // Ownership checks: if deadlock occurred, threads still hold the
        // ownership tokens so we expect non-zero atomics. If no deadlock,
        // they should have returned to idle.
        if deadlock_detected {
            eprintln!("(skipping ownership assertions - deadlock prevents cleanup)");
        } else {
            assert_eq!(
                owner_after, 0,
                "HISTORY_OWNER_REQ_ID must be 0 after both send calls complete"
            );
            assert_eq!(
                concurrent_after, 0,
                "CONCURRENT_SEND_COUNT must be 0 after both send calls complete"
            );
            assert_eq!(
                provider_after, 0,
                "PROVIDER_OWNER_REQ_ID must be 0 after both send calls complete"
            );

            // If session is readable, verify history integrity.
            if session_available {
                let mut msg_count = 0;
                let mut dupes = 0;
                let mut out_of_order = 0;

                if let Ok(guard) = SESSION.try_lock() {
                    if let Some(session) = guard.as_ref() {
                        msg_count = session.history.len();
                        for i in 1..session.history.len() {
                            if session.history[i].role == session.history[i - 1].role {
                                out_of_order += 1;
                            }
                        }
                        for i in 0..session.history.len() {
                            for j in (i + 1)..session.history.len() {
                                if session.history[i].content == session.history[j].content
                                    && session.history[i].role == session.history[j].role
                                {
                                    dupes += 1;
                                }
                            }
                        }
                    }
                    drop(guard);
                }

                if msg_count > 0 {
                    assert!(
                        dupes == 0,
                        "Found {dupes} duplicate message(s) in final history"
                    );
                    assert!(
                        out_of_order == 0,
                        "Found {out_of_order} out-of-order message pair(s)"
                    );
                    assert!(
                        final_hash != 0,
                        "History hash is 0 (corrupt)"
                    );
                    eprintln!("  History integrity: {msg_count} msgs, {dupes} dupes, {out_of_order} out-of-order");
                }
            }
        }

        if race_detected {
            eprintln!("╔══════════════════════════════════════════════════╗");
            eprintln!("║  RACE REPRODUCED: concurrency bug confirmed     ║");
            eprintln!("╚══════════════════════════════════════════════════╝");
            eprintln!("  The reentrant lock deadlock in put_session_state_back");
            eprintln!("  at line 2959 is a deterministic concurrency bug.");
            eprintln!("  It triggers whenever >=2 threads call");
            eprintln!("  session_send_inner() concurrently.");
            eprintln!();
            eprintln!("  No fix is implemented by this test.");
            eprintln!("  Proceed to fix phase when ready.");
            eprintln!();
        }

        assert!(
            race_detected,
            "Concurrency race or deadlock was NOT reproduced.\n\
             This is unusual when both threads synchronise on the same barrier.\n\
             See the 'Why the race may not have reproduced' section above."
        );
    }

    /// Truncates a string to at most `max` chars for debug display.
    fn truncate_debug(s: &str, max: usize) -> String {
        if s.len() <= max {
            s.to_string()
        } else {
            format!("{}...{}", &s[..max / 2], &s[s.len() - max / 2..])
        }
    }
}