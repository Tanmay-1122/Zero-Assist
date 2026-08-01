/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.memory

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zeroclaw.android.ZeroClawApplication
import com.zeroclaw.android.data.repository.AdvancedMemoryRepository
import com.zeroclaw.android.model.MemoryConsolidationConfig
import com.zeroclaw.android.model.MemoryConsolidationResult
import com.zeroclaw.android.model.MemoryFact
import com.zeroclaw.android.model.MemoryHealthStats
import com.zeroclaw.android.model.MemoryRetrievalResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for advanced memory browsing, search, and management.
 *
 * Coordinates semantic search, importance-based filtering, and consolidation.
 * Exposes memory state and health metrics for UI consumption.
 *
 * @param application Application context.
 */
class AdvancedMemoryViewModel(application: Application) : AndroidViewModel(application) {
    private val memoryRepository: AdvancedMemoryRepository =
        (application as ZeroClawApplication).advancedMemoryRepository

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _searchResults = MutableStateFlow<List<MemoryRetrievalResult>>(emptyList())
    val searchResults: StateFlow<List<MemoryRetrievalResult>> = _searchResults.asStateFlow()

    private val _allFacts = MutableStateFlow<List<MemoryFact>>(emptyList())
    val allFacts: StateFlow<List<MemoryFact>> = _allFacts.asStateFlow()

    private val _memoryHealth = MutableStateFlow<MemoryHealthStats?>(null)
    val memoryHealth: StateFlow<MemoryHealthStats?> = _memoryHealth.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _consolidationInProgress = MutableStateFlow(false)
    val consolidationInProgress: StateFlow<Boolean> = _consolidationInProgress.asStateFlow()

    private val _lastConsolidationResult = MutableStateFlow<MemoryConsolidationResult?>(null)
    val lastConsolidationResult: StateFlow<MemoryConsolidationResult?> =
        _lastConsolidationResult.asStateFlow()

    private val _selectedFact = MutableStateFlow<MemoryFact?>(null)
    val selectedFact: StateFlow<MemoryFact?> = _selectedFact.asStateFlow()

    private val _relatedFacts = MutableStateFlow<List<MemoryRetrievalResult>>(emptyList())
    val relatedFacts: StateFlow<List<MemoryRetrievalResult>> = _relatedFacts.asStateFlow()

    init {
        observeFacts()
        refreshHealthStats()
    }

    /**
     * Perform semantic search for memory facts.
     *
     * @param query Search query text.
     * @param workspaceId Workspace to search within.
     */
    fun performSemanticSearch(query: String, workspaceId: String = "default") {
        val normalizedQuery = query.trim()
        _searchQuery.value = normalizedQuery
        _isSearching.value = true

        viewModelScope.launch {
            try {
                _searchResults.value =
                    if (normalizedQuery.isBlank()) {
                        _allFacts.value.map { fact ->
                            MemoryRetrievalResult(
                                fact = fact,
                                relevanceScore = fact.importance,
                                retrievalMethod = "importance",
                            )
                        }
                    } else {
                        semanticSearchWithKeywordFallback(
                            query = normalizedQuery,
                            workspaceId = workspaceId,
                        )
                    }
            } finally {
                _isSearching.value = false
            }
        }
    }

    /**
     * Keyword search for specific tags or terms.
     *
     * @param keywords Search keywords.
     * @param workspaceId Workspace to search.
     */
    fun performKeywordSearch(keywords: List<String>, workspaceId: String = "default") {
        _isSearching.value = true

        viewModelScope.launch {
            try {
                val normalizedKeywords = keywords.map { it.trim() }.filter { it.isNotBlank() }
                val results =
                    if (normalizedKeywords.isEmpty()) {
                        emptyList()
                    } else {
                        memoryRepository.keywordSearch(
                            keywords = normalizedKeywords,
                            workspaceId = workspaceId,
                            limit = SEARCH_LIMIT,
                        )
                    }
                _searchResults.value = results.toSearchResults("keyword_search")
            } finally {
                _isSearching.value = false
            }
        }
    }

