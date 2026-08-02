//! MCP (Model Context Protocol) client — connects to external tool servers.
//!
//! Supports multiple transports: stdio (spawn local process), HTTP, and SSE.

use std::collections::HashMap;
use std::sync::Arc;
#[cfg(not(target_has_atomic = "64"))]
use std::sync::atomic::AtomicU32;
#[cfg(target_has_atomic = "64")]
use std::sync::atomic::AtomicU64;
use std::sync::atomic::Ordering;

use anyhow::{Context, Result, anyhow, bail};
use serde_json::json;
use tokio::sync::Mutex;
use tokio::time::{Duration, timeout};

use crate::mcp_protocol::{JsonRpcRequest, MCP_PROTOCOL_VERSION, McpToolDef, McpToolsListResult};
use crate::mcp_transport::{McpTransportConn, create_transport};
use zeroclaw_config::schema::McpServerConfig;

/// Timeout for receiving a response from an MCP server during init/list.
/// Generous because remote/streamable-HTTP servers (e.g. GitHub MCP) can be
/// slow to respond on first connection, especially over mobile networks.
/// Prevents a hung server from blocking the daemon indefinitely.
const RECV_TIMEOUT_SECS: u64 = 90;

/// Cap on how long the `notifications/initialized` send may take. Notifications
/// have no response; this guard stops a wedged server from stalling the handshake.
const NOTIFICATION_SEND_TIMEOUT_SECS: u64 = 10;

/// Default timeout for tool calls (seconds) when not configured per-server.
const DEFAULT_TOOL_TIMEOUT_SECS: u64 = 180;

/// Maximum allowed tool call timeout (seconds) — hard safety ceiling.
const MAX_TOOL_TIMEOUT_SECS: u64 = 600;

// ── Internal server state ──────────────────────────────────────────────────

struct McpServerInner {
    config: McpServerConfig,
    transport: Box<dyn McpTransportConn>,
    #[cfg(target_has_atomic = "64")]
    next_id: AtomicU64,
    #[cfg(not(target_has_atomic = "64"))]
    next_id: AtomicU32,
    tools: Vec<McpToolDef>,
}

// ── McpServer ──────────────────────────────────────────────────────────────

/// A live connection to one MCP server (any transport).
#[derive(Clone)]
pub struct McpServer {
    inner: Arc<Mutex<McpServerInner>>,
}

impl McpServer {
    /// Connect to the server, perform the initialize handshake, and fetch the tool list.
    pub async fn connect(config: McpServerConfig) -> Result<Self> {
        let server_name = config.name.clone();

        // ── STAGE 3: TRANSPORT ────────────────────────────────────────────
        eprintln!("[MCP][{}] Creating transport", server_name);
        let mut transport = create_transport(&config).with_context(|| {
            format!(
                "failed to create transport for MCP server `{}`",
                server_name
            )
        })?;
        eprintln!("[MCP][{}] Transport created successfully", server_name);

        // ── STAGE 3: INITIALIZE ───────────────────────────────────────────
        let id = 1u64;
        let init_req = JsonRpcRequest::new(
            id,
            "initialize",
            json!({
                "protocolVersion": MCP_PROTOCOL_VERSION,
                "capabilities": {},
                "clientInfo": {
                    "name": "zeroclaw",
                    "version": env!("CARGO_PKG_VERSION")
                }
            }),
        );
        eprintln!("[MCP][{}] Sending initialize request", server_name);

        let init_resp = timeout(
            Duration::from_secs(RECV_TIMEOUT_SECS),
            transport.send_and_recv(&init_req),
        )
        .await
        .with_context(|| {
            format!(
                "MCP server `{}` timed out after {}s waiting for initialize response",
                server_name, RECV_TIMEOUT_SECS
            )
        })??;

        eprintln!("[MCP][{}] Received initialize response", server_name);

        if let Some(ref err) = init_resp.error {
            eprintln!("[MCP][{}] Initialize REJECTED: {:?}", server_name, err);
            bail!(
                "MCP server `{}` rejected initialize: {:?}",
                server_name,
                init_resp.error
            );
        }

        // Notify server that client is initialized (no response expected for notifications)
        // For notifications, we send but don't wait for response
        let notif = JsonRpcRequest::notification("notifications/initialized", json!({}));
        // Best effort - ignore errors and timeouts for notifications, but never
        // let a wedged server stall the handshake.
        let _ = timeout(
            Duration::from_secs(NOTIFICATION_SEND_TIMEOUT_SECS),
            transport.send_and_recv(&notif),
        )
        .await;

        // ── STAGE 4: TOOLS/LIST ───────────────────────────────────────────
        let id = 2u64;
        let list_req = JsonRpcRequest::new(id, "tools/list", json!({}));
        eprintln!("[MCP][{}] Requesting tools/list", server_name);

        let list_resp = timeout(
            Duration::from_secs(RECV_TIMEOUT_SECS),
            transport.send_and_recv(&list_req),
        )
        .await
        .with_context(|| {
            format!(
                "MCP server `{}` timed out after {}s waiting for tools/list response",
                server_name, RECV_TIMEOUT_SECS
            )
        })??;

        let result = list_resp
            .result
            .ok_or_else(|| anyhow!("tools/list returned no result from `{}`", server_name))?;
        let tool_list: McpToolsListResult = serde_json::from_value(result)
            .with_context(|| format!("failed to parse tools/list from `{}`", server_name))?;

        let tool_count = tool_list.tools.len();
        eprintln!("[MCP][{}] Received {} tools from tools/list", server_name, tool_count);
        for t in &tool_list.tools {
            eprintln!("[MCP][{}]   tool={}", server_name, t.name);
        }

        let inner = McpServerInner {
            config,
            transport,
            #[cfg(target_has_atomic = "64")]
            next_id: AtomicU64::new(3), // Start at 3 since we used 1 and 2
            #[cfg(not(target_has_atomic = "64"))]
            next_id: AtomicU32::new(3), // Start at 3 since we used 1 and 2
            tools: tool_list.tools,
        };

        let tool_names: Vec<&str> = inner.tools.iter().map(|t| t.name.as_str()).collect();
        eprintln!("[MCP][{}] Connection complete — {} tool(s)", server_name, tool_names.len());
        tracing::info!(
            server = %inner.config.name,
            tool_count,
            tool_names = ?tool_names,
            "MCP server connected"
        );
        if tool_count == 0 {
            tracing::warn!(
                server = %inner.config.name,
                "MCP server returned zero tools — check server configuration"
            );
        }

        Ok(Self {
            inner: Arc::new(Mutex::new(inner)),
        })
    }

