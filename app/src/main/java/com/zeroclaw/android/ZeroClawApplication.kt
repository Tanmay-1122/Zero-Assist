/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

@file:Suppress("TooGenericExceptionThrown")

package com.zeroclaw.android

import android.app.Application
import android.content.Context
import android.os.SystemClock
import android.system.ErrnoException
import android.system.Os
import com.zeroclaw.android.service.ExternalZeroClawConfig
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.disk.directory
import coil3.memory.MemoryCache
import coil3.request.crossfade
import com.zeroclaw.android.backup.DriveBackupManager
import com.zeroclaw.android.backup.SyncRepository
import com.zeroclaw.android.data.DataStoreProvider
import com.zeroclaw.android.data.SecurePrefsProvider
import com.zeroclaw.android.data.local.ZeroClawDatabase
import com.zeroclaw.android.data.remote.OkHttpSkillsMarketplaceClient
import com.zeroclaw.android.data.remote.SkillsMarketplaceClient
import com.zeroclaw.android.data.oauth.AuthProfileWriter
import com.zeroclaw.android.data.repository.ActivityRepository
import com.zeroclaw.android.data.repository.ActiveConversationSessionRepository
import com.zeroclaw.android.data.repository.AgentRepository
import com.zeroclaw.android.data.repository.ApiKeyRepository
import com.zeroclaw.android.data.repository.ChannelConfigRepository
import com.zeroclaw.android.data.repository.ConversationHistoryRepository
import com.zeroclaw.android.data.repository.DataStoreActiveConversationSessionRepository
import com.zeroclaw.android.data.repository.DataStoreOnboardingRepository
import com.zeroclaw.android.data.repository.DataStoreSettingsRepository
import com.zeroclaw.android.data.repository.DataStoreStarredConversationRepository
import com.zeroclaw.android.data.repository.DataStoreWelcomeRepository
import com.zeroclaw.android.data.repository.EncryptedApiKeyRepository
import com.zeroclaw.android.data.repository.EstopRepository
import com.zeroclaw.android.data.repository.LogRepository
import com.zeroclaw.android.data.repository.OnboardingRepository
import com.zeroclaw.android.data.repository.PluginRepository
import com.zeroclaw.android.data.repository.RoomConversationHistoryRepository
import com.zeroclaw.android.data.repository.RoomActivityRepository
import com.zeroclaw.android.data.repository.GreetingHistoryRepository
import com.zeroclaw.android.data.repository.RoomAgentRepository
import com.zeroclaw.android.data.repository.RoomChannelConfigRepository
import com.zeroclaw.android.data.repository.RoomLogRepository
import com.zeroclaw.android.data.repository.RoomPluginRepository
import com.zeroclaw.android.data.repository.RoomTerminalEntryRepository
import com.zeroclaw.android.data.repository.SettingsRepository
import com.zeroclaw.android.data.repository.StarredConversationRepository
import com.zeroclaw.android.data.repository.TerminalEntryRepository
import com.zeroclaw.android.data.repository.WelcomeRepository
import com.zeroclaw.android.data.repository.WorkspaceRepository
import com.zeroclaw.android.data.repository.DataStoreWorkspaceRepository
import com.zeroclaw.android.model.ApiKey
import com.zeroclaw.android.model.RefreshCommand
import com.zeroclaw.android.data.repository.AdvancedMemoryRepository
import com.zeroclaw.android.repository.RoomAdvancedMemoryRepository
import com.zeroclaw.android.repository.ChannelRepository
import com.zeroclaw.android.repository.RoomChannelRepository
import com.zeroclaw.android.repository.AgentToolsRepository
import com.zeroclaw.android.repository.RoomAgentToolsRepository
import com.zeroclaw.android.repository.HardwareRepository
import com.zeroclaw.android.repository.RoomHardwareRepository
import com.zeroclaw.android.network.AppHttpClientFactory
import com.zeroclaw.android.model.ChannelType
import com.zeroclaw.android.model.CommunityPlugins
import com.zeroclaw.android.service.AndroidLocalSpeechOutputDriver
import com.zeroclaw.android.service.AndroidVoiceContactLookup
import com.zeroclaw.android.service.AndroidVoiceDeviceProfile
import com.zeroclaw.android.service.AndroidVoiceWakeupServiceController
import com.zeroclaw.android.service.AndroidOnnxPiperVoiceRuntime
import com.zeroclaw.android.service.AndroidOnDeviceSpeechRecognizer
import com.zeroclaw.android.service.AndroidLocalVoiceStorage
import com.zeroclaw.android.service.ChannelVoiceTaskHandoff
import com.zeroclaw.android.service.CompositeAppOwnedToolCatalog
import com.zeroclaw.android.service.CompositeLocalSpeechOutputDriver
import com.zeroclaw.android.service.CostBridge
import com.zeroclaw.android.service.cron.ffi.CronBridge
import com.zeroclaw.android.service.CustomVoiceSpeechOutputDriver
import com.zeroclaw.android.service.DaemonServiceBridge
import com.zeroclaw.android.service.EventBridge
import com.zeroclaw.android.service.HealthBridge
import com.zeroclaw.android.service.ImportanceScoringEngine
import com.zeroclaw.android.service.LocalSpeechOutputAdapter
import com.zeroclaw.android.service.LocalSpeechRecognizer
import com.zeroclaw.android.service.LocalSpeechSynthesizer
import com.zeroclaw.android.service.LocalSpeechWakeupDetector
import com.zeroclaw.android.service.LocalVoiceStorage
import com.zeroclaw.android.service.LocalVoiceCatalogRepository
import com.zeroclaw.android.service.LocalVoiceDownloadManager
import com.zeroclaw.android.service.MemoryBridge
import com.zeroclaw.android.service.MqttPluginChannelSync
import com.zeroclaw.android.service.PiperOnnxPackageDownloader
import com.zeroclaw.android.service.PiperOnnxRuntimeThreadPolicy
import com.zeroclaw.android.service.SharedPreferencesLocalVoiceCatalogStore
import com.zeroclaw.android.service.SharedPreferencesVoiceOutputPreferences
import com.zeroclaw.android.service.SemanticSimilarityService
import com.zeroclaw.android.service.SettingsVoiceWakeupPreferences
import com.zeroclaw.android.service.AgentStatusRepository
import com.zeroclaw.android.service.ConversationSessionLifecycleObserver
import com.zeroclaw.android.service.ConversationSessionManager
import com.zeroclaw.android.service.OnDeviceImageDescriberBridge
import com.zeroclaw.android.service.OnDeviceInferenceBridge
import com.zeroclaw.android.service.LiteRTInferenceEngine
import com.zeroclaw.android.service.LocalInferenceEngine
import com.zeroclaw.android.service.OnDeviceProofreaderBridge
import com.zeroclaw.android.service.OnDeviceRewriterBridge
import com.zeroclaw.android.service.OnDeviceSummarizerBridge
import com.zeroclaw.android.service.ScreenCaptureBridge
import com.zeroclaw.android.service.SkillsBridge
import com.zeroclaw.android.service.SkillsMarketplaceInstaller
import com.zeroclaw.android.service.ToolsBridge
import com.zeroclaw.android.service.VisionBridge
import com.zeroclaw.android.service.VoiceAssistantConversation
import com.zeroclaw.android.service.VoiceAssistantLaunchRequests
import com.zeroclaw.android.service.VoiceContactLookup
import com.zeroclaw.android.service.VoiceTaskHandoff
import com.zeroclaw.android.service.VoiceTaskRequest
import com.zeroclaw.android.service.VoiceWakeupDetector
import com.zeroclaw.android.service.VoiceWakeupServiceController
import com.zeroclaw.android.service.VoiceWakeupStartupCoordinator
import com.zeroclaw.android.service.VoiceOutputRoutingPolicy
import com.zeroclaw.android.service.VoiceOutputPreferences
import com.zeroclaw.android.service.ZeroClawVoiceAssistantConversation
import com.zeroclaw.android.service.VoiceDeviceTier
import com.zeroclaw.android.service.defaultFirstAudioTimeoutMs
import com.zeroclaw.android.service.termux.AndroidAssetTermuxBridgeScriptSource
import com.zeroclaw.android.service.termux.AndroidTermuxBootstrapLauncher
import com.zeroclaw.android.service.termux.AndroidTermuxRuntimeProbe
import com.zeroclaw.android.service.termux.DefaultTermuxRuntimeStatusProvider
import com.zeroclaw.android.service.termux.HttpTermuxCapabilitiesClient
import com.zeroclaw.android.service.termux.HttpTermuxExecutionClient
import com.zeroclaw.android.service.termux.HttpTermuxHealthClient
import com.zeroclaw.android.service.termux.RoomTermuxAuditRepository
import com.zeroclaw.android.service.termux.TermuxCapabilitiesClient
import com.zeroclaw.android.service.termux.TermuxAuditRepository
import com.zeroclaw.android.service.termux.TermuxBridgeEndpoint
import com.zeroclaw.android.service.termux.TermuxBridgeSupervisor
import com.zeroclaw.android.service.termux.TermuxBootstrapLaunchStatus
import com.zeroclaw.android.service.termux.TermuxExecutionClient
import com.zeroclaw.android.service.termux.TermuxRuntimeContract
import com.zeroclaw.android.service.termux.TermuxRuntimeStatusProvider
import com.zeroclaw.android.service.termux.TermuxToolCatalog
import com.zeroclaw.android.service.sandbox.LinuxSandboxManager
import com.zeroclaw.android.service.sandbox.ProotToolCatalog
import com.zeroclaw.android.service.devicecontrol.DeviceControlCallbackHandler
import com.zeroclaw.android.service.sandbox.SandboxBridgeServer
import fi.iki.elonen.NanoHTTPD
import com.zeroclaw.android.startup.AppStartupTrace
import com.zeroclaw.android.startup.AppStartupTasks
import com.zeroclaw.android.startup.AppWorkManagerConfigurationFactory
import com.zeroclaw.android.startup.isColdStartCriticalWindow
import com.zeroclaw.android.startup.NativeRuntimeGate
import com.zeroclaw.android.util.SessionLockManager

