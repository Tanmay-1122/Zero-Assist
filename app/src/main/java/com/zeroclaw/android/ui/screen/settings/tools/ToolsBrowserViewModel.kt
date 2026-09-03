// Copyright 2026 Zero-Assist Community, MIT License

package com.zeroclaw.android.ui.screen.settings.tools

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zeroclaw.android.ZeroClawApplication
import com.zeroclaw.android.model.ComposioReadiness
import com.zeroclaw.android.model.RefreshCommand
import com.zeroclaw.android.model.ToolSpec
import com.zeroclaw.android.service.ToolsBridge
import com.zeroclaw.android.util.ErrorSanitizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * UI state for the tools browser screen.
 *
 * @param T The type of content data.
 */
sealed interface ToolsUiState<out T> {
    /** Data is being loaded from the bridge. */
    data object Loading : ToolsUiState<Nothing>

    /**
     * Loading failed.
     *
     * @property detail Human-readable error message.
     */
    data class Error(
        val detail: String,
    ) : ToolsUiState<Nothing>

    /**
     * Data loaded successfully.
     *
     * @param T Content data type.
     * @property data The loaded content.
     */
    data class Content<T>(
        val data: T,
    ) : ToolsUiState<T>
}

/** Filter value for showing all tools. */
const val SOURCE_ALL = "All"

/** Filter value for showing only built-in tools. */
const val SOURCE_BUILT_IN = "built-in"

/**
 * ViewModel for the tools inventory browser screen.
 *
 * Loads the tool inventory from [ToolsBridge] and exposes search and
 * source-based filtering.
 *
 * @param application Application context for accessing [ZeroClawApplication.toolsBridge].
 */
class ToolsBrowserViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val app = application as ZeroClawApplication
    private val toolsBridge: ToolsBridge = app.toolsBridge

    private val _uiState =
        MutableStateFlow<ToolsUiState<List<ToolSpec>>>(ToolsUiState.Loading)

    /** Observable UI state for the tools list. */
    val uiState: StateFlow<ToolsUiState<List<ToolSpec>>> = _uiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")

    /** Current search query text. */
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _sourceFilter = MutableStateFlow(SOURCE_ALL)

    /** Current source filter value. */
    val sourceFilter: StateFlow<String> = _sourceFilter.asStateFlow()

    /** Distinct source values derived from the current tool list. */
    val availableSources: StateFlow<List<String>> =
        _uiState
            .map { state ->
                if (state is ToolsUiState.Content) {
                    val sources =
                        state.data
                            .map { it.source }
                            .distinct()
                            .sorted()
                    listOf(SOURCE_ALL) + sources
                } else {
                    listOf(SOURCE_ALL)
                }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(),
                initialValue = listOf(SOURCE_ALL),
            )

    /**
     * Pre-filtered UI state combining the raw tools list with
     * the current search query and source filter.
     *
     * Filtering runs in the ViewModel so the composable receives
     * an already-filtered list without recomputing on every recomposition.
     */
    val filteredUiState: StateFlow<ToolsUiState<List<ToolSpec>>> =
        combine(_uiState, _searchQuery, _sourceFilter) { state, query, source ->
            if (state is ToolsUiState.Content) {
                ToolsUiState.Content(filterTools(state.data, query, source))
            } else {
                state
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = ToolsUiState.Loading,
        )

    init {
        loadTools()
        observeRefreshCommands()
    }

    /** Reloads the tools list from the native layer. */
    fun loadTools() {
        _uiState.value = ToolsUiState.Loading
        viewModelScope.launch {
            loadToolsInternal()
        }
    }

    /**
     * Updates the search query for filtering tools.
     *
     * @param query New search text.
     */
    fun updateSearch(query: String) {
        _searchQuery.value = query
    }

    /**
     * Sets the source filter.
     *
     * @param source Source name to filter by, or [SOURCE_ALL] for no filter.
     */
    fun setSourceFilter(source: String) {
        _sourceFilter.value = source
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun loadToolsInternal() {
        try {
            val settings = app.settingsRepository.settings.first()
            val composioReadiness =
                ComposioReadiness.from(
                    enabled = settings.composioEnabled,
                    apiKey = settings.composioApiKey,
                )
            val tools =
                toolsBridge
                    .listTools()
                    .withComposioReadiness(composioReadiness)
            _uiState.value = ToolsUiState.Content(tools)
        } catch (e: Exception) {
            _uiState.value =
                ToolsUiState.Error(
                    ErrorSanitizer.sanitizeForUi(e),
                )
        }
    }

    private fun observeRefreshCommands() {
        viewModelScope.launch {
            app.refreshCommands.collect { command ->
                if (command == RefreshCommand.Skills || command == RefreshCommand.Tools) {
                    loadTools()
                }
            }
        }
    }

    /** Utility functions for tools filtering. */
    companion object {
        /**
         * Filters tools by search query and source.
         *
         * @param tools All tools from the daemon.
         * @param query Search query text.
         * @param source Source filter value.
         * @return Filtered list of tools.
         */
        private fun filterTools(
            tools: List<ToolSpec>,
            query: String,
            source: String,
        ): List<ToolSpec> {
            var result = tools
            if (source != SOURCE_ALL) {
                result = result.filter { it.source == source }
            }
            if (query.isNotBlank()) {
                result =
                    result.filter { tool ->
                        tool.name.contains(query, ignoreCase = true) ||
                            tool.description.contains(query, ignoreCase = true)
                    }
            }
            return result
        }

        private fun List<ToolSpec>.withComposioReadiness(
            readiness: ComposioReadiness,
        ): List<ToolSpec> {
            if (readiness.isActive) return this
            return map { tool ->
                if (tool.isComposioTool()) {
                    tool.copy(
                        isActive = false,
                        inactiveReason = "Composio: ${readiness.inactiveReason}",
                    )
                } else {
                    tool
                }
            }
        }

        private fun ToolSpec.isComposioTool(): Boolean {
            val normalizedSource = source.trim().lowercase()
            val normalizedName = name.trim().lowercase()
            return normalizedSource == SOURCE_COMPOSIO ||
                normalizedSource.startsWith("$SOURCE_COMPOSIO:") ||
                normalizedSource.contains(SOURCE_COMPOSIO) ||
                normalizedName.startsWith("${SOURCE_COMPOSIO}_")
        }

        private const val SOURCE_COMPOSIO = "composio"
    }
}