    /// Tools advertised by this server.
    pub async fn tools(&self) -> Vec<McpToolDef> {
        self.inner.lock().await.tools.clone()
    }

    /// Server display name.
    pub async fn name(&self) -> String {
        self.inner.lock().await.config.name.clone()
    }

    /// Call a tool on this server. Returns the raw JSON result.
    pub async fn call_tool(
        &self,
        tool_name: &str,
        arguments: serde_json::Value,
    ) -> Result<serde_json::Value> {
        let mut inner = self.inner.lock().await;
        let server_name = inner.config.name.clone();
        let id = inner.next_id.fetch_add(1, Ordering::Relaxed);
        let req = JsonRpcRequest::new(
            id,
            "tools/call",
            json!({ "name": tool_name, "arguments": arguments }),
        );

        // Use per-server tool timeout if configured, otherwise default.
        // Cap at MAX_TOOL_TIMEOUT_SECS for safety.
        let tool_timeout = inner
            .config
            .tool_timeout_secs
            .unwrap_or(DEFAULT_TOOL_TIMEOUT_SECS)
            .min(MAX_TOOL_TIMEOUT_SECS);

        tracing::debug!(
            server = %server_name,
            tool = %tool_name,
            timeout_s = tool_timeout,
            "MCP call_tool"
        );

        let resp = timeout(
            Duration::from_secs(tool_timeout),
            inner.transport.send_and_recv(&req),
        )
        .await
        .map_err(|_| {
            let msg = format!(
                "MCP server `{}` timed out after {}s during tool call `{tool_name}`",
                server_name, tool_timeout
            );
            tracing::error!(server = %server_name, tool = %tool_name, timeout_s = tool_timeout, "MCP tool call timed out");
            anyhow!("{}", msg)
        })?
        .with_context(|| {
            format!(
                "MCP server `{}` error during tool call `{tool_name}`",
                server_name
            )
        })?;

        if let Some(err) = resp.error {
            tracing::error!(server = %server_name, tool = %tool_name, code = err.code, message = %err.message, "MCP tool returned error");
            let data_hint = err
                .data
                .as_ref()
                .map(|d| format!(" (data: {})", serde_json::to_string(d).unwrap_or_default()))
                .unwrap_or_default();
            bail!("MCP tool `{tool_name}` error {}: {}{}", err.code, err.message, data_hint);
        }
        tracing::debug!(server = %server_name, tool = %tool_name, "MCP tool call succeeded");
        Ok(resp.result.unwrap_or(serde_json::Value::Null))
    }
}

// ── McpSubAgent & Hierarchical Agent Architecture ───────────────────────
//
// Each MCP server becomes a fully autonomous AI worker:
//   Main AI  ->  mcp_agent_<name>(goal)  ->  McpSubAgent (own LLM loop)
//                                              -> MCP server internal tools
//
// The Main AI NEVER sees internal MCP tool names (e.g. ui_click, app).
// Only the sub-agent's LLM can access them.

/// Provider configuration threaded into a sub-agent at creation time.
/// Carries exactly what is needed to make independent LLM calls.
#[derive(Clone, Debug)]
pub struct McpSubAgentProviderConfig {
    /// Resolved provider name (e.g. "openrouter", "anthropic", "deepseek").
    pub provider_name: String,
    /// Resolved model string (e.g. "deepseek/deepseek-chat").
    pub model: String,
    /// Optional API key override for this sub-agent's provider.
    pub api_key: Option<String>,
    /// Optional base URL override.
    pub base_url: Option<String>,
}

