/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfigTomlSecuritySectionsTest {
    @Test
    fun `default security sections are omitted`() {
        val toml = ConfigTomlBuilder.build(minimalConfig())

        assertFalse(toml.contains("[security.sandbox]"))
        assertFalse(toml.contains("[security.resources]"))
        assertFalse(toml.contains("[security.audit]"))
        assertFalse(toml.contains("[security.otp]"))
        assertFalse(toml.contains("[security.estop]"))
        assertFalse(toml.contains("[security.webauthn]"))
    }

    @Test
    fun `custom security sections are emitted`() {
        val toml =
            ConfigTomlBuilder.build(
                minimalConfig(
                    securitySandboxEnabled = true,
                    securitySandboxBackend = "firejail",
                    securitySandboxFirejailArgs = listOf("--private", "--net=none"),
                    securityResourcesMaxMemoryMb = 512,
                    securityResourcesMaxCpuTimeSecs = 30,
                    securityResourcesMaxSubprocesses = 4,
                    securityResourcesMemoryMonitoring = false,
                    securityAuditEnabled = false,
                    securityOtpEnabled = true,
                    securityOtpMethod = "totp",
                    securityOtpGatedActions = listOf("shell", "network"),
                    securityOtpGatedDomains = listOf("example.com"),
                    securityOtpGatedDomainCategories = listOf("finance"),
                    securityEstopEnabled = true,
                    securityEstopRequireOtpToResume = true,
                    securityWebauthnEnabled = true,
                    securityWebauthnRpId = "agent.local",
                    securityWebauthnRpOrigin = "https://agent.local",
                    securityWebauthnRpName = "Zero Assist",
                ),
            )

        assertTrue(toml.contains("[security.sandbox]"))
        assertTrue(toml.contains("backend = \"firejail\""))
        assertTrue(toml.contains("firejail_args = [\"--private\", \"--net=none\"]"))
        assertTrue(toml.contains("[security.resources]"))
        assertTrue(toml.contains("max_memory_mb = 512"))
        assertTrue(toml.contains("memory_monitoring = false"))
        assertTrue(toml.contains("[security.audit]"))
        assertTrue(toml.contains("[security.otp]"))
        assertTrue(toml.contains("gated_actions = [\"shell\", \"network\"]"))
        assertTrue(toml.contains("gated_domains = [\"example.com\"]"))
        assertTrue(toml.contains("gated_domain_categories = [\"finance\"]"))
        assertTrue(toml.contains("[security.estop]"))
        assertTrue(toml.contains("require_otp_to_resume = true"))
        assertTrue(toml.contains("[security.webauthn]"))
        assertTrue(toml.contains("rp_id = \"agent.local\""))
    }

    private fun minimalConfig(
        securitySandboxEnabled: Boolean? = null,
        securitySandboxBackend: String = "auto",
        securitySandboxFirejailArgs: List<String> = emptyList(),
        securityResourcesMaxMemoryMb: Int = GlobalTomlConfig.DEFAULT_RESOURCES_MAX_MEMORY_MB,
        securityResourcesMaxCpuTimeSecs: Int = GlobalTomlConfig.DEFAULT_RESOURCES_MAX_CPU_TIME_SECS,
        securityResourcesMaxSubprocesses: Int = GlobalTomlConfig.DEFAULT_RESOURCES_MAX_SUBPROCESSES,
        securityResourcesMemoryMonitoring: Boolean = true,
        securityAuditEnabled: Boolean = true,
        securityOtpEnabled: Boolean = false,
        securityOtpMethod: String = "totp",
        securityOtpGatedActions: List<String> = emptyList(),
        securityOtpGatedDomains: List<String> = emptyList(),
        securityOtpGatedDomainCategories: List<String> = emptyList(),
        securityEstopEnabled: Boolean = false,
        securityEstopRequireOtpToResume: Boolean = false,
        securityWebauthnEnabled: Boolean = false,
        securityWebauthnRpId: String = GlobalTomlConfig.DEFAULT_WEBAUTHN_RP_ID,
        securityWebauthnRpOrigin: String = GlobalTomlConfig.DEFAULT_WEBAUTHN_RP_ORIGIN,
        securityWebauthnRpName: String = GlobalTomlConfig.DEFAULT_WEBAUTHN_RP_NAME,
    ): GlobalTomlConfig =
        GlobalTomlConfig(
            provider = "",
            model = "",
            apiKey = "",
            baseUrl = "",
            securitySandboxEnabled = securitySandboxEnabled,
            securitySandboxBackend = securitySandboxBackend,
            securitySandboxFirejailArgs = securitySandboxFirejailArgs,
            securityResourcesMaxMemoryMb = securityResourcesMaxMemoryMb,
            securityResourcesMaxCpuTimeSecs = securityResourcesMaxCpuTimeSecs,
            securityResourcesMaxSubprocesses = securityResourcesMaxSubprocesses,
            securityResourcesMemoryMonitoring = securityResourcesMemoryMonitoring,
            securityAuditEnabled = securityAuditEnabled,
            securityOtpEnabled = securityOtpEnabled,
            securityOtpMethod = securityOtpMethod,
            securityOtpGatedActions = securityOtpGatedActions,
            securityOtpGatedDomains = securityOtpGatedDomains,
            securityOtpGatedDomainCategories = securityOtpGatedDomainCategories,
            securityEstopEnabled = securityEstopEnabled,
            securityEstopRequireOtpToResume = securityEstopRequireOtpToResume,
            securityWebauthnEnabled = securityWebauthnEnabled,
            securityWebauthnRpId = securityWebauthnRpId,
            securityWebauthnRpOrigin = securityWebauthnRpOrigin,
            securityWebauthnRpName = securityWebauthnRpName,
        )
}
