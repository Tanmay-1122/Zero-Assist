use async_trait::async_trait;
use zeroclaw_api::tool::ToolResult;

/// A typed request for the Shell capability.
/// Parsed from LLM JSON output once, then strongly typed.
#[derive(Debug, Clone)]
pub struct ShellRequest {
    /// The shell command to execute.
    pub command: String,
    /// Whether the user explicitly approved medium/high-risk commands.
    pub approved: bool,
}

/// A capability request — one variant per capability.
///
/// Phase 1: Only Shell. New variants are added as each capability is migrated.
///
/// Instead of passing `serde_json::Value` to executors, the runtime validates
/// LLM JSON output into the correct typed variant once, then dispatches.
/// This gives compile-time safety, autocomplete, and schema enforcement.
#[derive(Debug, Clone)]
pub struct MemoryRequest {
    pub action: String,
    pub query: String,
    pub content: Option<String>,
    pub tags: Option<Vec<String>>,
}

#[derive(Debug, Clone)]
pub struct WebRequest {
    pub action: String,
    pub url: Option<String>,
    pub query: Option<String>,
}

#[derive(Debug, Clone)]
pub struct TermuxRequest {
    pub command: String,
    pub arguments: Option<Vec<String>>,
    pub working_directory: Option<String>,
    pub timeout_seconds: Option<u64>,
}

#[derive(Debug, Clone)]
pub struct TermuxCapabilitiesRequest {
    pub refresh: Option<bool>,
}

#[derive(Debug, Clone)]
pub struct SandboxExecuteRequest {
    pub command: String,
    pub timeout: Option<u64>,
    pub working_dir: Option<String>,
    pub env: Option<serde_json::Map<String, serde_json::Value>>,
    pub background: Option<bool>,
    pub fresh: Option<bool>,
}

#[derive(Debug, Clone)]
pub struct SandboxManageProcessRequest {
    pub action: String,
    pub session_id: Option<String>,
    pub offset: Option<u64>,
    pub limit: Option<u64>,
}

#[derive(Debug, Clone)]
pub enum CapabilityRequest {
    Shell(ShellRequest),
    Termux(TermuxRequest),
    TermuxCapabilities(TermuxCapabilitiesRequest),
    Memory(MemoryRequest),
    Web(WebRequest),
    SandboxExecute(SandboxExecuteRequest),
    SandboxManageProcess(SandboxManageProcessRequest),
}

/// Parses a typed ShellRequest from raw LLM JSON arguments.
///
/// Returns an error if required fields are missing or have wrong types.
/// This is the only place JSON validation happens — executors receive ShellRequest.
pub fn parse_shell_request(args: &serde_json::Value) -> Result<ShellRequest, String> {
    let command = args
        .get("command")
        .and_then(|v| v.as_str())
        .ok_or_else(|| "Missing or invalid 'command' field in shell request".to_string())?;

    if command.is_empty() {
        return Err("'command' must not be empty".to_string());
    }

    let approved = args
        .get("approved")
        .and_then(|v| v.as_bool())
        .unwrap_or(false);

    Ok(ShellRequest {
        command: command.to_string(),
        approved,
    })
}