/// Connection status for one configured MCP server.
#[derive(Clone, Debug, serde::Serialize)]
pub struct McpServerStatus {
    /// Configured server name (as shown in the TOML).
    pub name: String,
    /// Whether the initialize + tools/list handshake succeeded.
    pub connected: bool,
    /// Number of tools indexed from this server (0 when not connected).
    pub tool_count: usize,
    /// Human-readable failure reason when `connected` is `false`.
    pub error: Option<String>,
}

/// Independent conversation context for an MCP worker sub-agent.
#[derive(Clone, Debug, Default)]
pub struct McpSubAgentContext {
    /// Full message history for this sub-agent's LLM loop.
    pub history: Vec<zeroclaw_api::provider::ChatMessage>,
    /// Domain state (e.g. active window handles, file paths).
    pub state: HashMap<String, serde_json::Value>,
}

/// Generate a specialised system prompt for one MCP server sub-agent.
///
/// The prompt scopes the agent strictly to its own server's tools and
/// prevents it from drifting into unrelated general-purpose answering.
fn build_subagent_system_prompt(server_name: &str, tools: &[McpToolDef]) -> String {
    let tool_list = if tools.is_empty() {
        "  (no tools discovered yet)".to_string()
    } else {
        tools
            .iter()
            .map(|t| {
                let desc = t
                    .description
                    .as_deref()
                    .unwrap_or("no description")
                    .chars()
                    .take(120)
                    .collect::<String>();
                format!("  - {} : {}", t.name, desc)
            })
            .collect::<Vec<_>>()
            .join("\n")
    };

    format!(
        "You are the {server_name} Agent — a specialised AI worker.\n\
         \n\
         Your ONLY responsibility is to use {server_name} tools to accomplish\n\
         the goal provided to you. You have access to the following tools:\n\
         \n\
         {tool_list}\n\
         \n\
         Strict rules:\n\
         - NEVER answer general questions unrelated to your tools.\n\
         - NEVER perform work that cannot be done with the tools listed above.\n\
         - Do not describe what you are going to do — just do it.\n\
         - Invoke tools iteratively until the goal is fully complete.\n\
         - When the goal is complete, respond ONLY with a concise JSON result:\n\
           {{\"status\": \"Completed\", \"summary\": \"<one-line description>\"}}\n\
         - On tool failure: retry once with corrected arguments, then report:\n\
           {{\"status\": \"Failed\", \"reason\": \"<what went wrong>\"}}\n\
         - You have NO knowledge of other MCP servers or the Main AI."
    )
}

/// A live, lazily-initialised worker sub-agent for one MCP server.
///
/// Owns an independent LLM reasoning loop: receives a high-level goal from the
/// Main AI, plans tool calls against its own MCP server, executes them, and
/// returns a structured result — all without the Main AI seeing internal tool
/// schemas.
pub struct McpSubAgent {
    pub server_name: String,
    pub config: McpServerConfig,
    pub server: McpServer,
    pub tools: Vec<McpToolDef>,
    pub provider_config: McpSubAgentProviderConfig,
    pub system_prompt: String,
    pub context: Arc<tokio::sync::Mutex<McpSubAgentContext>>,
}

/// Maximum tool-call iterations per sub-agent turn (safety ceiling).
const SUB_AGENT_MAX_ITERATIONS: usize = 8;

impl McpSubAgent {
    /// Connect to transport, run initialize handshake, discover tools, and
    /// build the specialised system prompt. No LLM call yet.
    ///
    /// **LOG 2** (lazy creation) and **LOG 4** (tool discovery) emit here.
    pub async fn create(
        config: McpServerConfig,
        provider_config: McpSubAgentProviderConfig,
    ) -> Result<Self> {
        let server_name = config.name.clone();

        // LOG 2 — lazy creation event
        eprintln!(
            "[MCP SUB-AGENT][{server_name}] LAZY CREATION: first use — connecting to server..."
        );
        tracing::info!(
            server = %server_name,
            "MCP sub-agent: lazy creation triggered"
        );

        // LOG 3 — model selection (sub-agents always inherit the Main AI provider)
        eprintln!(
            "[MCP SUB-AGENT][{server_name}] Model resolved: {}/{} (inherited from Main AI)",
            provider_config.provider_name,
            provider_config.model,
        );
        tracing::info!(
            server = %server_name,
            provider = %provider_config.provider_name,
            model = %provider_config.model,
            "MCP sub-agent: model selected"
        );

        let server = McpServer::connect(config.clone()).await?;
        let tools = server.tools().await;

        // LOG 4 — tool discovery
        let internal_tool_names: Vec<&str> = tools.iter().map(|t| t.name.as_str()).collect();
        eprintln!(
            "[MCP SUB-AGENT][{server_name}] Tool discovery complete: {} tool(s) found: {internal_tool_names:?}",
            tools.len()
        );
        tracing::info!(
            server = %server_name,
            tool_count = tools.len(),
            tool_names = ?internal_tool_names,
            "MCP sub-agent: internal tools discovered"
        );

        let system_prompt = build_subagent_system_prompt(&server_name, &tools);

        Ok(Self {
            server_name,
            config,
            server,
            tools,
            provider_config,
            system_prompt,
            context: Arc::new(tokio::sync::Mutex::new(McpSubAgentContext::default())),
        })
    }

