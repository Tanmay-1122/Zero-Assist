/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! FFI functions for automatic task delegation with intelligent agent matching.
//!
//! Automatically classifies incoming tasks, matches them to suitable agents,
//! and delegates without requiring user approval.

use crate::error::FfiError;
use crate::task_classifier_ffi::{AgentProfile, classify_task, find_best_agent};
use serde_json::json;

/// Automatically classify a task and find the best agent to handle it.
///
/// # Arguments
/// * `task_description` - User's task description
/// * `available_agents_json` - JSON array of available agents with specializations
///
/// # Returns
/// JSON with:
/// - `task_type`: Classification result (Research, Analysis, Development, etc.)
/// - `confidence`: Float 0-1 indicating classification confidence
/// - `best_agent`: Name of recommended agent
/// - `keywords`: Keywords that triggered the classification
#[uniffi::export]
pub fn auto_classify_and_match_agent(
    task_description: String,
    available_agents_json: String,
) -> Result<String, FfiError> {
    // Classify the task
    let classification = classify_task(&task_description);

    // Parse available agents from JSON
    let agents: Vec<AgentProfile> =
        serde_json::from_str(&available_agents_json).map_err(|e| FfiError::InvalidArgument {
            detail: format!("Invalid agents JSON: {e}"),
        })?;

    // Find best matching agent
    let best_agent = find_best_agent(&classification, &agents).ok_or(FfiError::StateError {
        detail: "No suitable agents available".to_string(),
    })?;

    // Return result
    let result = json!({
        "task_type": format!("{:?}", classification.task_type),
        "confidence": classification.confidence,
        "best_agent": best_agent,
        "keywords": classification.keywords,
    });

    Ok(result.to_string())
}

/// Directly delegate a task to a specific agent without approval.
///
/// # Arguments
/// * `agent_name` - Target agent name
/// * `task_description` - Task to delegate
///
/// # Returns
/// Delegation result with status
#[uniffi::export]
#[allow(clippy::unnecessary_wraps)]
pub fn auto_delegate_task(
    agent_name: String,
    task_description: String,
) -> Result<String, FfiError> {
    // Directly delegate without approval
    let message = format!("@{agent_name} {task_description}");

    Ok(json!({
        "status": "delegated",
        "agent": agent_name,
        "message": message,
        "requires_approval": false,
    })
    .to_string())
}
