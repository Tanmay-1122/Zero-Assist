/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.model.ApiKey
import com.zeroclaw.android.model.AppSettings
import java.lang.reflect.Modifier
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("SettingsTomlConfigFactory compatibility")
class SettingsTomlConfigFactoryCompatibilityTest {
    @Test
    @DisplayName("representative app settings emit the ZeroClaw Master TOML surface")
    fun `representative app settings emit master toml surface`() {
        val toml = buildToml(representativeSettings(), openAiKey())

        assertContainsAll(
            toml,
            listOf(
                """default_provider = "openai"""",
                """default_model = "gpt-4.1-mini"""",
                """api_key = "sk-test-openai"""",
                "[agent]",
                "[gateway]",
                "[memory]",
                "[identity]",
                "[cost]",
                "[reliability]",
                "[autonomy]",
                "[scheduler]",
                "[heartbeat]",
                "[observability]",
                "[[model_routes]]",
                "[composio]",
                "[browser]",
                "[workflow_folder]",
                "[shared_folder]",
                "[http_request]",
                "[proxy]",
                "[web_fetch]",
                "[web_search]",
                "[security.sandbox]",
                "[security.resources]",
                "[security.audit]",
                "[security.otp]",
                "[security.estop]",
                "[security.webauthn]",
                "[memory.qdrant]",
                "[[embedding_routes]]",

                "[skills]",
            ),
        )
        assertFalse(toml.contains("[fallback]"))
        assertFalse(toml.contains("fallback_providers"))
        assertFalse(toml.contains("[provider.anthropic]"))
        assertFalse(toml.contains("[provider.groq]"))
    }

    @Test
    @DisplayName("SAF folder fixtures enable daemon tools without leaking Android URIs")
    fun `saf folder fixtures enable daemon tools without leaking android uris`() {
        val toml =
            buildToml(
                AppSettings(
                    sharedFolderEnabled = true,
                    sharedFolderUri = "content://tree/shared-fixture",
                    workflowFolderEnabled = true,
                    workflowFolderUri = "content://tree/workflow-fixture",
                ),
            )

        assertContainsAll(
            toml,
            listOf(
                "[shared_folder]",
                "[workflow_folder]",
                "enabled = true",
            ),
        )
        assertFalse(toml.contains("content://tree/shared-fixture"))
        assertFalse(toml.contains("content://tree/workflow-fixture"))
        assertFalse(toml.contains("sharedFolderUri"))
        assertFalse(toml.contains("workflowFolderUri"))
    }

    @Test
    @DisplayName("default settings explicitly disable upstream-default web tools")
    fun `default settings explicitly disable upstream default web tools`() {
        val toml = buildToml(AppSettings())

        assertSectionContains(toml, "browser", "enabled = false")
        assertSectionContains(toml, "http_request", "enabled = false")
        assertSectionContains(toml, "web_fetch", "enabled = false")
        assertSectionContains(toml, "web_search", "enabled = false")
        assertSectionContains(toml, "workflow_folder", "enabled = true")
    }