    /// Execute a high-level goal using a **real multi-turn LLM reasoning loop**.
    ///
    /// Flow per iteration:
    ///   1. Build history from system prompt + accumulated turns.
    ///   2. **LOG 5**: emit LLM request event.
    ///   3. Call provider LLM with sub-agent's internal tool schemas.
    ///   4. Parse tool calls from response.
    ///   5. **LOG 6**: emit each chosen tool.
    ///   6. Execute tool on MCP server, append result to history.
    ///   7. Repeat until LLM stops calling tools or max_iterations reached.
    ///   8. **LOG 7**: emit final response before returning to Main AI.
    pub async fn execute_goal(&self, goal: &str) -> Result<serde_json::Value> {
        eprintln!("[MCP SUB-AGENT][{}] Executing goal: {goal}", self.server_name);
        tracing::info!(
            server = %self.server_name,
            goal = %goal,
            model = %self.provider_config.model,
            "MCP sub-agent: starting goal execution"
        );

        // Resolve the LLM provider for this sub-agent.
        let provider = zeroclaw_providers::create_provider_with_url(
            &self.provider_config.provider_name,
            self.provider_config.api_key.as_deref(),
            self.provider_config.base_url.as_deref(),
        )?;

        // Build the initial history: system prompt + user goal.
        let mut history: Vec<zeroclaw_api::provider::ChatMessage> = vec![
            zeroclaw_api::provider::ChatMessage::system(&self.system_prompt),
            zeroclaw_api::provider::ChatMessage::user(goal),
        ];

        // Persist user goal into the sub-agent's isolated context.
        {
            let mut ctx = self.context.lock().await;
            ctx.history = history.clone();
        }

        // Build ToolSpec list from discovered internal tools — these NEVER
        // leave this function; they are NOT exposed to the Main AI.
        let tool_specs: Vec<zeroclaw_api::tool::ToolSpec> = self
            .tools
            .iter()
            .map(|t| zeroclaw_api::tool::ToolSpec {
                name: t.name.clone(),
                description: t.description.clone().unwrap_or_default(),
                parameters: t.input_schema.clone(),
            })
            .collect();

        let mut turns_completed: usize = 0;
        let mut final_text = String::new();

        for iteration in 0..SUB_AGENT_MAX_ITERATIONS {
            // LOG 5 — LLM request by sub-agent
            eprintln!(
                "[MCP SUB-AGENT][{}] LLM REQUEST #{} | model={} | history_len={}",
                self.server_name,
                iteration + 1,
                self.provider_config.model,
                history.len()
            );
            tracing::info!(
                server = %self.server_name,
                iteration = iteration + 1,
                model = %self.provider_config.model,
                history_len = history.len(),
                "MCP sub-agent: LLM request"
            );

            let use_native = provider.supports_native_tools() && !tool_specs.is_empty();
            let request_tools: Option<&[zeroclaw_api::tool::ToolSpec]> = if use_native {
                Some(&tool_specs)
            } else {
                None
            };

            let response = provider
                .chat(
                    zeroclaw_api::provider::ChatRequest {
                        messages: &history,
                        tools: request_tools,
                    },
                    &self.provider_config.model,
                    Some(0.2),
                )
                .await
                .with_context(|| {
                    format!(
                        "MCP sub-agent '{}' LLM call failed at iteration {}",
                        self.server_name,
                        iteration + 1
                    )
                })?;

            let response_text = response.text.unwrap_or_default();
            turns_completed = iteration + 1;

            // Check for native tool calls first.
            let tool_calls_native = &response.tool_calls;

            if tool_calls_native.is_empty() {
                // No more tool calls — LLM is done.
                final_text = response_text.clone();
                history.push(zeroclaw_api::provider::ChatMessage::assistant(&response_text));
                break;
            }

            // Append the assistant turn.
            history.push(zeroclaw_api::provider::ChatMessage::assistant(&response_text));

            // Execute each tool call sequentially.
            for tc in tool_calls_native {
                let tool_name = &tc.name;
                let raw_args = if tc.arguments.is_empty() { "{}" } else { &tc.arguments };
                let args: serde_json::Value = serde_json::from_str(raw_args)
                    .unwrap_or(serde_json::Value::Object(serde_json::Map::new()));

                // LOG 6 — tool chosen by sub-agent
                eprintln!(
                    "[MCP SUB-AGENT][{}] TOOL CHOSEN: {tool_name} | args={raw_args}",
                    self.server_name
                );
                tracing::info!(
                    server = %self.server_name,
                    tool = %tool_name,
                    args = %raw_args,
                    "MCP sub-agent: tool chosen"
                );

                let tool_result = match self.server.call_tool(tool_name, args).await {
                    Ok(result) => {
                        let result_str = serde_json::to_string(&result)
                            .unwrap_or_else(|_| result.to_string());
                        eprintln!(
                            "[MCP SUB-AGENT][{}] TOOL RESULT: {tool_name} -> {result_str}",
                            self.server_name
                        );
                        result_str
                    }
                    Err(e) => {
                        let err_msg = format!("Tool `{tool_name}` failed: {e:#}");
                        eprintln!("[MCP SUB-AGENT][{}] TOOL ERROR: {err_msg}", self.server_name);
                        tracing::warn!(
                            server = %self.server_name,
                            tool = %tool_name,
                            error = %e,
                            "MCP sub-agent: tool execution failed"
                        );
                        err_msg
                    }
                };

                // Append tool result as a tool-role message.
                history.push(zeroclaw_api::provider::ChatMessage::tool(format!(
                    "[Tool result for {tool_name} (id={})]: {tool_result}",
                    tc.id
                )));
            }

            if turns_completed >= SUB_AGENT_MAX_ITERATIONS {
                final_text = format!(
                    "{{\"status\": \"Incomplete\", \"reason\": \"max iterations ({SUB_AGENT_MAX_ITERATIONS}) reached\"}}"
                );
            }
        }

        // Persist final context back to the sub-agent.
        {
            let mut ctx = self.context.lock().await;
            ctx.history = history;
        }

        // Build structured result for the Main AI.
        let result = if final_text.starts_with('{') {
            // LLM returned JSON directly — pass through.
            serde_json::from_str::<serde_json::Value>(&final_text).unwrap_or_else(|_| {
                json!({
                    "status": "Completed",
                    "server": self.server_name,
                    "goal": goal,
                    "output": final_text,
                    "turns": turns_completed
                })
            })
        } else {
            json!({
                "status": "Completed",
                "server": self.server_name,
                "goal": goal,
                "output": final_text,
                "turns": turns_completed
            })
        };

        // LOG 7 — final response returned to Main AI
        eprintln!(
            "[MCP SUB-AGENT][{}] FINAL RESPONSE -> Main AI | status={} | turns={turns_completed}",
            self.server_name,
            result.get("status").and_then(|s| s.as_str()).unwrap_or("?"),
        );
        tracing::info!(
            server = %self.server_name,
            goal = %goal,
            turns = turns_completed,
            status = ?result.get("status"),
            "MCP sub-agent: goal execution complete, returning to Main AI"
        );

        Ok(result)
    }
}

