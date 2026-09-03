/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! FFI functions for skill and plugin generation.
//!
//! Exposes the skill/plugin generator to the Android app via UniFFI.

use crate::error::FfiError;
use crate::skill_generator::{generate_plugin, generate_skill, install_plugin, install_skill};
use std::path::PathBuf;

/// Generate a skill from a natural language description.
///
/// Creates a skill manifest SKILL.md based on the provided description.
/// The skill is then installed to the workspace.
///
/// # Arguments
/// * `description` - Natural language description of the skill's purpose
/// * `author` - Optional author name
///
/// # Returns
/// JSON containing the generated skill manifest as a string
#[uniffi::export]
pub fn generate_and_install_skill(
    description: String,
    author: Option<String>,
) -> Result<String, FfiError> {
    let workspace_dir = get_workspace_dir()?;

    let skill =
        generate_skill(&description, author.as_deref()).map_err(|e| FfiError::GenerationError {
            detail: e.to_string(),
        })?;

    install_skill(&skill, &workspace_dir).map_err(|e| FfiError::GenerationError {
        detail: format!("Failed to install skill: {e}"),
    })?;

    serde_json::to_string(&serde_json::json!({
        "name": skill.name,
        "description": skill.description,
        "version": skill.version,
        "author": skill.author,
        "manifest": skill.content,
    }))
    .map_err(|e| FfiError::GenerationError {
        detail: format!("Serialization failed: {e}"),
    })
}

/// Generate a plugin from a natural language description.
///
/// Creates a plugin manifest from the provided description.
/// The plugin is then installed to the workspace.
///
/// # Arguments
/// * `description` - Natural language description of the plugin's purpose
/// * `author` - Optional author name
///
/// # Returns
/// JSON containing the generated plugin manifest
#[uniffi::export]
pub fn generate_and_install_plugin(
    description: String,
    author: Option<String>,
) -> Result<String, FfiError> {
    let workspace_dir = get_workspace_dir()?;

    let plugin = generate_plugin(&description, author.as_deref()).map_err(|e| {
        FfiError::GenerationError {
            detail: e.to_string(),
        }
    })?;

    install_plugin(&plugin, &workspace_dir).map_err(|e| FfiError::GenerationError {
        detail: format!("Failed to install plugin: {e}"),
    })?;

    serde_json::to_string(&serde_json::json!({
        "name": plugin.name,
        "description": plugin.description,
        "version": plugin.version,
        "author": plugin.author,
        "manifest": plugin.content,
    }))
    .map_err(|e| FfiError::GenerationError {
        detail: format!("Serialization failed: {e}"),
    })
}

/// Get the workspace directory path.
///
/// Checks the pre-set workspace dir first (from Kotlin), then the
/// `ZEROCLAW_WORKSPACE` env var, then the running daemon config,
/// and finally falls back to the default project data directory.
fn get_workspace_dir() -> Result<PathBuf, FfiError> {
    // Check pre-set workspace dir first (set from Kotlin, no daemon needed)
    if let Ok(path) = crate::runtime::resolve_workspace_dir() {
        return Ok(path);
    }
    // Fallback: env var
    if let Ok(path) = std::env::var("ZEROCLAW_WORKSPACE") {
        return Ok(PathBuf::from(path));
    }
    // Fallback: default project data dir
    directories::ProjectDirs::from("", "", "zeroclaw")
        .map(|dirs: directories::ProjectDirs| dirs.data_dir().to_path_buf())
        .ok_or_else(|| FfiError::StateError {
            detail: "Could not determine workspace directory".to_string(),
        })
}