    /**
     * Fetch and update memory health statistics.
     *
     * @param workspaceId Workspace to analyze.
     */
    fun refreshHealthStats(workspaceId: String = "default") {
        viewModelScope.launch {
            try {
                _memoryHealth.value = memoryRepository.getHealthStats(workspaceId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Select a fact to view details and related memories.
     *
     * @param fact The fact to select.
     */
    fun selectFact(fact: MemoryFact) {
        _selectedFact.value = fact

        viewModelScope.launch {
            try {
                _relatedFacts.value =
                    memoryRepository.getRelatedFacts(
                        factId = fact.id,
                        depth = RELATED_FACT_DEPTH,
                    )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Record that a fact was accessed, updating its importance.
     *
     * @param factId The fact that was accessed.
     */
    fun recordFactAccess(factId: String) {
        viewModelScope.launch {
            try {
                memoryRepository.recordAccess(factId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Delete a memory fact.
     *
     * @param factId The fact to delete.
     */
    fun deleteMemoryFact(factId: String) {
        viewModelScope.launch {
            try {
                memoryRepository.deleteFact(factId)
                if (_selectedFact.value?.id == factId) {
                    _selectedFact.value = null
                    _relatedFacts.value = emptyList()
                }
                refreshHealthStats()
                refreshCurrentSearch()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Run memory consolidation to clean up and optimize.
     *
     * @param workspaceId Workspace to consolidate.
     * @param config Consolidation settings.
     */
    fun runConsolidation(
        workspaceId: String = "default",
        config: MemoryConsolidationConfig = MemoryConsolidationConfig(),
    ) {
        _consolidationInProgress.value = true

        viewModelScope.launch {
            try {
                _lastConsolidationResult.value =
                    memoryRepository.consolidateMemory(
                        workspaceId = workspaceId,
                        config = config,
                    )

                // Refresh stats and facts after consolidation
                refreshHealthStats(workspaceId)
                refreshCurrentSearch(workspaceId)
            } finally {
                _consolidationInProgress.value = false
            }
        }
    }

    /**
     * Update importance score for a fact.
     *
     * @param factId The fact to update.
     * @param newImportance New importance score (0.0-1.0).
     */
    fun updateFactImportance(factId: String, newImportance: Float) {
        viewModelScope.launch {
            try {
                memoryRepository.updateImportance(factId, newImportance)
                refreshHealthStats()
                refreshCurrentSearch()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Clear all memory facts in a workspace.
     *
     * @param workspaceId Workspace to clear.
     */
    fun clearMemory(workspaceId: String = "default") {
        viewModelScope.launch {
            try {
                memoryRepository.clearWorkspace(workspaceId)
                _allFacts.value = emptyList()
                _searchResults.value = emptyList()
                _selectedFact.value = null
                _relatedFacts.value = emptyList()
                refreshHealthStats(workspaceId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Export memory facts as JSON for backup.
     *
     * @param workspaceId Workspace to export.
     * @return JSON string.
     */
    suspend fun exportMemory(workspaceId: String = "default"): String {
        return try {
            memoryRepository.exportFactsAsJson(workspaceId)
        } catch (e: Exception) {
            "{}"
        }
    }

    /**
     * Import memory facts from JSON.
     *
     * @param json JSON data to import.
     * @param workspaceId Workspace to import into.
     */
    fun importMemory(json: String, workspaceId: String = "default") {
        viewModelScope.launch {
            try {
                memoryRepository.importFactsFromJson(
                    json = json,
                    workspaceId = workspaceId,
                    mergeStrategy = "merge",
                )
                refreshHealthStats(workspaceId)
                refreshCurrentSearch(workspaceId)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun observeFacts(workspaceId: String = "default") {
        viewModelScope.launch {
            memoryRepository.observeFactsByWorkspace(workspaceId).collectLatest { facts ->
                _allFacts.value = facts
                if (_searchQuery.value.isBlank()) {
                    _searchResults.value = facts.toSearchResults("importance")
                }
            }
        }
    }

    private suspend fun semanticSearchWithKeywordFallback(
        query: String,
        workspaceId: String,
    ): List<MemoryRetrievalResult> =
        withContext(Dispatchers.IO) {
            val semanticResults =
                memoryRepository.semanticSearch(
                    query = query,
                    workspaceId = workspaceId,
                    limit = SEARCH_LIMIT,
                    minRelevance = MIN_RELEVANCE,
                )
            if (semanticResults.isNotEmpty()) {
                semanticResults
            } else {
                memoryRepository.keywordSearch(
                    keywords = query.split(Regex("\\s+")).filter { it.isNotBlank() },
                    workspaceId = workspaceId,
                    limit = SEARCH_LIMIT,
                ).toSearchResults("keyword_fallback")
            }
        }

    private suspend fun refreshCurrentSearch(workspaceId: String = "default") {
        val query = _searchQuery.value
        _searchResults.value =
            if (query.isBlank()) {
                _allFacts.value.toSearchResults("importance")
            } else {
                semanticSearchWithKeywordFallback(query, workspaceId)
            }
    }

    private fun List<MemoryFact>.toSearchResults(retrievalMethod: String): List<MemoryRetrievalResult> =
        map { fact ->
            MemoryRetrievalResult(
                fact = fact,
                relevanceScore = fact.importance,
                retrievalMethod = retrievalMethod,
            )
        }

    private companion object {
        private const val SEARCH_LIMIT = 20
        private const val MIN_RELEVANCE = 0.3f
        private const val RELATED_FACT_DEPTH = 2
    }
}