/// Manager handling lazy sub-agent creation, caching, lifecycle, and model resolution.
pub struct McpAgentManager {
    configs: HashMap<String, McpServerConfig>,
    cached_subagents: Arc<tokio::sync::Mutex<HashMap<String, Arc<McpSubAgent>>>>,
    statuses: Arc<std::sync::Mutex<HashMap<String, McpServerStatus>>>,
    /// Full config snapshot used to resolve provider API keys and routes.
    provider_configs: HashMap<String, McpSubAgentProviderConfig>,
}

impl McpAgentManager {
    pub fn new(
        configs: &[McpServerConfig],
        fallback_provider: &str,
        fallback_model: &str,
        fallback_api_key: Option<&str>,
        fallback_base_url: Option<&str>,
    ) -> Self {
        let mut map = HashMap::new();
        let mut statuses = HashMap::new();
        let mut provider_configs = HashMap::new();

        for cfg in configs {
            // Sub-agents always inherit the Main AI's provider and model so
            // every sub-agent speaks to the same LLM as the Main AI.
            provider_configs.insert(
                cfg.name.clone(),
                McpSubAgentProviderConfig {
                    provider_name: fallback_provider.to_string(),
                    model: fallback_model.to_string(),
                    api_key: fallback_api_key.map(str::to_string),
                    base_url: fallback_base_url.map(str::to_string),
                },
            );

            map.insert(cfg.name.clone(), cfg.clone());
            statuses.insert(
                cfg.name.clone(),
                McpServerStatus {
                    name: cfg.name.clone(),
                    connected: false,
                    tool_count: 0,
                    error: None,
                },
            );
        }

        Self {
            configs: map,
            cached_subagents: Arc::new(tokio::sync::Mutex::new(HashMap::new())),
            statuses: Arc::new(std::sync::Mutex::new(statuses)),
            provider_configs,
        }
    }