import java.io.File
import java.util.UUID
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient

/**
 * Application subclass that initialises the native Zero-Assist library and
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
    private val processStartElapsedRealtimeMs = SystemClock.elapsedRealtime()

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

    /** Shared in-memory history repository for agent group chat previews. */
    lateinit var conversationHistoryRepository: ConversationHistoryRepository
        private set

    /** Repository for the currently active persisted agent conversation ID. */
    lateinit var activeConversationSessionRepository: ActiveConversationSessionRepository
        private set

    /** Coordinates session persistence, recovery, and drawer metadata updates. */
    lateinit var conversationSessionManager: ConversationSessionManager
        private set

    /** Dashboard welcome repository backed by Jetpack DataStore. */
    lateinit var welcomeRepository: WelcomeRepository
        private set

    /** Repository for locally persisted starred conversation IDs. */
    lateinit var starredConversationRepository: StarredConversationRepository
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

    /** Workspace repository for organizing agents and conversations. */
    lateinit var workspaceRepository: WorkspaceRepository
        private set

    /** Advanced memory repository for semantic search and importance tracking. */
    lateinit var advancedMemoryRepository: AdvancedMemoryRepository
        private set

    /** Channel repository for multi-platform integrations. */
    lateinit var channelRepository: ChannelRepository
        private set

    /** Agent tools repository for ask_user, escalate, swarm, llm_task, project_intel. */
    lateinit var agentToolsRepository: AgentToolsRepository
        private set

    /** Hardware expansion repository for device management, GPIO, sensors, and actuators. */
    lateinit var hardwareRepository: HardwareRepository
        private set

    /** Emergency stop state repository. */
    lateinit var estopRepository: EstopRepository
        private set

    /** Greeting history repository for dynamic welcome messages (non-repeating, AI-generated). */
    lateinit var greetingHistoryRepository: GreetingHistoryRepository
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

    /** Shared client for the official skills marketplace catalog. */
    lateinit var skillsMarketplaceClient: SkillsMarketplaceClient
        private set

    /** Official skills marketplace downloader and installer. */
    lateinit var skillsMarketplaceInstaller: SkillsMarketplaceInstaller
        private set

    /** Bridge for tools inventory browsing FFI calls. */
    lateinit var toolsBridge: ToolsBridge
        private set

    /** Per-process token for the app-owned Termux bridge over localhost. */
    private val internalTermuxBridgeToken: String by lazy {
        loadOrCreateInternalTermuxBridgeToken()
    }

    /** Starts the bundled Termux loopback bridge after Termux permission is available. */
    val termuxBridgeSupervisor: TermuxBridgeSupervisor by lazy {
        TermuxBridgeSupervisor(
            launcher = AndroidTermuxBootstrapLauncher(this),
            scriptSource = AndroidAssetTermuxBridgeScriptSource(this),
            tokenProvider = { internalTermuxBridgeToken },
        )
    }

    /** Per-process token for the app-owned Linux sandbox bridge over localhost. */
    private val internalSandboxBridgeToken: String by lazy {
        UUID.randomUUID().toString()
    }

    /** Self-contained Alpine Linux sandbox manager (proot-based). */
    val linuxSandboxManager: LinuxSandboxManager by lazy {
        LinuxSandboxManager(context = this)
    }

    /**
     * Lightweight HTTP server that lets the Rust daemon invoke sandbox_execute
     * and sandbox_manage_process via authenticated localhost callbacks.
     * 
     * The timeout is configured from app settings (default 3 hours).
     */
    val sandboxBridgeServer: SandboxBridgeServer by lazy {
        // Read timeout from settings synchronously during initialization
        // If settings aren't ready yet, use the default
        val timeoutSecs = runBlocking {
            kotlin.runCatching { 
                settingsRepository.settings.first().linuxSandboxTimeoutSecs.toLong()
            }.getOrDefault(SandboxBridgeServer.DEFAULT_MAX_TIMEOUT_SECS)
        }
        
        SandboxBridgeServer(
            sandboxManager = linuxSandboxManager,
            authToken = internalSandboxBridgeToken,
            maxTimeoutSecs = timeoutSecs,
        )
    }

    /** Shared Termux readiness provider used by tools, prompts, and diagnostics. */
    val termuxRuntimeStatusProvider: TermuxRuntimeStatusProvider by lazy {
        DefaultTermuxRuntimeStatusProvider(
            probe = AndroidTermuxRuntimeProbe(this),
            healthClient = createTermuxHealthClient(),
        )
    }

    /** Termux runtime probe for checking package, permission, and bootstrap state. */
    val termuxRuntimeProbe: com.zeroclaw.android.service.termux.TermuxRuntimeProbe by lazy {
        AndroidTermuxRuntimeProbe(this)
    }

    /** Termux health client for checking bridge readiness. */
    val termuxHealthClient: com.zeroclaw.android.service.termux.TermuxHealthClient by lazy {
        createTermuxHealthClient()
    }

    /** Authenticated Termux capability discovery client used by terminal diagnostics. */
    val termuxCapabilitiesClient: TermuxCapabilitiesClient by lazy {
        HttpTermuxCapabilitiesClient(
            httpClient = sharedHttpClient,
            endpoints = termuxBridgeEndpoints(),
        )
    }

    /** Authenticated low-risk Termux execution client used by terminal smoke tests. */
    val termuxExecutionClient: TermuxExecutionClient by lazy {
        HttpTermuxExecutionClient(
            httpClient = sharedHttpClient,
            endpoints = termuxBridgeEndpoints(),
        )
    }

    /** Durable Termux audit store for approval, denial, block, and execution outcomes. */
    val termuxAuditRepository: TermuxAuditRepository by lazy {
        RoomTermuxAuditRepository(database.termuxAuditDao())
    }

    /** Bridge for memory browsing and management FFI calls. */
    lateinit var memoryBridge: MemoryBridge
        private set

    /** Bridge for direct-to-provider multimodal vision API calls. */
    val visionBridge: VisionBridge by lazy { VisionBridge() }

    /** One-shot requests to open the assistant popup from Android assistant entrypoints. */
    val voiceAssistantLaunchRequests: VoiceAssistantLaunchRequests by lazy {
        VoiceAssistantLaunchRequests()
    }

    /** Bridge for on-device Gemini Nano prompt inference. */
    val onDeviceInferenceBridge: OnDeviceInferenceBridge by lazy { OnDeviceInferenceBridge() }

    /** Local on-device LiteRT LM inference engine. */
    val liteRtInferenceEngine: LocalInferenceEngine by lazy {
        LiteRTInferenceEngine(this)
    }

    /** Bridge for on-device text summarization. */
    val onDeviceSummarizerBridge: OnDeviceSummarizerBridge by lazy {
        OnDeviceSummarizerBridge(this)
    }

    /** Bridge for on-device proofreading. */
    val onDeviceProofreaderBridge: OnDeviceProofreaderBridge by lazy {
        OnDeviceProofreaderBridge(this)
    }

    /** Bridge for on-device rewriting. */
    val onDeviceRewriterBridge: OnDeviceRewriterBridge by lazy {
        OnDeviceRewriterBridge(this)
    }

    /** Bridge for on-device image description. */
    val onDeviceImageDescriberBridge: OnDeviceImageDescriberBridge by lazy {
        OnDeviceImageDescriberBridge(this)
    }

    /** Bridge for MediaProjection-backed screen capture. */
    val screenCaptureBridge: ScreenCaptureBridge by lazy { ScreenCaptureBridge(this) }

    /** Shared catalog for downloadable/imported offline assistant voices. */
    val localVoiceCatalogRepository: LocalVoiceCatalogRepository by lazy {
        LocalVoiceCatalogRepository(
            store = SharedPreferencesLocalVoiceCatalogStore(this),
        )
    }

    /** App-private storage for imported custom voice model files. */
    val localVoiceStorage: LocalVoiceStorage by lazy {
        AndroidLocalVoiceStorage(this)
    }

    /** Downloads catalog voices into app-private storage before local playback. */
    val voiceDownloadManager: LocalVoiceDownloadManager by lazy {
        LocalVoiceDownloadManager(
            voiceCatalogRepository = localVoiceCatalogRepository,
            localVoiceStorage = localVoiceStorage,
            // PiperOnnxPackageDownloader fetches the raw .onnx + .onnx.json pair from
            // the catalog's downloadUri (HuggingFace) and packages them on-device into
            // the .voicepkg format that LocalVoiceStorage.importPackage() expects.
            downloader =
                PiperOnnxPackageDownloader(
                    cacheRoot = File(cacheDir, "voice-downloads"),
                    client = sharedHttpClient,
                ),
        )
    }

    /** User-controlled speech performance mode for assistant output routing. */
    val voiceOutputPreferences: VoiceOutputPreferences by lazy {
        SharedPreferencesVoiceOutputPreferences(this)
    }

    /** App-wide queue for local voice transcripts that should become terminal tasks. */
    val voiceTaskRequests: Channel<VoiceTaskRequest> = Channel(Channel.BUFFERED)

    /** Local transcript handoff into the main app task pipeline. */
    val voiceTaskHandoff: VoiceTaskHandoff by lazy {
        ChannelVoiceTaskHandoff(voiceTaskRequests)
    }

    /** Direct ZeroClaw session path for popup voice turns that need a spoken answer. */
    val voiceAssistantConversation: VoiceAssistantConversation by lazy {
        ZeroClawVoiceAssistantConversation()
    }

    /** Local contact lookup for permission-gated voice call commands. */
    val voiceContactLookup: VoiceContactLookup by lazy {
        AndroidVoiceContactLookup(this)
    }

    /** Speech recognizer for popup mic dictation, tuned for best one-shot accuracy. */
    val localSpeechRecognizer: LocalSpeechRecognizer by lazy {
        AndroidOnDeviceSpeechRecognizer(
            context = this,
            allowNetworkRecognition = true,
        )
    }

    /** Strict local/offline recognizer for foreground wake-phrase detection. */
    private val wakeupSpeechRecognizer: LocalSpeechRecognizer by lazy {
        AndroidOnDeviceSpeechRecognizer(this)
    }

    /** Local wake-phrase detector backed by Android's strict on-device speech recognizer. */
    val voiceWakeupDetector: VoiceWakeupDetector by lazy {
        LocalSpeechWakeupDetector(
            localSpeechRecognizer = wakeupSpeechRecognizer,
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate),
        )
    }

    /** Android foreground-service controller for guarded local wake-word startup. */
    val voiceWakeupServiceController: VoiceWakeupServiceController by lazy {
        AndroidVoiceWakeupServiceController(this)
    }

    /** Local-only speech synthesizer for assistant previews and popup speech output. */
    val localSpeechSynthesizer: LocalSpeechSynthesizer by lazy {
        val voiceDeviceProfile = AndroidVoiceDeviceProfile.current(this)
        LocalSpeechOutputAdapter(
            driver =
                CompositeLocalSpeechOutputDriver(
                    drivers =
                        listOf(
                            CustomVoiceSpeechOutputDriver(
                                runtime =
                                    AndroidOnnxPiperVoiceRuntime(
                                        threadPolicy =
                                            PiperOnnxRuntimeThreadPolicy.default(voiceDeviceProfile),
                                    ),
                                deviceTier = voiceDeviceProfile.tier,
                                firstAudioTimeoutMs = defaultFirstAudioTimeoutMs(voiceDeviceProfile.tier),
                            ),
                            AndroidLocalSpeechOutputDriver(this),
                        ),
                    scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
                    routingPolicy =
                        VoiceOutputRoutingPolicy(
                            deviceProfile = voiceDeviceProfile,
                            modeProvider = { voiceOutputPreferences.performanceMode.value },
                        ),
                ),
        )
    }

    /** Shared in-memory repository for live agent status. */
    lateinit var agentStatusRepository: AgentStatusRepository
        private set

    /** App-wide session lock manager observing the process lifecycle. */
    lateinit var sessionLockManager: SessionLockManager
        private set

    /** App-wide conversation lifecycle observer. */
    lateinit var conversationSessionLifecycleObserver: ConversationSessionLifecycleObserver
        private set

    /** Reconciles persisted wake-up preference when the app returns to the foreground. */
    lateinit var voiceWakeupStartupCoordinator: VoiceWakeupStartupCoordinator
        private set

    /** True once database-backed repositories are ready for UI consumers. */
    private val _repositoriesReady = MutableStateFlow(false)
    val repositoriesReady: StateFlow<Boolean> = _repositoriesReady.asStateFlow()

    /** Completes when native libraries (SQLCipher + zeroclaw) are loaded on a background thread. */
    private val nativeLibrariesReady = CompletableDeferred<Unit>()

    /** Initialized in onCreate; used for all background coroutine work. */
    private lateinit var ioScope: CoroutineScope

    /** Initialized in onCreate background thread after native libraries load. */
    private lateinit var nativeRuntimeGate: NativeRuntimeGate

    val driveBackupManager: DriveBackupManager by lazy {
        DriveBackupManager(this)
    }

    private lateinit var settingsDataStore: DataStore<Preferences>

    // Issue 2: state LazyThreadSafetyMode explicitly so the intent is unambiguous.
    // The default is already SYNCHRONIZED but declaring it prevents accidental
    // future changes from silently breaking thread-safety guarantees.
    val syncRepository: SyncRepository by lazy(LazyThreadSafetyMode.SYNCHRONIZED) {
        SyncRepository(
            context = this,
            settingsDataStore = settingsDataStore,
            driveBackupManager = driveBackupManager,
            agentDaoProvider = { database.agentDao() },
            pluginDaoProvider = { database.pluginDao() },
            connectedChannelDaoProvider = { database.connectedChannelDao() },
        )
    }

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
        AppHttpClientFactory.create()
    }

    override val workManagerConfiguration: Configuration
        get() =
            AppWorkManagerConfigurationFactory.create(debug = BuildConfig.DEBUG)

    override fun onCreate() {
        super.onCreate()
        AppStartupTrace.section("workmanager_initialize") {
            WorkManager.initialize(this, workManagerConfiguration)
        }

        agentStatusRepository = AgentStatusRepository()
        daemonBridge = DaemonServiceBridge(filesDir.absolutePath, agentStatusRepository)

        settingsDataStore = DataStoreProvider.getSettingsDataStore(this)
        settingsRepository = DataStoreSettingsRepository(this, settingsDataStore)
        apiKeyRepository = createApiKeyRepository(CoroutineScope(SupervisorJob() + Dispatchers.IO))
        onboardingRepository = DataStoreOnboardingRepository(this)
        activeConversationSessionRepository = DataStoreActiveConversationSessionRepository(this)
        welcomeRepository = DataStoreWelcomeRepository(this)
        starredConversationRepository = DataStoreStarredConversationRepository(this)
        workspaceRepository = DataStoreWorkspaceRepository(this)

        ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        estopRepository = EstopRepository(scope = ioScope)

        // All heavy I/O (native library loading, web asset extraction, sandbox server
        // startup) is moved to a background thread to prevent ANR under memory pressure.
        ioScope.launch {
            AppStartupTrace.section("native_runtime_gate_create") {
                nativeRuntimeGate = NativeRuntimeGate()
            }
            AppStartupTrace.section("native_runtime_load_libraries") {
                nativeRuntimeGate.loadLibraries()
            }
            nativeLibrariesReady.complete(Unit)

            // Estop polling uses FFI — safe to start after native libraries load.
            estopRepository.startPolling()

            AppStartupTrace.section("native_runtime_publish_tokens") {
                com.zeroclaw.ffi.setTermuxBridgeAuthToken(internalTermuxBridgeToken)
                com.zeroclaw.ffi.setSandboxBridgeAuthToken(internalSandboxBridgeToken)
                com.zeroclaw.ffi.setWorkspaceDir(File(filesDir, "workspace").absolutePath)
                publishInternalTermuxBridgeTokenToNativeEnv()
                publishInternalSandboxBridgeTokenToNativeEnv()
            }

            AppStartupTrace.section("sandbox_bridge_start") {
                try {
                    sandboxBridgeServer.start(NanoHTTPD.SOCKET_READ_TIMEOUT, true)
                } catch (e: java.io.IOException) {
                    Log.w(TAG, "SandboxBridgeServer failed to start: ${e.message}")
                }
            }

            AppStartupTrace.section("native_runtime_verify_and_overlay") {
                nativeRuntimeGate.verifyCrateVersion()
                nativeRuntimeGate.installBundledConfigOverlay(this@ZeroClawApplication)
            }

            withContext(Dispatchers.IO) {
                AppStartupTrace.section("web_assets_extract") {
                    ExternalZeroClawConfig.extractWebAssets(this@ZeroClawApplication)
                }
            }

            initializeDatabaseAsync(ioScope)
        }

        healthBridge = HealthBridge()
        costBridge = CostBridge()
        cronBridge = CronBridge()
        skillsBridge = SkillsBridge()
        skillsMarketplaceClient = OkHttpSkillsMarketplaceClient(sharedHttpClient)
        skillsMarketplaceInstaller =
            SkillsMarketplaceInstaller(
                marketplaceClient = skillsMarketplaceClient,
                skillsBridge = skillsBridge,
                cacheRoot = File(cacheDir, "skills-marketplace"),
            )
        val termuxToolCatalog = TermuxToolCatalog(
                            termuxRuntimeStatusProvider,
                        )
        toolsBridge =
            ToolsBridge(
                appOwnedToolCatalog =
                    CompositeAppOwnedToolCatalog(
                        termuxToolCatalog,
                        ProotToolCatalog(
                            sandboxManager = linuxSandboxManager,
                        ),
                    ),
            )
        ioScope.launch {
            settingsRepository.settings.collect { settings ->
                termuxToolCatalog.setPluginEnabled(settings.termuxEnabled)
                publishTermuxEnabledToNativeEnv(settings.termuxEnabled)
            }
        }
        memoryBridge = MemoryBridge()
        sessionLockManager = SessionLockManager(settingsRepository.settings, ioScope)
        ProcessLifecycleOwner.get().lifecycle.addObserver(sessionLockManager)
        voiceWakeupStartupCoordinator =
            VoiceWakeupStartupCoordinator(
                voiceWakeupPreferences = SettingsVoiceWakeupPreferences(settingsRepository),
                voiceWakeupDetectorProvider = { voiceWakeupDetector },
                voiceWakeupServiceController = voiceWakeupServiceController,
                scope = ioScope,
            )
        ProcessLifecycleOwner.get().lifecycle.addObserver(voiceWakeupStartupCoordinator)
        syncDaemonState(ioScope)
        migrateStaleOAuthEntries(ioScope)
    }

    /**
     * Initializes the database asynchronously on a background thread.
     * Waits for native libraries to finish loading first, since SQLCipher
     * requires them. After database is ready, completes repository initialization.
     */
    private fun initializeDatabaseAsync(ioScope: CoroutineScope) {
        ioScope.launch {
            try {
                // Wait for native libraries to load — SQLCipher depends on them.
                nativeLibrariesReady.await()

                database =
                    AppStartupTrace.suspendSection("database_build_sqlcipher") {
                        ZeroClawDatabase.build(this@ZeroClawApplication, ioScope)
                    }
                // Eagerly open the SQLCipher connection so the ~800ms keying happens
                // on the IO thread before any repository queries touch the main thread.
                AppStartupTrace.section("database_eager_warmup") {
                    AppStartupTasks.warmDatabase(database)
                }
                AppStartupTrace.suspendSection("database_repositories_initialize") {
                    initializeRepositoriesAfterDatabase(ioScope)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to initialize database", e)
                // Re-throw so the app crashes with clear error instead of hanging
                throw e
            }
        }
    }

    /**
     * Initializes all repositories after database is available.
     * Called from [initializeDatabaseAsync] after database build completes.
     * Suspends to switch to main thread for lifecycle operations.
     */
    private suspend fun initializeRepositoriesAfterDatabase(ioScope: CoroutineScope) {
        logRepository = RoomLogRepository(database.logEntryDao(), ioScope)
        activityRepository = RoomActivityRepository(database.activityEventDao(), ioScope)
        conversationHistoryRepository = RoomConversationHistoryRepository(database.conversationDao())
        agentRepository = RoomAgentRepository(database, syncRepository)
        pluginRepository = RoomPluginRepository(database.pluginDao(), syncRepository)
        channelConfigRepository = createChannelConfigRepository()
        terminalEntryRepository =
            RoomTerminalEntryRepository(database.terminalEntryDao(), ioScope)
        
        // Initialize advanced memory system
        val semanticService = SemanticSimilarityService()
        val scoringEngine = ImportanceScoringEngine()
        advancedMemoryRepository = RoomAdvancedMemoryRepository(
            database.memoryFactDao(),
            semanticService,
            scoringEngine,
        )
        
        // Initialize channel repository for integrations
        channelRepository = RoomChannelRepository(database.channelConfigurationDao())
        
        // Initialize agent tools repository
        agentToolsRepository = RoomAgentToolsRepository(
            database.askUserRequestDao(),
            database.agentEscalationDao(),
            database.agentSwarmDao(),
            database.llmTaskDao(),
            database.projectIntelligenceDao(),
            database.agentToolTraceDao(),
            this@ZeroClawApplication,
        )
        
        // Initialize hardware expansion repository
        hardwareRepository = RoomHardwareRepository(
            database.hardwareDeviceDao(),
            database.gpioPinDao(),
            database.sensorReadingDao(),
            database.sensorAlertDao(),
            database.actuatorCommandDao(),
            database.hardwareAuditLogDao(),
            this@ZeroClawApplication,
        )
        
        eventBridge = EventBridge(activityRepository, ioScope)
        daemonBridge.eventBridge = eventBridge
        conversationSessionManager =
            ConversationSessionManager(
                database = database,
                workspaceRepository = workspaceRepository,
                agentRepository = agentRepository,
                activeConversationSessionRepository = activeConversationSessionRepository,
                daemonBridge = daemonBridge,
            )

        conversationSessionLifecycleObserver =
            ConversationSessionLifecycleObserver(
                sessionManager = conversationSessionManager,
                scope = ioScope,
            )
        
        // Switch to main thread for lifecycle operations
        withContext(Dispatchers.Main) {
            ProcessLifecycleOwner.get().lifecycle.addObserver(conversationSessionLifecycleObserver)
        }

        _repositoriesReady.value = true
        
        ioScope.launch {
            reconcileMqttPluginChannelState()
            conversationSessionManager.archiveRecoveredSessionIfNeeded()
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun reconcileMqttPluginChannelState() {
        try {
            val plugin = pluginRepository.getById(CommunityPlugins.MQTT_CHANNEL) ?: return
            if (!plugin.isInstalled || !plugin.isEnabled) return

            val channel = channelConfigRepository.getByType(ChannelType.MQTT)
            if (channel?.isEnabled == true) return

            MqttPluginChannelSync.setEnabled(
                pluginRepository = pluginRepository,
                channelRepository = channelConfigRepository,
                enabled = true,
            )
            daemonBridge.markRestartRequired()
            Log.i(TAG, "Reconciled enabled MQTT plugin with connected channel state")
        } catch (e: Exception) {
            Log.w(TAG, "MQTT plugin/channel reconciliation failed", e)
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

    /**
     * Initializes deferred WorkManager tasks after the UI has started rendering.
     *
     * Called from MainActivity.onCreate to avoid blocking the main thread during
     * application startup. This ensures that background work scheduling does not
     * interfere with the first UI frame and prevents ANR (Application Not Responding)
     * timeouts caused by excessive initialization work.
     *
     * Uses a boolean flag to ensure this is only called once, even if MainActivity
     * is recreated (e.g., due to configuration changes).
     */
    @Suppress("InjectDispatcher")
    fun initializeDeferredWorkManager() {
        if (deferredWorkManagerInitialized) return
        deferredWorkManagerInitialized = true

        AppStartupTrace.mark("deferred_workmanager_initialize_requested")
        Log.d(TAG, "Initializing deferred WorkManager tasks")

        val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
        val ioScope = CoroutineScope(SupervisorJob() + ioDispatcher)

        AppStartupTasks.startDeferredWorkManagerTasks(
            context = this,
            scope = ioScope,
            repositoriesReady = repositoriesReady,
            settingsRepository = settingsRepository,
            syncRepository = syncRepository,
        )

        initializeDeferredTermuxBridgeSupervision(ioScope)
        Log.d(TAG, "Termux bridge startup is demand-driven.")
    }

    fun isColdStartCriticalPathActive(
        nowElapsedRealtimeMs: Long = SystemClock.elapsedRealtime(),
    ): Boolean =
        isColdStartCriticalWindow(
            processStartElapsedRealtimeMs = processStartElapsedRealtimeMs,
            nowElapsedRealtimeMs = nowElapsedRealtimeMs,
        )

    private var deferredWorkManagerInitialized = false
    private var deferredTermuxBridgeSupervisionInitialized = false

    private fun publishInternalTermuxBridgeTokenToNativeEnv() {
        try {
            Os.setenv(
                TERMUX_BRIDGE_TOKEN_ENV,
                internalTermuxBridgeToken,
                true,
            )
            // Default Termux tools OFF — the sandbox is the only shell backend
            // until the user explicitly enables the Termux plugin. The settings
            // collector keeps this env var in sync at runtime.
            Os.setenv(TERMUX_ENABLED_ENV, "0", true)
        } catch (e: ErrnoException) {
            Log.w(
                TAG,
                "Failed to publish Termux bridge token to native runtime: ${e.message}",
            )
        }
    }

    /** Publishes whether Termux bridge tools should be registered in sessions. */
    private fun publishTermuxEnabledToNativeEnv(enabled: Boolean) {
        try {
            Os.setenv(TERMUX_ENABLED_ENV, if (enabled) "1" else "0", true)
        } catch (e: ErrnoException) {
            Log.w(TAG, "Failed to publish Termux enabled flag to native runtime: ${e.message}")
        }
    }

    private fun publishInternalSandboxBridgeTokenToNativeEnv() {
        try {
            Os.setenv(SANDBOX_BRIDGE_TOKEN_ENV, internalSandboxBridgeToken, true)
            Os.setenv(
                SANDBOX_BRIDGE_BASE_URL_ENV,
                "http://127.0.0.1:${SandboxBridgeServer.DEFAULT_PORT}",
                true,
            )
        } catch (e: ErrnoException) {
            Log.w(TAG, "Failed to publish sandbox bridge token to native runtime: ${e.message}")
        }
    }

    private fun loadOrCreateInternalTermuxBridgeToken(): String {
        val prefs = getSharedPreferences(TERMUX_BRIDGE_PREFS, Context.MODE_PRIVATE)
        prefs.getString(TERMUX_BRIDGE_TOKEN_PREF_KEY, null)
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }

        return UUID.randomUUID().toString().also { token ->
            prefs.edit()
                .putString(TERMUX_BRIDGE_TOKEN_PREF_KEY, token)
                .apply()
        }
    }

    private fun createTermuxHealthClient(): HttpTermuxHealthClient =
        HttpTermuxHealthClient(
            httpClient = sharedHttpClient,
            endpoints = termuxBridgeEndpoints(),
        )

    private fun termuxBridgeEndpoints(): List<TermuxBridgeEndpoint> =
        listOf(
            TermuxBridgeEndpoint(
                baseUrl = TermuxRuntimeContract.DEFAULT_BRIDGE_BASE_URL,
                token = internalTermuxBridgeToken,
                tokenHeaderName = TermuxRuntimeContract.BRIDGE_TOKEN_HEADER,
                useBearerPrefix = false,
            ),
            TermuxBridgeEndpoint(
                baseUrl = TermuxRuntimeContract.FALLBACK_BRIDGE_BASE_URL,
                token = internalTermuxBridgeToken,
                tokenHeaderName = TermuxRuntimeContract.BRIDGE_TOKEN_HEADER,
                useBearerPrefix = false,
            ),
        )

    @Suppress("TooGenericExceptionCaught")
    private fun initializeDeferredTermuxBridgeSupervision(ioScope: CoroutineScope) {
        if (deferredTermuxBridgeSupervisionInitialized) return
        deferredTermuxBridgeSupervisionInitialized = true

        ioScope.launch {
            // The Termux bridge is opt-in: it must not spin up on devices that
            // only use the sandbox shell backend.
            val termuxEnabled = settingsRepository.settings.first().termuxEnabled
            if (!termuxEnabled) {
                Log.d(TAG, "Termux bridge supervision skipped — Termux disabled in settings.")
                return@launch
            }
            runCatching { termuxBridgeSupervisor.ensureStarted() }
                .onSuccess { result ->
                    when (result.status) {
                        TermuxBootstrapLaunchStatus.STARTED ->
                            Log.i(TAG, "Deferred Termux bridge startup requested.")
                        TermuxBootstrapLaunchStatus.FAILED ->
                            Log.w(TAG, "Deferred Termux bridge startup failed: ${result.reason}")
                    }
                }
                .onFailure { error ->
                    Log.w(
                        TAG,
                        "Deferred Termux bridge supervision startup failed: ${error.message}",
                    )
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
            syncRepository,
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
        private const val TAG = "ZeroAssistApp"
        private const val DB_QUERY_TIMEOUT_MS = 5000L
        private const val DISK_CACHE_SIZE_BYTES = 512L * 1024 * 1024
        private const val MEMORY_CACHE_PERCENT = 0.25
        private const val STALE_OAUTH_PROVIDER = "openai"
        private const val CODEX_PROVIDER = "openai-codex"
        private const val CHANNEL_CONFIG_PREFS = "connected_channel_secrets"
        private const val TERMUX_BRIDGE_TOKEN_ENV = "ZERO_ASSIST_TERMUX_BRIDGE_TOKEN"
        private const val TERMUX_ENABLED_ENV = "ZERO_ASSIST_TERMUX_ENABLED"
        private const val TERMUX_BRIDGE_PREFS = "termux_bridge"
        private const val TERMUX_BRIDGE_TOKEN_PREF_KEY = "bridge_token"
        private const val SANDBOX_BRIDGE_TOKEN_ENV = "ZERO_ASSIST_SANDBOX_BRIDGE_TOKEN"
        private const val SANDBOX_BRIDGE_BASE_URL_ENV = "ZERO_ASSIST_SANDBOX_BRIDGE_BASE_URL"
    }
}
