/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import java.net.URI
import com.zeroclaw.android.model.Agent
import com.zeroclaw.android.model.ComposioReadiness
import com.zeroclaw.android.model.ConnectedChannel
import com.zeroclaw.android.model.FieldInputType
import com.zeroclaw.android.model.FallbackProviderConfig
import com.zeroclaw.android.model.McpServerEntry
import com.zeroclaw.android.model.McpTransportType

/**
 * Resolved agent data ready for TOML serialization.
 *
 * All provider/URL resolution is performed before constructing this class
 * so that [ConfigTomlBuilder.buildAgentsToml] only needs to emit values.
 *
 * Upstream `[agents.<name>]` supports `temperature` (`Option<f64>`) and
 * `max_depth` (`u32`) — see `.claude/submodule-api-map.md` lines 235–236.
 *
 * @property name Agent name used as the TOML table key (`[agents.<name>]`).
 * @property provider Resolved upstream factory name (e.g. `"custom:http://host/v1"`).
 * @property model Model identifier (e.g. `"google/gemma-3-12b"`).
 * @property apiKey Decrypted API key value; blank if the provider needs none.
 * @property systemPrompt Agent system prompt; blank if not configured.
 * @property temperature Per-agent temperature override; null omits the field.
 * @property maxDepth Maximum reasoning depth; default omits the field.
 */
data class AgentTomlEntry(
    val name: String,
    val provider: String,
    val model: String,
    val apiKey: String = "",
    val systemPrompt: String = "",
    val temperature: Float? = null,
    val maxDepth: Int = Agent.DEFAULT_MAX_DEPTH,
)

/**
 * Aggregated global configuration values for TOML generation.
 *
 * Grouping these fields into a single data class avoids exceeding the
 * detekt `LongParameterList` threshold (6 parameters).
 *
 * Upstream sections mapped (see `.claude/submodule-api-map.md`):
 * - `default_temperature`, `default_provider`, `default_model`, `api_key`
 * - `[agent]` compact_context
 * - `[gateway]` host, port, pairing, rate limits, idempotency
 * - `[memory]` backend, hygiene, embedding, recall weights
 * - `[identity]` aieos_inline
 * - `[cost]` enabled, daily/monthly limits, warn percent
 * - `[reliability]` provider_retries, provider_backoff_ms, api_keys
 * - `[autonomy]` level, workspace, commands, paths, limits
 * - `[tunnel]` provider + sub-tables (cloudflare/tailscale/ngrok/custom)
 * - `[scheduler]` enabled, max_tasks, max_concurrent
 * - `[heartbeat]` enabled, interval_minutes
 * - `[observability]` backend, otel_endpoint, otel_service_name
 * - `[[model_routes]]` hint, provider, model
 * - `[composio]` enabled, api_key, entity_id
 * - `[browser]` enabled, allowed_domains
 * - `[shared_folder]` enabled
 * - `[workflow_folder]` enabled
 * - `[http_request]` enabled, allowed_domains
 * - `[google_workspace]` enabled, allowed_services, audit_log
 *
 * @property provider Android provider ID (e.g. "openai", "lmstudio").
 * @property model Model name (e.g. "gpt-4o").
 * @property apiKey Secret API key value.
 * @property baseUrl Provider endpoint URL.
 * @property temperature Default inference temperature (0.0–2.0).
 * @property compactContext Whether compact context mode is enabled.
 * @property costEnabled Whether cost limits are enforced.
 * @property dailyLimitUsd Daily spending cap in USD.
 * @property monthlyLimitUsd Monthly spending cap in USD.
 * @property costWarnAtPercent Percentage of limit at which to warn.
 * @property providerRetries Number of retries against the selected provider.
 * @property fallbackProviders Deprecated no-op list retained for persisted settings compatibility.
 * @property fallbackProviderConfigs Deprecated no-op list retained for persisted settings compatibility.
 * @property memoryBackend Memory backend name.
 * @property memoryAutoSave Whether the memory backend auto-saves conversation context.
 * @property identityJson AIEOS v1.1 identity JSON blob.
 * @property autonomyLevel Autonomy level: "readonly", "supervised", or "full".
 * @property workspaceOnly Whether to restrict file access to workspace only.
 * @property allowedCommands Allowed shell commands list.
 * @property forbiddenPaths Forbidden filesystem paths list.
 * @property maxActionsPerHour Maximum agent actions per hour.
 * @property maxCostPerDayCents Maximum daily cost in cents.
 * @property requireApprovalMediumRisk Whether medium-risk actions require approval.
 * @property blockHighRiskCommands Whether to block high-risk commands entirely.
 * @property tunnelProvider Tunnel provider name.
 * @property tunnelCloudflareToken Cloudflare tunnel auth token.
 * @property tunnelTailscaleFunnel Whether to enable Tailscale Funnel.
 * @property tunnelTailscaleHostname Custom Tailscale hostname.
 * @property tunnelNgrokAuthToken ngrok authentication token.
 * @property tunnelNgrokDomain Custom ngrok domain.
 * @property tunnelCustomCommand Custom tunnel start command.
 * @property tunnelCustomHealthUrl Health check URL for custom tunnel.
 * @property tunnelCustomUrlPattern URL extraction pattern for custom tunnel.
 * @property gatewayHost Gateway bind address.
 * @property gatewayPort Gateway bind port.
 * @property gatewayRequirePairing Whether gateway requires pairing tokens. Defaults to false
 *   on Android (upstream default: true) because mobile devices are typically behind NAT.
 * @property gatewayAllowPublicBind Whether to allow binding to 0.0.0.0.
 * @property gatewayPairedTokens Authorized pairing tokens list.
 * @property gatewayPairRateLimit Pairing rate limit per minute.
 * @property gatewayWebhookRateLimit Webhook rate limit per minute.
 * @property gatewayIdempotencyTtl Idempotency TTL in seconds.
 * @property schedulerEnabled Whether the task scheduler is active.
 * @property schedulerMaxTasks Maximum scheduler tasks.
 * @property schedulerMaxConcurrent Maximum concurrent task executions.
 * @property heartbeatEnabled Whether the heartbeat engine is active.
 * @property heartbeatIntervalMinutes Interval between heartbeat ticks.
 * @property observabilityBackend Observability backend name.
 * @property observabilityOtelEndpoint OpenTelemetry collector endpoint.
 * @property observabilityOtelServiceName Service name for OTel traces.
 * @property modelRoutesJson JSON array of model route objects.
 * @property memoryHygieneEnabled Whether memory hygiene is active.
 * @property memoryArchiveAfterDays Days before memory entries are archived.
 * @property memoryPurgeAfterDays Days before archived entries are purged.
 * @property memoryEmbeddingProvider Embedding provider name.
 * @property memoryEmbeddingModel Embedding model name.
 * @property memoryVectorWeight Weight for vector similarity in recall.
 * @property memoryKeywordWeight Weight for keyword matching in recall.
 * @property composioEnabled Whether Composio tool integration is active.
 * @property composioApiKey Composio `ck_` Sessions/MCP key or legacy REST project key.
 * @property composioEntityId Composio entity identifier for legacy REST keys.
 * @property mcpEnabled Whether external MCP tool loading is active.
 * @property mcpDeferredLoading Load MCP tool schemas on-demand via tool_search.
 * @property mcpServers List of configured MCP server entries.
 * @property browserEnabled Whether the browser tool is enabled.
 * @property browserAllowedDomains Allowed browser domains list.
 * @property httpRequestEnabled Whether the HTTP request tool is enabled.
 * @property httpRequestAllowedDomains Allowed HTTP domains list.
 * @property httpRequestMaxResponseSize Maximum response body size in bytes for HTTP requests.
 * @property httpRequestTimeoutSecs Request timeout in seconds for HTTP requests.
 * @property proxyEnabled Whether proxy configuration is active.
 * @property proxyHttpProxy HTTP proxy URL.
 * @property proxyHttpsProxy HTTPS proxy URL.
 * @property proxyNoProxy List of domains that bypass the proxy.
 * @property proxyAllProxy Catch-all proxy URL applied to all protocols.
 * @property proxyScope Proxy scope identifier (e.g. "zeroclaw" or "system").
 * @property proxyServiceSelectors Service selectors for selective proxy routing.
 * @property webFetchEnabled Whether the web fetch tool is enabled.
 * @property webFetchAllowedDomains Allowed domains for web fetch requests.
 * @property webFetchBlockedDomains Blocked domains for web fetch requests.
 * @property webFetchMaxResponseSize Maximum response body size in bytes.
 * @property webFetchTimeoutSecs Timeout for web fetch requests in seconds.
 * @property webSearchEnabled Whether the web search tool is enabled.
 * @property webSearchProvider Web search provider name (e.g. "duckduckgo", "brave").
 * @property webSearchBraveApiKey Brave Search API key for authenticated queries.
 * @property webSearchMaxResults Maximum number of search results to return.
 * @property webSearchTimeoutSecs Timeout for web search requests in seconds.
 * @property securitySandboxEnabled Whether sandboxing is enabled (null = upstream default).
 * @property securitySandboxBackend Sandbox backend name (e.g. "auto", "firejail").
 * @property securitySandboxFirejailArgs Extra arguments passed to Firejail.
 * @property securityResourcesMaxMemoryMb Maximum memory allocation in MB.
 * @property securityResourcesMaxCpuTimeSecs Maximum CPU time in seconds.
 * @property securityResourcesMaxSubprocesses Maximum number of subprocesses.
 * @property securityResourcesMemoryMonitoring Whether memory monitoring is active.
 * @property securityAuditEnabled Whether security audit logging is active.
 * @property securityOtpEnabled Whether one-time password verification is active.
 * @property securityOtpMethod OTP method (e.g. "totp", "pairing", "cli-prompt").
 * @property securityOtpTokenTtlSecs OTP token time-to-live in seconds.
 * @property securityOtpCacheValidSecs Duration a validated OTP remains cached in seconds.
 * @property securityOtpGatedActions Actions that require OTP verification.
 * @property securityOtpGatedDomains Domains whose actions require OTP verification.
 * @property securityOtpGatedDomainCategories Domain categories requiring OTP verification.
 * @property securityEstopEnabled Whether the emergency stop mechanism is active.
 * @property securityEstopRequireOtpToResume Whether resuming from e-stop requires OTP.
 * @property securityWebauthnEnabled Whether WebAuthn/passkey authentication is enabled.
 * @property securityWebauthnRpId WebAuthn relying-party ID (domain or host).
 * @property securityWebauthnRpOrigin WebAuthn relying-party origin URL.
 * @property securityWebauthnRpName WebAuthn relying-party display name.
 * @property memoryQdrantUrl Qdrant vector database connection URL.
 * @property memoryQdrantCollection Qdrant collection name for memory storage.
 * @property memoryQdrantApiKey Qdrant API key for authenticated access.
 * @property embeddingRoutesJson JSON array of embedding route objects.
 * @property skillsOpenSkillsEnabled Whether the open-skills community repository is enabled.
 * @property skillsOpenSkillsDir Custom directory for open-skills repository.
 * @property skillsPromptInjectionMode Skill prompt injection mode: "full" or "compact".
 * @property reliabilityBackoffMs Provider backoff duration in milliseconds.
 * @property reliabilityApiKeysJson JSON object mapping provider names to API keys.
 * @property sharedFolderEnabled Whether the shared folder plugin is enabled for SAF access.
 * @property workflowFolderEnabled Whether the workflow folder tools are enabled.
 * @property googleWorkspaceEnabled Whether Google Workspace integration is active.
 * @property googleWorkspaceAllowedServices Allowed Google Workspace services (e.g. "gmail", "drive").
 * @property googleWorkspaceAuditLog Whether Google Workspace API calls are audit-logged.
 */
