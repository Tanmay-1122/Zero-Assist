/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! AI-driven skill and plugin generation from natural language descriptions.
//!
//! Allows the daemon to generate skill/plugin manifests based on user
//! descriptions and safely write them to the workspace filesystem.

use anyhow::{Result, anyhow};
use std::fs;
use std::path::{Path, PathBuf};

/// Maximum length for skill/plugin names
const MAX_NAME_LENGTH: usize = 50;
const MAX_DESCRIPTION_LENGTH: usize = 500;

/// Represents a generated skill ready to be installed
pub struct GeneratedSkill {
    pub name: String,
    pub description: String,
    pub version: String,
    pub author: Option<String>,
    pub content: String, // SKILL.md or TOML content
}

/// Represents a generated plugin ready to be installed
pub struct GeneratedPlugin {
    pub name: String,
    pub description: String,
    pub version: String,
    pub author: Option<String>,
    pub content: String, // manifest.toml + plugin code
}

/// Generate a skill manifest from a natural language description.
///
/// # Arguments
/// * `description` - Natural language description of desired skill functionality
/// * `author` - Optional author name
///
/// # Returns
/// A `GeneratedSkill` struct containing the skill metadata and SKILL.md content
pub fn generate_skill(description: &str, author: Option<&str>) -> Result<GeneratedSkill> {
    validate_input(description, author)?;

    // Sanitize name from description
    let name = sanitize_name(description)?;

    // Generate SKILL.md content
    let content = generate_skill_manifest(&name, description, author);

    Ok(GeneratedSkill {
        name,
        description: description.to_string(),
        version: "0.1.0".to_string(),
        author: author.map(std::string::ToString::to_string),
        content,
    })
}

/// Generate a plugin manifest from a natural language description.
pub fn generate_plugin(description: &str, author: Option<&str>) -> Result<GeneratedPlugin> {
    validate_input(description, author)?;

    let name = sanitize_name(description)?;
    let content = generate_plugin_manifest(&name, description, author);

    Ok(GeneratedPlugin {
        name,
        description: description.to_string(),
        version: "0.1.0".to_string(),
        author: author.map(std::string::ToString::to_string),
        content,
    })
}

/// Install a generated skill to the workspace filesystem.
pub fn install_skill(skill: &GeneratedSkill, workspace_dir: &Path) -> Result<PathBuf> {
    let skill_dir = workspace_dir.join("skills").join(&skill.name);

    // Create skill directory
    fs::create_dir_all(&skill_dir).map_err(|e| anyhow!("Failed to create skill directory: {e}"))?;

    // Write SKILL.md
    let skill_path = skill_dir.join("SKILL.md");
    fs::write(&skill_path, &skill.content)
        .map_err(|e| anyhow!("Failed to write skill manifest: {e}"))?;

    Ok(skill_path)
}

/// Install a generated plugin to the workspace filesystem.
pub fn install_plugin(plugin: &GeneratedPlugin, workspace_dir: &Path) -> Result<PathBuf> {
    let plugin_dir = workspace_dir.join("plugins").join(&plugin.name);

    // Create plugin directory
    fs::create_dir_all(&plugin_dir)
        .map_err(|e| anyhow!("Failed to create plugin directory: {e}"))?;

    // Write manifest.toml
    let manifest_path = plugin_dir.join("manifest.toml");
    fs::write(&manifest_path, &plugin.content)
        .map_err(|e| anyhow!("Failed to write plugin manifest: {e}"))?;

    Ok(manifest_path)
}

/// Validate input constraints
fn validate_input(description: &str, author: Option<&str>) -> Result<()> {
    if description.is_empty() {
        return Err(anyhow!("Skill description cannot be empty"));
    }

    if description.len() > MAX_DESCRIPTION_LENGTH {
        return Err(anyhow!(
            "Description too long (max {MAX_DESCRIPTION_LENGTH} chars)"
        ));
    }

    if let Some(a) = author
        && a.is_empty()
    {
        return Err(anyhow!("Author name cannot be empty"));
    }

    Ok(())
}

/// Sanitize a name from description (alphanumeric + underscore, lowercase)
fn sanitize_name(description: &str) -> Result<String> {
    let name = description
        .split_whitespace()
        .take(3)
        .collect::<Vec<_>>()
        .join("_")
        .to_lowercase()
        .chars()
        .filter(|c| c.is_alphanumeric() || *c == '_')
        .collect::<String>();

    if name.is_empty() {
        return Err(anyhow!(
            "Could not generate valid skill name from description"
        ));
    }

    if name.len() > MAX_NAME_LENGTH {
        return Err(anyhow!(
            "Generated name too long (max {MAX_NAME_LENGTH} chars)"
        ));
    }

    Ok(name)
}

/// Generate SKILL.md content template
fn generate_skill_manifest(name: &str, description: &str, author: Option<&str>) -> String {
    let author_line = author
        .map(|a| format!("**Author:** {a}\n\n"))
        .unwrap_or_default();

    let content = format!(
        r#"# {name} Skill

**Description:** {description}

{author_line}**Version:** 0.1.0

## Tools

Define tools that this skill provides:

```toml
[[tools]]
name = "example_tool"
description = "Example tool description"
kind = "shell"
command = "echo 'Hello from {name}'"

[tools.args]
arg1 = "value1"
```

## Prompts

Define custom prompts for this skill:

### Analyze
Prompt for analyzing with this skill.

### Process
Prompt for processing data.

## Usage

Users can invoke this skill via:

```
skill_tools("{name}") -> ["example_tool"]
```

## Configuration

Optional configuration JSON for skill behavior.
"#
    );

    content
}

/// Generate plugin manifest content template
fn generate_plugin_manifest(name: &str, description: &str, author: Option<&str>) -> String {
    let author_line = author
        .map(|a| format!("author = \"{a}\"\n"))
        .unwrap_or_default();

    let content = format!(
        r#"[plugin]
name = "{name}"
description = "{description}"
version = "0.1.0"
{author_line}

[components]
# Define plugin components here

[[hooks]]
event = "on_load"
handler = "init"

[[hooks]]
event = "on_command"
handler = "handle_command"

[config]
# Default plugin configuration
enabled = true
"#
    );

    content
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_generate_skill() {
        let skill = generate_skill("Convert CSV files to JSON format", Some("TestAuthor"))
            .expect("should generate skill");
        assert_eq!(skill.name, "convert_csv_files");
        assert_eq!(skill.version, "0.1.0");
        assert!(skill.content.contains("CSV"));
    }

    #[test]
    fn test_sanitize_name() {
        let name = sanitize_name("Extract data from PDF").expect("should sanitize");
        assert_eq!(name, "extract_data_from");
    }

    #[test]
    fn test_validate_empty_description() {
        let result = validate_input("", None);
        assert!(result.is_err());
    }

    #[test]
    fn test_validate_long_description() {
        let long_desc = "a".repeat(MAX_DESCRIPTION_LENGTH + 1);
        let result = validate_input(&long_desc, None);
        assert!(result.is_err());
    }
}