    /// Get an existing cached sub-agent or lazily create one.
    ///
    /// **LOG 2** (lazy creation), **LOG 3** (model), **LOG 4** (tool discovery)
    /// are all emitted inside `McpSubAgent::create`.
    pub async fn get_or_create_subagent(&self, server_name: &str) -> Result<Arc<McpSubAgent>> {
        // Fast path: cache hit
        {
            let cache = self.cached_subagents.lock().await;
            if let Some(agent) = cache.get(server_name) {
                eprintln!(
                    "[MCP SUB-AGENT][{server_name}] Cache hit — reusing existing sub-agent"
                );
                return Ok(Arc::clone(agent));
            }
        }

        // Slow path: first use — lazy creation
        let config = self
            .configs
            .get(server_name)
            .ok_or_else(|| anyhow!("No MCP server configured with name `{server_name}`"))?
            .clone();

        let provider_cfg = self
            .provider_configs
            .get(server_name)
            .cloned()
            .unwrap_or_else(|| McpSubAgentProviderConfig {
                provider_name: "openrouter".to_string(),
                model: "anthropic/claude-sonnet-4".to_string(),
                api_key: None,
                base_url: None,
            });

        match McpSubAgent::create(config, provider_cfg).await {
            Ok(subagent) => {
                let arc_agent = Arc::new(subagent);
                let tool_count = arc_agent.tools.len();
                {
                    let mut cache = self.cached_subagents.lock().await;
                    cache.insert(server_name.to_string(), Arc::clone(&arc_agent));
                }
                {
                    let mut st = self.statuses.lock().unwrap();
                    st.insert(
                        server_name.to_string(),
                        McpServerStatus {
                            name: server_name.to_string(),
                            connected: true,
                            tool_count,
                            error: None,
                        },
                    );
                }
                Ok(arc_agent)
            }
            Err(e) => {
                let err_msg = format!("{e:#}");
                {
                    let mut st = self.statuses.lock().unwrap();
                    st.insert(
                        server_name.to_string(),
                        McpServerStatus {
                            name: server_name.to_string(),
                            connected: false,
                            tool_count: 0,
                            error: Some(err_msg.clone()),
                        },
                    );
                }
                bail!("Failed lazy creation of sub-agent `{server_name}`: {err_msg}");
            }
        }
    }

    /// Execute a high-level goal on a sub-agent worker.
    pub async fn execute_goal(&self, server_name: &str, goal: &str) -> Result<serde_json::Value> {
        let subagent = self.get_or_create_subagent(server_name).await?;
        subagent.execute_goal(goal).await
    }

    /// Safely destroy a cached sub-agent (e.g. when server is removed or disabled).
    pub async fn destroy_subagent(&self, server_name: &str) {
        let mut cache = self.cached_subagents.lock().await;
        cache.remove(server_name);
        eprintln!("[MCP SUB-AGENT][{server_name}] Cache entry destroyed");
    }

    /// Return statuses for all configured MCP servers.
    pub fn statuses(&self) -> Vec<McpServerStatus> {
        let st = self.statuses.lock().unwrap();
        st.values().cloned().collect()
    }
}

/// Helper function to sanitize a string into a clean tool identifier.
pub fn sanitize_identifier(s: &str) -> String {
    s.chars()
        .map(|c| if c.is_alphanumeric() || c == '_' { c } else { '_' })
        .collect()
}

/// Registry of MCP server sub-agents exposed to the Main AI.
///
/// The Main AI sees ONLY `mcp_agent_<server_name>` tool stubs.
/// Internal tool schemas stay hidden inside each sub-agent's LLM loop.
pub struct McpRegistry {
    manager: Arc<McpAgentManager>,
    /// Map of sub-agent tool name -> server name
    subagent_tools: HashMap<String, String>,
}

impl McpRegistry {
    /// Initialise sub-agent registry with server metadata.
    ///
    /// No network connections or tool discovery happen here — both are deferred
    /// until the first goal is delegated to each sub-agent.
    ///
    /// **LOG 1**: tools visible to the Main AI are emitted here.
    pub async fn connect_all(configs: &[McpServerConfig]) -> Result<Self> {
        Self::connect_all_with_provider(
            configs,
            "openrouter",
            "anthropic/claude-sonnet-4",
            None,
            None,
        )
        .await
    }

    /// Initialize sub-agent registry using provider credentials and settings from config.
    pub async fn connect_all_from_config(config: &zeroclaw_config::Config) -> Result<Self> {
        let active_mcp_servers = config.mcp.active_servers();
        let fallback_provider = config
            .providers
            .fallback
            .as_deref()
            .unwrap_or("openrouter");
        let fallback_model = config
            .providers
            .resolve_default_model()
            .unwrap_or_else(|| "anthropic/claude-sonnet-4".to_string());
        let fallback_api_key = config
            .providers
            .fallback_provider()
            .and_then(|e| e.api_key.as_deref());
        let fallback_base_url = config
            .providers
            .fallback_provider()
            .and_then(|e| e.base_url.as_deref());

        Self::connect_all_with_provider(
            &active_mcp_servers,
            fallback_provider,
            &fallback_model,
            fallback_api_key,
            fallback_base_url,
        )
        .await
    }