    @Test
    @DisplayName("every app setting is config-backed or explicitly Android-only")
    fun `every app setting is classified`() {
        val actualFields =
            AppSettings::class.java.declaredFields
                .filterNot { field -> Modifier.isStatic(field.modifiers) || field.isSynthetic }
                .map { field -> field.name }
                .toSet()

        val daemonConfigFields =
            setOf(
                "host",
                "port",
                "defaultProvider",
                "defaultModel",
                "defaultTemperature",
                "compactContext",
                "costEnabled",
                "dailyLimitUsd",
                "monthlyLimitUsd",
                "costWarnAtPercent",
                "providerRetries",
                "memoryBackend",
                "memoryAutoSave",
                "identityJson",
                "autonomyLevel",
                "workspaceOnly",
                "allowedCommands",
                "forbiddenPaths",
                "maxActionsPerHour",
                "maxCostPerDayCents",
                "requireApprovalMediumRisk",
                "blockHighRiskCommands",
                "tunnelProvider",
                "tunnelCloudflareToken",
                "tunnelTailscaleFunnel",
                "tunnelTailscaleHostname",
                "tunnelNgrokAuthToken",
                "tunnelNgrokDomain",
                "tunnelCustomCommand",
                "tunnelCustomHealthUrl",
                "tunnelCustomUrlPattern",
                "gatewayRequirePairing",
                "gatewayAllowPublicBind",
                "gatewayPairedTokens",
                "gatewayPairRateLimit",
                "gatewayWebhookRateLimit",
                "gatewayIdempotencyTtl",
                "schedulerEnabled",
                "schedulerMaxTasks",
                "schedulerMaxConcurrent",
                "heartbeatEnabled",
                "heartbeatIntervalMinutes",
                "observabilityBackend",
                "observabilityOtelEndpoint",
                "observabilityOtelServiceName",
                "modelRoutesJson",
                "memoryHygieneEnabled",
                "memoryArchiveAfterDays",
                "memoryPurgeAfterDays",
                "memoryEmbeddingProvider",
                "memoryEmbeddingModel",
                "memoryVectorWeight",
                "memoryKeywordWeight",
                "composioEnabled",
                "composioApiKey",
                "composioEntityId",
                "browserEnabled",
                "browserAllowedDomains",
                "httpRequestEnabled",
                "httpRequestAllowedDomains",
                "httpRequestMaxResponseSize",
                "httpRequestTimeoutSecs",
                "webFetchEnabled",
                "webFetchAllowedDomains",
                "webFetchBlockedDomains",
                "webFetchMaxResponseSize",
                "webFetchTimeoutSecs",
                "webSearchEnabled",
                "webSearchProvider",
                "webSearchBraveApiKey",
                "webSearchMaxResults",
                "webSearchTimeoutSecs",

                "securitySandboxEnabled",
                "securitySandboxBackend",
                "securitySandboxFirejailArgs",
                "securityResourcesMaxMemoryMb",
                "securityResourcesMaxCpuTimeSecs",
                "securityResourcesMaxSubprocesses",
                "securityResourcesMemoryMonitoring",
                "securityAuditEnabled",
                "securityOtpEnabled",
                "securityOtpMethod",
                "securityOtpTokenTtlSecs",
                "securityOtpCacheValidSecs",
                "securityOtpGatedActions",
                "securityOtpGatedDomains",
                "securityOtpGatedDomainCategories",
                "securityEstopEnabled",
                "securityEstopRequireOtpToResume",
                "securityWebauthnEnabled",
                "securityWebauthnRpId",
                "securityWebauthnRpOrigin",
                "securityWebauthnRpName",
                "memoryQdrantUrl",
                "memoryQdrantCollection",
                "memoryQdrantApiKey",
                "embeddingRoutesJson",

                "skillsOpenSkillsEnabled",
                "skillsOpenSkillsDir",
                "skillsPromptInjectionMode",
                "proxyEnabled",
                "proxyHttpProxy",
                "proxyHttpsProxy",
                "proxyAllProxy",
                "proxyNoProxy",
                "proxyScope",
                "proxyServiceSelectors",
                "reliabilityBackoffMs",
                "reliabilityApiKeysJson",
                "sharedFolderEnabled",
                "workflowFolderEnabled",
            )
        val androidOnlyFields =
            setOf(
                "autoStartOnBoot",
                "lockEnabled",
                "lockTimeoutMinutes",
                "pinHash",
                "pluginRegistryUrl",
                "pluginSyncEnabled",
                "pluginSyncIntervalHours",
                "lastPluginSyncTimestamp",
                "stripThinkingTags",
                "fallbackProviders",
                "fallbackProviderConfigsJson",
                "terminalAutoDelegateEnabled",
                "voiceWakeupRequested",
                "theme",
                "sharedFolderUri",
                "workflowFolderUri",
            )
        val classifiedFields = daemonConfigFields + androidOnlyFields

        val unclassifiedFields = actualFields - classifiedFields
        val staleClassifications = classifiedFields - actualFields

        assertTrue(
            unclassifiedFields.isEmpty(),
            "Unclassified AppSettings fields: ${unclassifiedFields.sorted()}",
        )
        assertTrue(
            staleClassifications.isEmpty(),
            "Classified fields no longer in AppSettings: ${staleClassifications.sorted()}",
        )
    }

