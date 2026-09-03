/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.plugins

import android.app.Application
import android.content.Intent
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zeroclaw.android.ZeroClawApplication
import com.zeroclaw.android.data.remote.OkHttpPluginRegistryClient
import com.zeroclaw.android.data.repository.setOfficialPluginEnabled
import com.zeroclaw.android.google.GoogleWorkspaceAuthManager
import com.zeroclaw.android.model.CommunityPlugins
import com.zeroclaw.android.model.OfficialPlugins
import com.zeroclaw.android.model.Plugin
import com.zeroclaw.android.model.PluginCategory
import com.zeroclaw.android.model.RefreshCommand
import com.zeroclaw.android.model.ToolSpec
import com.zeroclaw.android.service.sandbox.SandboxState
import com.zeroclaw.android.service.MqttPluginChannelSync
import com.zeroclaw.android.util.ErrorSanitizer
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import com.zeroclaw.android.ui.screen.settings.tools.SOURCE_BUILT_IN

/** Tab index for the installed plugins tab. */
const val TAB_INSTALLED = 0

/** Tab index for the available (uninstalled) plugins tab. */
const val TAB_AVAILABLE = 1

/** Tab index for the skills tab. */
const val TAB_SKILLS = 2

/** Tab index for the MCP tab. */
const val TAB_MCP = 3


/**
 * ViewModel for the plugin list screen.
 *
 * Provides tab-filtered and search-filtered plugin lists along with
 * install/uninstall and toggle operations. Also supports manual
 * and automatic plugin registry synchronisation.
 *
 * @param application Application context for accessing the plugin repository.
 */
class PluginsViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val app = application as ZeroClawApplication
    private val repository = app.pluginRepository
    private val settingsRepository = app.settingsRepository
    val gwsAuthManager = GoogleWorkspaceAuthManager(application)
    private val _snackbarMessage = MutableSharedFlow<String>(extraBufferCapacity = 1)

    /**
     * Tracks the current Google sign-in state reactively so that
     * [gwsNeedsSignIn] triggers recomposition when it changes.
     */
    private val _gwsIsSignedIn = MutableStateFlow(gwsAuthManager.isSignedIn)

    init {
        viewModelScope.launch {
            settingsRepository.settings.first().let { settings ->
                repository.syncOfficialPluginStates(settings)
            }
        }
        viewModelScope.launch {
            settingsRepository.migrationNoticePending.first().let { pending ->
                if (pending) {
                    _snackbarMessage.tryEmit(
                        "Web Search and Web Fetch have been enabled. " +
                            "You can disable them in Plugins settings.",
                    )
                    settingsRepository.clearMigrationNotice()
                }
            }
        }
        loadToolsAsPlugins()
        viewModelScope.launch {
            app.refreshCommands.collect { command ->
                if (command == RefreshCommand.Tools || command == RefreshCommand.Skills) {
                    loadToolsAsPlugins()
                }
            }
        }
    }

    private val _selectedTab = MutableStateFlow(TAB_INSTALLED)

    /** Currently selected tab index. */
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    private val _searchQuery = MutableStateFlow("")

    /** Current search query text. */
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _syncState = MutableStateFlow<SyncUiState>(SyncUiState.Idle)

    /** Current state of the plugin registry sync operation. */
    val syncState: StateFlow<SyncUiState> = _syncState.asStateFlow()

    /** One-shot messages for display in a [SnackbarHost]. */
    val snackbarMessage: SharedFlow<String> = _snackbarMessage.asSharedFlow()

    /**
     * Reactive flag: true when the Google Workspace plugin is installed
     * but the user has not yet signed in with Google.
     *
     * Derived from [repository.plugins] + [_gwsIsSignedIn] so Compose
     * recomposes automatically when either state changes.
     */
    val gwsNeedsSignIn: StateFlow<Boolean> =
        combine(repository.plugins, _gwsIsSignedIn) { all, signedIn ->
            val gwsPlugin = all.find { it.id == OfficialPlugins.GOOGLE_WORKSPACE }
            gwsPlugin?.isInstalled == true && !signedIn
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), false)

    private val _tools = MutableStateFlow<List<Plugin>>(emptyList())

    /** Loads daemon tools and maps them to Plugin entries. */
    private fun loadToolsAsPlugins() {
        viewModelScope.launch {
            try {
                val toolSpecs = app.toolsBridge.listTools()
                _tools.value = toolSpecs.map { it.toPluginModel() }
            } catch (_: Exception) {
                // Daemon not running — tools will appear as available once started
            }
        }
    }

    /** Filtered plugin list based on tab and search query. */
    val plugins: StateFlow<List<Plugin>> =
        combine(repository.plugins, _tools, _selectedTab, _searchQuery) { dbPlugins, toolPlugins, tab, query ->
            val all = dbPlugins + toolPlugins
            val tabFiltered =
                when (tab) {
                    TAB_INSTALLED -> all.filter { it.isInstalled }
                    else -> all.filter { !it.isInstalled }
                }
            if (query.isBlank()) {
                tabFiltered
            } else {
                tabFiltered.filter { plugin ->
                    plugin.name.contains(query, ignoreCase = true) ||
                        plugin.description.contains(query, ignoreCase = true)
                }
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), emptyList())

    /** Number of installed plugins that have a newer version available. */
    val updatesAvailableCount: StateFlow<Int> =
        repository.plugins
            .map { all ->
                all.count { plugin ->
                    plugin.isInstalled &&
                        plugin.remoteVersion != null &&
                        plugin.remoteVersion != plugin.version
                }
            }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(), 0)

    /**
     * Selects the tab at the given index.
     *
     * @param tab Tab index to select.
     */
    fun selectTab(tab: Int) {
        _selectedTab.value = tab
    }

    /**
     * Updates the search query for filtering plugins.
     *
     * @param query New search text.
     */
    fun updateSearch(query: String) {
        _searchQuery.value = query
    }

    /**
     * Installs the plugin with the given identifier.
     *
     * Emits a [snackbarMessage] on failure.
     *
     * @param pluginId Unique plugin identifier.
     */
    @Suppress("TooGenericExceptionCaught")
    fun installPlugin(pluginId: String) {
        if (pluginId.startsWith("tool:")) {
            _snackbarMessage.tryEmit("Daemon tools are always installed — start the daemon to activate them.")
            return
        }
        viewModelScope.launch {
            try {
                if (pluginId == OfficialPlugins.LINUX_SANDBOX) {
                    app.linuxSandboxManager.setup()
                    sandboxStateCheck(pluginId)
                } else if (pluginId == OfficialPlugins.GOOGLE_WORKSPACE) {
                    installGoogleWorkspace()
                } else if (pluginId == OfficialPlugins.TERMUX) {
                    installTermux()
                }
                repository.install(pluginId)
            } catch (e: Exception) {
                Log.w(TAG, "Install failed for $pluginId", e)
                _snackbarMessage.tryEmit("Install failed: ${ErrorSanitizer.sanitizeForUi(e)}")
            }
        }
    }

    /** Wait for sandbox setup to finish, then mark as not-installed on error. */
    private suspend fun sandboxStateCheck(pluginId: String) {
        app.linuxSandboxManager.state.collect { state ->
            if (state is SandboxState.Error) {
                repository.uninstall(pluginId)
                _snackbarMessage.tryEmit("Sandbox install failed: ${state.message}")
                return@collect
            }
            if (state is SandboxState.Ready) return@collect
        }
    }

    /** Sets up sandbox if needed, installs the GWS CLI, waits for completion. */
    private suspend fun installGoogleWorkspace() {
        val sandbox = app.linuxSandboxManager
        // Ensure sandbox is ready
        if (sandbox.state.value !is SandboxState.Ready) {
            sandbox.setup()
            sandbox.state.collect { state ->
                if (state is SandboxState.Error) {
                    throw IllegalStateException("Sandbox setup failed: ${state.message}")
                }
                if (state is SandboxState.Ready) return@collect
            }
        }
        // Install GWS CLI
        if (!sandbox.isGoogleWorkspaceCliInstalled()) {
            sandbox.installGoogleWorkspaceCli()
            sandbox.state.collect { state ->
                when (state) {
                    is SandboxState.Ready -> {
                        if (!sandbox.isGoogleWorkspaceCliInstalled()) {
                            throw IllegalStateException("GWS CLI not found after install")
                        }
                        return@collect
                    }
                    is SandboxState.Error -> throw IllegalStateException("GWS CLI install failed: ${state.message}")
                    else -> { /* waiting */ }
                }
            }
        }
    }

    /**
     * Checks if Termux is installed on the device and marks the plugin as
     * installed if so. If not, opens the F-Droid install page.
     */
    private fun installTermux() {
        val probe = app.termuxRuntimeProbe
        val snapshot = probe.snapshot()
        if (snapshot.packageState.availability ==
            com.zeroclaw.android.service.termux.TermuxPackageAvailability.INSTALLED
        ) {
            // Termux is already installed — just mark as installed in DB
            return
        }
        // Open F-Droid page for user to install
        val intent = android.content.Intent(
            android.content.Intent.ACTION_VIEW,
            android.net.Uri.parse("https://f-droid.org/en/packages/com.termux/"),
        )
        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        app.startActivity(intent)
        _snackbarMessage.tryEmit("Please install Termux from F-Droid, then try again.")
    }

    /**
     * Uninstalls the plugin with the given identifier.
     *
     * Emits a [snackbarMessage] on failure.
     *
     * @param pluginId Unique plugin identifier.
     */
    @Suppress("TooGenericExceptionCaught")
    fun uninstallPlugin(pluginId: String) {
        viewModelScope.launch {
            try {
                if (pluginId == OfficialPlugins.LINUX_SANDBOX) {
                    app.linuxSandboxManager.reset()
                }
                repository.uninstall(pluginId)
            } catch (e: Exception) {
                Log.w(TAG, "Uninstall failed for $pluginId", e)
                _snackbarMessage.tryEmit("Uninstall failed: ${ErrorSanitizer.sanitizeForUi(e)}")
            }
        }
    }

    /**
     * Toggles the enabled state of the given plugin.
     *
     * Emits a [snackbarMessage] on failure.
     *
     * @param pluginId Unique plugin identifier.
     */
    @Suppress("TooGenericExceptionCaught")
    fun togglePlugin(pluginId: String) {
        if (pluginId.startsWith("tool:")) {
            _snackbarMessage.tryEmit("Daemon tools cannot be toggled from here — they reflect daemon state.")
            return
        }
        viewModelScope.launch {
            try {
                val plugin = repository.getById(pluginId)
                if (plugin?.isOfficial == true) {
                    toggleOfficialPlugin(plugin)
                } else if (plugin?.id == CommunityPlugins.MQTT_CHANNEL) {
                    toggleMqttPlugin(plugin)
                } else {
                    repository.toggleEnabled(pluginId)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Toggle failed for $pluginId", e)
                _snackbarMessage.tryEmit("Toggle failed: ${ErrorSanitizer.sanitizeForUi(e)}")
            }
        }
    }

    /**
     * Triggers an immediate plugin registry sync.
     *
     * Fetches the remote plugin catalog and merges it into the local
     * database. Updates [syncState] throughout the operation.
     */
    @Suppress("TooGenericExceptionCaught")
    fun syncNow() {
        if (_syncState.value is SyncUiState.Syncing) return
        _syncState.value = SyncUiState.Syncing

        viewModelScope.launch {
            try {
                val settings = settingsRepository.settings.first()
                val client = OkHttpPluginRegistryClient(app.sharedHttpClient)
                val remotePlugins = client.fetchPlugins(settings.pluginRegistryUrl)
                repository.mergeRemotePlugins(remotePlugins)
                settingsRepository.setLastPluginSyncTimestamp(System.currentTimeMillis())
                val successState = SyncUiState.Success(remotePlugins.size)
                _syncState.value = successState
                launch {
                    delay(SYNC_SUCCESS_DISPLAY_MS)
                    _syncState.compareAndSet(successState, SyncUiState.Idle)
                }
            } catch (e: Exception) {
                _syncState.value =
                    SyncUiState.Error(ErrorSanitizer.sanitizeForUi(e))
            }
        }
    }

    /**
     * Resets all official plugin enabled states to their seed defaults.
     *
     * All plugins are disabled by default. Updates both AppSettings
     * (source of truth) and Room via sync.
     */
    @Suppress("TooGenericExceptionCaught")
    fun restoreDefaults() {
        viewModelScope.launch {
            try {
                settingsRepository.setWebSearchEnabled(false)
                settingsRepository.setWebFetchEnabled(false)
                settingsRepository.setHttpRequestEnabled(false)
                settingsRepository.setBrowserEnabled(false)
                settingsRepository.setComposioEnabled(false)
                settingsRepository.setSharedFolderEnabled(false)
                settingsRepository.setWorkflowFolderEnabled(true)
                settingsRepository.setGoogleWorkspaceEnabled(false)
                settingsRepository.setTermuxEnabled(false)
                val settings = settingsRepository.settings.first()
                repository.syncOfficialPluginStates(settings)
                _snackbarMessage.tryEmit("Official plugins restored to defaults")
            } catch (e: Exception) {
                Log.w(TAG, "Restore defaults failed", e)
                _snackbarMessage.tryEmit("Restore failed: ${ErrorSanitizer.sanitizeForUi(e)}")
            }
        }
    }

    private suspend fun toggleOfficialPlugin(plugin: Plugin) {
        val enabled = !plugin.isEnabled
        val settingUpdated = settingsRepository.setOfficialPluginEnabled(plugin.id, enabled)
        if (!settingUpdated) return

        repository.setEnabled(plugin.id, enabled)
        app.daemonBridge.markRestartRequired()
    }

    /** Returns the Google sign-in Intent. */
    fun getGwsSignInIntent(): Intent = gwsAuthManager.getSignInIntent()

    /** Signs out of Google Workspace and updates reactive state. */
    fun gwsSignOut() {
        gwsAuthManager.signOut {
            _gwsIsSignedIn.value = false
            _snackbarMessage.tryEmit("Google Workspace signed out")
        }
    }

    /** Completes Google Workspace sign-in and enables the plugin. */
    fun handleGwsSignInResult(data: Intent?) {
        viewModelScope.launch {
            val success = gwsAuthManager.handleSignInResult(data)
            if (success) {
                settingsRepository.setOfficialPluginEnabled(OfficialPlugins.GOOGLE_WORKSPACE, true)
                repository.setEnabled(OfficialPlugins.GOOGLE_WORKSPACE, true)
                app.daemonBridge.markRestartRequired()
                // Push updated sign-in state so gwsNeedsSignIn recomposes
                _gwsIsSignedIn.value = gwsAuthManager.isSignedIn
                _snackbarMessage.tryEmit("Google Workspace signed in and enabled")
            } else {
                _snackbarMessage.tryEmit("Google sign-in failed or was cancelled")
            }
        }
    }

    private suspend fun toggleMqttPlugin(plugin: Plugin) {
        val enabled = !plugin.isEnabled
        MqttPluginChannelSync.setEnabled(
            pluginRepository = repository,
            channelRepository = app.channelConfigRepository,
            enabled = enabled,
        )
        app.daemonBridge.markRestartRequired()
        _snackbarMessage.tryEmit(
            if (enabled) {
                "MQTT channel enabled. Review broker settings in Connected Channels if needed."
            } else {
                "MQTT channel disabled"
            },
        )
    }

    /** Constants for [PluginsViewModel]. */
    companion object {
        private const val TAG = "PluginsVM"
        private const val SYNC_SUCCESS_DISPLAY_MS = 3000L

        /** Maps a daemon [ToolSpec] to a [Plugin] for display in the plugin tabs. */
        private fun ToolSpec.toPluginModel(): Plugin =
            Plugin(
                id = "tool:$name",
                name = name,
                description = if (isActive) description else "Inactive: $inactiveReason",
                version = source,
                author = "Daemon",
                category = PluginCategory.TOOL,
                isInstalled = true,
                isEnabled = isActive,
            )
    }
}