    pub async fn connect_all_with_provider(
        configs: &[McpServerConfig],
        fallback_provider: &str,
        fallback_model: &str,
        fallback_api_key: Option<&str>,
        fallback_base_url: Option<&str>,
    ) -> Result<Self> {
        let manager = Arc::new(McpAgentManager::new(
            configs,
            fallback_provider,
            fallback_model,
            fallback_api_key,
            fallback_base_url,
        ));
        let mut subagent_tools = HashMap::new();

        eprintln!(
            "[MCP HIERARCHICAL REGISTRY] Registering {} server(s) — no connections yet (lazy)",
            configs.len()
        );

        for config in configs {
            if !config.enabled {
                continue;
            }
            let tool_name = format!("mcp_agent_{}", sanitize_identifier(&config.name));
            eprintln!(
                "[MCP HIERARCHICAL REGISTRY] Exposing sub-agent tool: {tool_name} -> server {}",
                config.name
            );
            subagent_tools.insert(tool_name, config.name.clone());
        }

        // LOG 1 — tools sent to the Main AI (sub-agent stubs only)
        let main_ai_tool_names: Vec<&str> = subagent_tools.keys().map(String::as_str).collect();
        eprintln!(
            "\n===== TOOLS_REGISTERED_FOR_MAIN_AI =====\ncount={}\ntool_names={main_ai_tool_names:?}\n========================================",
            main_ai_tool_names.len()
        );
        tracing::info!(
            count = main_ai_tool_names.len(),
            tool_names = ?main_ai_tool_names,
            "MCP hierarchical registry: tools exposed to Main AI (sub-agent stubs only)"
        );

        Ok(Self {
            manager,
            subagent_tools,
        })
    }

    /// Returns high-level sub-agent tool names visible to the Main AI.
    pub fn tool_names(&self) -> Vec<String> {
        self.subagent_tools.keys().cloned().collect()
    }

    /// Returns the tool definition for a given sub-agent tool.
    pub async fn get_tool_def(&self, tool_name: &str) -> Option<McpToolDef> {
        let server_name = self.subagent_tools.get(tool_name)?;
        let cfg = self.manager.configs.get(server_name)?;

        let desc = cfg.description.as_deref().unwrap_or(
            "Specialised AI worker sub-agent. Send a high-level goal; the sub-agent plans and executes it using its own tools.",
        );

        Some(McpToolDef {
            name: tool_name.to_string(),
            description: Some(desc.to_string()),
            input_schema: json!({
                "type": "object",
                "properties": {
                    "goal": {
                        "type": "string",
                        "description": format!(
                            "High-level goal for the {server_name} sub-agent to accomplish. \
                             Be specific. The sub-agent has full control over {server_name} tools."
                        )
                    }
                },
                "required": ["goal"]
            }),
        })
    }

    /// Delegate a goal to the named sub-agent, wait for completion, and return
    /// the structured result.
    ///
    /// **LOG 7** (final response to Main AI) is emitted inside `execute_goal`.
    pub async fn call_tool(
        &self,
        tool_name: &str,
        arguments: serde_json::Value,
    ) -> Result<String> {
        let server_name = self
            .subagent_tools
            .get(tool_name)
            .ok_or_else(|| anyhow!("unknown MCP sub-agent tool `{tool_name}`"))?;

        let goal = arguments
            .get("goal")
            .and_then(|v| v.as_str())
            .unwrap_or_else(|| arguments.as_str().unwrap_or("Execute server task"));

        let result = self.manager.execute_goal(server_name, goal).await?;
        serde_json::to_string_pretty(&result)
            .with_context(|| format!("failed to serialize result of MCP sub-agent `{server_name}`"))
    }

    pub fn is_empty(&self) -> bool {
        self.subagent_tools.is_empty()
    }

    pub fn server_count(&self) -> usize {
        self.subagent_tools.len()
    }

    pub fn tool_count(&self) -> usize {
        self.subagent_tools.len()
    }

