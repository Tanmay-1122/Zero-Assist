/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.capability

import com.zeroclaw.android.diagnostics.RichRuntimeDiagnostics
import com.zeroclaw.android.media.MediaCategory
import com.zeroclaw.android.media.MediaSearchTool
import java.util.concurrent.ConcurrentHashMap

/**
 * Security metadata levels for capabilities.
 */
enum class SecurityLevel {
    SAFE,
    LOCAL_ONLY,
    NETWORK,
    FILESYSTEM,
    DEVICE_CONTROL,
    ROOT_REQUIRED,
}

/**
 * Abstract Capability definition.
 */
data class Capability(
    val id: String, // e.g., "IMAGE_SEARCH", "GITHUB", "DEVICE_CONTROL", "CALENDAR"
    val version: Int = 1,
    val description: String,
    val securityLevel: SecurityLevel = SecurityLevel.NETWORK,
    val supportsStreaming: Boolean = false,
    val supportsOffline: Boolean = false,
)

/**
 * Contract for capability execution providers.
 */
interface CapabilityProvider {
    val providerId: String
    val capabilityId: String
    val priority: Int // Higher value indicates higher preference
    val isAvailable: Boolean

    suspend fun execute(parametersJson: String): String
}

/**
 * Result of a capability resolution attempt.
 */
sealed interface ResolutionResult {
    data class Success(
        val providerId: String,
        val capabilityId: String,
        val outputJson: String,
    ) : ResolutionResult

    data class Failure(
        val capabilityId: String,
        val reason: String,
    ) : ResolutionResult
}

/**
 * Dynamic registry storing capabilities and mapped execution providers.
 */
object CapabilityRegistry {
    private val capabilities = ConcurrentHashMap<String, Capability>()
    private val providers = ConcurrentHashMap<String, MutableList<CapabilityProvider>>()

    init {
        // Register Core Default Capabilities
        registerCapability(
            Capability(
                id = "IMAGE_SEARCH",
                description = "Discover and search image assets and metadata",
                securityLevel = SecurityLevel.NETWORK,
            )
        )
        registerCapability(
            Capability(
                id = "DEVICE_CONTROL",
                description = "Perform native device operations and control settings",
                securityLevel = SecurityLevel.DEVICE_CONTROL,
                supportsOffline = true,
            )
        )
        registerCapability(
            Capability(
                id = "GITHUB",
                description = "Access GitHub repositories, pull requests, and diffs",
                securityLevel = SecurityLevel.NETWORK,
            )
        )

        // Auto-register Default Native Providers
        registerProvider(object : CapabilityProvider {
            override val providerId: String = "native_media_search_tool"
            override val capabilityId: String = "IMAGE_SEARCH"
            override val priority: Int = 10
            override val isAvailable: Boolean = true

            override suspend fun execute(parametersJson: String): String {
                return MediaSearchTool.searchMedia(parametersJson, MediaCategory.IMAGE)
            }
        })
    }

    fun registerCapability(capability: Capability) {
        capabilities[capability.id] = capability
    }

    fun registerProvider(provider: CapabilityProvider) {
        val list = providers.computeIfAbsent(provider.capabilityId) { mutableListOf() }
        synchronized(list) {
            list.removeIf { it.providerId == provider.providerId }
            list.add(provider)
            list.sortByDescending { it.priority }
        }
    }

    fun getCapability(capabilityId: String): Capability? {
        return capabilities[capabilityId]
    }

    fun getProviders(capabilityId: String): List<CapabilityProvider> {
        return providers[capabilityId]?.toList() ?: emptyList()
    }

    fun getAllCapabilities(): List<Capability> {
        return capabilities.values.toList()
    }
}

/**
 * Capability Resolver negotiating providers, enforcing security, and managing fallback chains.
 */
object CapabilityResolver {

    /**
     * Resolves and executes the optimal provider for a requested [capabilityId].
     */
    suspend fun resolveAndExecute(
        capabilityId: String,
        parametersJson: String,
        grantedSecurityLevel: SecurityLevel = SecurityLevel.DEVICE_CONTROL,
    ): ResolutionResult {
        val capability = CapabilityRegistry.getCapability(capabilityId)
            ?: return ResolutionResult.Failure(capabilityId, "Capability not registered: $capabilityId")

        // Enforce security level
        if (capability.securityLevel.ordinal > grantedSecurityLevel.ordinal) {
            RichRuntimeDiagnostics.record("SECURITY", "Denied execution for $capabilityId: Security level exceeded")
            return ResolutionResult.Failure(capabilityId, "Security permission denied for $capabilityId")
        }

        val availableProviders = CapabilityRegistry.getProviders(capabilityId)
            .filter { it.isAvailable }

        if (availableProviders.isEmpty()) {
            return ResolutionResult.Failure(capabilityId, "No active provider available for $capabilityId")
        }

        // Automatic Fallback Chain Execution
        var lastExceptionMessage: String? = null
        for (provider in availableProviders) {
            try {
                RichRuntimeDiagnostics.record("CAPABILITY_RESOLVER", "Selected provider ${provider.providerId} for $capabilityId")
                val resultJson = provider.execute(parametersJson)
                return ResolutionResult.Success(
                    providerId = provider.providerId,
                    capabilityId = capabilityId,
                    outputJson = resultJson,
                )
            } catch (e: Exception) {
                lastExceptionMessage = e.message ?: "Unknown error"
                RichRuntimeDiagnostics.record("CAPABILITY_FALLBACK", "Provider ${provider.providerId} failed: $lastExceptionMessage. Falling back...")
            }
        }

        return ResolutionResult.Failure(capabilityId, "All providers failed for $capabilityId. Last error: $lastExceptionMessage")
    }
}
