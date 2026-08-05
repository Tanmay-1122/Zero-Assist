/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! Memory tool wrappers used by Android sessions.
//!
//! Upstream `SecurityPolicy` is `pub(crate)`, so the upstream
//! `MemoryStoreTool` and `MemoryForgetTool` cannot be constructed from the FFI
//! crate. These wrappers replicate the upstream behavior without that security
//! check. On Android, the user directly initiates all agent actions, so the
//! upstream read-only and rate-limit checks are unnecessary here.

use std::sync::Arc;

use async_trait::async_trait;
use zeroclaw::memory::{Memory, MemoryCategory};
use zeroclaw::tools::{Tool, ToolResult};

/// FFI-specific memory store tool that bypasses `SecurityPolicy`.
///
/// On Android the user directly initiates all agent actions, so the upstream
/// read-only / rate-limit checks are unnecessary. The tool delegates directly
/// to the [`Memory`] backend.
pub(super) struct FfiMemoryStoreTool {
    /// The memory backend shared with the daemon.
    memory: Arc<dyn Memory>,
}

impl FfiMemoryStoreTool {
    pub(super) fn new(memory: Arc<dyn Memory>) -> Self {
        Self { memory }
    }
}

#[async_trait]
impl Tool for FfiMemoryStoreTool {
    fn name(&self) -> &'static str {
        "memory_store"
    }

    fn description(&self) -> &'static str {
        "Store a fact, preference, or note in long-term memory. \
         Use category 'core' for permanent facts, 'daily' for session notes, \
         'conversation' for chat context, or a custom category name."
    }

    fn parameters_schema(&self) -> serde_json::Value {
        serde_json::json!({
            "type": "object",
            "properties": {
                "key": {
                    "type": "string",
                    "description": "Unique key for this memory (e.g. 'user_lang', 'project_stack')"
                },
                "content": {
                    "type": "string",
                    "description": "The information to remember"
                },
                "category": {
                    "type": "string",
                    "description": "Memory category: 'core' (permanent), 'daily' (session), \
                                    'conversation' (chat), or a custom category name. \
                                    Defaults to 'core'."
                }
            },
            "required": ["key", "content"]
        })
    }

    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let key = args
            .get("key")
            .and_then(|v| v.as_str())
            .ok_or_else(|| anyhow::anyhow!("Missing 'key' parameter"))?;

        let content = args
            .get("content")
            .and_then(|v| v.as_str())
            .ok_or_else(|| anyhow::anyhow!("Missing 'content' parameter"))?;

        let category = match args.get("category").and_then(|v| v.as_str()) {
            Some("core") | None => MemoryCategory::Core,
            Some("daily") => MemoryCategory::Daily,
            Some("conversation") => MemoryCategory::Conversation,
            Some(other) => MemoryCategory::Custom(other.to_string()),
        };

        match self.memory.store(key, content, category, None).await {
            Ok(()) => Ok(ToolResult {
                success: true,
                output: format!("Stored memory: {key}"),
                error: None,
            blocks: Vec::new(),
            metadata: None,
            }),
            Err(e) => Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some(format!("Failed to store memory: {e}")),
            blocks: Vec::new(),
            metadata: None,
            }),
        }
    }
}

/// FFI-specific memory forget tool that bypasses `SecurityPolicy`.
///
/// See the module-level rationale for why Android uses an FFI-local wrapper.
pub(super) struct FfiMemoryForgetTool {
    /// The memory backend shared with the daemon.
    memory: Arc<dyn Memory>,
}

impl FfiMemoryForgetTool {
    pub(super) fn new(memory: Arc<dyn Memory>) -> Self {
        Self { memory }
    }
}

#[async_trait]
impl Tool for FfiMemoryForgetTool {
    fn name(&self) -> &'static str {
        "memory_forget"
    }

    fn description(&self) -> &'static str {
        "Remove a memory by key. Use to delete outdated facts or sensitive \
         data. Returns whether the memory was found and removed."
    }

    fn parameters_schema(&self) -> serde_json::Value {
        serde_json::json!({
            "type": "object",
            "properties": {
                "key": {
                    "type": "string",
                    "description": "The key of the memory to forget"
                }
            },
            "required": ["key"]
        })
    }

    async fn execute(&self, args: serde_json::Value) -> anyhow::Result<ToolResult> {
        let key = args
            .get("key")
            .and_then(|v| v.as_str())
            .ok_or_else(|| anyhow::anyhow!("Missing 'key' parameter"))?;

        match self.memory.forget(key).await {
            Ok(true) => Ok(ToolResult {
                success: true,
                output: format!("Forgot memory: {key}"),
                error: None,
            blocks: Vec::new(),
            metadata: None,
            }),
            Ok(false) => Ok(ToolResult {
                success: true,
                output: format!("No memory found with key: {key}"),
                error: None,
            blocks: Vec::new(),
            metadata: None,
            }),
            Err(e) => Ok(ToolResult {
                success: false,
                output: String::new(),
                error: Some(format!("Failed to forget memory: {e}")),
            blocks: Vec::new(),
            metadata: None,
            }),
        }
    }
}