@Suppress("LongParameterList")
data class GlobalTomlConfig(
    val provider: String,
    val model: String,
    val apiKey: String,
    val baseUrl: String,
    val temperature: Float = DEFAULT_GLOBAL_TEMPERATURE,
    val compactContext: Boolean = false,
    val maxToolIterations: Int = DEFAULT_MAX_TOOL_ITERATIONS,
    val costEnabled: Boolean = false,
    val dailyLimitUsd: Float = DEFAULT_DAILY_LIMIT,
    val monthlyLimitUsd: Float = DEFAULT_MONTHLY_LIMIT,
    val costWarnAtPercent: Int = DEFAULT_WARN_PERCENT,
    val providerRetries: Int = DEFAULT_RETRIES,
    val fallbackProviders: List<String> = emptyList(),
    val fallbackProviderConfigs: List<FallbackProviderConfig> = emptyList(),
    val memoryBackend: String = DEFAULT_MEMORY,
    val memoryAutoSave: Boolean = true,
    val identityJson: String = "",
    val autonomyLevel: String = "supervised",
    val workspaceOnly: Boolean = true,
    val allowedCommands: List<String> = emptyList(),
    val forbiddenPaths: List<String> = emptyList(),
    val maxActionsPerHour: Int = DEFAULT_MAX_ACTIONS,
    val maxCostPerDayCents: Int = DEFAULT_MAX_COST_CENTS,
    val requireApprovalMediumRisk: Boolean = true,
    val blockHighRiskCommands: Boolean = true,
    val tunnelProvider: String = "none",
    val tunnelCloudflareToken: String = "",
    val tunnelTailscaleFunnel: Boolean = false,
    val tunnelTailscaleHostname: String = "",
    val tunnelNgrokAuthToken: String = "",
    val tunnelNgrokDomain: String = "",
    val tunnelCustomCommand: String = "",
    val tunnelCustomHealthUrl: String = "",
    val tunnelCustomUrlPattern: String = "",
    val gatewayHost: String = "127.0.0.1",
    val gatewayPort: Int = DEFAULT_GATEWAY_PORT,
    val gatewayRequirePairing: Boolean = false,
    val gatewayAllowPublicBind: Boolean = false,
    val gatewayWebDistDir: String = "",
    val gatewayPairedTokens: List<String> = emptyList(),
    val gatewayPairRateLimit: Int = DEFAULT_PAIR_RATE,
    val gatewayWebhookRateLimit: Int = DEFAULT_WEBHOOK_RATE,
    val gatewayIdempotencyTtl: Int = DEFAULT_IDEMPOTENCY_TTL,
    val schedulerEnabled: Boolean = true,
    val schedulerMaxTasks: Int = DEFAULT_SCHEDULER_TASKS,
    val schedulerMaxConcurrent: Int = DEFAULT_SCHEDULER_CONCURRENT,
    val heartbeatEnabled: Boolean = false,
    val heartbeatIntervalMinutes: Int = DEFAULT_HEARTBEAT_INTERVAL,
    val observabilityBackend: String = "none",
    val observabilityOtelEndpoint: String = "",
    val observabilityOtelServiceName: String = "zeroclaw",
    val modelRoutesJson: String = "[]",
    val memoryHygieneEnabled: Boolean = true,
    val memoryArchiveAfterDays: Int = DEFAULT_ARCHIVE_DAYS,
    val memoryPurgeAfterDays: Int = DEFAULT_PURGE_DAYS,
    val memoryEmbeddingProvider: String = "none",
    val memoryEmbeddingModel: String = "",
    val memoryVectorWeight: Float = DEFAULT_VECTOR_WEIGHT,
    val memoryKeywordWeight: Float = DEFAULT_KEYWORD_WEIGHT,
    val composioEnabled: Boolean = false,
    val composioApiKey: String = "",
    val composioEntityId: String = "default",
    val mcpEnabled: Boolean = false,
    val mcpDeferredLoading: Boolean = false,
    val mcpServers: List<McpServerEntry> = emptyList(),
    val browserEnabled: Boolean = false,
    val browserAllowedDomains: List<String> = emptyList(),
    val httpRequestEnabled: Boolean = false,
    val httpRequestAllowedDomains: List<String> = emptyList(),
    val httpRequestMaxResponseSize: Int = DEFAULT_HTTP_REQUEST_MAX_RESPONSE_SIZE,
    val httpRequestTimeoutSecs: Int = DEFAULT_HTTP_REQUEST_TIMEOUT_SECS,

    val proxyEnabled: Boolean = false,
    val proxyHttpProxy: String = "",
    val proxyHttpsProxy: String = "",
    val proxyNoProxy: List<String> = emptyList(),
    val proxyAllProxy: String = "",
    val proxyScope: String = "zeroclaw",
    val proxyServiceSelectors: List<String> = emptyList(),
    val webFetchEnabled: Boolean = false,
    val webFetchAllowedDomains: List<String> = emptyList(),
    val webFetchBlockedDomains: List<String> = emptyList(),
    val webFetchMaxResponseSize: Int = DEFAULT_WEB_FETCH_MAX_RESPONSE_SIZE,
    val webFetchTimeoutSecs: Int = DEFAULT_WEB_FETCH_TIMEOUT_SECS,
    val webSearchEnabled: Boolean = false,
    val webSearchProvider: String = "duckduckgo",
    val webSearchBraveApiKey: String = "",
    val webSearchMaxResults: Int = DEFAULT_WEB_SEARCH_MAX_RESULTS,
    val webSearchTimeoutSecs: Int = DEFAULT_WEB_SEARCH_TIMEOUT_SECS,
    val securitySandboxEnabled: Boolean? = null,
    val securitySandboxBackend: String = "auto",
    val securitySandboxFirejailArgs: List<String> = emptyList(),
    val securityResourcesMaxMemoryMb: Int = DEFAULT_RESOURCES_MAX_MEMORY_MB,
    val securityResourcesMaxCpuTimeSecs: Int = DEFAULT_RESOURCES_MAX_CPU_TIME_SECS,
    val securityResourcesMaxSubprocesses: Int = DEFAULT_RESOURCES_MAX_SUBPROCESSES,
    val securityResourcesMemoryMonitoring: Boolean = true,
    val securityAuditEnabled: Boolean = false,
    val securityOtpEnabled: Boolean = false,
    val securityOtpMethod: String = "totp",
    val securityOtpTokenTtlSecs: Int = DEFAULT_OTP_TOKEN_TTL_SECS,
    val securityOtpCacheValidSecs: Int = DEFAULT_OTP_CACHE_VALID_SECS,
    val securityOtpGatedActions: List<String> = DEFAULT_OTP_GATED_ACTIONS,
    val securityOtpGatedDomains: List<String> = emptyList(),
    val securityOtpGatedDomainCategories: List<String> = emptyList(),
    val securityEstopEnabled: Boolean = false,
    val securityEstopRequireOtpToResume: Boolean = true,
    val securityWebauthnEnabled: Boolean = false,
    val securityWebauthnRpId: String = DEFAULT_WEBAUTHN_RP_ID,
    val securityWebauthnRpOrigin: String = DEFAULT_WEBAUTHN_RP_ORIGIN,
    val securityWebauthnRpName: String = DEFAULT_WEBAUTHN_RP_NAME,
    val memoryQdrantUrl: String = "",
    val memoryQdrantCollection: String = "zeroclaw_memories",
    val memoryQdrantApiKey: String = "",
    val embeddingRoutesJson: String = "[]",

    val skillsOpenSkillsEnabled: Boolean = false,
    val skillsOpenSkillsDir: String = "",
    val skillsPromptInjectionMode: String = "full",
    val reliabilityBackoffMs: Int = DEFAULT_RELIABILITY_BACKOFF_MS,
    val reliabilityApiKeysJson: String = "{}",
    val sharedFolderEnabled: Boolean = false,
    val sharedFolderUri: String = "",
    val workflowFolderEnabled: Boolean = true,
    val workflowFolderUri: String = "",
    val googleWorkspaceEnabled: Boolean = false,
    val googleWorkspaceAllowedServices: List<String> = emptyList(),
    val googleWorkspaceAuditLog: Boolean = false,
    val prootBrowserEnabled: Boolean = false,
    val prootBrowserDistro: String = "alpine",
    val prootBrowserBackend: String = "agent_browser",
    val prootBrowserSessionName: String = "zeroclaw",
    val prootBrowserChromeDriverPort: Int = 9515,
    val prootBrowserAllowedDomains: List<String> = emptyList(),
    val prootBrowserMaxActionsPerHour: Int = 100,
) {
    /** Constants for [GlobalTomlConfig]. */
    companion object {
        /** Default inference temperature. */
        const val DEFAULT_GLOBAL_TEMPERATURE = 0.7f

        /** Default max tool iterations (0 = use Rust default of 10). */
        const val DEFAULT_MAX_TOOL_ITERATIONS = 0

        /** Sentinel for unlimited iterations (emitted to daemon). */
        const val MAX_TOOL_ITERATIONS_UNLIMITED = 99999

        /** Default daily cost limit in USD. */
        const val DEFAULT_DAILY_LIMIT = 10f

        /** Default monthly cost limit in USD. */
        const val DEFAULT_MONTHLY_LIMIT = 100f

        /** Default cost warning threshold percentage. */
        const val DEFAULT_WARN_PERCENT = 80

        /** Default number of provider retries. */
        const val DEFAULT_RETRIES = 2

        /** Default memory backend. */
        const val DEFAULT_MEMORY = "sqlite"

        /** Default max actions per hour (aligned with upstream AutonomyConfig default). */
        const val DEFAULT_MAX_ACTIONS = 20

        /** Default max cost per day in cents (aligned with upstream AutonomyConfig default). */
        const val DEFAULT_MAX_COST_CENTS = 500

        /** Default gateway port. */
        const val DEFAULT_GATEWAY_PORT = 42617

        /** Default pair rate limit per minute. */
        const val DEFAULT_PAIR_RATE = 10

        /** Default webhook rate limit per minute. */
        const val DEFAULT_WEBHOOK_RATE = 60

        /** Default idempotency TTL in seconds. */
        const val DEFAULT_IDEMPOTENCY_TTL = 300

        /** Default scheduler max tasks. */
        const val DEFAULT_SCHEDULER_TASKS = 64

        /** Default scheduler max concurrent. */
        const val DEFAULT_SCHEDULER_CONCURRENT = 4

        /** Default heartbeat interval in minutes. */
        const val DEFAULT_HEARTBEAT_INTERVAL = 30

        /** Default memory archive threshold. */
        const val DEFAULT_ARCHIVE_DAYS = 7

        /** Default memory purge threshold. */
        const val DEFAULT_PURGE_DAYS = 30

        /** Default vector weight. */
        const val DEFAULT_VECTOR_WEIGHT = 0.7f

        /** Default keyword weight. */
        const val DEFAULT_KEYWORD_WEIGHT = 0.3f

        /** Default web fetch max response size in bytes. */
        const val DEFAULT_WEB_FETCH_MAX_RESPONSE_SIZE = 500_000

        /** Default web fetch timeout in seconds. */
        const val DEFAULT_WEB_FETCH_TIMEOUT_SECS = 30

        /** Default web search max results. */
        const val DEFAULT_WEB_SEARCH_MAX_RESULTS = 5

        /** Default web search timeout in seconds. */
        const val DEFAULT_WEB_SEARCH_TIMEOUT_SECS = 15

        /** Default resource limit: max memory in MB. */
        const val DEFAULT_RESOURCES_MAX_MEMORY_MB = 512

        /** Default resource limit: max CPU time in seconds. */
        const val DEFAULT_RESOURCES_MAX_CPU_TIME_SECS = 60

        /** Default resource limit: max subprocesses. */
        const val DEFAULT_RESOURCES_MAX_SUBPROCESSES = 10

        /** Default OTP token TTL in seconds. */
        const val DEFAULT_OTP_TOKEN_TTL_SECS = 30

        /** Default OTP cache validity in seconds. */
        const val DEFAULT_OTP_CACHE_VALID_SECS = 300

        /** Default OTP-gated actions. */
        val DEFAULT_OTP_GATED_ACTIONS =
            listOf(
                "shell",
                "file_write",
                "browser_open",
                "browser",
                "memory_forget",
            )

        /** Default WebAuthn relying-party ID. */
        const val DEFAULT_WEBAUTHN_RP_ID = "localhost"

        /** Default WebAuthn relying-party origin. */
        const val DEFAULT_WEBAUTHN_RP_ORIGIN = "http://localhost:42617"

        /** Default WebAuthn relying-party display name. */
        const val DEFAULT_WEBAUTHN_RP_NAME = "ZeroClaw"

        /** Default reliability backoff in milliseconds. */
        const val DEFAULT_RELIABILITY_BACKOFF_MS = 500

        /** Default HTTP request max response size in bytes (1 MB). */
        const val DEFAULT_HTTP_REQUEST_MAX_RESPONSE_SIZE = 1_000_000

        /** Default HTTP request timeout in seconds. */
        const val DEFAULT_HTTP_REQUEST_TIMEOUT_SECS = 30

        /** Valid upstream autonomy levels (from AutonomyLevel enum). */
        val VALID_AUTONOMY_LEVELS = setOf("readonly", "supervised", "full")

        /**
         * Maps UI autonomy level strings to valid config values.
         *
         * The UI presents three autonomy modes:
         * - "supervised": Agent asks before taking actions
         * - "constrained": Agent acts within boundaries without asking
         * - "unconstrained": Agent acts freely with no restrictions
         *
         * These are mapped to the valid config levels:
         * - "supervised" -> "supervised" (unchanged)
         * - "constrained" -> "readonly" (constrained boundaries = read-only)
         * - "unconstrained" -> "full" (unrestricted = full autonomy)
         *
         * Any other input defaults to "supervised" for safety.
         *
         * @param uiLevel The autonomy level from the UI/settings
         * @return The mapped config-valid autonomy level
         */
        fun mapUiAutonomyLevel(uiLevel: String): String =
            when (uiLevel) {
                "supervised" -> "supervised"
                "constrained" -> "readonly"
                "unconstrained" -> "full"
                else -> "supervised" // Default to supervised for safety
            }
    }
}

