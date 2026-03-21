/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.crossfade
import com.zeroclaw.android.data.SecurePrefsProvider
import com.zeroclaw.android.data.StorageHealth
import com.zeroclaw.android.data.local.ZeroClawDatabase
import com.zeroclaw.android.data.oauth.AuthProfileWriter
import com.zeroclaw.android.data.repository.ActivityRepository
import com.zeroclaw.android.data.repository.AgentRepository
import com.zeroclaw.android.data.repository.ApiKeyRepository
import com.zeroclaw.android.data.repository.ChannelConfigRepository
import com.zeroclaw.android.data.repository.DataStoreOnboardingRepository
import com.zeroclaw.android.data.repository.DataStoreSettingsRepository
import com.zeroclaw.android.data.repository.EncryptedApiKeyRepository
import com.zeroclaw.android.data.repository.EstopRepository
import com.zeroclaw.android.data.repository.LogRepository
import com.zeroclaw.android.data.repository.OnboardingRepository
import com.zeroclaw.android.data.repository.PluginRepository
import com.zeroclaw.android.data.repository.RoomActivityRepository
import com.zeroclaw.android.data.repository.RoomAgentRepository
import com.zeroclaw.android.data.repository.RoomChannelConfigRepository
import com.zeroclaw.android.data.repository.RoomLogRepository
import com.zeroclaw.android.data.repository.RoomPluginRepository
import com.zeroclaw.android.data.repository.RoomTerminalEntryRepository
import com.zeroclaw.android.data.repository.SettingsRepository
import com.zeroclaw.android.data.repository.TerminalEntryRepository
import com.zeroclaw.android.model.RefreshCommand
import com.zeroclaw.android.service.CostBridge
import com.zeroclaw.android.service.CronBridge
import com.zeroclaw.android.service.DaemonServiceBridge
import com.zeroclaw.android.service.EventBridge
import com.zeroclaw.android.service.HealthBridge
import com.zeroclaw.android.service.MemoryBridge
import com.zeroclaw.android.service.PluginSyncWorker
import com.zeroclaw.android.service.SkillsBridge
import com.zeroclaw.android.service.ToolsBridge
import com.zeroclaw.android.service.VisionBridge
import com.zeroclaw.android.util.SessionLockManager
import com.zeroclaw.android.service.PersistentEpochBuffer
import com.zeroclaw.ffi.getVersion
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Application subclass that initialises the native ZeroClaw library and
 * shared service components.
 *
 * The native library is loaded once during process creation so that every
 * component can call FFI functions without additional setup. Shared
 * singletons are created here and available for the lifetime of the process.
 *
 * Persistent data is stored in a Room database ([ZeroClawDatabase]) that
 * survives process restarts. Settings and API keys remain in DataStore
 * and EncryptedSharedPreferences respectively.
 */
