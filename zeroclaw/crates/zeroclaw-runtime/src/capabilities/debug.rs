use std::time::Duration;

/// Record of a capability execution decision.
///
/// Logged every time the runtime resolves a capability and executes an action.
/// Used for debugging, audit, and the Developer UI planner inspector.
#[derive(Debug, Clone)]
pub struct ExecutionDecision {
    pub timestamp: chrono::DateTime<chrono::Utc>,
    pub user_intent: String,
    pub capability: String,
    pub provider: String,
    pub action: String,
    pub internal_tool: String,
    pub reason: String,
    pub duration: Duration,
    pub success: bool,
    pub fallback_chain: Vec<String>,
}

impl ExecutionDecision {
    pub fn new(
        capability: &str,
        provider: &str,
        action: &str,
        internal_tool: &str,
        reason: &str,
    ) -> Self {
        Self {
            timestamp: chrono::Utc::now(),
            user_intent: String::new(),
            capability: capability.to_string(),
            provider: provider.to_string(),
            action: action.to_string(),
            internal_tool: internal_tool.to_string(),
            reason: reason.to_string(),
            duration: Duration::ZERO,
            success: true,
            fallback_chain: Vec::new(),
        }
    }
}

/// Bounded ring buffer of execution decisions.
///
/// Phase 1: Stub. Full implementation (ring buffer + export + CLI) comes in Phase 10.
pub struct DecisionLogger {
    decisions: Vec<ExecutionDecision>,
    max_entries: usize,
}

impl DecisionLogger {
    pub fn new(max_entries: usize) -> Self {
        Self {
            decisions: Vec::with_capacity(max_entries.min(64)),
            max_entries,
        }
    }

    pub fn log(&mut self, decision: ExecutionDecision) {
        if self.decisions.len() >= self.max_entries {
            self.decisions.remove(0);
        }
        self.decisions.push(decision);
    }

    pub fn recent(&self, n: usize) -> &[ExecutionDecision] {
        let start = self.decisions.len().saturating_sub(n);
        &self.decisions[start..]
    }

    pub fn export_to_json(&self) -> serde_json::Value {
        use serde_json::json;
        let entries: Vec<serde_json::Value> = self
            .decisions
            .iter()
            .map(|d| {
                json!({
                    "timestamp": d.timestamp.to_rfc3339(),
                    "capability": d.capability,
                    "provider": d.provider,
                    "action": d.action,
                    "internal_tool": d.internal_tool,
                    "reason": d.reason,
                    "duration_ms": d.duration.as_millis(),
                    "success": d.success,
                    "fallback_chain": d.fallback_chain,
                })
            })
            .collect();
        json!({ "log": entries, "count": entries.len() })
    }
}
