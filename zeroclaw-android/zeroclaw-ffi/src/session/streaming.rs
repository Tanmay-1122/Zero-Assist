/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! Streaming and progress callback helpers used by Android sessions.

use std::sync::Arc;

use tokio_util::sync::CancellationToken;

use super::{AgentLoopOutcome, FfiSessionListener, STREAM_CHUNK_MIN_CHARS};

/// Sentinel value emitted by upstream to signal the transition from
/// tool-call progress lines to streamed response tokens.
///
/// After this sentinel, all subsequent deltas are response content
/// until the loop iteration ends.
pub(super) const DRAFT_CLEAR_SENTINEL: &str = "\x00CLEAR\x00";

/// Extracts a short hint from tool call arguments for the progress display.
///
/// Returns the most relevant argument value for each registered tool.
/// Truncation is handled downstream by `ToolDisplayFormatter` in Kotlin.
pub(super) fn truncate_tool_args_hint(tool_name: &str, arguments_json: &str) -> String {
    let args: serde_json::Value =
        serde_json::from_str(arguments_json).unwrap_or(serde_json::json!({}));

    // Early returns for tools needing special handling
    match tool_name {
        "cron_list" => return "Listing all cron jobs".to_string(),
        "termux_get_capabilities" => return "Checking termux capabilities".to_string(),
        "memory_recall" => {
            let q = args
                .get("query")
                .and_then(|v| v.as_str())
                .filter(|s| !s.is_empty());
            if let Some(query) = q {
                return query.to_string();
            }
            let since = args
                .get("since")
                .and_then(|v| v.as_str())
                .unwrap_or("");
            let until = args
                .get("until")
                .and_then(|v| v.as_str())
                .unwrap_or("");
            return match (since.is_empty(), until.is_empty()) {
                (false, false) => format!("{since} to {until}"),
                (false, true) => since.to_string(),
                (true, false) => until.to_string(),
                (true, true) => String::new(),
            };
        }
        _ => {}
    }

    let hint = match tool_name {
        // Shell / execution
        "sandbox_execute" | "termux_run" => args.get("command").and_then(|v| v.as_str()),
        "device_control" => args.get("goal").and_then(|v| v.as_str()),

        // File / folder
        "shared_folder_list" | "shared_folder_read" | "shared_folder_write"
        | "workflow_folder_list" | "workflow_folder_read" | "workflow_folder_write" => {
            args.get("path").and_then(|v| v.as_str())
        }

        // Memory (store/forget only — recall handled above)
        "memory_store" | "memory_forget" => args.get("key").and_then(|v| v.as_str()),

        // Web / HTTP
        "web_search_tool" => args.get("query").and_then(|v| v.as_str()),
        "web_fetch" | "http_request" => args.get("url").and_then(|v| v.as_str()),

        // Cron (cron_list handled above)
        "cron_runs" => args.get("job_id").and_then(|v| v.as_str()),

        // Sandbox process management
        "sandbox_manage_process" => args.get("action").and_then(|v| v.as_str()),

        // Composio / tool search
        "composio" => args.get("action").and_then(|v| v.as_str()),
        "tool_search" => args.get("query").and_then(|v| v.as_str()),

        // Generic fallback for MCP / unknown tools
        _ => args
            .get("command")
            .and_then(|v| v.as_str())
            .or_else(|| args.get("query").and_then(|v| v.as_str()))
            .or_else(|| args.get("path").and_then(|v| v.as_str()))
            .or_else(|| args.get("action").and_then(|v| v.as_str()))
            .or_else(|| args.get("url").and_then(|v| v.as_str()))
            .or_else(|| args.get("target").and_then(|v| v.as_str())),
    };

    hint.unwrap_or("").to_string()
}

