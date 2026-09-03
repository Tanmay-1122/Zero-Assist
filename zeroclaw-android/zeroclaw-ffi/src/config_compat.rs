/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

//! Compatibility helpers for upstream ZeroClaw config schema changes.
//!
//! Android still feeds some v1-style TOML into the FFI layer. ZeroClaw Master
//! moved provider settings under `[providers]` and renamed `channels_config` to
//! `channels`, so this module centralizes the migration and active-provider
//! lookups used by the rest of the binding.

use std::collections::HashMap;

use zeroclaw::config::{McpServerConfig, McpTransport, ModelProviderConfig};

const COMPOSIO_SESSIONS_ENDPOINT: &str = "https://connect.composio.dev/mcp";
const COMPOSIO_CONSUMER_KEY_HEADER: &str = "x-consumer-api-key";

/// Parses config TOML using upstream's v1-to-current migration path.
pub(crate) fn parse_config_toml(raw: &str) -> Result<zeroclaw::Config, String> {
    let prepared = match zeroclaw::config::migration::migrate_file(raw) {
        Ok(Some(migrated)) => migrated,
        Ok(None) => raw.to_string(),
        Err(e) => return Err(format!("failed to parse config TOML: {e}")),
    };
    let compat: zeroclaw::config::migration::V1Compat =
        toml::from_str(&prepared).map_err(|e| format!("failed to parse config TOML: {e}"))?;

    Ok(compat.into_config())
}

fn fallback_profile(config: &zeroclaw::Config) -> Option<(&str, &ModelProviderConfig)> {
    let key = config.providers.fallback.as_deref()?.trim();
    if key.is_empty() {
        return None;
    }
    config
        .providers
        .models
        .get(key)
        .map(|profile| (key, profile))
}

/// Returns the active provider factory name from the current provider profile.
pub(crate) fn active_provider_name(config: &zeroclaw::Config) -> Option<String> {
    let (key, profile) = fallback_profile(config)?;
    let name = profile
        .name
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .unwrap_or(key);
    Some(name.to_string())
}

/// Returns the active provider factory name, or `default` if none is configured.
pub(crate) fn active_provider_name_or_default(config: &zeroclaw::Config, default: &str) -> String {
    active_provider_name(config).unwrap_or_else(|| default.to_string())
}

/// Returns the active model from the current provider profile.
pub(crate) fn active_model(config: &zeroclaw::Config) -> Option<String> {
    config.providers.resolve_default_model()
}

/// Returns the active model, or `default` if none is configured.
pub(crate) fn active_model_or_default(config: &zeroclaw::Config, default: &str) -> String {
    active_model(config).unwrap_or_else(|| default.to_string())
}

/// Returns the active provider temperature, or `default` if none is configured.
pub(crate) fn active_temperature_or_default(config: &zeroclaw::Config, default: f64) -> f64 {
    fallback_profile(config)
        .and_then(|(_, profile)| profile.temperature)
        .unwrap_or(default)
}

/// Returns the active provider API key, if one is configured.
pub(crate) fn active_api_key(config: &zeroclaw::Config) -> Option<&str> {
    fallback_profile(config).and_then(|(_, profile)| {
        profile
            .api_key
            .as_deref()
            .map(str::trim)
            .filter(|value| !value.is_empty())
    })
}

/// Returns the active provider base URL, if one is configured.
pub(crate) fn active_base_url(config: &zeroclaw::Config) -> Option<&str> {
    fallback_profile(config).and_then(|(_, profile)| {
        profile
            .base_url
            .as_deref()
            .map(str::trim)
            .filter(|value| !value.is_empty())
    })
}

