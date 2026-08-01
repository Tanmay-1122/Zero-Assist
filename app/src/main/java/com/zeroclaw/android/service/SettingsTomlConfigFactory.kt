package com.zeroclaw.android.service

import com.zeroclaw.android.model.ApiKey
import com.zeroclaw.android.model.AppSettings
import com.zeroclaw.android.model.McpServerEntry
import kotlinx.serialization.json.Json

object SettingsTomlConfigFactory {
    @Suppress("LongMethod")
    fun fromSettings(
        settings: AppSettings,
        apiKey: ApiKey?,
        apiKeyValue: String = apiKey?.key.orEmpty(),
    ): GlobalTomlConfig =
        GlobalTomlConfig(
            provider = settings.defaultProvider,
            model = settings.defaultModel,
            apiKey = apiKeyValue,
            baseUrl = apiKey?.baseUrl.orEmpty(),
            temperature = settings.defaultTemperature,
            compactContext = settings.compactContext,
            maxToolIterations = settings.maxToolIterations,
            costEnabled = settings.costEnabled,
            dailyLimitUsd = settings.dailyLimitUsd,
            monthlyLimitUsd = settings.monthlyLimitUsd,
            costWarnAtPercent = settings.costWarnAtPercent,
            providerRetries = settings.providerRetries,
            fallbackProviders = emptyList(),
            fallbackProviderConfigs = emptyList(),
            memoryBackend = settings.memoryBackend,
            memoryAutoSave = settings.memoryAutoSave,
            identityJson = settings.identityJson,
            autonomyLevel = GlobalTomlConfig.mapUiAutonomyLevel(settings.autonomyLevel),
            workspaceOnly = settings.workspaceOnly,
            allowedCommands = splitCsv(settings.allowedCommands),
            forbiddenPaths = splitCsv(settings.forbiddenPaths),
            maxActionsPerHour = settings.maxActionsPerHour,
            maxCostPerDayCents = settings.maxCostPerDayCents,
            requireApprovalMediumRisk = settings.requireApprovalMediumRisk,
            blockHighRiskCommands = settings.blockHighRiskCommands,
            tunnelProvider = settings.tunnelProvider,
            tunnelCloudflareToken = settings.tunnelCloudflareToken,
            tunnelTailscaleFunnel = settings.tunnelTailscaleFunnel,
            tunnelTailscaleHostname = settings.tunnelTailscaleHostname,
            tunnelNgrokAuthToken = settings.tunnelNgrokAuthToken,
            tunnelNgrokDomain = settings.tunnelNgrokDomain,
            tunnelCustomCommand = settings.tunnelCustomCommand,
            tunnelCustomHealthUrl = settings.tunnelCustomHealthUrl,
            tunnelCustomUrlPattern = settings.tunnelCustomUrlPattern,
            gatewayHost = settings.host,
            gatewayPort = settings.port,
            gatewayRequirePairing = settings.gatewayRequirePairing,
            gatewayAllowPublicBind = settings.gatewayAllowPublicBind,
            gatewayWebDistDir = settings.gatewayWebDistDir,
            gatewayPairedTokens = splitCsv(settings.gatewayPairedTokens),
            gatewayPairRateLimit = settings.gatewayPairRateLimit,
            gatewayWebhookRateLimit = settings.gatewayWebhookRateLimit,
            gatewayIdempotencyTtl = settings.gatewayIdempotencyTtl,
            schedulerEnabled = settings.schedulerEnabled,
            schedulerMaxTasks = settings.schedulerMaxTasks,
            schedulerMaxConcurrent = settings.schedulerMaxConcurrent,
            heartbeatEnabled = settings.heartbeatEnabled,
            heartbeatIntervalMinutes = settings.heartbeatIntervalMinutes,
            observabilityBackend = settings.observabilityBackend,
            observabilityOtelEndpoint = settings.observabilityOtelEndpoint,
            observabilityOtelServiceName = settings.observabilityOtelServiceName,
            modelRoutesJson = settings.modelRoutesJson,
            memoryHygieneEnabled = settings.memoryHygieneEnabled,
            memoryArchiveAfterDays = settings.memoryArchiveAfterDays,
            memoryPurgeAfterDays = settings.memoryPurgeAfterDays,
            memoryEmbeddingProvider = settings.memoryEmbeddingProvider,
            memoryEmbeddingModel = settings.memoryEmbeddingModel,
            memoryVectorWeight = settings.memoryVectorWeight,
            memoryKeywordWeight = settings.memoryKeywordWeight,
            composioEnabled = settings.composioEnabled,
            composioApiKey = settings.composioApiKey,
            composioEntityId = settings.composioEntityId,
            mcpEnabled = settings.mcpEnabled,
            mcpDeferredLoading = false, // Force false until deferred loading is fully working
            mcpServers = parseMcpServers(settings.mcpServersJson),
            browserEnabled = settings.browserEnabled,
            browserAllowedDomains = splitCsv(settings.browserAllowedDomains),
            sharedFolderEnabled = settings.sharedFolderEnabled,
            sharedFolderUri = settings.sharedFolderUri,
            workflowFolderEnabled = settings.workflowFolderEnabled,
            workflowFolderUri = settings.workflowFolderUri,
            httpRequestEnabled = settings.httpRequestEnabled,
            httpRequestAllowedDomains = splitCsv(settings.httpRequestAllowedDomains),
            httpRequestMaxResponseSize = settings.httpRequestMaxResponseSize,
            httpRequestTimeoutSecs = settings.httpRequestTimeoutSecs,
            webFetchEnabled = settings.webFetchEnabled,
            webFetchAllowedDomains = splitCsv(settings.webFetchAllowedDomains),
            webFetchBlockedDomains = splitCsv(settings.webFetchBlockedDomains),
            webFetchMaxResponseSize = settings.webFetchMaxResponseSize,
            webFetchTimeoutSecs = settings.webFetchTimeoutSecs,
            webSearchEnabled = settings.webSearchEnabled,
            webSearchProvider = settings.webSearchProvider,
            webSearchBraveApiKey = settings.webSearchBraveApiKey,
            webSearchMaxResults = settings.webSearchMaxResults,
            webSearchTimeoutSecs = settings.webSearchTimeoutSecs,

            securitySandboxEnabled = settings.securitySandboxEnabled,
            securitySandboxBackend = settings.securitySandboxBackend,
            securitySandboxFirejailArgs = splitCsv(settings.securitySandboxFirejailArgs),
            securityResourcesMaxMemoryMb = settings.securityResourcesMaxMemoryMb,
            securityResourcesMaxCpuTimeSecs = settings.securityResourcesMaxCpuTimeSecs,
            securityResourcesMaxSubprocesses = settings.securityResourcesMaxSubprocesses,
            securityResourcesMemoryMonitoring = settings.securityResourcesMemoryMonitoring,
            securityAuditEnabled = settings.securityAuditEnabled,
            securityOtpEnabled = settings.securityOtpEnabled,
            securityOtpMethod = settings.securityOtpMethod,
            securityOtpTokenTtlSecs = settings.securityOtpTokenTtlSecs,
            securityOtpCacheValidSecs = settings.securityOtpCacheValidSecs,
            securityOtpGatedActions = splitCsv(settings.securityOtpGatedActions),
            securityOtpGatedDomains = splitCsv(settings.securityOtpGatedDomains),
            securityOtpGatedDomainCategories = splitCsv(settings.securityOtpGatedDomainCategories),
            securityEstopEnabled = settings.securityEstopEnabled,
            securityEstopRequireOtpToResume = settings.securityEstopRequireOtpToResume,
            securityWebauthnEnabled = settings.securityWebauthnEnabled,
            securityWebauthnRpId = settings.securityWebauthnRpId,
            securityWebauthnRpOrigin = settings.securityWebauthnRpOrigin,
            securityWebauthnRpName = settings.securityWebauthnRpName,
            memoryQdrantUrl = settings.memoryQdrantUrl,
            memoryQdrantCollection = settings.memoryQdrantCollection,
            memoryQdrantApiKey = settings.memoryQdrantApiKey,
            embeddingRoutesJson = settings.embeddingRoutesJson,

            skillsOpenSkillsEnabled = settings.skillsOpenSkillsEnabled,
            skillsOpenSkillsDir = settings.skillsOpenSkillsDir,
            skillsPromptInjectionMode = settings.skillsPromptInjectionMode,
            proxyEnabled = settings.proxyEnabled,
            proxyHttpProxy = settings.proxyHttpProxy,
            proxyHttpsProxy = settings.proxyHttpsProxy,
            proxyAllProxy = settings.proxyAllProxy,
            proxyNoProxy = splitCsv(settings.proxyNoProxy),
            proxyScope = settings.proxyScope,
            proxyServiceSelectors = splitCsv(settings.proxyServiceSelectors),
            reliabilityBackoffMs = settings.reliabilityBackoffMs,
            reliabilityApiKeysJson = settings.reliabilityApiKeysJson,
            googleWorkspaceEnabled = settings.googleWorkspaceEnabled,
            googleWorkspaceAllowedServices = splitCsv(settings.googleWorkspaceAllowedServices),
            googleWorkspaceAuditLog = settings.googleWorkspaceAuditLog,
            prootBrowserEnabled = settings.prootBrowserEnabled,
            prootBrowserDistro = settings.prootBrowserDistro,
            prootBrowserBackend = settings.prootBrowserBackend,
            prootBrowserSessionName = settings.prootBrowserSessionName,
            prootBrowserChromeDriverPort = settings.prootBrowserChromeDriverPort,
            prootBrowserAllowedDomains = splitCsv(settings.prootBrowserAllowedDomains),
            prootBrowserMaxActionsPerHour = settings.prootBrowserMaxActionsPerHour,
        )

    private fun splitCsv(csv: String): List<String> =
        csv
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private val json = Json { ignoreUnknownKeys = true }

    private fun parseMcpServers(jsonString: String): List<McpServerEntry> =
        if (jsonString.isBlank() || jsonString == "[]") {
            emptyList()
        } else {
            try {
                json.decodeFromString<List<McpServerEntry>>(jsonString)
            } catch (_: Exception) {
                emptyList()
            }
        }
}