/// Streams the final response text to the listener in chunks of at least
/// [`STREAM_CHUNK_MIN_CHARS`] characters, split on whitespace boundaries.
pub(super) fn stream_response_text(
    text: &str,
    listener: &Arc<dyn FfiSessionListener>,
    cancel_token: &CancellationToken,
) -> Result<(), AgentLoopOutcome> {
    let mut chunk = String::new();
    for word in text.split_inclusive(char::is_whitespace) {
        if cancel_token.is_cancelled() {
            return Err(AgentLoopOutcome::Cancelled);
        }
        chunk.push_str(word);
        if chunk.len() >= STREAM_CHUNK_MIN_CHARS {
            listener.on_response_chunk(std::mem::take(&mut chunk));
        }
    }
    if !chunk.is_empty() {
        listener.on_response_chunk(chunk);
    }
    Ok(())
}

/// Dispatches a single progress delta string to the appropriate listener callback.
///
/// The upstream agent loop emits deltas in two phases:
///
/// 1. **Progress phase** -- emoji-prefixed status lines describing thinking,
///    tool starts, tool completions, and other progress.
/// 2. **Response phase** -- raw text chunks of the assistant's streamed reply,
///    entered after [`DRAFT_CLEAR_SENTINEL`] is received.
///
/// `streaming_response` tracks which phase we are in and is mutated when
/// the sentinel is encountered.
pub(crate) fn dispatch_delta(
    delta: &str,
    listener: &dyn FfiSessionListener,
    streaming_response: &mut bool,
) {
    if delta == DRAFT_CLEAR_SENTINEL {
        *streaming_response = true;
        return;
    }

    if *streaming_response {
        listener.on_response_chunk(delta.to_string());
        return;
    }

    let trimmed = delta.trim_end_matches('\n');
    if trimmed.is_empty() {
        return;
    }

    let mut chars = trimmed.chars();
    if let Some(first) = chars.next() {
        let rest = chars.as_str();
        match first {
            '\u{1f914}' => {
                // Thinking / planning
                listener.on_thinking(rest.trim().to_string());
            }
            '\u{23f3}' => {
                // Tool start -- format: "tool_name: hint text"
                let rest = rest.trim();
                let (name, hint) = match rest.find(':') {
                    Some(pos) => (rest[..pos].trim(), rest[pos + 1..].trim()),
                    None => (rest, ""),
                };
                listener.on_tool_start(name.to_string(), hint.to_string());
            }
            '\u{2705}' => {
                // Tool success -- format: "tool_name (3s)"
                let (name, secs) = parse_tool_completion(rest.trim());
                listener.on_tool_result(name, true, secs);
            }
            '\u{274c}' => {
                // Tool failure -- format: "tool_name (2s)"
                let (name, secs) = parse_tool_completion(rest.trim());
                listener.on_tool_result(name, false, secs);
            }
            '\u{1f4ac}' => {
                // Informational progress
                listener.on_progress(rest.trim().to_string());
            }
            _ => {
                // Unrecognised prefix -- treat as generic progress
                listener.on_progress(trimmed.to_string());
            }
        }
    }
}

/// Parses a tool completion string into `(tool_name, duration_seconds)`.
///
/// Expected format: `"tool_name (Ns)"` where `N` is an integer.
/// If no parenthesised duration is found, returns `(input, 0)`.
///
/// # Examples
///
/// ```text
/// "read_file (3s)" -> ("read_file", 3)
/// "read_file"      -> ("read_file", 0)
/// ```
fn parse_tool_completion(s: &str) -> (String, u64) {
    if let Some(paren_start) = s.rfind('(') {
        let name = s[..paren_start].trim();
        let inside = &s[paren_start + 1..];
        let secs = inside
            .trim_end_matches(')')
            .trim()
            .trim_end_matches('s')
            .trim()
            .parse::<u64>()
            .unwrap_or(0);
        (name.to_string(), secs)
    } else {
        (s.to_string(), 0)
    }
}

#[cfg(test)]
#[allow(clippy::unwrap_used)]
mod tests {
    use super::*;
    use std::sync::Mutex as StdMutex;

    struct RecordingListener {
        events: StdMutex<Vec<String>>,
    }

    impl RecordingListener {
        fn new() -> Self {
            Self {
                events: StdMutex::new(Vec::new()),
            }
        }

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