    private fun buildToml(
        settings: AppSettings,
        apiKey: ApiKey? = null,
    ): String =
        ConfigTomlBuilder.build(
            SettingsTomlConfigFactory.fromSettings(
                settings = settings,
                apiKey = apiKey,
            ),
        )

    private fun representativeSettings(): AppSettings =
        AppSettings(
            defaultProvider = "openai",
            defaultModel = "gpt-4.1-mini",
            defaultTemperature = 0.4f,
            compactContext = true,
            costEnabled = true,
            providerRetries = 4,
            fallbackProviders = "anthropic, groq",
            memoryBackend = "sqlite",
            memoryEmbeddingProvider = "openai",
            memoryEmbeddingModel = "text-embedding-3-small",
            identityJson = """{"name":"Zero Assist"}""",
            autonomyLevel = "unconstrained",
            allowedCommands = "git, gradle",
            forbiddenPaths = "/system, /proc",
            gatewayRequirePairing = true,
            gatewayPairedTokens = "pair-a, pair-b",
            heartbeatEnabled = true,
            observabilityBackend = "otel",
            observabilityOtelEndpoint = "http://127.0.0.1:4318",
            modelRoutesJson =
                """[{"hint":"code","provider":"openai","model":"gpt-4.1-mini"}]""",
            composioEnabled = true,
            composioApiKey = "ck_test_sessions_key",
            composioEntityId = "default",
            browserEnabled = true,
            browserAllowedDomains = "docs.zeroclaw.dev, example.com",
            sharedFolderEnabled = true,
            sharedFolderUri = "content://tree/shared-fixture",
            workflowFolderEnabled = true,
            workflowFolderUri = "content://tree/workflow-fixture",
            httpRequestEnabled = true,
            httpRequestAllowedDomains = "api.zeroclaw.dev",

            proxyEnabled = true,
            proxyHttpProxy = "http://proxy.local:8080",
            proxyNoProxy = "localhost, 127.0.0.1",
            webFetchEnabled = true,
            webFetchAllowedDomains = "docs.zeroclaw.dev",
            webFetchBlockedDomains = "blocked.example.com",
            webSearchEnabled = true,
            webSearchProvider = "brave",
            webSearchBraveApiKey = "brave-test-key",
            securitySandboxEnabled = false,
            securitySandboxBackend = "none",
            securityResourcesMaxMemoryMb = 1024,
            securityResourcesMaxCpuTimeSecs = 120,
            securityResourcesMaxSubprocesses = 32,
            securityResourcesMemoryMonitoring = false,
            securityAuditEnabled = false,
            securityOtpEnabled = true,
            securityOtpGatedDomains = "admin.example.com",
            securityEstopEnabled = true,
            securityWebauthnEnabled = true,
            securityWebauthnRpId = "agent.local",
            securityWebauthnRpOrigin = "https://agent.local",
            securityWebauthnRpName = "Zero Assist",
            memoryQdrantUrl = "http://qdrant.local:6333",
            memoryQdrantApiKey = "qdrant-test-key",
            embeddingRoutesJson =
                """[{"hint":"memory","provider":"openai","model":"text-embedding-3-small","dimensions":1536}]""",

            skillsOpenSkillsEnabled = true,
            skillsOpenSkillsDir = "/data/local/tmp/open-skills",
            skillsPromptInjectionMode = "compact",
            reliabilityBackoffMs = 1_000,
            reliabilityApiKeysJson = """{"openai":"sk-route-a","groq":"gsk-route-b"}""",
        )

    private fun openAiKey(): ApiKey =
        ApiKey(
            id = "key-openai",
            provider = "openai",
            key = "sk-test-openai",
        )

    private fun assertContainsAll(
        value: String,
        expectedFragments: List<String>,
    ) {
        for (fragment in expectedFragments) {
            assertTrue(value.contains(fragment), "Missing TOML fragment: $fragment")
        }
    }

    private fun assertSectionContains(
        toml: String,
        section: String,
        expectedFragment: String,
    ) {
        val body =
            toml
                .substringAfter("[$section]", missingDelimiterValue = "")
                .substringBefore("\n[")
        assertTrue(body.contains(expectedFragment), "Section [$section] missing $expectedFragment")
    }
}
