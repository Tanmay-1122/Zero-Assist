use crate::agent::history_pruner::remove_orphaned_tool_messages;
use anyhow::Result;
use serde::{Deserialize, Serialize};
use std::path::Path;
use zeroclaw_providers::ChatMessage;

/// Default trigger for auto-compaction when non-system message count exceeds this threshold.
/// Prefer passing the config-driven value via `run_tool_call_loop`; this constant is only
/// used when callers omit the parameter.
pub const DEFAULT_MAX_HISTORY_MESSAGES: usize = 50;

/// Find the largest byte index `<= i` that is a valid char boundary.
/// MSRV-compatible replacement for `str::floor_char_boundary` (stable in 1.91).
pub fn floor_char_boundary(s: &str, i: usize) -> usize {
    if i >= s.len() {
        return s.len();
    }
    let mut pos = i;
    while pos > 0 && !s.is_char_boundary(pos) {
        pos -= 1;
    }
    pos
}

/// Truncate a tool result to `max_chars`, keeping head (2/3) + tail (1/3)
/// with a marker in the middle. Returns input unchanged if within limit or
/// `max_chars == 0` (disabled).
pub fn truncate_tool_result(output: &str, max_chars: usize) -> String {
    if max_chars == 0 || output.len() <= max_chars {
        return output.to_string();
    }
    let head_len = max_chars * 2 / 3;
    let tail_len = max_chars.saturating_sub(head_len);
    let head_end = floor_char_boundary(output, head_len);
    // ceil_char_boundary: find smallest byte index >= i on a char boundary
    let tail_start_raw = output.len().saturating_sub(tail_len);
    let tail_start = if tail_start_raw >= output.len() {
        output.len()
    } else {
        let mut pos = tail_start_raw;
        while pos < output.len() && !output.is_char_boundary(pos) {
            pos += 1;
        }
        pos
    };
    // Guard against overlap when max_chars is very small
    if head_end >= tail_start {
        return output[..floor_char_boundary(output, max_chars)].to_string();
    }
    let truncated_chars = tail_start - head_end;
    format!(
        "{}\n\n[... {} characters truncated ...]\n\n{}",
        &output[..head_end],
        truncated_chars,
        &output[tail_start..]
    )
}

/// Truncate a tool message's content, preserving JSON structure when the
/// message stores `tool_call_id` alongside `content` (native tool-call
/// format). Without this, `truncate_tool_result` destroys the JSON envelope
/// and downstream providers receive a `null` `call_id` (#5425).
pub fn truncate_tool_message(msg_content: &str, max_chars: usize) -> String {
    if max_chars == 0 || msg_content.len() <= max_chars {
        return msg_content.to_string();
    }
    if let Ok(mut obj) =
        serde_json::from_str::<serde_json::Map<String, serde_json::Value>>(msg_content)
        && obj.contains_key("tool_call_id")
        && let Some(serde_json::Value::String(inner)) = obj.get("content")
    {
        let truncated = truncate_tool_result(inner, max_chars);
        obj.insert("content".to_string(), serde_json::Value::String(truncated));
        return serde_json::to_string(&obj).unwrap_or_else(|_| msg_content.to_string());
    }
    truncate_tool_result(msg_content, max_chars)
}

/// Aggressively trim old tool result messages in history to recover from
/// context overflow. Keeps the last `protect_last_n` messages untouched.
/// Returns total characters saved.
pub fn fast_trim_tool_results(
    history: &mut [zeroclaw_providers::ChatMessage],
    protect_last_n: usize,
) -> usize {
    let trim_to = 2000;
    let mut saved = 0;
    let cutoff = history.len().saturating_sub(protect_last_n);
    for msg in &mut history[..cutoff] {
        if msg.role == "tool" && msg.content.len() > trim_to {
            let original_len = msg.content.len();
            msg.content = truncate_tool_message(&msg.content, trim_to);
            saved += original_len - msg.content.len();
        }
    }
    saved
}

/// Emergency: drop oldest non-system, non-recent messages from history.
/// Tool groups (assistant + consecutive tool messages) are dropped
/// atomically to preserve tool_use/tool_result pairing. See #4810.
/// Returns number of messages dropped.
pub fn emergency_history_trim(
    history: &mut Vec<zeroclaw_providers::ChatMessage>,
    keep_recent: usize,
) -> usize {
    let mut dropped = 0;
    let target_drop = history.len().saturating_sub(keep_recent) / 3;
    let mut i = 0;
    while dropped < target_drop && i < history.len().saturating_sub(keep_recent) {
        if history[i].role == "system" {
            i += 1;
        } else if history[i].role == "assistant" {
            // Count following tool messages — drop as atomic group
            let mut tool_count = 0;
            while i + 1 + tool_count < history.len().saturating_sub(keep_recent)
                && history[i + 1 + tool_count].role == "tool"
            {
                tool_count += 1;
            }
            for _ in 0..=tool_count {
                history.remove(i);
                dropped += 1;
            }
        } else {
            history.remove(i);
            dropped += 1;
        }
    }
    dropped += remove_orphaned_tool_messages(history);
    dropped
}

/// Estimate token count for a message history using an improved heuristic.
/// Uses ~4 chars/token for English text but accounts for framing tokens per message.
pub fn estimate_history_tokens(history: &[ChatMessage]) -> usize {
    history
        .iter()
        .map(|m| {
            let content_tokens = if m.content.is_empty() {
                0
            } else {
                // ~4 chars per token for general text, + role framing tokens
                std::cmp::max(1, m.content.len().div_ceil(4))
            };
            // Per-message overhead: role label, separators, metadata
            content_tokens + 4
        })
        .sum()
}

