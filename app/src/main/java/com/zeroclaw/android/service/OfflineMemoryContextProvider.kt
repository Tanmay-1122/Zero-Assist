/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.data.repository.SettingsRepository
import kotlinx.coroutines.flow.first

/** Supplies the optional, tightly bounded memory context used by Offline Mode. */
fun interface OfflineMemoryContextProvider {
    /** Returns formatted memory relevant to [query], or null when memory is disabled/unavailable. */
    suspend fun contextFor(query: String): String?
}

/**
 * Reads only local memory for Offline Mode.
 *
 * Remote memory backends and external embedding providers are deliberately skipped so enabling
 * memory here cannot turn a local-model request into a network request.
 */
class SettingsBackedOfflineMemoryContextProvider(
    private val settingsRepository: SettingsRepository,
    private val memoryBridge: MemoryBridge,
) : OfflineMemoryContextProvider {
    override suspend fun contextFor(query: String): String? {
        val settings = settingsRepository.settings.first()
        if (!settings.offlineModeMemoryEnabled || query.isBlank()) return null
        if (settings.memoryBackend !in LOCAL_MEMORY_BACKENDS) return null
        if (settings.memoryEmbeddingProvider != "none") return null

        return runCatching {
            memoryBridge
                .recallMemory(query = query, limit = MAX_MEMORY_ENTRIES.toUInt())
                .mapNotNull { entry ->
                    entry.content.trim().takeIf { it.isNotEmpty() }?.let { content ->
                        "- $content"
                    }
                }
                .take(MAX_MEMORY_ENTRIES)
                .joinToString("\n")
                .takeIf { it.isNotBlank() }
        }.getOrNull()
    }

    private companion object {
        private const val MAX_MEMORY_ENTRIES = 8
        private val LOCAL_MEMORY_BACKENDS = setOf("sqlite", "markdown", "lucid")
    }
}