/**
 * Builds a valid TOML configuration string for the Zero-Assist daemon.
 *
 * The upstream [Config][zeroclaw::config::Config] struct requires at minimum
 * a `default_temperature` field. This builder constructs a TOML document from
 * the user's stored settings and API key, resolving Android provider IDs to
 * the upstream Rust factory conventions.
 *
 * Upstream provider name conventions (from `create_provider(name, api_key)`):
 * - Standard cloud: `"openai"`, `"anthropic"`, etc. (hardcoded endpoints)
 * - Ollama default: `"ollama"` (hardcoded to `http://localhost:11434`)
 * - Custom OpenAI-compatible: `"custom:http://host/v1"` (URL in name)
 * - Custom Anthropic-compatible: `"anthropic-custom:http://host"` (URL in name)
 */
@Suppress("TooManyFunctions", "LargeClass")
object ConfigTomlBuilder {
    /**
     * Placeholder API key injected for self-hosted providers (LM Studio,
     * vLLM, LocalAI, Ollama) that don't require authentication.
     *
     * The upstream [OpenAiCompatibleProvider] unconditionally requires
     * `api_key` to be `Some(...)` and will error before sending any HTTP
     * request if it is `None`. Local servers ignore the resulting
     * `Authorization: Bearer not-needed` header.
     */
    private const val PLACEHOLDER_API_KEY = "not-needed"