    /// Per-server connection statuses.
    pub fn statuses(&self) -> Vec<McpServerStatus> {
        self.manager.statuses()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use zeroclaw_config::schema::McpTransport;

    #[test]
    fn tool_name_prefix_format() {
        let prefixed = format!("{}__{}", "filesystem", "read_file");
        assert_eq!(prefixed, "filesystem__read_file");
    }

    #[tokio::test]
    async fn connect_nonexistent_command_fails_cleanly() {
        // A command that doesn't exist should fail at spawn, not panic.
        let config = McpServerConfig {
            name: "nonexistent".to_string(),
            command: "/usr/bin/this_binary_does_not_exist_zeroclaw_test".to_string(),
            args: vec![],
            env: std::collections::HashMap::default(),
            tool_timeout_secs: None,
            transport: McpTransport::Stdio,
            url: None,
            headers: std::collections::HashMap::default(),
            enabled: true,
            description: None,
        };
        let result = McpServer::connect(config).await;
        assert!(result.is_err());
        let msg = result.err().unwrap().to_string();
        assert!(msg.contains("failed to create transport"), "got: {msg}");
    }

    #[tokio::test]
    async fn connect_all_nonfatal_on_single_failure() {
        // If one server config is bad, connect_all should succeed (with 0 servers).
        let configs = vec![McpServerConfig {
            name: "bad".to_string(),
            command: "/usr/bin/does_not_exist_zc_test".to_string(),
            args: vec![],
            env: std::collections::HashMap::default(),
            tool_timeout_secs: None,
            transport: McpTransport::Stdio,
            url: None,
            headers: std::collections::HashMap::default(),
            enabled: true,
            description: None,
        }];
        let registry = McpRegistry::connect_all(&configs).await
            .expect("connect_all should not fail");
        // enabled server will be registered as a stub even if connection not attempted yet
        assert_eq!(registry.tool_count(), 1); // stub registered
    }

    #[test]
    fn http_transport_requires_url() {
        let config = McpServerConfig {
            name: "test".into(),
            transport: McpTransport::Http,
            ..Default::default()
        };
        let result = create_transport(&config);
        assert!(result.is_err());
    }

    #[test]
    fn sse_transport_requires_url() {
        let config = McpServerConfig {
            name: "test".into(),
            transport: McpTransport::Sse,
            ..Default::default()
        };
        let result = create_transport(&config);
        assert!(result.is_err());
    }

    // ── Empty registry (no servers) ────────────────────────────────────────

    #[tokio::test]
    async fn empty_registry_is_empty() {
        let registry = McpRegistry::connect_all(&[]).await
            .expect("connect_all on empty slice should succeed");
        assert!(registry.is_empty());
        assert_eq!(registry.server_count(), 0);
        assert_eq!(registry.tool_count(), 0);
    }

    #[tokio::test]
    async fn empty_registry_tool_names_is_empty() {
        let registry = McpRegistry::connect_all(&[]).await
            .expect("connect_all should succeed");
        assert!(registry.tool_names().is_empty());
    }

    #[tokio::test]
    async fn empty_registry_get_tool_def_returns_none() {
        let registry = McpRegistry::connect_all(&[]).await
            .expect("connect_all should succeed");
        let result = registry.get_tool_def("nonexistent__tool").await;
        assert!(result.is_none());
    }

    #[tokio::test]
    async fn empty_registry_call_tool_unknown_name_returns_error() {
        let registry = McpRegistry::connect_all(&[]).await
            .expect("connect_all should succeed");
        let err = registry
            .call_tool("nonexistent__tool", serde_json::json!({}))
            .await
            .expect_err("should fail for unknown tool");
        assert!(err.to_string().contains("unknown MCP sub-agent tool"), "got: {err}");
    }

    #[tokio::test]
    async fn connect_all_empty_gives_zero_servers() {
        let registry = McpRegistry::connect_all(&[]).await
            .expect("connect_all should succeed");
        // Verify all three count methods agree on zero.
        assert_eq!(registry.server_count(), 0);
        assert_eq!(registry.tool_count(), 0);
        assert!(registry.is_empty());
    }

    #[test]
    fn system_prompt_contains_server_name() {
        let tools = vec![
            McpToolDef {
                name: "app".to_string(),
                description: Some("Launch or focus an application".to_string()),
                input_schema: serde_json::json!({}),
            },
            McpToolDef {
                name: "ui_click".to_string(),
                description: Some("Click a UI element".to_string()),
                input_schema: serde_json::json!({}),
            },
        ];
        let prompt = build_subagent_system_prompt("Levii-pc", &tools);
        assert!(prompt.contains("Levii-pc Agent"), "expected server name in prompt");
        assert!(prompt.contains("app"), "expected tool name in prompt");
        assert!(prompt.contains("ui_click"), "expected tool name in prompt");
        assert!(!prompt.contains("Main AI"), "should not mention Main AI in subagent prompt");
    }

    #[test]
    fn subagent_inherits_main_ai_provider_model() {
        let configs = vec![McpServerConfig {
            name: "test".into(),
            ..Default::default()
        }];
        let manager = McpAgentManager::new(
            &configs,
            "custom:https://ai.hackclub.com/proxy/v1",
            "openrouter/free",
            Some("hackclub-key"),
            Some("https://ai.hackclub.com/proxy/v1"),
        );
        let provider_cfg = manager
            .provider_configs
            .get("test")
            .cloned()
            .expect("provider config should exist");
        assert_eq!(
            provider_cfg.provider_name,
            "custom:https://ai.hackclub.com/proxy/v1"
        );
        assert_eq!(provider_cfg.model, "openrouter/free");
        assert_eq!(provider_cfg.api_key.as_deref(), Some("hackclub-key"));
    }

    #[tokio::test]
    async fn connect_all_from_config_uses_config_provider_credentials() {
        let mut config = zeroclaw_config::Config::default();
        config.mcp.enabled = true;
        config.mcp.servers.push(McpServerConfig {
            name: "test_server".to_string(),
            enabled: true,
            ..Default::default()
        });

        let mut provider_entry = zeroclaw_config::schema::ModelProviderConfig::default();
        provider_entry.model = Some("gemini-2.5-flash".to_string());
        provider_entry.api_key = Some("test-secret-key".to_string());
        provider_entry.base_url = Some("https://api.example.com/v1".to_string());

        config.providers.fallback = Some("custom_provider".to_string());
        config
            .providers
            .models
            .insert("custom_provider".to_string(), provider_entry);

        let registry = McpRegistry::connect_all_from_config(&config)
            .await
            .expect("should connect from config");

        let p_cfg = registry
            .manager
            .provider_configs
            .get("test_server")
            .expect("test_server provider config should exist");

        assert_eq!(p_cfg.provider_name, "custom_provider");
        assert_eq!(p_cfg.model, "gemini-2.5-flash");
        assert_eq!(p_cfg.api_key.as_deref(), Some("test-secret-key"));
        assert_eq!(p_cfg.base_url.as_deref(), Some("https://api.example.com/v1"));
    }
}