class ZeroClawApplication :
    Application(),
    SingletonImageLoader.Factory,
    Configuration.Provider {
    /**
     * Shared bridge between the Android service layer and the Rust FFI.
     *
     * Initialised in [onCreate] and available for the lifetime of the process.
     * Access from [ZeroClawDaemonService][com.zeroclaw.android.service.ZeroClawDaemonService]
     * and [DaemonViewModel][com.zeroclaw.android.viewmodel.DaemonViewModel].
     */
    lateinit var daemonBridge: DaemonServiceBridge
        private set

    /** Room database instance for agents, plugins, logs, and activity events. */
    lateinit var database: ZeroClawDatabase
        private set

    /** Application settings repository backed by Jetpack DataStore. */
    lateinit var settingsRepository: SettingsRepository
        private set

    /** API key repository backed by EncryptedSharedPreferences. */
    lateinit var apiKeyRepository: ApiKeyRepository
        private set

    /** Log repository backed by Room with automatic pruning. */
    lateinit var logRepository: LogRepository
        private set

    /** Activity feed repository backed by Room with automatic pruning. */
    lateinit var activityRepository: ActivityRepository
        private set

    /** Onboarding state repository backed by Jetpack DataStore. */
    lateinit var onboardingRepository: OnboardingRepository
        private set

    /** Agent repository backed by Room. */
    lateinit var agentRepository: AgentRepository
        private set

    /** Plugin repository backed by Room. */
    lateinit var pluginRepository: PluginRepository
        private set

    /** Channel configuration repository backed by Room + EncryptedSharedPreferences. */
    lateinit var channelConfigRepository: ChannelConfigRepository
        private set

    /** Terminal REPL entry repository backed by Room. */
    lateinit var terminalEntryRepository: TerminalEntryRepository
        private set

    /** Emergency stop state repository. */
    lateinit var estopRepository: EstopRepository
        private set

    /** Bridge for structured health detail FFI calls. */
    lateinit var healthBridge: HealthBridge
        private set

    /** Bridge for cost-tracking FFI calls. */
    lateinit var costBridge: CostBridge
        private set

    /** Bridge for daemon event callbacks from the native layer. */
    lateinit var eventBridge: EventBridge
        private set

    /** Bridge for cron job CRUD FFI calls. */
    lateinit var cronBridge: CronBridge
        private set

    /** Bridge for skills browsing and management FFI calls. */
    lateinit var skillsBridge: SkillsBridge
        private set

    /** Bridge for tools inventory browsing FFI calls. */
    lateinit var toolsBridge: ToolsBridge
        private set

    /** Bridge for memory browsing and management FFI calls. */
    lateinit var memoryBridge: MemoryBridge
        private set

    /** Bridge for direct-to-provider multimodal vision API calls. */
    val visionBridge: VisionBridge by lazy { VisionBridge() }

    /** Persistent buffer for failed terminal messages (Zero-Message-Drop). */
    lateinit var epochBuffer: PersistentEpochBuffer
        private set

    /** App-wide session lock manager observing the process lifecycle. */
    lateinit var sessionLockManager: SessionLockManager
        private set

    /**
     * Event bus for triggering immediate data refresh across ViewModels.
     *
     * The terminal REPL emits commands here after mutating operations
     * (cron add, skill install, etc.) so that the Dashboard and other
     * screens update without waiting for the next poll cycle.
     */
    val refreshCommands: MutableSharedFlow<RefreshCommand> =
        MutableSharedFlow(
            extraBufferCapacity = 1,
            onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

    /**
     * Shared [OkHttpClient] for all HTTP callers within the app.
     *
     * Uses a bounded connection pool to prevent thread and socket leaks.
     * Callers should reference this instance rather than creating their own.
     * Cleaned up in [onTerminate].
     */
    val sharedHttpClient: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectionPool(
                ConnectionPool(
                    MAX_IDLE_CONNECTIONS,
                    KEEP_ALIVE_DURATION_SECONDS.toLong(),
                    TimeUnit.SECONDS,
                ),
            ).connectTimeout(HTTP_CONNECT_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
            .readTimeout(HTTP_READ_TIMEOUT_SECONDS.toLong(), TimeUnit.SECONDS)
            .build()
    }

    override val workManagerConfiguration: Configuration
        get() =
            Configuration
                .Builder()
                .setMinimumLoggingLevel(
                    if (BuildConfig.DEBUG) {
                        Log.DEBUG
                    } else {
                        Log.ERROR
                    },
                ).build()

    override fun onCreate() {
        super.onCreate()
        WorkManager.initialize(this, workManagerConfiguration)
        System.loadLibrary("sqlcipher")
        System.loadLibrary("zeroclaw")
        verifyCrateVersion()

        @Suppress("InjectDispatcher")
        val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        val ioScope = CoroutineScope(SupervisorJob() + ioDispatcher)

        daemonBridge = DaemonServiceBridge(filesDir.absolutePath)
        database = ZeroClawDatabase.build(this, ioScope)
        settingsRepository = DataStoreSettingsRepository(this)
        apiKeyRepository = createApiKeyRepository(ioScope)
        logRepository = RoomLogRepository(database.logEntryDao(), ioScope)
        activityRepository = RoomActivityRepository(database.activityEventDao(), ioScope)
        onboardingRepository = DataStoreOnboardingRepository(this)
        agentRepository = RoomAgentRepository(database.agentDao())
        pluginRepository = RoomPluginRepository(database.pluginDao())
        channelConfigRepository = createChannelConfigRepository()
        terminalEntryRepository =
            RoomTerminalEntryRepository(database.terminalEntryDao(), ioScope)
        estopRepository = EstopRepository(scope = ioScope)
        estopRepository.startPolling()
        healthBridge = HealthBridge()
        costBridge = CostBridge()
        cronBridge = CronBridge()
        skillsBridge = SkillsBridge()
        toolsBridge = ToolsBridge()
        memoryBridge = MemoryBridge()
        eventBridge = EventBridge(activityRepository, ioScope)
        daemonBridge.eventBridge = eventBridge
        epochBuffer = PersistentEpochBuffer(context = this)
        daemonBridge.persistentBuffer = epochBuffer

        sessionLockManager = SessionLockManager(settingsRepository.settings, ioScope)
        ProcessLifecycleOwner.get().lifecycle.addObserver(sessionLockManager)

        syncDaemonState(ioScope)
        migrateStaleOAuthEntries(ioScope)
        schedulePluginSyncIfEnabled(ioScope)
    }

    /**
     * Checks that the loaded native library version matches the app version.
     *
     * A mismatch indicates a partial update left a stale `.so` file. This
     * is logged as a warning rather than a crash so the app remains usable,
     * but the mismatch may cause unexpected FFI behaviour.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun verifyCrateVersion() {
        try {
            val crateVersion = getVersion()
            val appVersion = BuildConfig.VERSION_NAME
            if (crateVersion != appVersion) {
                Log.w(
                    TAG,
                    "Crate/app version mismatch: native=$crateVersion, app=$appVersion",
                )
            }
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to verify crate version: ${e.message}")
        }
    }

    /**
     * Probes the Rust FFI layer to detect whether the daemon is already running.
     *
     * This handles the case where the foreground service kept the daemon alive
     * across a process death (via [START_STICKY]) but the newly created
     * [DaemonServiceBridge] defaults to [ServiceState.STOPPED]. Without this
     * probe, the UI would show the daemon as offline and attempts to start it
     * would fail with "daemon already running".
     *
     * @param scope Background scope for the non-blocking probe.
     */
    private fun syncDaemonState(scope: CoroutineScope) {
        scope.launch {
            daemonBridge.syncState()
        }
    }

    /**
     * Migrates stale OAuth API key entries from `openai` to `openai-codex`.
     *
     * Before this fix, the OAuth login flow incorrectly stored ChatGPT
     * tokens under the `openai` provider. The `openai` provider sends
     * requests to `api.openai.com` (standard API), while OAuth tokens
     * must be routed through the `openai-codex` provider which targets
     * `chatgpt.com/backend-api/codex/responses`.
     *
     * This migration runs once per launch. It finds any `openai` entries
     * with a non-empty [ApiKey.refreshToken][com.zeroclaw.android.model.ApiKey.refreshToken]
     * (indicating OAuth), re-saves them as `openai-codex`, writes the
     * corresponding [AuthProfileWriter] file for the Rust [AuthService],
     * and updates the default provider setting.
     *
     * @param scope Background scope for the migration coroutine.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun migrateStaleOAuthEntries(scope: CoroutineScope) {
        scope.launch {
            try {
                // Use a timeout to prevent blocking indefinitely if the DB/Keystore is slow
                val allKeys = withTimeoutOrNull(DB_QUERY_TIMEOUT_MS) {
                    apiKeyRepository.keys.first()
                } ?: return@launch

                val staleOAuthKeys =
                    allKeys.filter { it.provider == STALE_OAUTH_PROVIDER && it.refreshToken.isNotEmpty() }
                if (staleOAuthKeys.isEmpty()) return@launch

                migrateSingleOAuthKeys(staleOAuthKeys)
                updateDefaultProviderIfNeeded()
                Log.i(TAG, "Migrated ${staleOAuthKeys.size} stale OAuth keys")
            } catch (e: InterruptedException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "OAuth migration failed: ${e.message}")
            }
        }
    }

    private suspend fun migrateSingleOAuthKeys(staleOAuthKeys: List<ApiKey>) {
        for (staleKey in staleOAuthKeys) {
            val migrated = staleKey.copy(provider = CODEX_PROVIDER, key = "")
            apiKeyRepository.save(migrated)

            if (staleKey.expiresAt > 0L) {
                AuthProfileWriter.writeCodexProfile(
                    context = this,
                    accessToken = staleKey.key,
                    refreshToken = staleKey.refreshToken,
                    expiresAtMs = staleKey.expiresAt,
                )
            }
        }
    }

    private suspend fun updateDefaultProviderIfNeeded() {
        val currentSettings = settingsRepository.settings.first()
        if (currentSettings.defaultProvider == STALE_OAUTH_PROVIDER) {
            settingsRepository.setDefaultProvider(CODEX_PROVIDER)
        }
    }

    private fun schedulePluginSyncIfEnabled(scope: CoroutineScope) {
        scope.launch {
            settingsRepository.settings.collect { settings ->
                val workManager = WorkManager.getInstance(this@ZeroClawApplication)
                if (settings.pluginSyncEnabled) {
                    val constraints = Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build()
                    val request = PeriodicWorkRequestBuilder<PluginSyncWorker>(1, TimeUnit.HOURS)
                        .setConstraints(constraints)
                        .build()
                    workManager.enqueueUniquePeriodicWork(
                        "PluginSync",
                        ExistingPeriodicWorkPolicy.KEEP,
                        request
                    )
                } else {
                    workManager.cancelUniqueWork("PluginSync")
                }
            }
        }
    }

    private fun createApiKeyRepository(scope: CoroutineScope): ApiKeyRepository {
        return EncryptedApiKeyRepository(this, scope)
    }

    private fun createChannelConfigRepository(): ChannelConfigRepository {
        return RoomChannelConfigRepository(
            database.connectedChannelDao(),
            SecurePrefsProvider.create(this, CHANNEL_CONFIG_PREFS).first,
        )
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, MEMORY_CACHE_PERCENT).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve("image_cache"))
                    .maxSizeBytes(DISK_CACHE_SIZE_BYTES)
                    .build()
            }
            .crossfade(true)
            .build()
    }

    /**
     * Companion object holding application-wide constants.
     *
     * Contains shared logging tags, network configuration timeouts, OAuth migration settings,
     * and storage preferences used throughout the application lifecycle.
     */
    companion object {
        private const val TAG = "ZeroClawApp"
        private const val MAX_IDLE_CONNECTIONS = 5
        private const val KEEP_ALIVE_DURATION_SECONDS = 30L
        private const val HTTP_CONNECT_TIMEOUT_SECONDS = 15L
        private const val HTTP_READ_TIMEOUT_SECONDS = 15L
        private const val DB_QUERY_TIMEOUT_MS = 5000L
        private const val DISK_CACHE_SIZE_BYTES = 512L * 1024 * 1024
        private const val MEMORY_CACHE_PERCENT = 0.25
        private const val STALE_OAUTH_PROVIDER = "openai"
        private const val CODEX_PROVIDER = "openai-codex"
        private const val CHANNEL_CONFIG_PREFS = "connected_channel_secrets"
    }
}