/// Applies an Android hot-swap or agent override to the current provider profile.
pub(crate) fn apply_provider_override(
    config: &mut zeroclaw::Config,
    provider: String,
    model: String,
    api_key: Option<String>,
    base_url: Option<String>,
    temperature: Option<f64>,
) {
    let provider = provider.trim().to_string();
    if provider.is_empty() {
        return;
    }

    config.providers.fallback = Some(provider.clone());
    let entry = config.providers.models.entry(provider.clone()).or_default();

    if entry
        .name
        .as_deref()
        .map(str::trim)
        .unwrap_or_default()
        .is_empty()
    {
        entry.name = Some(provider);
    }
    entry.model = Some(model);

    if let Some(key) = api_key.filter(|value| !value.trim().is_empty()) {
        entry.api_key = Some(key);
    }
    if let Some(url) = base_url.filter(|value| !value.trim().is_empty()) {
        entry.base_url = Some(url);
    }
    if let Some(value) = temperature {
        entry.temperature = Some(value);
    }
}

/// Returns whether a Composio key is a Sessions/MCP consumer key.
pub(crate) fn is_composio_sessions_key(key: &str) -> bool {
    key.trim().starts_with("ck_")
}

/// Returns whether a Composio key is a CLI user login key.
pub(crate) fn is_composio_cli_user_key(key: &str) -> bool {
    key.trim().starts_with("uak_")
}

/// Builds the synthetic Composio Sessions MCP server from config, if enabled.
pub(crate) fn composio_mcp_server_from_config(
    config: &zeroclaw::Config,
) -> Option<McpServerConfig> {
    if !config.composio.enabled {
        return None;
    }

    let key = config
        .composio
        .api_key
        .as_deref()
        .map(str::trim)
        .filter(|value| !value.is_empty())?;

    if !is_composio_sessions_key(key) {
        return None;
    }

    let mut headers = HashMap::new();
    headers.insert(COMPOSIO_CONSUMER_KEY_HEADER.to_string(), key.to_string());

    Some(McpServerConfig {
        name: "composio".to_string(),
        transport: McpTransport::Http,
        url: Some(COMPOSIO_SESSIONS_ENDPOINT.to_string()),
        command: String::new(),
        args: Vec::new(),
        env: HashMap::new(),
        headers,
        tool_timeout_secs: Some(120),
        enabled: true,
        description: None,
        transport_options: None,
    })
}