    #[test]
    fn test_dispatch_thinking_first_round() {
        let listener = RecordingListener::new();
        let mut streaming = false;
        dispatch_delta("\u{1f914} Planning next steps\n", &listener, &mut streaming);
        assert!(!streaming);
        assert_eq!(listener.events(), vec!["thinking:Planning next steps"]);
    }

    #[test]
    fn test_dispatch_thinking_round_n() {
        let listener = RecordingListener::new();
        let mut streaming = false;
        dispatch_delta(
            "\u{1f914} Re-evaluating approach\n",
            &listener,
            &mut streaming,
        );
        assert_eq!(listener.events(), vec!["thinking:Re-evaluating approach"]);
    }

    #[test]
    fn test_dispatch_tool_start_with_hint() {
        let listener = RecordingListener::new();
        let mut streaming = false;
        dispatch_delta(
            "\u{23f3} read_file: /src/main.rs\n",
            &listener,
            &mut streaming,
        );
        assert_eq!(listener.events(), vec!["tool_start:read_file:/src/main.rs"]);
    }

    #[test]
    fn test_dispatch_tool_start_no_hint() {
        let listener = RecordingListener::new();
        let mut streaming = false;
        dispatch_delta("\u{23f3} list_files\n", &listener, &mut streaming);
        assert_eq!(listener.events(), vec!["tool_start:list_files:"]);
    }

    #[test]
    fn test_dispatch_tool_success() {
        let listener = RecordingListener::new();
        let mut streaming = false;
        dispatch_delta("\u{2705} read_file (3s)\n", &listener, &mut streaming);
        assert_eq!(listener.events(), vec!["tool_result:read_file:true:3"]);
    }

    #[test]
    fn test_dispatch_tool_failure() {
        let listener = RecordingListener::new();
        let mut streaming = false;
        dispatch_delta(
            "\u{274c} execute_command (12s)\n",
            &listener,
            &mut streaming,
        );
        assert_eq!(
            listener.events(),
            vec!["tool_result:execute_command:false:12"]
        );
    }

    #[test]
    fn test_dispatch_got_tool_calls() {
        let listener = RecordingListener::new();
        let mut streaming = false;
        dispatch_delta("\u{1f4ac} Got 3 tool calls\n", &listener, &mut streaming);
        assert_eq!(listener.events(), vec!["progress:Got 3 tool calls"]);
    }

    #[test]
    fn test_dispatch_sentinel_switches_to_response() {
        let listener = RecordingListener::new();
        let mut streaming = false;
        dispatch_delta(DRAFT_CLEAR_SENTINEL, &listener, &mut streaming);
        assert!(streaming);
        assert!(listener.events().is_empty());
    }

    #[test]
    fn test_dispatch_response_chunks_after_sentinel() {
        let listener = RecordingListener::new();
        let mut streaming = false;

        dispatch_delta(DRAFT_CLEAR_SENTINEL, &listener, &mut streaming);
        assert!(streaming);

        dispatch_delta("Hello, ", &listener, &mut streaming);
        dispatch_delta("world!", &listener, &mut streaming);

        assert_eq!(
            listener.events(),
            vec!["response_chunk:Hello, ", "response_chunk:world!",]
        );
    }

    #[test]
    fn test_parse_tool_completion_with_seconds() {
        let (name, secs) = parse_tool_completion("read_file (3s)");
        assert_eq!(name, "read_file");
        assert_eq!(secs, 3);
    }

    #[test]
    fn test_parse_tool_completion_no_parens() {
        let (name, secs) = parse_tool_completion("list_files");
        assert_eq!(name, "list_files");
        assert_eq!(secs, 0);
    }