    /** Default Ollama endpoint used by the upstream Rust factory. */
    private const val OLLAMA_DEFAULT_URL = "http://localhost:11434"

    /** Android provider IDs that map to `custom:URL` in the TOML. */
    private val OPENAI_COMPATIBLE_SELF_HOSTED =
        setOf(
            "lmstudio",
            "vllm",
            "localai",
            "custom-openai",
        )

    /**
     * Builds a TOML configuration string from the given parameters.
     *
     * Fields with blank values are omitted from the output. The
     * `default_temperature` field is always present because the
     * upstream parser requires it.
     *
     * @param provider Android provider ID (e.g. "openai", "lmstudio").
     * @param model Model name (e.g. "gpt-4o").
     * @param apiKey Secret API key value (may be blank for local providers).
     * @param baseUrl Provider endpoint URL (may be blank for cloud providers).
     * @return A valid TOML configuration string.
     */
    fun build(
        provider: String,
        model: String,
        apiKey: String,
        baseUrl: String,
    ): String =
        build(
            GlobalTomlConfig(
                provider = provider,
                model = model,
                apiKey = apiKey,
                baseUrl = baseUrl,
            ),
        )

    /**
     * Builds a complete TOML configuration string from a [GlobalTomlConfig].
     *
     * Emits all upstream-supported sections conditionally based on the
     * config values. Sections with only default values are omitted to
     * keep the TOML output minimal.
     *
     * @param config Aggregated global configuration values.
     * @return A valid TOML configuration string.
     */
    @Suppress("CognitiveComplexMethod", "LongMethod")
    fun build(config: GlobalTomlConfig): String =
        buildString {
            appendLine("default_temperature = ${config.temperature}")

            val resolvedProvider = resolveProvider(config.provider, config.baseUrl)
            if (resolvedProvider.isNotBlank()) {
                appendLine("default_provider = ${tomlString(resolvedProvider)}")
            }

            if (config.model.isNotBlank()) {
                appendLine("default_model = ${tomlString(config.model)}")
            }

            val effectiveKey =
                config.apiKey.ifBlank {
                    if (needsPlaceholderKey(resolvedProvider)) PLACEHOLDER_API_KEY else ""
                }
            if (effectiveKey.isNotBlank()) {
                appendLine("api_key = ${tomlString(effectiveKey)}")
            }

            val hasAgentSection = config.compactContext ||
                config.maxToolIterations != GlobalTomlConfig.DEFAULT_MAX_TOOL_ITERATIONS
            if (hasAgentSection) {
                appendLine()
                appendLine("[agent]")
                if (config.compactContext) {
                    appendLine("compact_context = true")
                }
                if (config.maxToolIterations != GlobalTomlConfig.DEFAULT_MAX_TOOL_ITERATIONS) {
                    val effective = if (config.maxToolIterations <= 0 ||
                        config.maxToolIterations >= GlobalTomlConfig.MAX_TOOL_ITERATIONS_UNLIMITED
                    ) {
                        GlobalTomlConfig.MAX_TOOL_ITERATIONS_UNLIMITED
                    } else {
                        config.maxToolIterations
                    }
                    appendLine("max_tool_iterations = $effective")
                }
            }

            appendGatewaySection(config)
            appendMemorySection(config)

            if (config.identityJson.isNotBlank()) {
                appendLine()
                appendLine("[identity]")
                appendLine("format = \"aieos\"")
                appendLine("aieos_inline = ${tomlString(config.identityJson)}")
            }

            if (config.costEnabled) {
                appendLine()
                appendLine("[cost]")
                appendLine("enabled = true")
                appendLine("daily_limit_usd = ${config.dailyLimitUsd}")
                appendLine("monthly_limit_usd = ${config.monthlyLimitUsd}")
                appendLine("warn_at_percent = ${config.costWarnAtPercent.coerceAtLeast(0)}")
            }

            appendReliabilitySection(config)
            appendAutonomySection(config)
            appendTunnelSection(config)
            appendSchedulerSection(config)
            appendHeartbeatSection(config)
            appendObservabilitySection(config)
            appendModelRoutesSection(config)
            appendComposioSection(config)
            appendMcpSection(config)
            appendBrowserSection(config)
            appendProotBrowserSection(config)
            appendWorkflowFolderSection(config)
            appendSharedFolderSection(config)
            appendHttpRequestSection(config)
            appendProxySection(config)
            appendWebFetchSection(config)
            appendWebSearchSection(config)
            ConfigTomlSecuritySections.appendTo(this, config)
            appendMemoryQdrantSection(config)
            appendEmbeddingRoutesSection(config)

            appendSkillsSection(config)
            appendGoogleWorkspaceSection(config)
        }

    /**
     * Appends the `[reliability]` TOML section when non-default values exist.
     *
     * @param config Configuration to read reliability values from.
     */
    private fun StringBuilder.appendReliabilitySection(config: GlobalTomlConfig) {
        val hasCustomRetries =
            config.providerRetries != GlobalTomlConfig.DEFAULT_RETRIES
        val hasCustomBackoff =
            config.reliabilityBackoffMs != GlobalTomlConfig.DEFAULT_RELIABILITY_BACKOFF_MS
        val hasApiKeys = config.reliabilityApiKeysJson != "{}"
        val hasAnyReliability = hasCustomRetries || hasCustomBackoff || hasApiKeys
        if (!hasAnyReliability) return

        appendLine()
        appendLine("[reliability]")
        if (hasCustomRetries) {
            appendLine("provider_retries = ${config.providerRetries.coerceAtLeast(0)}")
        }
        if (hasCustomBackoff) {
            appendLine("provider_backoff_ms = ${config.reliabilityBackoffMs.coerceAtLeast(0)}")
        }
        appendReliabilityApiKeys(config.reliabilityApiKeysJson)
    }

    /**
     * Parses the reliability API keys JSON and appends the flat array.
     *
     * Upstream `api_keys` is `Vec<String>` — a flat list of keys for
     * round-robin rotation, not a provider-keyed map.
     *
     * @param json JSON object string mapping provider names to API keys.
     */
    private fun StringBuilder.appendReliabilityApiKeys(json: String) {
        if (json == "{}") return
        try {
            val keysObj = org.json.JSONObject(json)
            val keys = mutableListOf<String>()
            val iter = keysObj.keys()
            while (iter.hasNext()) {
                val key = keysObj.getString(iter.next())
                if (key.isNotBlank()) keys.add(key)
            }
            if (keys.isNotEmpty()) {
                val list = keys.joinToString(", ") { tomlString(it) }
                appendLine("api_keys = [$list]")
            }
        } catch (_: org.json.JSONException) {
            // Ignore malformed JSON
        }
    }

    /**
     * Appends the `[gateway]` TOML section with all gateway-related fields.
     *
     * Upstream fields: host, port, require_pairing, allow_public_bind,
     * paired_tokens, pair_rate_limit_per_minute, webhook_rate_limit_per_minute,
     * idempotency_ttl_secs (see `.claude/submodule-api-map.md` lines 349-358).
     *
     * @param config Configuration to read gateway values from.
     */
    private fun StringBuilder.appendGatewaySection(config: GlobalTomlConfig) {
        appendLine()
        appendLine("[gateway]")
        appendLine("host = ${tomlString(config.gatewayHost)}")
        appendLine("port = ${config.gatewayPort.coerceAtLeast(0)}")
        appendLine("require_pairing = ${config.gatewayRequirePairing}")
        appendLine("allow_public_bind = ${config.gatewayAllowPublicBind}")
        if (config.gatewayWebDistDir.isNotBlank()) {
            appendLine("web_dist_dir = ${tomlString(config.gatewayWebDistDir)}")
        }
        if (config.gatewayPairedTokens.isNotEmpty()) {
            val list = config.gatewayPairedTokens.joinToString(", ") { tomlString(it) }
            appendLine("paired_tokens = [$list]")
        }
        appendLine("pair_rate_limit_per_minute = ${config.gatewayPairRateLimit.coerceAtLeast(0)}")
        appendLine("webhook_rate_limit_per_minute = ${config.gatewayWebhookRateLimit.coerceAtLeast(0)}")
        appendLine("idempotency_ttl_secs = ${config.gatewayIdempotencyTtl.coerceAtLeast(0)}")
    }

    /**
     * Appends the `[memory]` TOML section with backend and hygiene fields.
     *
     * Upstream fields: backend, auto_save, hygiene_enabled, archive_after_days,
     * purge_after_days, embedding_provider, embedding_model, vector_weight,
     * keyword_weight (see `.claude/submodule-api-map.md` lines 314-327).
     *
     * @param config Configuration to read memory values from.
     */
    private fun StringBuilder.appendMemorySection(config: GlobalTomlConfig) {
        appendLine()
        appendLine("[memory]")
        appendLine("backend = ${tomlString(config.memoryBackend)}")
        appendLine("auto_save = ${config.memoryAutoSave}")
        appendLine("hygiene_enabled = ${config.memoryHygieneEnabled}")
        appendLine("archive_after_days = ${config.memoryArchiveAfterDays.coerceAtLeast(0)}")
        appendLine("purge_after_days = ${config.memoryPurgeAfterDays.coerceAtLeast(0)}")
        if (config.memoryEmbeddingProvider != "none") {
            appendLine("embedding_provider = ${tomlString(config.memoryEmbeddingProvider)}")
            if (config.memoryEmbeddingModel.isNotBlank()) {
                appendLine("embedding_model = ${tomlString(config.memoryEmbeddingModel)}")
            }
        }
        appendLine("vector_weight = ${config.memoryVectorWeight}")
        appendLine("keyword_weight = ${config.memoryKeywordWeight}")
    }

