/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.capability

import com.zeroclaw.android.service.RichPipelineFeatureFlags
import com.zeroclaw.android.service.RichPromptEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CapabilityResolutionEngineTest {

    @Test
    fun testCapabilityRegistrationAndLookup() {
        val cap = CapabilityRegistry.getCapability("IMAGE_SEARCH")
        assertNotNull(cap)
        assertEquals("IMAGE_SEARCH", cap!!.id)

        val providers = CapabilityRegistry.getProviders("IMAGE_SEARCH")
        assertTrue(providers.isNotEmpty())
        assertEquals("native_media_search_tool", providers.first().providerId)
    }

    @Test
    fun testPriorityResolutionAndExecution() = runBlocking {
        // Register secondary higher-priority provider
        val highPrioProvider = object : CapabilityProvider {
            override val providerId: String = "high_prio_image_provider"
            override val capabilityId: String = "IMAGE_SEARCH"
            override val priority: Int = 100
            override val isAvailable: Boolean = true

            override suspend fun execute(parametersJson: String): String {
                return "{\"results\":[{\"type\":\"image\",\"url\":\"https://highprio.com/img.jpg\",\"title\":\"High Prio\"}]}"
            }
        }
        CapabilityRegistry.registerProvider(highPrioProvider)

        val result = CapabilityResolver.resolveAndExecute("IMAGE_SEARCH", "Saturn")
        assertTrue(result is ResolutionResult.Success)

        val success = result as ResolutionResult.Success
        assertEquals("high_prio_image_provider", success.providerId)
        assertTrue(success.outputJson.contains("highprio.com"))
    }

    @Test
    fun testAutomaticFallbackChain() = runBlocking {
        // Register failing high-priority provider
        val failingProvider = object : CapabilityProvider {
            override val providerId: String = "failing_provider"
            override val capabilityId: String = "FALLBACK_TEST_CAP"
            override val priority: Int = 100
            override val isAvailable: Boolean = true

            override suspend fun execute(parametersJson: String): String {
                throw RuntimeException("Network timeout error")
            }
        }
        val fallbackProvider = object : CapabilityProvider {
            override val providerId: String = "fallback_provider"
            override val capabilityId: String = "FALLBACK_TEST_CAP"
            override val priority: Int = 50
            override val isAvailable: Boolean = true

            override suspend fun execute(parametersJson: String): String {
                return "{\"ok\":true}"
            }
        }

        CapabilityRegistry.registerCapability(
            Capability(id = "FALLBACK_TEST_CAP", description = "Fallback testing capability")
        )
        CapabilityRegistry.registerProvider(failingProvider)
        CapabilityRegistry.registerProvider(fallbackProvider)

        val result = CapabilityResolver.resolveAndExecute("FALLBACK_TEST_CAP", "params")
        assertTrue(result is ResolutionResult.Success)

        val success = result as ResolutionResult.Success
        assertEquals("fallback_provider", success.providerId)
    }

    @Test
    fun testSecurityLevelEnforcement() = runBlocking {
        CapabilityRegistry.registerCapability(
            Capability(
                id = "ROOT_CAP",
                description = "Root required test cap",
                securityLevel = SecurityLevel.ROOT_REQUIRED,
            )
        )

        val result = CapabilityResolver.resolveAndExecute(
            capabilityId = "ROOT_CAP",
            parametersJson = "{}",
            grantedSecurityLevel = SecurityLevel.NETWORK,
        )

        assertTrue(result is ResolutionResult.Failure)
        val failure = result as ResolutionResult.Failure
        assertTrue(failure.reason.contains("Security permission denied"))
    }

    @Test
    fun testPromptEngineIncludesAbstractCapabilities() {
        RichPipelineFeatureFlags.setMode(com.zeroclaw.android.service.PipelineMode.RICH)
        val systemPrompt = RichPromptEngine.buildSystemPrompt("Base prompt")
        assertTrue(systemPrompt.contains("IMAGE_SEARCH"))
        assertTrue(systemPrompt.contains("DEVICE_CONTROL"))
        assertTrue(systemPrompt.contains("GITHUB"))
    }
}
