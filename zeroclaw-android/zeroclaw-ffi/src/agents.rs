/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! Agent and delegation management for the Android FFI layer.
//!
//! Exposes agent listing, status querying, and task delegation functionality
//! to the terminal REPL and group chat interfaces.

use crate::error::FfiError;
use crate::runtime;

/// List all available delegate agents configured in the daemon.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running.
pub(crate) fn list_agents_inner() -> Result<String, FfiError> {
    runtime::with_daemon_config(|config| {
        let agents: Vec<serde_json::Value> = config
            .agents
            .iter()
            .map(|(name, agent_config)| {
                let description = agent_config.system_prompt.as_ref().map_or_else(
                    || format!("{name} agent"),
                    |p| p.chars().take(100).collect::<String>(),
                );

                serde_json::json!({
                    "name": name,
                    "provider": agent_config.provider,
                    "model": agent_config.model,
                    "description": description,
                    "agentic": agent_config.agentic,
                    "temperature": agent_config.temperature,
                    "max_iterations": agent_config.max_iterations,
                })
            })
            .collect();

        serde_json::to_string(&agents).map_err(|e| FfiError::SpawnError {
            detail: format!("Failed to serialize agents: {e}"),
        })
    })?
}

/// Get details of a specific agent by name.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running or
/// agent not found.
pub(crate) fn get_agent_inner(agent_name: String) -> Result<String, FfiError> {
    runtime::with_daemon_config(|config| {
        let agent = config
            .agents
            .get(&agent_name)
            .ok_or_else(|| FfiError::StateError {
                detail: format!("Agent '{agent_name}' not found"),
            })?;

        let details = serde_json::json!({
            "name": agent_name,
            "provider": agent.provider,
            "model": agent.model,
            "system_prompt": agent.system_prompt.as_deref().unwrap_or(""),
            "agentic": agent.agentic,
            "temperature": agent.temperature,
            "max_depth": agent.max_depth,
            "max_iterations": agent.max_iterations,
            "allowed_tools": agent.allowed_tools,
        });

        serde_json::to_string(&details).map_err(|e| FfiError::SpawnError {
            detail: format!("Failed to serialize agent details: {e}"),
        })
    })?
}

/// Delegate a task to a specific agent.
///
/// This sends the task through the daemon's delegation system to be
/// processed by the specified sub-agent.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if the daemon is not running,
/// agent not found, or delegation fails.
pub(crate) fn delegate_task_inner(agent_name: String, task: String) -> Result<String, FfiError> {
    let _ = runtime::with_daemon_config(|config| {
        let _ = config
            .agents
            .get(&agent_name)
            .ok_or_else(|| FfiError::StateError {
                detail: format!("Agent '{agent_name}' not found"),
            })?;
        Ok::<(), FfiError>(())
    })?;

    // Send the delegation request to the agent
    // This will be processed asynchronously by the daemon
    let message = format!("@{agent_name} {task}");
    let result = crate::runtime::send_message_inner(message)?;

    Ok(result)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_list_agents_not_running() {
        let result = list_agents_inner();
        assert!(result.is_err());
    }

    #[test]
    fn test_get_agent_not_running() {
        let result = get_agent_inner("test-agent".into());
        assert!(result.is_err());
    }

    #[test]
    fn test_delegate_task_not_running() {
        let result = delegate_task_inner("test-agent".into(), "test task".into());
        assert!(result.is_err());
    }
}
