/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zeroclaw.android.ZeroClawApplication
import com.zeroclaw.android.model.ActivityEvent
import com.zeroclaw.android.model.CostSummary
import com.zeroclaw.android.model.CronJob
import com.zeroclaw.android.model.DaemonStatus
import com.zeroclaw.android.model.KeyRejectionEvent
import com.zeroclaw.android.model.LiveActivityItem
import com.zeroclaw.android.model.MemoryConflict
import com.zeroclaw.android.model.RefreshCommand
import com.zeroclaw.android.model.ServiceState
import com.zeroclaw.android.service.CostBridge
import com.zeroclaw.android.service.cron.ffi.CronBridge
import com.zeroclaw.android.service.DaemonServiceBridge
import com.zeroclaw.android.service.LiveActivityGrouper
import com.zeroclaw.android.service.ZeroClawDaemonService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Represents the possible states of an asynchronous daemon UI operation.
 *
 * @param T The type of data held in the [Content] variant.
 */
sealed interface DaemonUiState<out T> {
    /** No operation has been initiated. */
    data object Idle : DaemonUiState<Nothing>

    /** An operation is in progress. */
    data object Loading : DaemonUiState<Nothing>

    /**
     * An operation failed.
     *
     * @property detail Human-readable error description.
     * @property retry Optional callback to retry the failed operation.
     */
    data class Error(
        val detail: String,
        val retry: (() -> Unit)? = null,
    ) : DaemonUiState<Nothing>

    /**
     * An operation completed successfully.
     *
     * @param T The type of the result payload.
     * @property data The result payload.
     */
    data class Content<T>(
        val data: T,
    ) : DaemonUiState<T>
}

/**
 * ViewModel for the daemon control screen.
 *
 * Exposes daemon state as [StateFlow] instances for lifecycle-aware
 * collection in Compose via `collectAsStateWithLifecycle`. Daemon
 * lifecycle control (start/stop) is performed by sending [Intent]
 * actions to [ZeroClawDaemonService], while messaging uses the
 * shared [DaemonServiceBridge] directly.
 *
 * Automatically starts and stops status polling based on
 * [ServiceState] transitions from the bridge.
 *
 * @param application Application context for accessing
 *   [ZeroClawApplication.daemonBridge] and starting the service.
 */
class DaemonViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val app = application as ZeroClawApplication
    private val bridge: DaemonServiceBridge = app.daemonBridge
    private val costBridge: CostBridge = app.costBridge
    private val cronBridge: CronBridge = app.cronBridge

    /**
     * Count of enabled agents, derived from the agent repository flow.
     *
     * Scoped with [SharingStarted.WhileSubscribed] so collection stops
     * when no UI is observing, saving database query overhead.
     *
     * Defers repository access until [ZeroClawApplication.repositoriesReady]
     * is true to avoid crashing on the lateinit property before DB init.
     */
    val enabledAgentCount: StateFlow<Int> =
        app.repositoriesReady
            .flatMapLatest { ready ->
                if (ready) app.agentRepository.agents else emptyFlow()
            }
            .map { list -> list.count { it.isEnabled } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)

    /**
     * Count of installed plugins, derived from the plugin repository flow.
     */
    val installedPluginCount: StateFlow<Int> =
        app.repositoriesReady
            .flatMapLatest { ready ->
                if (ready) app.pluginRepository.plugins else emptyFlow()
            }
            .map { list -> list.count { it.isInstalled } }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)

    /**
     * Recent activity events for the dashboard feed.
     */
    val activityEvents: StateFlow<List<ActivityEvent>> =
        app.repositoriesReady
            .flatMapLatest { ready ->
                if (ready) app.activityRepository.events else emptyFlow()
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val liveActivityGrouper = LiveActivityGrouper()

    /**
     * Live activity items for the dashboard, grouped by request lifecycle.
     *
     * Collects from the FFI event bridge and groups flat daemon events
     * into request-lifecycle items with processing steps.
     */
    val liveActivities: StateFlow<List<LiveActivityItem>> =
        app.repositoriesReady
            .flatMapLatest { ready ->
                if (ready) app.eventBridge.events.map { event -> liveActivityGrouper.process(event) }
                else emptyFlow()
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** Current lifecycle state of the daemon service. */
    val serviceState: StateFlow<ServiceState> = bridge.serviceState

    /** Most recently fetched daemon health snapshot. */
    val daemonStatus: StateFlow<DaemonStatus?> = bridge.lastStatus

    private val _statusState =
        MutableStateFlow<DaemonUiState<DaemonStatus>>(DaemonUiState.Idle)

    /** UI state of the daemon status section. */
    val statusState: StateFlow<DaemonUiState<DaemonStatus>> =
        _statusState.asStateFlow()

    private val _keyRejectionEvent = MutableStateFlow<KeyRejectionEvent?>(null)

    /**
     * Most recent API key rejection event detected during a send operation.
     *
     * Non-null when a key rejection has been detected and not yet dismissed
     * by the user via [dismissKeyRejection].
     */
    val keyRejectionEvent: StateFlow<KeyRejectionEvent?> = _keyRejectionEvent.asStateFlow()

    private val _costSummary = MutableStateFlow<CostSummary?>(null)

    /**
     * Aggregated cost summary fetched periodically while the daemon is running.
     *
     * Includes session, daily, and monthly costs plus token usage figures.
     * Returns `null` when the daemon is not running or no cost poll has completed.
     */
    val costSummary: StateFlow<CostSummary?> = _costSummary.asStateFlow()

    private val _cronJobs = MutableStateFlow<List<CronJob>>(emptyList())

    /**
     * List of cron jobs fetched periodically while the daemon is running.
     *
     * Empty when the daemon is not running or no cron poll has completed.
     */
    val cronJobs: StateFlow<List<CronJob>> = _cronJobs.asStateFlow()

    private var costPollJob: Job? = null
    private var cronPollJob: Job? = null
    private var webPollJob: Job? = null

    private val _webEnabled = MutableStateFlow<Boolean?>(null)

    /**
     * Whether the gateway web dashboard server is currently enabled.
     *
     * `null` means unknown (daemon not running or not yet polled).
     */
    val webEnabled: StateFlow<Boolean?> = _webEnabled.asStateFlow()

    /**
     * Whether the web server toggle is currently in-flight.
     */
    private val _webToggling = MutableStateFlow(false)
    val webToggling: StateFlow<Boolean> = _webToggling.asStateFlow()

    private val _localIpAddress = MutableStateFlow<String?>(null)
    val localIpAddress: StateFlow<String?> = _localIpAddress.asStateFlow()

    private val _gatewayPort = MutableStateFlow(42617)
    val gatewayPort: StateFlow<Int> = _gatewayPort.asStateFlow()

    init {
        viewModelScope.launch {
            bridge.serviceState.collect { state ->
                when (state) {
                    ServiceState.RUNNING -> {
                        refreshDashboardMetrics()
                        startCostPolling()
                        startCronPolling()
                        startWebPolling()
                        refreshLocalIpAddress()
                        _gatewayPort.value = getGatewayPort()
                    }
                    ServiceState.STOPPED -> {
                        stopAllPolling()
                        _statusState.value = DaemonUiState.Idle
                        _costSummary.value = null
                        _cronJobs.value = emptyList()
                        _webEnabled.value = null
                    }
                    ServiceState.ERROR -> {
                        stopAllPolling()
                        _statusState.value =
                            DaemonUiState.Error(
                                detail = bridge.lastError.value ?: "Unknown daemon error",
                                retry = { requestStart() },
                            )
                        _costSummary.value = null
                        _cronJobs.value = emptyList()
                        _webEnabled.value = null
                    }
                    ServiceState.STARTING ->
                        _statusState.value = DaemonUiState.Loading
                    ServiceState.STOPPING ->
                        _statusState.value = DaemonUiState.Loading
                }
            }
        }

        viewModelScope.launch {
            bridge.lastStatus.collect { status ->
                if (status != null) {
                    _statusState.value = DaemonUiState.Content(status)
                }
            }
        }

        viewModelScope.launch {
            bridge.keyRejections.collect { event ->
                _keyRejectionEvent.value = event
            }
        }

        viewModelScope.launch {
            app.refreshCommands.collect { command ->
                handleRefreshCommand(command)
            }
        }
    }

    /**
     * Requests the daemon to start.
     */
    fun requestStart() {
        performStart()
    }

    /**
     * Requests the daemon to stop.
     */
    fun requestStop() {
        performStop()
    }

    private fun performStart() {
        val intent =
            Intent(
                getApplication(),
                ZeroClawDaemonService::class.java,
            ).apply {
                action = ZeroClawDaemonService.ACTION_START
            }
        getApplication<Application>().startForegroundService(intent)
    }

    private fun performStop() {
        val intent =
            Intent(
                getApplication(),
                ZeroClawDaemonService::class.java,
            ).apply {
                action = ZeroClawDaemonService.ACTION_STOP
            }
        getApplication<Application>().startService(intent)
    }

    /** Clears the current key rejection event after the user has dismissed it. */
    fun dismissKeyRejection() {
        _keyRejectionEvent.value = null
    }

    /** Pending memory conflict requiring user action, or null. */
    val memoryConflict: StateFlow<MemoryConflict?> = bridge.memoryConflict

    /** Warning when memory health check fails post-startup, or null. */
    val memoryHealthWarning: StateFlow<String?> = bridge.memoryHealthWarning

    /**
     * Resolves the pending memory conflict dialog.
     *
     * @param shouldDelete True to delete stale files, false to keep.
     */
    fun resolveMemoryConflict(shouldDelete: Boolean) {
        bridge.resolveMemoryConflict(shouldDelete)
    }

    /**
     * Dismisses the memory health warning banner.
     */
    fun dismissMemoryHealthWarning() {
        bridge.dismissMemoryHealthWarning()
    }

    /**
     * Handles a refresh command by immediately fetching the relevant data.
     *
     * @param command The refresh command to handle.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun handleRefreshCommand(command: RefreshCommand) {
        viewModelScope.launch {
            try {
                when (command) {
                    RefreshCommand.Cron ->
                        _cronJobs.value = cronBridge.listJobs()
                    RefreshCommand.Cost ->
                        _costSummary.value = costBridge.getCostSummary()
                    RefreshCommand.Health -> {
                        // Health detail no longer displayed on dashboard.
                    }
                    RefreshCommand.Tools -> {
                        // Tool inventory refreshes are handled by the tools screen.
                    }
                    RefreshCommand.Skills -> {
                        // Skills refreshes are handled by the skills/tools screens
                        // and terminal session invalidation. The daemon dashboard
                        // has nothing to reload for this event.
                    }
                }
            } catch (_: Exception) {
                /** Refresh failure is non-fatal; the next poll cycle will retry. */
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun refreshDashboardMetrics() {
        viewModelScope.launch {
            try {
                _costSummary.value = costBridge.getCostSummary()
            } catch (_: Exception) {
                // cost refresh failure is non-fatal
            }
            try {
                _cronJobs.value = cronBridge.listJobs()
            } catch (_: Exception) {
                // cron refresh failure is non-fatal
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private fun startCostPolling() {
        stopCostPolling()
        costPollJob =
            viewModelScope.launch {
                while (true) {
                    delay(COST_POLL_INTERVAL_MS)
                    try {
                        _costSummary.value = costBridge.getCostSummary()
                    } catch (_: Exception) {
                        // cost poll failure is non-fatal
                    }
                }
            }
    }

    private fun stopCostPolling() {
        costPollJob?.cancel()
        costPollJob = null
    }

    @Suppress("TooGenericExceptionCaught")
    private fun startCronPolling() {
        stopCronPolling()
        cronPollJob =
            viewModelScope.launch {
                while (true) {
                    delay(CRON_POLL_INTERVAL_MS)
                    try {
                        _cronJobs.value = cronBridge.listJobs()
                    } catch (_: Exception) {
                        /** Cron poll failure is non-fatal. */
                    }
                }
            }
    }

    private fun stopCronPolling() {
        cronPollJob?.cancel()
        cronPollJob = null
    }

    private fun stopAllPolling() {
        stopCostPolling()
        stopCronPolling()
        stopWebPolling()
    }

    @Suppress("TooGenericExceptionCaught")
    private fun startWebPolling() {
        stopWebPolling()
        webPollJob =
            viewModelScope.launch {
                while (true) {
                    delay(WEB_POLL_INTERVAL_MS)
                    try {
                        fetchWebStatus()
                    } catch (_: Exception) {
                        // web poll failure is non-fatal
                    }
                }
            }
    }

    private fun stopWebPolling() {
        webPollJob?.cancel()
        webPollJob = null
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun fetchWebStatus() {
        val port = getGatewayPort()
        val client = app.sharedHttpClient
        val request =
            Request.Builder()
                .url("http://127.0.0.1:$port/admin/web/status")
                .get()
                .build()
        try {
            val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
            if (response.isSuccessful) {
                val body = response.body?.string() ?: return
                val json = JSONObject(body)
                _webEnabled.value = json.optBoolean("web_enabled", true)
            }
        } catch (_: Exception) {
            // daemon may not be fully ready yet
        }
    }

    /**
     * Toggles the web dashboard server on or off.
     *
     * Calls POST /admin/web/toggle on the local gateway.
     */
    @Suppress("TooGenericExceptionCaught")
    fun toggleWebServer() {
        if (_webToggling.value) return
        viewModelScope.launch {
            _webToggling.value = true
            try {
                val port = getGatewayPort()
                val client = app.sharedHttpClient
                val request =
                    Request.Builder()
                        .url("http://127.0.0.1:$port/admin/web/toggle")
                        .post("".toRequestBody("application/json".toMediaType()))
                        .build()
                val response = withContext(Dispatchers.IO) { client.newCall(request).execute() }
                if (response.isSuccessful) {
                    val body = response.body?.string() ?: return@launch
                    val json = JSONObject(body)
                    _webEnabled.value = json.optBoolean("web_enabled", true)
                }
            } catch (_: Exception) {
                // toggle failure is non-fatal
            } finally {
                _webToggling.value = false
            }
        }
    }

    private suspend fun getGatewayPort(): Int {
        val settings = app.settingsRepository.settings.first()
        return settings.port
    }

    private fun refreshLocalIpAddress() {
        _localIpAddress.value = getLocalIpAddress(app)
    }

    /** Constants for [DaemonViewModel]. */
    companion object {
        private const val COST_POLL_INTERVAL_MS = 30_000L
        private const val CRON_POLL_INTERVAL_MS = 30_000L
        private const val WEB_POLL_INTERVAL_MS = 10_000L
        private const val STOP_TIMEOUT_MS = 5_000L

        /** Returns the device's non-loopback IPv4 address, or null. */
        fun getLocalIpAddress(context: Context): String? {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return null
            val network = cm.activeNetwork ?: return null
            val linkProps = cm.getLinkProperties(network) ?: return null
            return linkProps.linkAddresses
                .firstOrNull {
                    val addr = it.address?.hostAddress ?: ""
                    addr.contains(".") && !addr.startsWith("127.")
                }
                ?.address
                ?.hostAddress
        }
    }
}
