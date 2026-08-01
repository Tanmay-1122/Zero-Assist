/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

internal object ConfigTomlSecuritySections {
    fun appendTo(
        builder: StringBuilder,
        config: GlobalTomlConfig,
    ) {
        with(builder) {
            appendSandboxSection(config)
            appendResourcesSection(config)
            appendAuditSection(config)
            appendOtpSection(config)
            appendEstopSection(config)
            appendWebauthnSection(config)
        }
    }

    private fun StringBuilder.appendSandboxSection(config: GlobalTomlConfig) {
        val hasEnabled = config.securitySandboxEnabled != null
        val hasBackend = config.securitySandboxBackend != "auto"
        val hasArgs = config.securitySandboxFirejailArgs.isNotEmpty()
        if (!hasEnabled && !hasBackend && !hasArgs) return

        appendLine()
        appendLine("[security.sandbox]")
        if (hasEnabled) {
            appendLine("enabled = ${config.securitySandboxEnabled}")
        }
        if (hasBackend) {
            appendLine("backend = ${tomlString(config.securitySandboxBackend)}")
        }
        if (hasArgs) {
            val list = config.securitySandboxFirejailArgs.joinToString(", ") { tomlString(it) }
            appendLine("firejail_args = [$list]")
        }
    }

    private fun StringBuilder.appendResourcesSection(config: GlobalTomlConfig) {
        val hasCustomMemory =
            config.securityResourcesMaxMemoryMb != GlobalTomlConfig.DEFAULT_RESOURCES_MAX_MEMORY_MB
        val hasCustomCpu =
            config.securityResourcesMaxCpuTimeSecs != GlobalTomlConfig.DEFAULT_RESOURCES_MAX_CPU_TIME_SECS
        val hasCustomSubproc =
            config.securityResourcesMaxSubprocesses != GlobalTomlConfig.DEFAULT_RESOURCES_MAX_SUBPROCESSES
        val hasCustomMonitoring = !config.securityResourcesMemoryMonitoring
        if (!(hasCustomMemory || hasCustomCpu || hasCustomSubproc || hasCustomMonitoring)) return

        appendLine()
        appendLine("[security.resources]")
        appendLine("max_memory_mb = ${config.securityResourcesMaxMemoryMb.coerceAtLeast(0)}")
        appendLine("max_cpu_time_seconds = ${config.securityResourcesMaxCpuTimeSecs.coerceAtLeast(0)}")
        appendLine("max_subprocesses = ${config.securityResourcesMaxSubprocesses.coerceAtLeast(0)}")
        appendLine("memory_monitoring = ${config.securityResourcesMemoryMonitoring}")
    }

    private fun StringBuilder.appendAuditSection(config: GlobalTomlConfig) {
        if (config.securityAuditEnabled) return

        appendLine()
        appendLine("[security.audit]")
        appendLine("enabled = false")
    }

    private fun StringBuilder.appendOtpSection(config: GlobalTomlConfig) {
        if (!config.securityOtpEnabled) return

        appendLine()
        appendLine("[security.otp]")
        appendLine("enabled = true")
        appendLine("method = ${tomlString(config.securityOtpMethod)}")
        appendLine("token_ttl_secs = ${config.securityOtpTokenTtlSecs.coerceAtLeast(0)}")
        appendLine("cache_valid_secs = ${config.securityOtpCacheValidSecs.coerceAtLeast(0)}")
        appendStringList("gated_actions", config.securityOtpGatedActions)
        appendStringList("gated_domains", config.securityOtpGatedDomains)
        appendStringList("gated_domain_categories", config.securityOtpGatedDomainCategories)
    }

    private fun StringBuilder.appendEstopSection(config: GlobalTomlConfig) {
        if (!config.securityEstopEnabled) return

        appendLine()
        appendLine("[security.estop]")
        appendLine("enabled = true")
        appendLine("require_otp_to_resume = ${config.securityEstopRequireOtpToResume}")
    }

    private fun StringBuilder.appendWebauthnSection(config: GlobalTomlConfig) {
        val hasCustomRpId = config.securityWebauthnRpId != GlobalTomlConfig.DEFAULT_WEBAUTHN_RP_ID
        val hasCustomRpOrigin =
            config.securityWebauthnRpOrigin != GlobalTomlConfig.DEFAULT_WEBAUTHN_RP_ORIGIN
        val hasCustomRpName =
            config.securityWebauthnRpName != GlobalTomlConfig.DEFAULT_WEBAUTHN_RP_NAME
        val hasAnyCustom = hasCustomRpId || hasCustomRpOrigin || hasCustomRpName
        if (!config.securityWebauthnEnabled && !hasAnyCustom) return

        appendLine()
        appendLine("[security.webauthn]")
        appendLine("enabled = ${config.securityWebauthnEnabled}")
        appendLine("rp_id = ${tomlString(config.securityWebauthnRpId)}")
        appendLine("rp_origin = ${tomlString(config.securityWebauthnRpOrigin)}")
        appendLine("rp_name = ${tomlString(config.securityWebauthnRpName)}")
    }

    private fun StringBuilder.appendStringList(
        key: String,
        values: List<String>,
    ) {
        if (values.isEmpty()) return

        val list = values.joinToString(", ") { tomlString(it) }
        appendLine("$key = [$list]")
    }

    private fun tomlString(value: String): String = ConfigTomlBuilder.tomlString(value)
}
