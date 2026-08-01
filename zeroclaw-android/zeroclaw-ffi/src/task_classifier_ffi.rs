/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! Task classification for intelligent agent delegation - FFI layer.
//!
//! Duplicated from zeroclaw tools layer to be self-contained in FFI.
//! Automatically classifies incoming tasks based on keywords and content.

use serde::{Deserialize, Serialize};
use std::collections::HashMap;

/// Task classification types based on domain expertise.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
pub enum TaskType {
    /// Research, investigation, information gathering
    Research,
    /// Analysis, data processing, insights
    Analysis,
    /// Software development, coding, debugging
    Development,
    /// Writing, documentation, content creation
    Writing,
    /// Infrastructure, operations, deployment
    Infrastructure,
    /// Orchestration, planning, coordination
    Orchestration,
    /// General tasks that don't fit other categories
    General,
}

/// Agent profile for matching with task types.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AgentProfile {
    pub name: String,
    pub role: String,
    pub specializations: String,
}

/// Result of task classification.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TaskClassification {
    pub task_type: TaskType,
    pub confidence: f32,
    pub keywords: Vec<String>,
}

/// Classifies a task description and returns the task type and confidence.
#[allow(clippy::cast_precision_loss, clippy::too_many_lines)]
pub fn classify_task(task_text: &str) -> TaskClassification {
    let text_lower = task_text.to_lowercase();

    let mut scores: HashMap<TaskType, usize> = HashMap::new();
    let mut found_keywords = Vec::new();

    // Research keywords
    let research_keywords = vec![
        "research",
        "investigate",
        "find",
        "search",
        "discover",
        "study",
        "learn",
        "information",
        "data gathering",
        "explore",
        "background",
        "reference",
        "example",
        "documentation",
        "api",
        "guide",
    ];

    // Analysis keywords
    let analysis_keywords = vec![
        "analyze",
        "analysis",
        "analyze",
        "interpret",
        "pattern",
        "trend",
        "insight",
        "summary",
        "overview",
        "metrics",
        "data",
        "statistics",
        "performance",
        "benchmark",
        "compare",
    ];

    // Development keywords
    let development_keywords = vec![
        "code",
        "develop",
        "implement",
        "fix",
        "debug",
        "build",
        "create",
        "write",
        "function",
        "method",
        "class",
        "test",
        "refactor",
        "optimize",
        "bug",
        "error",
        "feature",
        "enhancement",
    ];

    // Writing keywords
    let writing_keywords = vec![
        "write",
        "document",
        "content",
        "article",
        "blog",
        "post",
        "email",
        "letter",
        "draft",
        "compose",
        "edit",
        "review",
        "copyedit",
        "revise",
        "narrative",
        "description",
        "explanation",
        "guide",
        "tutorial",
    ];

    // Infrastructure keywords
    let infrastructure_keywords = vec![
        "deploy",
        "infrastructure",
        "devops",
        "ci/cd",
        "pipeline",
        "server",
        "container",
        "docker",
        "kubernetes",
        "cloud",
        "aws",
        "gcp",
        "azure",
        "setup",
        "configure",
        "install",
        "maintain",
        "operations",
        "monitor",
    ];

    // Orchestration keywords
    let orchestration_keywords = vec![
        "plan",
        "orchestrate",
        "coordinate",
        "organize",
        "schedule",
        "delegate",
        "assign",
        "manage",
        "workflow",
        "pipeline",
        "sequence",
        "order",
        "prioritize",
        "strategy",
        "approach",
        "method",
    ];

    // Score each category
    for keyword in &research_keywords {
        if text_lower.contains(keyword) {
            *scores.entry(TaskType::Research).or_insert(0) += 1;
            found_keywords.push(keyword.to_string());
        }
    }

    for keyword in &analysis_keywords {
        if text_lower.contains(keyword) {
            *scores.entry(TaskType::Analysis).or_insert(0) += 1;
            found_keywords.push(keyword.to_string());
        }
    }

    for keyword in &development_keywords {
        if text_lower.contains(keyword) {
            *scores.entry(TaskType::Development).or_insert(0) += 1;
            found_keywords.push(keyword.to_string());
        }
    }

    for keyword in &writing_keywords {
        if text_lower.contains(keyword) {
            *scores.entry(TaskType::Writing).or_insert(0) += 1;
            found_keywords.push(keyword.to_string());
        }
    }

    for keyword in &infrastructure_keywords {
        if text_lower.contains(keyword) {
            *scores.entry(TaskType::Infrastructure).or_insert(0) += 1;
            found_keywords.push(keyword.to_string());
        }
    }

    for keyword in &orchestration_keywords {
        if text_lower.contains(keyword) {
            *scores.entry(TaskType::Orchestration).or_insert(0) += 1;
            found_keywords.push(keyword.to_string());
        }
    }

    // Find the highest-scoring category
    let (task_type, max_score) = scores
        .iter()
        .max_by_key(|(_, count)| *count)
        .map_or((TaskType::General, 0), |(&key, &value)| (key, value));

    // Calculate confidence (max possible is around 5 keywords per category)
    let confidence = (max_score as f32 / 5.0).min(1.0);

    // Remove duplicates from keywords
    found_keywords.sort();
    found_keywords.dedup();

    TaskClassification {
        task_type,
        confidence,
        keywords: found_keywords,
    }
}

/// Finds the best agent from a list to handle the classified task.
#[allow(
    clippy::cast_possible_truncation,
    clippy::cast_precision_loss,
    clippy::cast_sign_loss
)]
pub fn find_best_agent(
    classification: &TaskClassification,
    agents: &[AgentProfile],
) -> Option<String> {
    if agents.is_empty() {
        return None;
    }

    let mut best_agent = None;
    let mut best_score = 0;

    for agent in agents {
        let role_lower = agent.role.to_lowercase();
        let spec_lower = agent.specializations.to_lowercase();
        let combined = format!("{role_lower} {spec_lower}");

        let mut score = 0;

        // Match based on task type and agent role
        match classification.task_type {
            TaskType::Research => {
                if combined.contains("researcher") || combined.contains("research") {
                    score += 10;
                }
            }
            TaskType::Analysis => {
                if combined.contains("analyst") || combined.contains("analysis") {
                    score += 10;
                }
            }
            TaskType::Development => {
                if combined.contains("coder")
                    || combined.contains("development")
                    || combined.contains("coding")
                    || combined.contains("developer")
                {
                    score += 10;
                }
            }
            TaskType::Writing => {
                if combined.contains("writer") || combined.contains("writing") {
                    score += 10;
                }
            }
            TaskType::Infrastructure => {
                if combined.contains("executor")
                    || combined.contains("infrastructure")
                    || combined.contains("devops")
                    || combined.contains("operations")
                {
                    score += 10;
                }
            }
            TaskType::Orchestration => {
                if combined.contains("planner")
                    || combined.contains("orchestration")
                    || combined.contains("planning")
                    || combined.contains("master")
                {
                    score += 10;
                }
            }
            TaskType::General => {
                // Any agent can handle general tasks, fallback to first available
                if best_agent.is_none() {
                    best_agent = Some(agent.name.clone());
                }
                score += 1;
            }
        }

        // Bonus for confidence
        if score > 0 {
            score = (score as f32 * classification.confidence) as usize;
        }

        if score > best_score {
            best_score = score;
            best_agent = Some(agent.name.clone());
        }
    }

    best_agent.or_else(|| agents.first().map(|a| a.name.clone()))
}