    #[test]
    fn test_truncate_tool_args_hint_sandbox_execute() {
        let hint = truncate_tool_args_hint("sandbox_execute", r#"{"command":"ls -la"}"#);
        assert_eq!(hint, "ls -la");
    }

    #[test]
    fn test_truncate_tool_args_hint_termux_run() {
        let hint = truncate_tool_args_hint("termux_run", r#"{"command":"pkg install git"}"#);
        assert_eq!(hint, "pkg install git");
    }

    #[test]
    fn test_truncate_tool_args_hint_shared_folder() {
        let hint = truncate_tool_args_hint("shared_folder_read", r#"{"path":"/sdcard/docs"}"#);
        assert_eq!(hint, "/sdcard/docs");
    }

    #[test]
    fn test_truncate_tool_args_hint_memory_store() {
        let hint = truncate_tool_args_hint("memory_store", r#"{"key":"user/prefs","content":"dark"}"#);
        assert_eq!(hint, "user/prefs");
    }

    #[test]
    fn test_truncate_tool_args_hint_web_search() {
        let hint = truncate_tool_args_hint("web_search_tool", r#"{"query":"rust async"}"#);
        assert_eq!(hint, "rust async");
    }

    #[test]
    fn test_truncate_tool_args_hint_web_fetch() {
        let hint = truncate_tool_args_hint("web_fetch", r#"{"url":"https://example.com"}"#);
        assert_eq!(hint, "https://example.com");
    }

    #[test]
    fn test_truncate_tool_args_hint_cron_runs() {
        let hint = truncate_tool_args_hint("cron_runs", r#"{"job_id":"abc-123"}"#);
        assert_eq!(hint, "abc-123");
    }

    #[test]
    fn test_truncate_tool_args_hint_cron_list() {
        let hint = truncate_tool_args_hint("cron_list", "{}");
        assert_eq!(hint, "Listing all cron jobs");
    }

    #[test]
    fn test_truncate_tool_args_hint_unknown_tool() {
        let hint = truncate_tool_args_hint("unknown", r#"{"query":"search term"}"#);
        assert_eq!(hint, "search term");
    }

    #[test]
    fn test_truncate_tool_args_hint_invalid_json() {
        let hint = truncate_tool_args_hint("sandbox_execute", "not json");
        assert!(hint.is_empty());
    }

    #[test]
    fn test_truncate_tool_args_hint_memory_recall_query() {
        let hint = truncate_tool_args_hint("memory_recall", r#"{"query":"rust async"}"#);
        assert_eq!(hint, "rust async");
    }

    #[test]
    fn test_truncate_tool_args_hint_memory_recall_since_until() {
        let hint = truncate_tool_args_hint(
            "memory_recall",
            r#"{"since":"2024-01-01","until":"2024-12-31"}"#,
        );
        assert_eq!(hint, "2024-01-01 to 2024-12-31");
    }

    #[test]
    fn test_truncate_tool_args_hint_memory_recall_since_only() {
        let hint = truncate_tool_args_hint("memory_recall", r#"{"since":"2024-06-01"}"#);
        assert_eq!(hint, "2024-06-01");
    }

    #[test]
    fn test_truncate_tool_args_hint_memory_recall_empty() {
        let hint = truncate_tool_args_hint("memory_recall", "{}");
        assert!(hint.is_empty());
    }

    #[test]
    fn test_truncate_tool_args_hint_cron_list_static() {
        let hint = truncate_tool_args_hint("cron_list", "{}");
        assert_eq!(hint, "Listing all cron jobs");
    }

    #[test]
    fn test_truncate_tool_args_hint_termux_get_capabilities_static() {
        let hint = truncate_tool_args_hint("termux_get_capabilities", "{}");
        assert_eq!(hint, "Checking termux capabilities");
    }

    #[test]
    fn test_stream_response_text_short() {
        let recording = Arc::new(RecordingListener::new());
        let listener: Arc<dyn FfiSessionListener> = recording.clone();
        let token = CancellationToken::new();

        let result = stream_response_text("Hello world", &listener, &token);
        assert!(result.is_ok());

        let events = recording.events();
        assert_eq!(events.len(), 1);
        assert_eq!(events[0], "response_chunk:Hello world");
    }

    #[test]
    fn test_stream_response_text_cancelled() {
        let recording = Arc::new(RecordingListener::new());
        let listener: Arc<dyn FfiSessionListener> = recording.clone();
        let token = CancellationToken::new();
        token.cancel();

        let result = stream_response_text("Hello world", &listener, &token);
        assert!(result.is_err());
    }
}