/// Converts raw LLM tool arguments into a typed CapabilityRequest.
///
/// The runtime calls this once per tool invocation. After validation,
/// all downstream code works with typed structs.
pub fn parse_capability_request(
    tool_name: &str,
    args: &serde_json::Value,
) -> Result<CapabilityRequest, String> {
    match tool_name {
        "shell" => parse_shell_request(args).map(CapabilityRequest::Shell),
        "termux_get_capabilities" => {
            let refresh = args
                .get("refresh")
                .and_then(|v| v.as_bool());
            Ok(CapabilityRequest::TermuxCapabilities(TermuxCapabilitiesRequest { refresh }))
        }
        "termux_run" => {
            let command = args
                .get("command")
                .and_then(|v| v.as_str())
                .ok_or_else(|| "Missing or invalid 'command' field in termux_run request".to_string())?;
            
            if command.is_empty() {
                return Err("'command' must not be empty".to_string());
            }

            let arguments = args
                .get("arguments")
                .and_then(|v| v.as_array())
                .map(|arr| {
                    arr.iter()
                        .filter_map(|v| v.as_str().map(|s| s.to_string()))
                        .collect::<Vec<_>>()
                });
            
            let working_directory = args
                .get("working_directory")
                .and_then(|v| v.as_str())
                .map(|s| s.to_string());
            
            let timeout_seconds = args
                .get("timeout_seconds")
                .and_then(|v| v.as_u64());

            Ok(CapabilityRequest::Termux(TermuxRequest {
                command: command.to_string(),
                arguments,
                working_directory,
                timeout_seconds,
            }))
        }
        "memory" => {
            let action = args
                .get("action")
                .and_then(|v| v.as_str())
                .ok_or_else(|| "Missing 'action' in memory request".to_string())?
                .to_string();
            let query = args
                .get("query")
                .and_then(|v| v.as_str())
                .unwrap_or("")
                .to_string();
            let content = args.get("content").and_then(|v| v.as_str()).map(|s| s.to_string());
            let tags = args.get("tags").and_then(|v| v.as_array()).map(|a| {
                a.iter().filter_map(|v| v.as_str().map(|s| s.to_string())).collect()
            });
            Ok(CapabilityRequest::Memory(MemoryRequest { action, query, content, tags }))
        }
        "web" => {
            let action = args
                .get("action")
                .and_then(|v| v.as_str())
                .ok_or_else(|| "Missing 'action' in web request".to_string())?
                .to_string();
            let url = args.get("url").and_then(|v| v.as_str()).map(|s| s.to_string());
            let query = args.get("query").and_then(|v| v.as_str()).map(|s| s.to_string());
            Ok(CapabilityRequest::Web(WebRequest { action, url, query }))
        }
        "sandbox_execute" => {
            let command = args
                .get("command")
                .and_then(|v| v.as_str())
                .ok_or_else(|| "Missing or invalid 'command' field in sandbox_execute request".to_string())?;
            
            if command.is_empty() {
                return Err("'command' must not be empty".to_string());
            }

            let timeout = args.get("timeout").and_then(|v| v.as_u64());
            let working_dir = args.get("working_dir").and_then(|v| v.as_str()).map(|s| s.to_string());
            let env = args.get("env").and_then(|v| v.as_object()).cloned();
            let background = args.get("background").and_then(|v| v.as_bool());
            let fresh = args.get("fresh").and_then(|v| v.as_bool());

            Ok(CapabilityRequest::SandboxExecute(SandboxExecuteRequest {
                command: command.to_string(),
                timeout,
                working_dir,
                env,
                background,
                fresh,
            }))
        }
        "sandbox_manage_process" => {
            let action = args
                .get("action")
                .and_then(|v| v.as_str())
                .ok_or_else(|| "Missing 'action' in sandbox_manage_process request".to_string())?
                .to_string();
            
            let session_id = args.get("session_id").and_then(|v| v.as_str()).map(|s| s.to_string());
            let offset = args.get("offset").and_then(|v| v.as_u64());
            let limit = args.get("limit").and_then(|v| v.as_u64());

            Ok(CapabilityRequest::SandboxManageProcess(SandboxManageProcessRequest {
                action,
                session_id,
                offset,
                limit,
            }))
        }
        other => Err(format!("Unknown capability tool name: {other}")),
    }
}

/// Executes actions for a capability.
///
/// One executor per capability. The executor receives a typed request
/// and returns a ToolResult. No serde_json::Value manipulation inside executors.
#[async_trait]
pub trait CapabilityExecutor: Send + Sync {
    /// Execute a request for this capability.
    async fn execute(&self, request: CapabilityRequest) -> anyhow::Result<ToolResult>;
}