/// Trim conversation history to prevent unbounded growth.
/// Preserves the system prompt (first message if role=system) and the most recent messages.
pub fn trim_history(history: &mut Vec<ChatMessage>, max_history: usize) {
    // Nothing to trim if within limit
    let has_system = history.first().is_some_and(|m| m.role == "system");
    let non_system_count = if has_system {
        history.len() - 1
    } else {
        history.len()
    };

    if non_system_count <= max_history {
        return;
    }

    let start = if has_system { 1 } else { 0 };
    let to_remove = non_system_count - max_history;
    history.drain(start..start + to_remove);
    remove_orphaned_tool_messages(history);
}

/// Adaptive history trimming: reduces history to stay within a token budget.
/// Unlike trim_history (which trims by message count), this trims by estimated
/// token count, preserving the most relevant messages.
///
/// Strategy:
/// 1. Collapse tool+result pairs into summaries
/// 2. Truncate oversized tool results
/// 3. Drop oldest messages that exceed budget
pub fn adaptive_trim_history(
    history: &mut Vec<ChatMessage>,
    max_tokens: usize,
    keep_recent: usize,
) -> usize {
    if history.is_empty() || max_tokens == 0 {
        return 0;
    }

    let mut saved = 0usize;
    let mut cycles = 0;

    while estimate_history_tokens(history) > max_tokens && cycles < 10 {
        cycles += 1;

        // Phase 1: Collapse assistant+tool pairs
        let mut i = 0;
        while i < history.len() {
            if history[i].role == "assistant" {
                let mut tool_count = 0;
                while i + 1 + tool_count < history.len()
                    && history[i + 1 + tool_count].role == "tool"
                {
                    tool_count += 1;
                }
                if tool_count > 0 {
                    let summary = format!("[Tool exchange: {tool_count} call(s) — results collapsed]");
                    history[i].content = summary;
                    for _ in 0..tool_count {
                        history.remove(i + 1);
                    }
                    saved += tool_count;
                    continue;
                }
            }
            i += 1;
        }

        if estimate_history_tokens(history) <= max_tokens {
            break;
        }

        // Phase 2: Truncate tool results
        let trim_to = 2000;
        let protect_until = history.len().saturating_sub(keep_recent);
        for msg in history[..protect_until].iter_mut() {
            if msg.role == "tool" && msg.content.len() > trim_to {
                let original_len = msg.content.len();
                let head_len = trim_to * 2 / 3;
                let tail_len = trim_to.saturating_sub(head_len);
                let tail_start = msg.content.len().saturating_sub(tail_len);
                if head_len < tail_start {
                    msg.content = format!(
                        "{}\n\n[... {} chars truncated ...]\n\n{}",
                        &msg.content[..head_len],
                        tail_start - head_len,
                        &msg.content[tail_start..]
                    );
                    saved += original_len - msg.content.len();
                }
            }
        }

        if estimate_history_tokens(history) <= max_tokens {
            break;
        }

        // Phase 3: Drop oldest unprotected messages
        let protected = |idx: usize| -> bool {
            if history[idx].role == "system" {
                return true;
            }
            idx >= history.len().saturating_sub(keep_recent)
        };

        // Drop oldest assistant+tool groups (maintaining pairing)
        for i in 0..history.len().saturating_sub(keep_recent) {
            if protected(i) {
                continue;
            }
            if history[i].role == "assistant" {
                let mut tool_count = 0;
                while i + 1 + tool_count < history.len()
                    && history[i + 1 + tool_count].role == "tool"
                {
                    tool_count += 1;
                }
                for _ in 0..=tool_count {
                    if i < history.len() {
                        history.remove(i);
                        saved += 1;
                    }
                }
                break;
            } else if history[i].role != "system" {
                history.remove(i);
                saved += 1;
                break;
            }
        }
    }

    saved
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct InteractiveSessionState {
    pub version: u32,
    pub history: Vec<ChatMessage>,
}

impl InteractiveSessionState {
    fn from_history(history: &[ChatMessage]) -> Self {
        Self {
            version: 1,
            history: history.to_vec(),
        }
    }
}

pub fn load_interactive_session_history(
    path: &Path,
    system_prompt: &str,
) -> Result<Vec<ChatMessage>> {
    if !path.exists() {
        return Ok(vec![ChatMessage::system(system_prompt)]);
    }

    let raw = std::fs::read_to_string(path)?;
    let mut state: InteractiveSessionState = serde_json::from_str(&raw)?;
    if state.history.is_empty() {
        state.history.push(ChatMessage::system(system_prompt));
    } else if state.history.first().map(|msg| msg.role.as_str()) != Some("system") {
        state.history.insert(0, ChatMessage::system(system_prompt));
    }

    // Self-heal persisted sessions that were written with orphaned
    // tool_result messages (e.g. a crash mid-compaction, or a trim that
    // dropped the assistant tool_use block but left its tool_result).
    // Without this the next API call fails with 400 "unexpected tool_use_id
    // found in tool_result blocks" and the session stays bricked until the
    // file is deleted. See #5813.
    remove_orphaned_tool_messages(&mut state.history);

    Ok(state.history)
}

pub fn save_interactive_session_history(path: &Path, history: &[ChatMessage]) -> Result<()> {
    if let Some(parent) = path.parent() {
        std::fs::create_dir_all(parent)?;
    }

    let payload = serde_json::to_string_pretty(&InteractiveSessionState::from_history(history))?;
    std::fs::write(path, payload)?;
    Ok(())
}
