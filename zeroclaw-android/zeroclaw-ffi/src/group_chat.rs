/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! Group chat session management for multi-agent coordination.
//!
//! Manages the lifecycle of group chat sessions in the terminal,
//! including adding/removing agents, broadcasting messages, and
//! coordinating responses.

use crate::error::FfiError;
use std::sync::Mutex;

/// A group chat session in the terminal.
///
/// Maintains the list of active agents and session metadata.
#[derive(Clone, Debug)]
pub(crate) struct GroupChatSession {
    pub id: String,
    pub agents: Vec<String>,
    pub created_at_ms: u64,
    pub is_active: bool,
    pub message_count: u32,
}

/// Global group chat session state (protected by mutex).
static GROUP_CHAT_SESSION: Mutex<Option<GroupChatSession>> = Mutex::new(None);

fn current_time_ms() -> u64 {
    let millis = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_millis();
    u64::try_from(millis).unwrap_or(u64::MAX)
}

/// Locks the group chat session, recovering from poison.
fn lock_session() -> std::sync::MutexGuard<'static, Option<GroupChatSession>> {
    GROUP_CHAT_SESSION.lock().unwrap_or_else(|e| {
        tracing::warn!("Group chat session mutex was poisoned; recovering: {e}");
        e.into_inner()
    })
}

/// Start a new group chat session, optionally with initial agents.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if a session is already active.
pub(crate) fn start_group_chat_inner(agent_names: Option<Vec<String>>) -> Result<String, FfiError> {
    let mut session_guard = lock_session();

    if session_guard.is_some() {
        return Err(FfiError::StateError {
            detail: "A group chat session is already active. Stop it first.".into(),
        });
    }

    let session_id = uuid::Uuid::new_v4().to_string();
    let agents = agent_names.unwrap_or_default();
    let created_at_ms = current_time_ms();

    let session = GroupChatSession {
        id: session_id.clone(),
        agents: agents.clone(),
        created_at_ms,
        is_active: true,
        message_count: 0,
    };

    *session_guard = Some(session.clone());

    let result = serde_json::json!({
        "session_id": session_id,
        "agents": agents,
        "status": "started",
        "created_at_ms": created_at_ms,
    });

    serde_json::to_string(&result).map_err(|e| FfiError::SpawnError {
        detail: format!("Failed to serialize group chat session: {e}"),
    })
}

/// Stop the active group chat session.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no session is active.
pub(crate) fn stop_group_chat_inner() -> Result<String, FfiError> {
    let mut session_guard = lock_session();

    let session = session_guard.take().ok_or_else(|| FfiError::StateError {
        detail: "No active group chat session".into(),
    })?;

    let result = serde_json::json!({
        "session_id": session.id,
        "status": "stopped",
        "message_count": session.message_count,
        "agents": session.agents,
    });

    serde_json::to_string(&result).map_err(|e| FfiError::SpawnError {
        detail: format!("Failed to serialize group chat stop: {e}"),
    })
}

/// Get the status of the active group chat session.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no session is active.
pub(crate) fn get_group_chat_status_inner() -> Result<String, FfiError> {
    let session_guard = lock_session();

    let session = session_guard.as_ref().ok_or_else(|| FfiError::StateError {
        detail: "No active group chat session".into(),
    })?;

    let result = serde_json::json!({
        "session_id": session.id,
        "agents": session.agents,
        "agent_count": session.agents.len(),
        "is_active": session.is_active,
        "message_count": session.message_count,
        "created_at_ms": session.created_at_ms,
        "uptime_ms": current_time_ms().saturating_sub(session.created_at_ms),
    });

    serde_json::to_string(&result).map_err(|e| FfiError::SpawnError {
        detail: format!("Failed to serialize group chat status: {e}"),
    })
}

/// Add an agent to the active group chat session.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no session is active or
/// agent is already in the group.
pub(crate) fn add_agent_to_group_inner(agent_name: String) -> Result<String, FfiError> {
    let mut session_guard = lock_session();

    let session = session_guard.as_mut().ok_or_else(|| FfiError::StateError {
        detail: "No active group chat session".into(),
    })?;

    if session.agents.contains(&agent_name) {
        return Err(FfiError::StateError {
            detail: format!("Agent '{agent_name}' is already in the group"),
        });
    }

    session.agents.push(agent_name.clone());

    let result = serde_json::json!({
        "agent": agent_name,
        "agents": session.agents.clone(),
        "agent_count": session.agents.len(),
        "status": "added",
    });

    serde_json::to_string(&result).map_err(|e| FfiError::SpawnError {
        detail: format!("Failed to serialize group agent add: {e}"),
    })
}

/// Remove an agent from the active group chat session.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no session is active or
/// agent is not in the group.
pub(crate) fn remove_agent_from_group_inner(agent_name: String) -> Result<String, FfiError> {
    let mut session_guard = lock_session();

    let session = session_guard.as_mut().ok_or_else(|| FfiError::StateError {
        detail: "No active group chat session".into(),
    })?;

    if !session.agents.contains(&agent_name) {
        return Err(FfiError::StateError {
            detail: format!("Agent '{agent_name}' is not in the group"),
        });
    }

    session.agents.retain(|a| a != &agent_name);

    let result = serde_json::json!({
        "agent": agent_name,
        "agents": session.agents.clone(),
        "agent_count": session.agents.len(),
        "status": "removed",
    });

    serde_json::to_string(&result).map_err(|e| FfiError::SpawnError {
        detail: format!("Failed to serialize group agent remove: {e}"),
    })
}

