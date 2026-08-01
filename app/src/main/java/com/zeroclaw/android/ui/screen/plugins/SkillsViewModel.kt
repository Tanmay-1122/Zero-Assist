package com.zeroclaw.android.ui.screen.plugins

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zeroclaw.android.ZeroClawApplication
import com.zeroclaw.android.data.remote.SkillsMarketplaceClient
import com.zeroclaw.android.model.MarketplaceSkill
import com.zeroclaw.android.model.RefreshCommand
import com.zeroclaw.android.model.Skill
import com.zeroclaw.android.service.SkillsBridge
import com.zeroclaw.android.service.SkillsMarketplaceInstaller
import com.zeroclaw.android.util.ErrorSanitizer
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * UI state for the skills tab.
 *
 * @param T The type of content data.
 */
sealed interface SkillsUiState<out T> {
    data object Loading : SkillsUiState<Nothing>

    data class Error(
        val detail: String,
    ) : SkillsUiState<Nothing>

    data class Content<T>(
        val data: T,
    ) : SkillsUiState<T>
}

/**
 * Marketplace item state decorated with install status for the current workspace.
 */
data class MarketplaceSkillItem(
    val skill: MarketplaceSkill,
    val isInstalled: Boolean,
    val isInstalling: Boolean,
)

/**
 * ViewModel for the skills tab inside the Plugins and Skills screen.
 *
 * Loads installed skills plus the official marketplace and exposes install/remove operations.
 */
class SkillsViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val app = application as ZeroClawApplication
    private val skillsBridge: SkillsBridge = app.skillsBridge
    private val marketplaceClient: SkillsMarketplaceClient = app.skillsMarketplaceClient
    private val marketplaceInstaller: SkillsMarketplaceInstaller = app.skillsMarketplaceInstaller

    private val _uiState =
        MutableStateFlow<SkillsUiState<List<Skill>>>(SkillsUiState.Loading)

    val uiState: StateFlow<SkillsUiState<List<Skill>>> = _uiState.asStateFlow()

    private val _marketplaceUiState =
        MutableStateFlow<SkillsUiState<List<MarketplaceSkill>>>(SkillsUiState.Loading)

    val marketplaceUiState: StateFlow<SkillsUiState<List<MarketplaceSkill>>> =
        _marketplaceUiState.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _installingSkillName = MutableStateFlow<String?>(null)
    val installingSkillName: StateFlow<String?> = _installingSkillName.asStateFlow()

    private val _snackbarMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarMessage: SharedFlow<String> = _snackbarMessage.asSharedFlow()

    val filteredUiState: StateFlow<SkillsUiState<List<Skill>>> =
        combine(_uiState, _searchQuery) { state, query ->
            if (state is SkillsUiState.Content) {
                SkillsUiState.Content(filterSkills(state.data, query))
            } else {
                state
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = SkillsUiState.Loading,
        )

    val filteredMarketplaceUiState: StateFlow<SkillsUiState<List<MarketplaceSkillItem>>> =
        combine(_marketplaceUiState, _searchQuery, _uiState, _installingSkillName) {
                marketplaceState,
                query,
                installedState,
                installingSkillName,
            ->
            when (marketplaceState) {
                is SkillsUiState.Content -> {
                    val installedNames =
                        when (installedState) {
                            is SkillsUiState.Content -> installedState.data.mapTo(mutableSetOf()) { it.name }
                            else -> emptySet()
                        }
                    SkillsUiState.Content(
                        filterMarketplaceSkills(marketplaceState.data, query).map { skill ->
                            MarketplaceSkillItem(
                                skill = skill,
                                isInstalled = installedNames.contains(skill.name),
                                isInstalling = installingSkillName == skill.name,
                            )
                        },
                    )
                }

                is SkillsUiState.Error -> SkillsUiState.Error(marketplaceState.detail)
                SkillsUiState.Loading -> SkillsUiState.Loading
            }
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(),
            initialValue = SkillsUiState.Loading,
        )

    init {
        loadSkills()
        loadMarketplaceSkills()
        observeRefreshCommands()
    }

    fun loadSkills() {
        _uiState.value = SkillsUiState.Loading
        viewModelScope.launch {
            loadSkillsInternal()
        }
    }

    fun loadMarketplaceSkills() {
        _marketplaceUiState.value = SkillsUiState.Loading
        viewModelScope.launch {
            loadMarketplaceSkillsInternal()
        }
    }

    fun updateSearch(query: String) {
        _searchQuery.value = query
    }

    fun installSkill(source: String) {
        viewModelScope.launch {
            runMutation("Skill installed") {
                skillsBridge.installSkill(source)
            }
        }
    }

    fun installMarketplaceSkill(skillName: String) {
        if (_installingSkillName.value != null) return

        viewModelScope.launch {
            _installingSkillName.value = skillName
            try {
                marketplaceInstaller.install(skillName)
                app.refreshCommands.tryEmit(RefreshCommand.Skills)
                _snackbarMessage.tryEmit("$skillName added to ZeroClaw")
                loadSkillsInternal()
            } catch (e: Exception) {
                _snackbarMessage.tryEmit(ErrorSanitizer.sanitizeForUi(e))
            } finally {
                _installingSkillName.value = null
            }
        }
    }

    fun removeSkill(name: String) {
        viewModelScope.launch {
            runMutation("Skill removed") {
                skillsBridge.removeSkill(name)
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun loadSkillsInternal() {
        try {
            val skills = skillsBridge.listSkills()
            _uiState.value = SkillsUiState.Content(skills)
        } catch (e: Exception) {
            _uiState.value =
                SkillsUiState.Error(
                    ErrorSanitizer.sanitizeForUi(e),
                )
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun loadMarketplaceSkillsInternal() {
        try {
            _marketplaceUiState.value =
                SkillsUiState.Content(
                    marketplaceClient.fetchRegistry(),
                )
        } catch (e: Exception) {
            _marketplaceUiState.value =
                SkillsUiState.Error(
                    ErrorSanitizer.sanitizeForUi(e),
                )
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun runMutation(
        successMessage: String,
        block: suspend () -> Any?,
    ) {
        try {
            block()
            app.refreshCommands.tryEmit(RefreshCommand.Skills)
            _snackbarMessage.tryEmit(successMessage)
            loadSkillsInternal()
        } catch (e: Exception) {
            _snackbarMessage.tryEmit(ErrorSanitizer.sanitizeForUi(e))
        }
    }

    private fun observeRefreshCommands() {
        viewModelScope.launch {
            app.refreshCommands.collect { command ->
                if (command == RefreshCommand.Skills) {
                    loadSkillsInternal()
                }
            }
        }
    }

    companion object {
        private fun filterSkills(
            skills: List<Skill>,
            query: String,
        ): List<Skill> {
            if (query.isBlank()) return skills
            return skills.filter { skill ->
                skill.name.contains(query, ignoreCase = true) ||
                    skill.description.contains(query, ignoreCase = true)
            }
        }

        private fun filterMarketplaceSkills(
            skills: List<MarketplaceSkill>,
            query: String,
        ): List<MarketplaceSkill> {
            if (query.isBlank()) return skills
            return skills.filter { skill ->
                skill.name.contains(query, ignoreCase = true) ||
                    skill.description.contains(query, ignoreCase = true) ||
                    skill.author.contains(query, ignoreCase = true) ||
                    skill.category.contains(query, ignoreCase = true) ||
                    skill.tags.any { it.contains(query, ignoreCase = true) }
            }
        }
    }
}