    /**
     * Appends the `[autonomy]` TOML section.
     *
     * Upstream fields: level, workspace_only, allowed_commands, forbidden_paths,
     * max_actions_per_hour, max_cost_per_day_cents, require_approval_for_medium_risk,
     * block_high_risk_commands (see `.claude/submodule-api-map.md` lines 258-266).
     *
     * @param config Configuration to read autonomy values from.
     */
    private fun StringBuilder.appendAutonomySection(config: GlobalTomlConfig) {
        val level = config.autonomyLevel
        require(level in GlobalTomlConfig.VALID_AUTONOMY_LEVELS) {
            "Invalid autonomy level '$level': must be one of ${GlobalTomlConfig.VALID_AUTONOMY_LEVELS}"
        }
        appendLine()
        appendLine("[autonomy]")
        appendLine("level = ${tomlString(level)}")
        appendLine("workspace_only = ${config.workspaceOnly}")
        if (config.allowedCommands.isNotEmpty()) {
            val list = config.allowedCommands.joinToString(", ") { tomlString(it) }
            appendLine("allowed_commands = [$list]")
        }
        if (config.forbiddenPaths.isNotEmpty()) {
            val list = config.forbiddenPaths.joinToString(", ") { tomlString(it) }
            appendLine("forbidden_paths = [$list]")
        }
        appendLine("max_actions_per_hour = ${config.maxActionsPerHour.coerceAtLeast(0)}")
        appendLine("max_cost_per_day_cents = ${config.maxCostPerDayCents.coerceAtLeast(0)}")
        appendLine("require_approval_for_medium_risk = ${config.requireApprovalMediumRisk}")
        appendLine("block_high_risk_commands = ${config.blockHighRiskCommands}")
        // All tools available to all channels — no exclusions by default.
        // Channels get the same tool parity as the main app.
        appendLine("non_cli_excluded_tools = []")
        // Default trust level for channels. CLI is always Full.
        appendLine("default_channel_trust_level = \"standard\"")
        // Per-channel trust overrides (empty = use default for all channels).
        // Example: channel_trust_levels = { telegram = "standard", webhook = "restricted" }
        appendLine("channel_trust_levels = {}")
    }

    /**
     * Appends the `[tunnel]` TOML section when a tunnel provider is configured.
     *
     * Upstream fields: provider, cloudflare.token, tailscale.funnel/hostname,
     * ngrok.auth_token/domain, custom.start_command/health_url/url_pattern
     * (see `.claude/submodule-api-map.md` lines 332-346).
     *
     * @param config Configuration to read tunnel values from.
     */
    @Suppress("CognitiveComplexMethod")
    private fun StringBuilder.appendTunnelSection(config: GlobalTomlConfig) {
        if (config.tunnelProvider == "none") return
        appendLine()
        appendLine("[tunnel]")
        appendLine("provider = ${tomlString(config.tunnelProvider)}")
        when (config.tunnelProvider) {
            "cloudflare" -> {
                appendLine("[tunnel.cloudflare]")
                appendLine("token = ${tomlString(config.tunnelCloudflareToken)}")
            }
            "tailscale" -> {
                appendLine("[tunnel.tailscale]")
                appendLine("funnel = ${config.tunnelTailscaleFunnel}")
                if (config.tunnelTailscaleHostname.isNotBlank()) {
                    appendLine("hostname = ${tomlString(config.tunnelTailscaleHostname)}")
                }
            }
            "ngrok" -> {
                appendLine("[tunnel.ngrok]")
                appendLine("auth_token = ${tomlString(config.tunnelNgrokAuthToken)}")
                if (config.tunnelNgrokDomain.isNotBlank()) {
                    appendLine("domain = ${tomlString(config.tunnelNgrokDomain)}")
                }
            }
            "custom" -> {
                appendLine("[tunnel.custom]")
                appendLine("start_command = ${tomlString(config.tunnelCustomCommand)}")
                if (config.tunnelCustomHealthUrl.isNotBlank()) {
                    appendLine("health_url = ${tomlString(config.tunnelCustomHealthUrl)}")
                }
                if (config.tunnelCustomUrlPattern.isNotBlank()) {
                    appendLine("url_pattern = ${tomlString(config.tunnelCustomUrlPattern)}")
                }
            }
        }
    }

    /**
     * Appends the `[scheduler]` TOML section.
     *
     * Upstream fields: enabled, max_tasks, max_concurrent
     * (see `.claude/submodule-api-map.md` lines 299-303).
     *
     * @param config Configuration to read scheduler values from.
     */
    private fun StringBuilder.appendSchedulerSection(config: GlobalTomlConfig) {
        appendLine()
        appendLine("[scheduler]")
        appendLine("enabled = ${config.schedulerEnabled}")
        appendLine("max_tasks = ${config.schedulerMaxTasks.coerceAtLeast(0)}")
        appendLine("max_concurrent = ${config.schedulerMaxConcurrent.coerceAtLeast(0)}")
    }

    /**
     * Appends the `[heartbeat]` TOML section.
     *
     * Upstream fields: enabled, interval_minutes
     * (see `.claude/submodule-api-map.md` lines 306-310).
     *
     * @param config Configuration to read heartbeat values from.
     */
    private fun StringBuilder.appendHeartbeatSection(config: GlobalTomlConfig) {
        appendLine()
        appendLine("[heartbeat]")
        appendLine("enabled = ${config.heartbeatEnabled}")
        appendLine("interval_minutes = ${config.heartbeatIntervalMinutes.coerceAtLeast(0)}")
    }

    /**
     * Appends the `[observability]` TOML section.
     *
     * Upstream fields: backend, otel_endpoint, otel_service_name
     * (see `.claude/submodule-api-map.md` lines 250-253).
     *
     * @param config Configuration to read observability values from.
     */
    private fun StringBuilder.appendObservabilitySection(config: GlobalTomlConfig) {
        appendLine()
        appendLine("[observability]")
        appendLine("backend = ${tomlString(config.observabilityBackend)}")
        if (config.observabilityBackend == "otel") {
            if (config.observabilityOtelEndpoint.isNotBlank()) {
                appendLine("otel_endpoint = ${tomlString(config.observabilityOtelEndpoint)}")
            }
            appendLine("otel_service_name = ${tomlString(config.observabilityOtelServiceName)}")
        }
    }

    /**
     * Appends `[[model_routes]]` TOML array entries from the JSON array.
     *
     * Upstream fields: hint, provider, model
     * (see `.claude/submodule-api-map.md` lines 241-245).
     *
     * @param config Configuration to read model routes JSON from.
     */
    private fun StringBuilder.appendModelRoutesSection(config: GlobalTomlConfig) {
        if (config.modelRoutesJson == "[]" || config.modelRoutesJson.isBlank()) return
        try {
            val arr = org.json.JSONArray(config.modelRoutesJson)
            for (i in 0 until arr.length()) {
                val route = arr.getJSONObject(i)
                val hint = route.optString("hint", "")
                val provider = route.optString("provider", "")
                val model = route.optString("model", "")
                if (hint.isBlank() || provider.isBlank() || model.isBlank()) continue
                appendLine()
                appendLine("[[model_routes]]")
                appendLine("hint = ${tomlString(hint)}")
                appendLine("provider = ${tomlString(provider)}")
                appendLine("model = ${tomlString(model)}")
                val apiKey = route.optString("api_key", "")
                if (apiKey.isNotBlank()) {
                    appendLine("api_key = ${tomlString(apiKey)}")
                }
            }
        } catch (_: org.json.JSONException) {
            // Ignore malformed JSON
        }
    }