/// Send a message to all agents in the active group chat session.
///
/// # Errors
///
/// Returns [`FfiError::StateError`] if no session is active or
/// message sending fails.
pub(crate) fn send_group_message_inner(message: String) -> Result<String, FfiError> {
    let mut session_guard = lock_session();

    let session = session_guard.as_mut().ok_or_else(|| FfiError::StateError {
        detail: "No active group chat session".into(),
    })?;

    if session.agents.is_empty() {
        return Err(FfiError::StateError {
            detail: "Cannot send message: no agents in the group".into(),
        });
    }

    // Broadcast message to all agents in the group
    let agent_mentions = session
        .agents
        .iter()
        .map(|agent| format!("@{agent}"))
        .collect::<Vec<_>>()
        .join(" ");

    let broadcast_message = if agent_mentions.is_empty() {
        message.clone()
    } else {
        format!("{agent_mentions} {message}")
    };

    session.message_count += 1;
    let message_count = session.message_count;

    // Send the message through the daemon
    crate::runtime::send_message_inner(broadcast_message)?;

    let result = serde_json::json!({
        "status": "sent",
        "message_count": message_count,
        "agents_notified": session.agents.len(),
        "agents": session.agents.clone(),
    });

    serde_json::to_string(&result).map_err(|e| FfiError::SpawnError {
        detail: format!("Failed to serialize group message send: {e}"),
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    static TEST_SESSION_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

    fn lock_test_session() -> std::sync::MutexGuard<'static, ()> {
        TEST_SESSION_LOCK
            .lock()
            .unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    fn cleanup_session() {
        let mut session_guard = lock_session();
        *session_guard = None;
    }

    #[test]
    fn test_start_group_chat() {
        let _guard = lock_test_session();
        cleanup_session();
        let result = start_group_chat_inner(None);
        assert!(result.is_ok());
        let session = lock_session();
        assert!(session.is_some());
        drop(session);
        cleanup_session();
    }

    #[test]
    fn test_start_group_chat_with_agents() {
        let _guard = lock_test_session();
        cleanup_session();

        let agents = vec!["agent1".into(), "agent2".into()];
        let result = start_group_chat_inner(Some(agents.clone()));
        assert!(result.is_ok());

        let session_guard = lock_session();
        assert!(session_guard.is_some());
        if let Some(session) = session_guard.as_ref() {
            assert_eq!(session.agents, agents);
        }
        drop(session_guard);
        cleanup_session();
    }

    #[test]
    fn test_cannot_start_duplicate_session() {
        let _guard = lock_test_session();
        cleanup_session();
        let result = start_group_chat_inner(None);
        assert!(result.is_ok());

        let result2 = start_group_chat_inner(None);
        assert!(result2.is_err());
        match result2.expect_err("duplicate group chat should fail") {
            FfiError::StateError { detail } => {
                assert!(detail.contains("already active"));
            }
            _ => panic!("expected StateError"),
        }
        // Clean up
        cleanup_session();
    }

    #[test]
    fn test_stop_group_chat() {
        let _guard = lock_test_session();
        cleanup_session();

        let _ = start_group_chat_inner(None);
        let result = stop_group_chat_inner();
        assert!(result.is_ok());

        let session_guard = lock_session();
        assert!(session_guard.is_none());
        drop(session_guard);
        cleanup_session();
    }

    #[test]
    fn test_get_status_no_session() {
        let _guard = lock_test_session();
        let mut session_guard = lock_session();
        *session_guard = None;
        drop(session_guard);

        let result = get_group_chat_status_inner();
        assert!(result.is_err());
    }

    #[test]
    fn test_add_agent_to_group() {
        let _guard = lock_test_session();
        let mut session_guard = lock_session();
        *session_guard = None;
        drop(session_guard);

        let _ = start_group_chat_inner(Some(vec!["agent1".into()]));
        let result = add_agent_to_group_inner("agent2".into());
        assert!(result.is_ok());

        let session_guard = lock_session();
        if let Some(session) = session_guard.as_ref() {
            assert_eq!(session.agents.len(), 2);
            assert!(session.agents.contains(&"agent2".to_string()));
        }
        drop(session_guard);
        let _ = stop_group_chat_inner();
    }

    #[test]
    fn test_remove_agent_from_group() {
        let _guard = lock_test_session();
        let mut session_guard = lock_session();
        *session_guard = None;
        drop(session_guard);

        let agents = vec!["agent1".into(), "agent2".into()];
        let _ = start_group_chat_inner(Some(agents));
        let result = remove_agent_from_group_inner("agent1".into());
        assert!(result.is_ok());

        let session_guard = lock_session();
        if let Some(session) = session_guard.as_ref() {
            assert_eq!(session.agents.len(), 1);
            assert!(session.agents.contains(&"agent2".to_string()));
        }
        drop(session_guard);
        let _ = stop_group_chat_inner();
    }
}