/// Returns configured MCP servers.
/// Composio is a standalone tool (ComposioTool), not an MCP server.
pub(crate) fn effective_mcp_servers_from_config(config: &zeroclaw::Config) -> Vec<McpServerConfig> {
    if config.mcp.enabled {
        config.mcp.active_servers()
    } else {
        Vec::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parses_android_app_style_config_fixture() {
        let config = parse_config_toml(
            r#"
default_temperature = 0.4
default_provider = "openai"
default_model = "gpt-4.1-mini"
api_key = "sk-test-openai"

[gateway]
host = "127.0.0.1"
port = 42617
require_pairing = true
allow_public_bind = false
pair_rate_limit_per_minute = 10
webhook_rate_limit_per_minute = 60
idempotency_ttl_secs = 300

[memory]
backend = "sqlite"
auto_save = true
hygiene_enabled = true
archive_after_days = 7
purge_after_days = 30
vector_weight = 0.7
keyword_weight = 0.3

[autonomy]
level = "full"
workspace_only = true
max_actions_per_hour = 9999
max_cost_per_day_cents = 999999
require_approval_for_medium_risk = true
block_high_risk_commands = true
non_cli_excluded_tools = []
default_channel_trust_level = "standard"
channel_trust_levels = {}

[browser]
enabled = false

[http_request]
enabled = false

[web_fetch]
enabled = false

[web_search]
enabled = false

[workflow_folder]
enabled = true

[shared_folder]
enabled = true

[composio]
enabled = true
api_key = "ck_test_sessions_key"
entity_id = "default"
"#,
        )
        .expect("Android app-style config should parse");

        assert_eq!(active_provider_name(&config).as_deref(), Some("openai"));
        assert_eq!(active_model(&config).as_deref(), Some("gpt-4.1-mini"));
        assert_eq!(active_api_key(&config), Some("sk-test-openai"));
        assert!((active_temperature_or_default(&config, 0.7) - 0.4).abs() < f64::EPSILON);
        assert!(!config.browser.enabled);
        assert!(!config.http_request.enabled);
        assert!(!config.web_fetch.enabled);
        assert!(!config.web_search.enabled);
    }

    #[test]
    fn parses_current_master_provider_config_fixture() {
        let config = parse_config_toml(
            r#"
schema_version = 2

[providers]
fallback = "anthropic"

[providers.models.anthropic]
name = "anthropic"
api_key = "sk-ant-test"
model = "claude-sonnet-4-5-20250929"
temperature = 0.2

[[providers.model_routes]]
hint = "code"
provider = "anthropic"
model = "claude-sonnet-4-5-20250929"
"#,
        )
        .expect("current master config should parse");

        assert_eq!(active_provider_name(&config).as_deref(), Some("anthropic"));
        assert_eq!(
            active_model(&config).as_deref(),
            Some("claude-sonnet-4-5-20250929")
        );
        assert_eq!(active_api_key(&config), Some("sk-ant-test"));
        assert_eq!(config.providers.model_routes.len(), 1);
    }

    #[test]
    fn validate_config_inner_accepts_mi02_config_fixtures() {
        let fixtures = [
            (
                "android app-style",
                r#"
default_temperature = 0.4
default_provider = "openai"
default_model = "gpt-4.1-mini"
api_key = "sk-test-openai"

[gateway]
host = "127.0.0.1"
port = 42617
require_pairing = true

[memory]
backend = "sqlite"
auto_save = true

[composio]
enabled = true
api_key = "ck_test_sessions_key"
entity_id = "default"
"#,
            ),
            (
                "current master",
                r#"
schema_version = 2

[providers]
fallback = "anthropic"

[providers.models.anthropic]
name = "anthropic"
api_key = "sk-ant-test"
model = "claude-sonnet-4-5-20250929"
temperature = 0.2

[gateway]
host = "127.0.0.1"
port = 42617
"#,
            ),
        ];

        for (name, fixture) in fixtures {
            let result = crate::runtime::validate_config_inner(fixture.to_string())
                .expect("validate_config_inner should not return an FFI error");
            assert!(
                result.is_empty(),
                "{name} fixture should validate: {result}"
            );
        }
    }

    #[test]
    fn composio_sessions_key_is_standalone_not_mcp_server() {
        let config = parse_config_toml(
            r#"
[composio]
enabled = true
api_key = "ck_test_sessions_key"
entity_id = "default"

[mcp]
enabled = false
"#,
        )
        .expect("Composio Sessions config should parse");

        let servers = effective_mcp_servers_from_config(&config);
        assert!(servers.is_empty(), "Composio is a standalone tool, not an MCP server");
    }

    #[test]
    fn composio_cli_user_key_is_not_sessions_key() {
        assert!(is_composio_cli_user_key("uak_test_user_key"));
        assert!(!is_composio_sessions_key("uak_test_user_key"));
    }

    #[test]
    fn prepares_legacy_channels_config_before_deserializing() {
        let config = parse_config_toml(
            r#"
default_provider = "openrouter"
default_model = "anthropic/claude-sonnet-4"
default_temperature = 0.7

[channels_config.matrix]
homeserver = "https://matrix.org"
access_token = "matrix-token"
room_id = "!android:matrix.org"
allowed_users = ["@user:matrix.org"]
"#,
        )
        .expect("legacy channels_config fixture should parse");

        let matrix = config
            .channels
            .matrix
            .as_ref()
            .expect("matrix channel should be migrated");
        assert!(
            matrix
                .allowed_rooms
                .contains(&"!android:matrix.org".to_string()),
            "room_id should migrate into allowed_rooms"
        );
    }
}