    /**
     * Builds a standalone `[mcp]` TOML section for hot-reloading the daemon config.
     *
     * Unlike [appendMcpSection] which appends to an existing TOML document,
     * this method produces a standalone TOML fragment that can be passed to
     * [reloadDaemonConfig][com.zeroclaw.ffi.reloadDaemonConfig] to update only
     * the MCP section of a running daemon without restarting it.
     *
     * @param enabled Whether MCP tool loading is globally enabled.
     * @param deferredLoading Load MCP tool schemas on-demand via `tool_search`.
     * @param servers List of configured MCP server entries.
     * @return A standalone TOML string containing only `[mcp]` + `[[mcp.servers]]`.
     */
    fun buildMcpToml(
        enabled: Boolean,
        deferredLoading: Boolean,
        servers: List<McpServerEntry>,
    ): String = buildString {
        appendLine("[mcp]")
        appendLine("enabled = $enabled")
        appendLine("deferred_loading = $deferredLoading")

        for (server in servers) {
            if (!server.enabled) continue
            appendLine()
            appendLine("[[mcp.servers]]")
            appendLine("name = ${tomlString(server.name)}")
            appendLine("enabled = ${server.enabled}")
            appendLine("transport = ${tomlString(server.transport.tomlValue)}")
            if (server.description.isNotBlank()) {
                appendLine("description = ${tomlString(server.description)}")
            }

            when (server.transport) {
                McpTransportType.STDIO -> {
                    if (server.command.isNotBlank()) {
                        appendLine("command = ${tomlString(server.command)}")
                    }
                    if (server.args.isNotEmpty()) {
                        val args = server.args.joinToString(", ") { tomlString(it) }
                        appendLine("args = [$args]")
                    }
                    if (server.env.isNotEmpty()) {
                        appendLine("[mcp.servers.env]")
                        for ((key, value) in server.env) {
                            appendLine("${tomlKey(key)} = ${tomlString(value)}")
                        }
                    }
                }
                McpTransportType.LOCALHOST_STDIO -> {
                    if (server.url.isNotBlank()) {
                        appendLine("url = ${tomlString(server.url)}")
                    }
                    if (server.command.isNotBlank()) {
                        appendLine("command = ${tomlString(server.command)}")
                    }
                    if (server.args.isNotEmpty()) {
                        val args = server.args.joinToString(", ") { tomlString(it) }
                        appendLine("args = [$args]")
                    }
                    if (server.env.isNotEmpty()) {
                        appendLine("[mcp.servers.env]")
                        for ((key, value) in server.env) {
                            appendLine("${tomlKey(key)} = ${tomlString(value)}")
                        }
                    }
                }
                McpTransportType.HTTP, McpTransportType.SSE -> {
                    if (server.url.isNotBlank()) {
                        appendLine("url = ${tomlString(server.url)}")
                    }
                    if (server.headers.isNotEmpty()) {
                        appendLine("[mcp.servers.headers]")
                        for ((key, value) in server.headers) {
                            appendLine("${tomlKey(key)} = ${tomlString(value)}")
                        }
                    }
                }
            }

            if (server.toolTimeoutSecs != null) {
                appendLine("tool_timeout_secs = ${server.toolTimeoutSecs}")
            }
        }
    }

    /**
     * Appends the `[composio]` TOML section when Composio is enabled.
     *
     * Rust interprets `ck_...` values as Composio Sessions/MCP keys and
     * legacy project keys as REST keys. Android emits an inactive section for
     * blank or `uak_...` CLI login keys so the daemon never treats them as
     * usable tool credentials.
     *
     * @param config Configuration to read Composio values from.
     */
    private fun StringBuilder.appendComposioSection(config: GlobalTomlConfig) {
        if (!config.composioEnabled) return
        val readiness =
            ComposioReadiness.from(
                enabled = config.composioEnabled,
                apiKey = config.composioApiKey,
            )
        appendLine()
        appendLine("[composio]")
        if (!readiness.isActive) {
            appendLine("enabled = false")
            return
        }
        appendLine("enabled = true")
        appendLine("api_key = ${tomlString(config.composioApiKey.trim())}")
        appendLine("entity_id = ${tomlString(config.composioEntityId)}")
    }

    /**
     * Appends the `[mcp]` TOML section with server definitions.
     *
     * Upstream fields: enabled, deferred_loading, [[mcp.servers]]
     * (see schema.rs McpConfig / McpServerConfig).
     *
     * @param config Configuration to read MCP values from.
     */
    private fun StringBuilder.appendMcpSection(config: GlobalTomlConfig) {
        val enabledServers = config.mcpServers.filter { it.enabled }
        if (!config.mcpEnabled && enabledServers.isEmpty()) return

        appendLine()
        appendLine("[mcp]")
        appendLine("enabled = ${config.mcpEnabled}")
        appendLine("deferred_loading = ${config.mcpDeferredLoading}")

        for (server in config.mcpServers) {
            appendLine()
            appendLine("[[mcp.servers]]")
            appendLine("name = ${tomlString(server.name)}")
            appendLine("enabled = ${server.enabled}")
            appendLine("transport = ${tomlString(server.transport.tomlValue)}")

            when (server.transport) {
                McpTransportType.STDIO -> {
                    if (server.command.isNotBlank()) {
                        appendLine("command = ${tomlString(server.command)}")
                    }
                    if (server.args.isNotEmpty()) {
                        val args = server.args.joinToString(", ") { tomlString(it) }
                        appendLine("args = [$args]")
                    }
                    if (server.env.isNotEmpty()) {
                        appendLine("[mcp.servers.env]")
                        for ((key, value) in server.env) {
                            appendLine("${tomlKey(key)} = ${tomlString(value)}")
                        }
                    }
                }
                McpTransportType.LOCALHOST_STDIO -> {
                    if (server.url.isNotBlank()) {
                        appendLine("url = ${tomlString(server.url)}")
                    }
                    if (server.command.isNotBlank()) {
                        appendLine("command = ${tomlString(server.command)}")
                    }
                    if (server.args.isNotEmpty()) {
                        val args = server.args.joinToString(", ") { tomlString(it) }
                        appendLine("args = [$args]")
                    }
                    if (server.env.isNotEmpty()) {
                        appendLine("[mcp.servers.env]")
                        for ((key, value) in server.env) {
                            appendLine("${tomlKey(key)} = ${tomlString(value)}")
                        }
                    }
                }
                McpTransportType.HTTP, McpTransportType.SSE -> {
                    if (server.url.isNotBlank()) {
                        appendLine("url = ${tomlString(server.url)}")
                    }
                    if (server.headers.isNotEmpty()) {
                        appendLine("[mcp.servers.headers]")
                        for ((key, value) in server.headers) {
                            appendLine("${tomlKey(key)} = ${tomlString(value)}")
                        }
                    }
                }
            }

            if (server.toolTimeoutSecs != null) {
                appendLine("tool_timeout_secs = ${server.toolTimeoutSecs}")
            }
        }
    }

    /**
     * Appends the `[browser]` TOML section.
     *
     * Upstream fields: enabled, allowed_domains
     * (see `.claude/submodule-api-map.md` lines 377-379).
     *
     * The upstream browser tool defaults to enabled when the section is
     * omitted, so Android must emit `enabled = false` when the user disables
     * the plugin.
     *
     * @param config Configuration to read browser values from.
     */
    private fun StringBuilder.appendBrowserSection(config: GlobalTomlConfig) {
        appendLine()
        appendLine("[browser]")
        if (!config.browserEnabled) {
            appendLine("enabled = false")
            return
        }
        appendLine("enabled = true")
        if (config.browserAllowedDomains.isNotEmpty()) {
            val list = config.browserAllowedDomains.joinToString(", ") { tomlString(it) }
            appendLine("allowed_domains = [$list]")
        }
    }

    /**
     * Appends the `[browser.proot]` TOML section for PRoot Browser plugin.
     *
     * This section configures browser automation tools running inside a PRoot Linux environment.
     * Supports on-demand ChromeDriver lifecycle management for optimal resource usage.
     *
     * Upstream fields: enabled, distro, backend, session_name, chromedriver_port, allowed_domains, max_actions_per_hour
     *
     * @param config Configuration to read PRoot browser values from.
     */
    private fun StringBuilder.appendProotBrowserSection(config: GlobalTomlConfig) {
        appendLine()
        appendLine("[browser.proot]")
        if (!config.prootBrowserEnabled) {
            appendLine("enabled = false")
            return
        }
        appendLine("enabled = true")
        appendLine("distro = ${tomlString(config.prootBrowserDistro)}")
        appendLine("backend = ${tomlString(config.prootBrowserBackend)}")
        appendLine("session_name = ${tomlString(config.prootBrowserSessionName)}")
        appendLine("chromedriver_port = ${config.prootBrowserChromeDriverPort}")
        
        if (config.prootBrowserAllowedDomains.isNotEmpty()) {
            val list = config.prootBrowserAllowedDomains.joinToString(", ") { tomlString(it) }
            appendLine("allowed_domains = [$list]")
        }
        
        appendLine("max_actions_per_hour = ${config.prootBrowserMaxActionsPerHour}")
    }

    /**
     * Appends the `[shared_folder]` TOML section when the shared folder plugin is enabled.
     *
     * The shared folder plugin provides SAF (Storage Access Framework) tools:
     * - shared_folder_list: List files and directories
     * - shared_folder_read: Read file contents
     * - shared_folder_write: Write files and create directories
     *
     * @param config Configuration to read shared folder settings from.
     */
    private fun StringBuilder.appendSharedFolderSection(config: GlobalTomlConfig) {
        // Only inject the shared_folder tools when BOTH the plugin is enabled AND a folder
        // has actually been selected via the SAF picker. This prevents the three file-system
        // tools (shared_folder_list/read/write) from polluting every LLM prompt when
        // no folder is configured, which was causing unnecessary token overhead.
        if (!config.sharedFolderEnabled || config.sharedFolderUri.isBlank()) return
        appendLine()
        appendLine("[shared_folder]")
        appendLine("enabled = true")
    }

    /**
     * Appends the `[workflow_folder]` TOML section when workflow-folder tools are enabled.
     *
     * A blank custom URI is valid here because Android provides an app-owned default
     * workflow folder at startup.
     */
    private fun StringBuilder.appendWorkflowFolderSection(config: GlobalTomlConfig) {
        if (!config.workflowFolderEnabled) return
        appendLine()
        appendLine("[workflow_folder]")
        appendLine("enabled = true")
    }

    /**
     * Appends the `[http_request]` TOML section.
     *
     * Upstream fields: enabled, allowed_domains
     * (see `.claude/submodule-api-map.md` lines 396-399).
     *
     * The HTTP request plugin is deny-by-default in the Android UI. When no
     * allowlist is configured, disable the section entirely so the model does
     * not repeatedly try a tool that must reject every request.
     *
     * @param config Configuration to read HTTP request values from.
     */
    private fun StringBuilder.appendHttpRequestSection(config: GlobalTomlConfig) {
        appendLine()
        appendLine("[http_request]")
        if (!config.httpRequestEnabled || config.httpRequestAllowedDomains.isEmpty()) {
            appendLine("enabled = false")
            return
        }
        appendLine("enabled = true")
        val list = config.httpRequestAllowedDomains.joinToString(", ") { tomlString(it) }
        appendLine("allowed_domains = [$list]")
        if (config.httpRequestMaxResponseSize != GlobalTomlConfig.DEFAULT_HTTP_REQUEST_MAX_RESPONSE_SIZE) {
            appendLine("max_response_size = ${config.httpRequestMaxResponseSize.coerceAtLeast(0)}")
        }
        if (config.httpRequestTimeoutSecs != GlobalTomlConfig.DEFAULT_HTTP_REQUEST_TIMEOUT_SECS) {
            appendLine("timeout_secs = ${config.httpRequestTimeoutSecs.coerceAtLeast(0)}")
        }
    }

    /**
     * Appends the `[proxy]` TOML section when proxy is enabled.
     *
     * Upstream fields: enabled, http_proxy, https_proxy, no_proxy,
     * all_proxy, scope, services.
     *
     * @param config Configuration to read proxy values from.
     */
    private fun StringBuilder.appendProxySection(config: GlobalTomlConfig) {
        if (!config.proxyEnabled) return
        appendLine()
        appendLine("[proxy]")
        appendLine("enabled = true")
        if (config.proxyHttpProxy.isNotBlank()) {
            appendLine("http_proxy = ${tomlString(config.proxyHttpProxy)}")
        }
        if (config.proxyHttpsProxy.isNotBlank()) {
            appendLine("https_proxy = ${tomlString(config.proxyHttpsProxy)}")
        }
        if (config.proxyNoProxy.isNotEmpty()) {
            val list = config.proxyNoProxy.joinToString(", ") { tomlString(it) }
            appendLine("no_proxy = [$list]")
        }
        if (config.proxyAllProxy.isNotBlank()) {
            appendLine("all_proxy = ${tomlString(config.proxyAllProxy)}")
        }
        if (config.proxyScope != "zeroclaw") {
            appendLine("scope = ${tomlString(config.proxyScope)}")
        }
        if (config.proxyServiceSelectors.isNotEmpty()) {
            val list = config.proxyServiceSelectors.joinToString(", ") { tomlString(it) }
            appendLine("services = [$list]")
        }
    }

    /**
     * Appends the `[web_fetch]` TOML section.
     *
     * Upstream fields: enabled, allowed_domains, blocked_domains,
     * max_response_size, timeout_secs.
     *
     * When web fetch is enabled and `allowed_domains` is omitted, the
     * upstream daemon defaults to wildcard (`*`), matching the Android UI's
     * "empty allows all" wording. When disabled, Android must still emit the
     * section because upstream defaults to enabled when it is omitted.
     *
     * @param config Configuration to read web fetch values from.
     */
    private fun StringBuilder.appendWebFetchSection(config: GlobalTomlConfig) {
        appendLine()
        appendLine("[web_fetch]")
        if (!config.webFetchEnabled) {
            appendLine("enabled = false")
            return
        }
        appendLine("enabled = true")
        if (config.webFetchAllowedDomains.isNotEmpty()) {
            val list = config.webFetchAllowedDomains.joinToString(", ") { tomlString(it) }
            appendLine("allowed_domains = [$list]")
        }
        if (config.webFetchBlockedDomains.isNotEmpty()) {
            val list = config.webFetchBlockedDomains.joinToString(", ") { tomlString(it) }
            appendLine("blocked_domains = [$list]")
        }
        if (config.webFetchMaxResponseSize != GlobalTomlConfig.DEFAULT_WEB_FETCH_MAX_RESPONSE_SIZE) {
            appendLine("max_response_size = ${config.webFetchMaxResponseSize.coerceAtLeast(0)}")
        }
        if (config.webFetchTimeoutSecs != GlobalTomlConfig.DEFAULT_WEB_FETCH_TIMEOUT_SECS) {
            appendLine("timeout_secs = ${config.webFetchTimeoutSecs.coerceAtLeast(0)}")
        }
    }

    /**
     * Appends the `[web_search]` TOML section.
     *
     * Upstream fields: enabled, provider, brave_api_key, max_results,
     * timeout_secs.
     *
     * Upstream defaults web search to enabled when the section is omitted, so
     * Android emits an explicit disabled section when the setting is off.
     *
     * @param config Configuration to read web search values from.
     */
    private fun StringBuilder.appendWebSearchSection(config: GlobalTomlConfig) {
        appendLine()
        appendLine("[web_search]")
        if (!config.webSearchEnabled) {
            appendLine("enabled = false")
            return
        }
        appendLine("enabled = true")
        appendLine("provider = ${tomlString(config.webSearchProvider)}")
        if (config.webSearchBraveApiKey.isNotBlank()) {
            appendLine("brave_api_key = ${tomlString(config.webSearchBraveApiKey)}")
        }
        if (config.webSearchMaxResults != GlobalTomlConfig.DEFAULT_WEB_SEARCH_MAX_RESULTS) {
            appendLine("max_results = ${config.webSearchMaxResults.coerceAtLeast(0)}")
        }
        if (config.webSearchTimeoutSecs != GlobalTomlConfig.DEFAULT_WEB_SEARCH_TIMEOUT_SECS) {
            appendLine("timeout_secs = ${config.webSearchTimeoutSecs.coerceAtLeast(0)}")
        }
    }

    /**
     * Appends the `[memory.qdrant]` TOML section when Qdrant is configured.
     *
     * Upstream fields: url, collection, api_key.
     *
     * @param config Configuration to read Qdrant memory values from.
     */
    private fun StringBuilder.appendMemoryQdrantSection(config: GlobalTomlConfig) {
        if (config.memoryQdrantUrl.isBlank() && config.memoryQdrantApiKey.isBlank()) return
        appendLine()
        appendLine("[memory.qdrant]")
        if (config.memoryQdrantUrl.isNotBlank()) {
            appendLine("url = ${tomlString(config.memoryQdrantUrl)}")
        }
        appendLine("collection = ${tomlString(config.memoryQdrantCollection)}")
        if (config.memoryQdrantApiKey.isNotBlank()) {
            appendLine("api_key = ${tomlString(config.memoryQdrantApiKey)}")
        }
    }

    /**
     * Appends `[[embedding_routes]]` TOML array entries from the JSON array.
     *
     * Upstream fields: hint, provider, model, dimensions, api_key.
     *
     * @param config Configuration to read embedding routes JSON from.
     */
    private fun StringBuilder.appendEmbeddingRoutesSection(config: GlobalTomlConfig) {
        if (config.embeddingRoutesJson == "[]" || config.embeddingRoutesJson.isBlank()) return
        try {
            val arr = org.json.JSONArray(config.embeddingRoutesJson)
            for (i in 0 until arr.length()) {
                val route = arr.getJSONObject(i)
                val hint = route.optString("hint", "")
                val provider = route.optString("provider", "")
                val model = route.optString("model", "")
                if (hint.isBlank() || provider.isBlank() || model.isBlank()) continue
                appendLine()
                appendLine("[[embedding_routes]]")
                appendLine("hint = ${tomlString(hint)}")
                appendLine("provider = ${tomlString(provider)}")
                appendLine("model = ${tomlString(model)}")
                val dimensions = route.optInt("dimensions", 0)
                if (dimensions > 0) {
                    appendLine("dimensions = $dimensions")
                }
                val apiKey = route.optString("api_key", "")
                if (apiKey.isNotBlank()) {
                    appendLine("api_key = ${tomlString(apiKey)}")
                }
            }
        } catch (_: org.json.JSONException) {
            // Ignore malformed JSON
        }
    }

    /**
     * Appends the `[skills]` TOML section when non-default values exist.
     *
     * Upstream fields: open_skills_enabled, open_skills_dir, prompt_injection_mode.
     *
     * @param config Configuration to read skills values from.
     */
    private fun StringBuilder.appendSkillsSection(config: GlobalTomlConfig) {
        val hasNonDefault =
            config.skillsOpenSkillsEnabled ||
                config.skillsOpenSkillsDir.isNotBlank() ||
                config.skillsPromptInjectionMode != "full"
        if (!hasNonDefault) return

        appendLine()
        appendLine("[skills]")
        if (config.skillsOpenSkillsEnabled) {
            appendLine("open_skills_enabled = true")
        }
        if (config.skillsOpenSkillsDir.isNotBlank()) {
            appendLine("open_skills_dir = ${tomlString(config.skillsOpenSkillsDir)}")
        }
        if (config.skillsPromptInjectionMode != "full") {
            appendLine(
                "prompt_injection_mode = ${tomlString(config.skillsPromptInjectionMode)}",
            )
        }
    }

    /**
     * Appends the `[google_workspace]` TOML section when Google Workspace is enabled.
     *
     * Upstream fields: enabled, allowed_services, allowed_operations, credentials_path,
     * default_account, rate_limit_per_minute, timeout_secs, audit_log.
     */
    private fun StringBuilder.appendGoogleWorkspaceSection(config: GlobalTomlConfig) {
        if (!config.googleWorkspaceEnabled) return
        appendLine()
        appendLine("[google_workspace]")
        appendLine("enabled = true")
        if (config.googleWorkspaceAllowedServices.isNotEmpty()) {
            val list = config.googleWorkspaceAllowedServices.joinToString(", ") { tomlString(it) }
            appendLine("allowed_services = [$list]")
        }
        appendLine("rate_limit_per_minute = 60")
        appendLine("timeout_secs = 30")
        appendLine("audit_log = ${config.googleWorkspaceAuditLog}")
    }

    /**
     * Builds the `[channels_config]` TOML section from enabled channels.
     *
     * The CLI channel is disabled (`cli = false`) because the Android app
     * uses the FFI bridge for direct messaging instead of stdin/stdout.
     *
     * @param channelsWithSecrets List of pairs: (channel, all config values including secrets).
     * @return TOML string for the channels_config section, or empty if no channels.
     */
    fun buildChannelsToml(
        channelsWithSecrets: List<Pair<ConnectedChannel, Map<String, String>>>,
    ): String {
        if (channelsWithSecrets.isEmpty()) return ""
        return buildString {
            appendLine()
            appendLine("[channels_config]")
            appendLine("cli = false")

            for ((channel, values) in channelsWithSecrets) {
                appendLine()
                appendLine("[channels_config.${channel.type.tomlKey}]")
                // Always emit `enabled = true` — the Rust config structs default `enabled`
                // to false, so any channel section without this line is silently ignored
                // by the daemon. The Android UI only produces config for channels the user
                // has actively configured, so they should always be enabled.
                appendLine("enabled = true")
                for (spec in channel.type.fields) {
                    val value = values[spec.key].orEmpty()
                    if (value.isBlank() && !spec.isRequired) continue
                    appendTomlField(spec.key, value, spec.inputType)
                }
            }

        }
    }

    /**
     * Builds `[agents.<name>]` TOML sections for per-agent provider configuration.
     *
     * The upstream [DelegateAgentConfig] struct supports `provider`, `model`,
     * `system_prompt`, and `api_key` fields per agent. Only non-blank optional
     * fields are emitted.
     *
     * @param agents Resolved agent entries to serialize.
     * @return TOML string with one `[agents.<name>]` section per entry,
     *   or empty if [agents] is empty.
     */
    @Suppress("CognitiveComplexMethod")
    fun buildAgentsToml(agents: List<AgentTomlEntry>): String {
        if (agents.isEmpty()) return ""
        return buildString {
            for (entry in agents) {
                appendLine()
                appendLine("[agents.${tomlKey(entry.name)}]")
                appendLine("provider = ${tomlString(entry.provider)}")
                appendLine("model = ${tomlString(entry.model)}")
                if (entry.systemPrompt.isNotBlank()) {
                    appendLine("system_prompt = ${tomlString(entry.systemPrompt)}")
                }
                val effectiveKey =
                    entry.apiKey.ifBlank {
                        if (needsPlaceholderKey(entry.provider)) PLACEHOLDER_API_KEY else ""
                    }
                if (effectiveKey.isNotBlank()) {
                    appendLine("api_key = ${tomlString(effectiveKey)}")
                }
                if (entry.temperature != null) {
                    appendLine("temperature = ${entry.temperature}")
                }
                if (entry.maxDepth != Agent.DEFAULT_MAX_DEPTH) {
                    appendLine("max_depth = ${entry.maxDepth.coerceAtLeast(0)}")
                }
            }
        }
    }

    /**
     * Appends a single TOML field with the appropriate value format.
     *
     * @param key TOML field key.
     * @param value Raw string value from the UI.
     * @param inputType Field input type determining the TOML format.
     */
    private fun StringBuilder.appendTomlField(
        key: String,
        value: String,
        inputType: FieldInputType,
    ) {
        when (inputType) {
            FieldInputType.NUMBER -> appendLine("$key = ${value.ifBlank { "0" }}")
            FieldInputType.BOOLEAN -> appendLine("$key = ${value.lowercase()}")
            FieldInputType.LIST -> {
                val items =
                    value
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .joinToString(", ") { tomlString(it) }
                appendLine("$key = [$items]")
            }
            else -> appendLine("$key = ${tomlString(value)}")
        }
    }

    /**
     * Maps an Android provider ID and optional base URL to the upstream
     * Rust factory provider name.
     *
     * @param provider Android provider ID.
     * @param baseUrl Optional endpoint URL.
     * @return The resolved provider string for the TOML, or blank if
     *   [provider] is blank.
     */
    internal fun resolveProvider(
        provider: String,
        baseUrl: String,
    ): String {
        if (provider.isBlank()) return ""

        val trimmedUrl = baseUrl.trim()

        if (provider == "custom-anthropic" && trimmedUrl.isNotEmpty()) {
            return "anthropic-custom:$trimmedUrl"
        }

        if (provider in OPENAI_COMPATIBLE_SELF_HOSTED && trimmedUrl.isNotEmpty()) {
            return "custom:$trimmedUrl"
        }

        if (provider == "ollama" && trimmedUrl.isNotEmpty() && !isDefaultOllamaUrl(trimmedUrl)) {
            return "custom:$trimmedUrl"
        }

        return provider
    }

    /**
     * Returns true when the provided URL should be treated as the default
     * local Ollama endpoint on Android.
     *
     * Besides `localhost`, the Android emulator reaches the host machine via
     * `10.0.2.2`, so that alias should keep the plain `ollama` provider too.
     */
    private fun isDefaultOllamaUrl(baseUrl: String): Boolean {
        val trimmed = baseUrl.trim().trimEnd('/')
        if (trimmed == OLLAMA_DEFAULT_URL) return true

        return runCatching {
            val uri = URI(trimmed)
            val host = uri.host?.lowercase()
            val port = uri.port
            val path = uri.path.orEmpty().trimEnd('/')
            host in setOf("localhost", "127.0.0.1", "10.0.2.2") &&
                port == 11434 &&
                path.isEmpty()
        }.getOrDefault(false)
    }

    /**
     * Returns true if the resolved provider requires a placeholder API key.
     *
     * The upstream [OpenAiCompatibleProvider] unconditionally demands
     * `api_key` to be non-null. Self-hosted servers (LM Studio, vLLM,
     * LocalAI, Ollama) don't need authentication, but the provider
     * factory still needs *some* value to avoid a "key not set" error.
     *
     * @param resolvedProvider The resolved TOML provider string.
     * @return True if [PLACEHOLDER_API_KEY] should be injected.
     */
    private fun needsPlaceholderKey(resolvedProvider: String): Boolean = resolvedProvider.startsWith("custom:") || resolvedProvider == "ollama"

    /**
     * Formats a value as a quoted TOML key.
     *
     * Bare keys may only contain ASCII letters, digits, dashes, and underscores.
     * Keys containing any other characters (spaces, dots, etc.) must be quoted.
     *
     * @param key Raw key value.
     * @return The key suitable for use in a TOML table header or dotted key.
     */
    private fun tomlKey(key: String): String {
        val isBareKey =
            key.isNotEmpty() && key.all { it.isLetterOrDigit() || it == '-' || it == '_' }
        return if (isBareKey) key else tomlString(key)
    }

    internal fun tomlString(value: String): String =
        buildString {
            append('"')
            for (ch in value) {
                when {
                    ch == '\\' -> append("\\\\")
                    ch == '"' -> append("\\\"")
                    ch == '\n' -> append("\\n")
                    ch == '\r' -> append("\\r")
                    ch == '\t' -> append("\\t")
                    ch == '\b' -> append("\\b")
                    ch == '\u000C' -> append("\\f")
                    ch.code in CONTROL_RANGE_START..CONTROL_RANGE_END ||
                        ch.code == DELETE_CHAR -> {
                        append("\\u")
                        append(
                            ch.code
                                .toString(HEX_RADIX)
                                .padStart(UNICODE_PAD_LENGTH, '0'),
                        )
                    }
                    else -> append(ch)
                }
            }
            append('"')
        }

    /** Radix for hexadecimal encoding. */
    private const val HEX_RADIX = 16

    /** Pad length for Unicode escape sequences. */
    private const val UNICODE_PAD_LENGTH = 4

    /** Start of the C0 control character range. */
    private const val CONTROL_RANGE_START = 0x00

    /** End of the C0 control character range. */
    private const val CONTROL_RANGE_END = 0x1F

    /** ASCII DEL character code. */
    private const val DELETE_CHAR = 0x7F
}
