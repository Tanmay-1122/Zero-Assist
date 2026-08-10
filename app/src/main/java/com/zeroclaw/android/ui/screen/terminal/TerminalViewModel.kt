/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.terminal

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.Immutable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zeroclaw.android.ZeroClawApplication
import com.zeroclaw.android.data.ProviderRegistry
import com.zeroclaw.android.data.model.CanvasFrame
import com.zeroclaw.android.model.AppSettings
import com.zeroclaw.android.model.BackgroundProcessState
import com.zeroclaw.android.model.LogSeverity
import com.zeroclaw.android.model.OnDeviceCaptureRequest
import com.zeroclaw.android.model.OnDeviceTool
import com.zeroclaw.android.model.ProcessType
import com.zeroclaw.android.model.ProcessedImage
import com.zeroclaw.android.model.ProviderAuthType
import com.zeroclaw.android.model.RefreshCommand
import com.zeroclaw.android.model.ServiceState
import com.zeroclaw.android.model.TerminalEntry
import com.zeroclaw.android.model.ToolSpec
import com.zeroclaw.android.media.MediaIntentClassifier
import com.zeroclaw.android.media.NativeImageSearch
import com.zeroclaw.android.service.BackgroundProcessLogger
import com.zeroclaw.android.service.ConversationSeedMessage
import com.zeroclaw.android.model.McpServerEntry
import com.zeroclaw.android.service.ConfigTomlBuilder
import kotlinx.serialization.json.Json
import com.zeroclaw.android.service.ConversationSessionBridge
import com.zeroclaw.android.service.ConversationSessionListener
import com.zeroclaw.android.service.ZeroClawDaemonService
import com.zeroclaw.android.service.termux.TermuxBootstrapAvailability
import com.zeroclaw.android.service.termux.TermuxBootstrapLaunchResult
import com.zeroclaw.android.service.termux.TermuxBootstrapLaunchStatus
import com.zeroclaw.android.service.termux.TermuxCapabilitiesResult
import com.zeroclaw.android.service.termux.TermuxCapabilitiesSnapshot
import com.zeroclaw.android.service.termux.TermuxExecutionRequest
import com.zeroclaw.android.service.termux.TermuxExecutionApproval
import com.zeroclaw.android.service.termux.TermuxExecutionResult
import com.zeroclaw.android.service.termux.TermuxExecutionSnapshot
import com.zeroclaw.android.service.termux.TermuxHealthStatus
import com.zeroclaw.android.service.termux.TermuxPackageAvailability
import com.zeroclaw.android.service.termux.TermuxPermissionAvailability
import com.zeroclaw.android.service.termux.TermuxRuntimeStatus
import com.zeroclaw.android.service.termux.TermuxApprovalRequest
import com.zeroclaw.android.service.termux.TermuxAuditState
import com.zeroclaw.android.service.termux.TermuxRuntimeContract
import com.zeroclaw.android.service.getBackgroundProcessLogger
import com.zeroclaw.android.service.InferenceMessage
import com.zeroclaw.android.service.EngineState
import com.zeroclaw.android.util.ErrorSanitizer
import com.zeroclaw.android.util.ImageProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.ArrayDeque

/**
 * ViewModel for the terminal REPL screen.
 *
 * Routes user input through the [CommandRegistry] to classify it as a Rhai
 * expression, a local action, or a chat message, then dispatches accordingly.
 * Rhai expressions are evaluated against the daemon via FFI on
 * [Dispatchers.IO]. All inputs and outputs are persisted through the
 * [TerminalEntryRepository][com.zeroclaw.android.data.repository.TerminalEntryRepository]
 * so history survives navigation and app restarts.
 *
 * Supports image attachments via the photo picker, with vision requests
 * routed through the `send_vision` Rhai function.
 *
 * @param application Application context for accessing repositories and bridges.
 */
class TerminalViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val app = application as ZeroClawApplication
    private val repository = app.terminalEntryRepository
    private val logRepository = app.logRepository
    private val settingsRepository = app.settingsRepository
    private val agentRepository = app.agentRepository
    private val apiKeyRepository = app.apiKeyRepository
    private val sessionBridge = ConversationSessionBridge()

    private val cachedSettings: StateFlow<AppSettings> =
        settingsRepository.settings
            .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    private val loadingState = MutableStateFlow(false)
    private val pendingImagesState = MutableStateFlow<List<ProcessedImage>>(emptyList())
    private val processingImagesState = MutableStateFlow(false)
    private val _streamingState = MutableStateFlow(StreamingState.idle())
    /**
     * Monotonically increasing turn identifier. Bumped at the start of every
     * agent turn; each [KotlinSessionListener] captures the value current at
     * its creation and every callback checks it against the live counter
     * before mutating [_streamingState]. This guards against a callback from
     * an abandoned/overlapping native session_send call landing after a
     * newer turn has already started or finished, which otherwise reopens
     * (or permanently stalls) the "Responding..." card after a reply has
     * already rendered.
     */
    private val turnGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    /** Watchdog job that force-resolves a turn if no terminal callback arrives in time. */
    private var turnWatchdogJob: Job? = null
    private val _history = MutableStateFlow<List<String>>(emptyList())
    private val _historyIndex = MutableStateFlow(NO_HISTORY_SELECTION)
    private val _isSessionReady = MutableStateFlow(false)
    private val _cameraCaptureRequest = MutableStateFlow<OnDeviceCaptureRequest?>(null)
    private val _screenCaptureRequest = MutableStateFlow<OnDeviceCaptureRequest?>(null)
    private val pendingTermuxApprovalState = MutableStateFlow<TermuxApprovalRequest?>(null)
    
    // Background process tracking
    private val backgroundLogger = getBackgroundProcessLogger()
    val backgroundProcessState: StateFlow<BackgroundProcessState> =
        backgroundLogger.state.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            BackgroundProcessState(),
        )
    
    private val agentTurnMutex = Mutex()
    private val sessionLifecycleMutex = Mutex()
    private var sessionIsActive = false
    private var pendingSessionDestroyAfterDaemonRestart = false
    private var pendingCameraPrompt: String = ""
    private var pendingScreenPrompt: String = ""
    /** Tracks recipient/app from a bodyless message request so the next input can complete it. */
    private var pendingMessageContext: PendingMessageContext? = null

    /** Observable terminal state combining persisted entries with transient UI state. */
    val state: StateFlow<TerminalState> =
        combine(
            repository.entries,
            loadingState,
            pendingImagesState,
            processingImagesState,
            pendingTermuxApprovalState,
        ) { entries, loading, images, processingImages, pendingTermuxApproval ->
            TerminalState(
                blocks = entries.map(::toBlock),
                isLoading = loading,
                pendingImages = images,
                isProcessingImages = processingImages,
                pendingTermuxApproval = pendingTermuxApproval,
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TerminalState())

    /** Previous input lines for history navigation, newest last. */
    val history: StateFlow<List<String>> = _history.asStateFlow()

    /** Current position in the input history, or -1 when not navigating. */
    val historyIndex: StateFlow<Int> = _historyIndex.asStateFlow()

    /** Observable streaming state for the live agent session. */
    val streamingState: StateFlow<StreamingState> = _streamingState.asStateFlow()

    /** True when the native agent session is active and ready. */
    val isSessionReady: StateFlow<Boolean> = _isSessionReady.asStateFlow()

    /** One-shot request for the UI to open the camera capture sheet. */
    val cameraCaptureRequest: StateFlow<OnDeviceCaptureRequest?> = _cameraCaptureRequest.asStateFlow()

    /** One-shot request for the UI to launch the screen capture consent flow. */
    val screenCaptureRequest: StateFlow<OnDeviceCaptureRequest?> = _screenCaptureRequest.asStateFlow()

    private val _canvasPanelOpen = MutableStateFlow(false)
    val canvasPanelOpen: StateFlow<Boolean> = _canvasPanelOpen.asStateFlow()

    private val _activeCanvasId = MutableStateFlow("default")
    val activeCanvasId: StateFlow<String> = _activeCanvasId.asStateFlow()

    fun toggleCanvasPanel() {
        _canvasPanelOpen.value = !_canvasPanelOpen.value
    }

    fun setActiveCanvasId(canvasId: String) {
        _activeCanvasId.value = canvasId
    }

    init {
        addWelcomeMessage()
        observeDaemonRestarts()
        observeRefreshCommands()
        observeVoiceTaskRequests()
    }


    /**
     * Tears down the agent session when the ViewModel is destroyed.
     */
    @Suppress("TooGenericExceptionCaught")
    override fun onCleared() {
        super.onCleared()
        try {
            sessionBridge.destroy()
            sessionIsActive = false
            pendingSessionDestroyAfterDaemonRestart = false
            _isSessionReady.value = false
        } catch (e: Exception) {
            logRepository.append(LogSeverity.WARN, TAG, "Session destroy failed: ${e.message}")
        }
    }

    /**
     * Invalidates the cached native session when the daemon restarts.
     *
     * Shared-folder tool availability is decided when the Rust session is
     * created. If the daemon hot-reloads after plugin or folder settings
     * change, the terminal must rebuild its session before the next turn so
     * the tool registry and system prompt reflect the new daemon config.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun observeDaemonRestarts() {
        viewModelScope.launch {
            app.daemonBridge.serviceState.collect { state ->
                if (state == ServiceState.RUNNING) {
                    finalizePendingSessionResetAfterDaemonRestart()
                } else {
                    invalidateSessionForDaemonRestart(state)
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun invalidateSessionForDaemonRestart(state: ServiceState) {
        var shouldLog = false
        sessionLifecycleMutex.withLock {
            if (!sessionIsActive) return@withLock
            sessionIsActive = false
            pendingSessionDestroyAfterDaemonRestart = true
            _isSessionReady.value = false
            shouldLog = true
        }

        if (shouldLog) {
            logRepository.append(
                LogSeverity.DEBUG,
                TAG,
                "Daemon state changed to $state; invalidating cached terminal session.",
            )
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun finalizePendingSessionResetAfterDaemonRestart() {
        var shouldDestroy = false
        sessionLifecycleMutex.withLock {
            if (!pendingSessionDestroyAfterDaemonRestart) return@withLock
            pendingSessionDestroyAfterDaemonRestart = false
            shouldDestroy = true
        }

        if (!shouldDestroy) return

        runCatching {
            withContext(Dispatchers.IO) {
                sessionBridge.destroy()
            }
        }.onFailure {
            logRepository.append(
                LogSeverity.WARN,
                TAG,
                "Session cleanup after daemon restart failed: ${it.message}",
            )
        }

        logRepository.append(
            LogSeverity.DEBUG,
            TAG,
            "Daemon restarted; terminal session will be rebuilt on the next turn.",
        )
    }

    private fun observeRefreshCommands() {
        viewModelScope.launch {
            app.refreshCommands.collect { command ->
                if (command == RefreshCommand.Skills) {
                    invalidateSessionForSkillChange()
                }
            }
        }
    }

    private fun observeVoiceTaskRequests() {
        viewModelScope.launch {
            app.voiceTaskRequests.receiveAsFlow().collect { request ->
                submitInput(request.text)
            }
        }
    }

    private suspend fun invalidateSessionForSkillChange() {
        forceResetSessionInternal()
        logRepository.append(
            LogSeverity.DEBUG,
            TAG,
            "Skills changed; terminal session will rebuild on the next turn.",
        )
    }

    /**
     * Submits user input for processing.
     *
     * Parses the input through [CommandRegistry.parseAndTranslate] and
     * dispatches based on the result type:
     * - [CommandResult.RhaiExpression]: persists the input, evaluates
     *   via FFI, and persists the response or error.
     * - [CommandResult.LocalAction]: handles "help" and "clear" locally.
     * - [CommandResult.ChatMessage]: wraps in a `send()` Rhai call,
     *   or `send_vision()` when images are attached.
     *
     * @param text The raw text entered by the user.
     */
    fun submitInput(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() && pendingImagesState.value.isEmpty()) return

        appendToHistory(trimmed)
        _historyIndex.value = NO_HISTORY_SELECTION

        val result = CommandRegistry.parseAndTranslate(trimmed)
        when (result) {
            is CommandResult.RhaiExpression -> executeRhai(trimmed, result.expression)
            is CommandResult.LocalAction ->
                handleLocalAction(
                    action = result.action,
                    args = result.args,
                    rawArgsText = result.rawArgsText,
                    displayText = result.displayText,
                )
            is CommandResult.ChatMessage ->
                executeChatMessage(
                    displayText = trimmed,
                )
        }
    }

    fun performOnDeviceTool(
        tool: OnDeviceTool,
        draft: String = "",
    ) {
        val trimmedDraft = draft.trim()
        when (tool) {
            OnDeviceTool.CAMERA_CAPTURE -> {
                pendingCameraPrompt = trimmedDraft
                _cameraCaptureRequest.value = OnDeviceCaptureRequest(prompt = trimmedDraft)
            }

            OnDeviceTool.SCREEN_CAPTURE -> {
                pendingScreenPrompt = trimmedDraft
                _screenCaptureRequest.value = OnDeviceCaptureRequest(prompt = trimmedDraft)
            }

            else -> {
                viewModelScope.launch {
                    executeOnDeviceTool(
                        tool = tool,
                        prompt = trimmedDraft,
                        images = pendingImagesState.value,
                        displayInput = trimmedDraft,
                    )
                }
            }
        }
    }

    fun dismissCameraCaptureRequest() {
        _cameraCaptureRequest.value = null
        pendingCameraPrompt = ""
    }

    fun consumeScreenCaptureRequest() {
        _screenCaptureRequest.value = null
    }

    fun onCameraImageCaptured(image: ProcessedImage) {
        _cameraCaptureRequest.value = null
        viewModelScope.launch {
            executeOnDeviceTool(
                tool = OnDeviceTool.DESCRIBE_IMAGE,
                prompt = pendingCameraPrompt,
                images = listOf(image),
                displayInput = pendingCameraPrompt.ifBlank { "Describe captured image" },
            )
            pendingCameraPrompt = ""
        }
    }

    fun onScreenCapturePermissionResult(
        resultCode: Int,
        data: Intent?,
    ) {
        app.screenCaptureBridge.handlePermissionResult(resultCode, data)
        viewModelScope.launch {
            val captureResult =
                app.screenCaptureBridge.captureScreen(app)
            captureResult.fold(
                onSuccess = { captured ->
                    executeOnDeviceTool(
                        tool = OnDeviceTool.DESCRIBE_IMAGE,
                        prompt = pendingScreenPrompt,
                        images = listOf(captured),
                        displayInput = pendingScreenPrompt.ifBlank { "Describe current screen" },
                    )
                },
                onFailure = { error ->
                    repository.append(
                        content = "Screen capture failed: ${error.message ?: "Unknown error"}",
                        entryType = ENTRY_TYPE_ERROR,
                    )
                },
            )
            pendingScreenPrompt = ""
        }
    }

    private fun executeChatMessage(
        displayText: String,
    ) {
        val images = pendingImagesState.value
        pendingImagesState.value = emptyList()

        val inputImageUris = images.map { it.originalUri }
        viewModelScope.launch {
            executeChatMessageInternal(
                displayText = displayText,
                images = images,
                inputImageUris = inputImageUris,
            )
        }
    }

    private suspend fun executeChatMessageInternal(
        displayText: String,
        images: List<ProcessedImage>,
        inputImageUris: List<String>,
    ) {
        repository.append(
            content = displayText,
            entryType = ENTRY_TYPE_INPUT,
            imageUris = inputImageUris,
        )

        pendingMessageContext = null

        if (!isChatProviderConfigured()) {
            repository.append(
                content = NO_PROVIDER_WARNING,
                entryType = ENTRY_TYPE_SYSTEM,
            )
            return
        }

        executeAgentTurnInternal(
            message = displayText,
            images = images,
        )
    }

    /**
     * Navigates backward (older) through input history.
     *
     * @return The history entry at the new position, or `null` if history is empty.
     */
    fun historyUp(): String? {
        val items = _history.value
        if (items.isEmpty()) return null
        val current = _historyIndex.value
        val newIndex =
            if (current == NO_HISTORY_SELECTION) {
                items.lastIndex
            } else {
                (current - 1).coerceAtLeast(0)
            }
        _historyIndex.value = newIndex
        return items[newIndex]
    }

    /**
     * Navigates forward (newer) through input history.
     *
     * @return The history entry at the new position, or `null` if past the newest entry.
     */
    fun historyDown(): String? {
        val items = _history.value
        val current = _historyIndex.value
        if (current == NO_HISTORY_SELECTION || items.isEmpty()) return null
        val newIndex = current + 1
        if (newIndex > items.lastIndex) {
            _historyIndex.value = NO_HISTORY_SELECTION
            return null
        }
        _historyIndex.value = newIndex
        return items[newIndex]
    }

    /**
     * Processes and stages images from the photo picker.
     *
     * Runs [ImageProcessor.process] on [Dispatchers.IO] to downscale,
     * compress, and base64-encode the selected images. Results are appended
     * to the pending images list, capped at [MAX_IMAGES].
     *
     * @param uris Content URIs returned by the photo picker.
     */
    fun attachImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            processingImagesState.value = true
            try {
                val contentResolver = app.contentResolver
                val processed = ImageProcessor.process(contentResolver, uris)
                val current = pendingImagesState.value
                pendingImagesState.value = (current + processed).take(MAX_IMAGES)
            } finally {
                processingImagesState.value = false
            }
        }
    }

    /**
     * Removes a pending image at the given index.
     *
     * @param index Zero-based index into the pending images list.
     */
    fun removeImage(index: Int) {
        val current = pendingImagesState.value
        if (index in current.indices) {
            pendingImagesState.value = current.toMutableList().apply { removeAt(index) }
        }
    }

    /**
     * Evaluates a Rhai expression via the session bridge and persists the result.
     *
     * The input is immediately persisted as an "input" entry. The expression
     * is then evaluated on [Dispatchers.IO]. Successful results are persisted
     * as "response" entries; failures are persisted as "error" entries.
     *
     * @param displayText The original user input shown in the scrollback.
     * @param expression The Rhai expression to evaluate.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun executeRhai(
        displayText: String,
        expression: String,
    ) {
        loadingState.value = true
        viewModelScope.launch {
            repository.append(content = displayText, entryType = ENTRY_TYPE_INPUT)

            try {
                val rawResult =
                    withContext(Dispatchers.IO) {
                        sessionBridge.evalReplExpression(expression)
                    }
                
                val displayResult =
                    if (cachedSettings.value.stripThinkingTags) {
                        stripThinkingTags(stripToolCallTags(rawResult))
                    } else {
                        stripToolCallTags(rawResult)
                    }.ifBlank {
                        rawResult.trim().ifBlank { EMPTY_RESPONSE_FALLBACK }
                    }

                repository.append(content = displayResult, entryType = ENTRY_TYPE_RESPONSE)

                handleBindResult(displayResult)
                emitRefreshIfNeeded(expression)
            } catch (e: Exception) {
                val sanitized = ErrorSanitizer.sanitizeForUi(e)
                logRepository.append(LogSeverity.ERROR, TAG, "REPL eval failed: $sanitized")
                repository.append(content = sanitized, entryType = ENTRY_TYPE_ERROR)
            } finally {
                loadingState.value = false
            }
        }
    }


    /**
     * Executes a user message through the live agent session.
     *
     * Sends the message to the Rust-side agent loop through [ConversationSessionBridge].
     * Progress events are delivered to [_streamingState] through the
     * [KotlinSessionListener] callback. On completion, the full response
     * is persisted to the terminal repository.
     *
     * Images are passed as separate lists of base64 data and MIME types.
     * The Rust layer composes `[IMAGE:...]` markers so the upstream
     * provider can convert them to multimodal content parts.
     *
     * @param message The message text to send to the agent.
     * @param images Attached images to include in the request.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun executeAgentTurn(
        message: String,
        images: List<ProcessedImage> = emptyList(),
    ) {
        viewModelScope.launch {
            executeAgentTurnInternal(
                message = message,
                images = images,
            )
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun executeAgentTurnInternal(
        message: String,
        images: List<ProcessedImage> = emptyList(),
    ) {
        agentTurnMutex.withLock {
            val myGeneration = turnGeneration.incrementAndGet()
            _streamingState.update { StreamingState(phase = StreamingPhase.THINKING) }
            startTurnWatchdog(myGeneration)

            val logger = getBackgroundProcessLogger()
            val processId = logger.logOperation(
                ProcessType.ANALYSIS,
                "Processing message: ${message.take(50)}...",
                "",
            )

            try {
                withContext(Dispatchers.IO) {
                    sendAgentTurnWithRetry(
                        message = message,
                        images = images,
                        listener = KotlinSessionListener(myGeneration),
                    )
                }
                logger.completeProcess(processId)
            } catch (e: Exception) {
                val sanitized = ErrorSanitizer.sanitizeForUi(e)
                logger.failProcess(processId, e.message ?: "Unknown error")
                if (myGeneration == turnGeneration.get()) {
                    _streamingState.update {
                        StreamingState(phase = StreamingPhase.ERROR, errorMessage = sanitized)
                    }
                }
                logRepository.append(LogSeverity.ERROR, TAG, "Agent turn failed: $sanitized")
                repository.append(content = sanitized, entryType = ENTRY_TYPE_ERROR)
            } finally {
                stopTurnWatchdog()
            }
        }
    }

    /**
     * Starts a watchdog that force-resolves the streaming state if no
     * terminal callback ([StreamingPhase.COMPLETE], [StreamingPhase.ERROR],
     * or [StreamingPhase.CANCELLED]) arrives within [TURN_WATCHDOG_TIMEOUT_MS].
     *
     * Without this, a hung provider call or a callback dropped/misrouted on
     * the native side leaves the "Responding..." card visible forever with
     * no way for the user to recover short of restarting the app.
     */
    private fun startTurnWatchdog(generation: Long) {
        turnWatchdogJob?.cancel()
        turnWatchdogJob = viewModelScope.launch {
            delay(TURN_WATCHDOG_TIMEOUT_MS)
            if (generation != turnGeneration.get()) return@launch
            if (!_streamingState.value.phase.isActive) return@launch
            logRepository.append(
                LogSeverity.WARN,
                TAG,
                "Agent turn $generation exceeded ${TURN_WATCHDOG_TIMEOUT_MS}ms with no terminal " +
                    "callback; force-resolving so the UI does not stay stuck.",
            )
            _streamingState.update {
                StreamingState(
                    phase = StreamingPhase.ERROR,
                    errorMessage = "This request took too long and did not respond. " +
                        "It may still finish in the background \u2014 try again if needed.",
                )
            }
        }
    }

    private fun stopTurnWatchdog() {
        turnWatchdogJob?.cancel()
        turnWatchdogJob = null
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun sendAgentTurnWithRetry(
        message: String,
        images: List<ProcessedImage>,
        listener: KotlinSessionListener,
    ) {
        runCatching {
            sendAgentTurnInternal(message, images, listener)
        }.recoverCatching { error ->
            if (shouldRetryWithFreshSession(error)) {
                logRepository.append(
                    LogSeverity.WARN,
                    TAG,
                    "Session became stale; rebuilding native session and retrying once.",
                )
                forceResetSessionInternal()
                sendAgentTurnInternal(message, images, listener)
            } else {
                throw error
            }
        }.getOrThrow()
    }

    private suspend fun sendAgentTurnInternal(
        message: String,
        images: List<ProcessedImage>,
        listener: KotlinSessionListener,
    ) {
        val isImageDisplayRequest = images.isEmpty() && MediaIntentClassifier.isImageDisplayRequest(message)
        // Handle explicit image-display requests in the client so the result is
        // rendered as native Markdown images instead of opening a browser or
        // delegating the request to device control.
        if (isImageDisplayRequest) {
            listener.onThinking("Finding images…")
            val nativeResponse = NativeImageSearch.search(message).getOrNull()
            if (!nativeResponse.isNullOrBlank()) {
                listener.onResponseChunk(nativeResponse)
                listener.onComplete(nativeResponse)
                return
            }
        }

        val modelMessage = if (isImageDisplayRequest) {
            IMAGE_DISPLAY_MODEL_DIRECTIVE + message
        } else {
            message
        }

        if (isOnDevicePrimaryAgent()) {
            sendOnDeviceTurn(modelMessage, listener)
            return
        }
        prepareAgentSession(message)
        sessionBridge.send(
            message = modelMessage,
            imageData = images.map { it.base64Data },
            mimeTypes = images.map { it.mimeType },
            listener = listener,
        )
    }

    private suspend fun isOnDevicePrimaryAgent(): Boolean {
        if (cachedSettings.value.defaultProvider == "on-device") return true
        val agents = agentRepository.agents.first()
        val primary = agents.firstOrNull {
            it.isEnabled && it.provider.isNotBlank() && it.modelName.isNotBlank()
        } ?: return false
        return primary.provider == "on-device"
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun sendOnDeviceTurn(
        message: String,
        listener: KotlinSessionListener,
    ) {
        val engine = app.liteRtInferenceEngine
        try {
            if (engine.engineState.value != EngineState.READY) {
                val downloaded = engine.getDownloadedModels().firstOrNull()
                if (downloaded != null) {
                    listener.onThinking("Loading on-device model…")
                    engine.initialize(downloaded)
                } else {
                    listener.onError("No on-device model downloaded. Download one in Settings > On-Device AI.")
                    return
                }
            }
            listener.onThinking("")
            val history = repository.entries.first()
                .filterChatConversationEntries()
                .takeLast(MAX_SESSION_SEED_MESSAGES)
                .map { entry ->
                    InferenceMessage(
                        role = if (entry.entryType == ENTRY_TYPE_INPUT) "user" else "assistant",
                        content = entry.content,
                    )
                } + InferenceMessage(role = "user", content = message)
            val response = engine.chat(
                messages = history,
                systemPrompt = null,
            )
            listener.onResponseChunk(response)
            listener.onComplete(response)
        } catch (e: Exception) {
            val sanitized = ErrorSanitizer.sanitizeForUi(e)
            listener.onError(sanitized)
        }
    }

    /**
     * Builds a standalone MCP TOML fragment from the current settings.
     *
     * Called before every session start to hot-reload the daemon's MCP config
     * so that server changes take effect without a full daemon restart.
     */
    private suspend fun buildMcpToml(): String {
        val settings = settingsRepository.settings.first()
        val servers = parseMcpServersFromJson(settings.mcpServersJson)
        // Force deferred_loading=false to ensure MCP tools are injected directly.
        // The old default (true) may be stale in persisted settings.
        val toml = ConfigTomlBuilder.buildMcpToml(
            enabled = settings.mcpEnabled,
            deferredLoading = false,
            servers = servers,
        )
        android.util.Log.d("ZeroAssist", "[MCP TOML] Standalone MCP TOML:\n$toml")
        // Also log via eprintln fallback for logcat
        System.err.println("[MCP TOML] === MCP TOML SENT TO DAEMON ===")
        for (line in toml.lines()) {
            System.err.println("[MCP TOML] $line")
        }
        System.err.println("[MCP TOML] === END MCP TOML ===")
        android.util.Log.d("ZeroAssist", "[MCP TOML] Servers parsed from JSON (${servers.size}):")
        for (s in servers) {
            android.util.Log.d("ZeroAssist", "[MCP TOML]   name=${s.name}, transport=${s.transport}, url=${s.url}, enabled=${s.enabled}, headers=${s.headers}, env=${s.env}")
        }
        return toml
    }

    private val mcpJson = Json { ignoreUnknownKeys = true }

    private fun parseMcpServersFromJson(jsonString: String): List<McpServerEntry> =
        if (jsonString.isBlank() || jsonString == "[]") {
            emptyList()
        } else {
            try {
                mcpJson.decodeFromString(jsonString)
            } catch (_: Exception) {
                emptyList()
            }
        }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun prepareAgentSession(currentMessage: String) {
        sessionLifecycleMutex.withLock {
            if (sessionIsActive) return
            val seedMessages = buildSeedMessagesForChat(currentMessage)
            val mcpToml = buildMcpToml()
            runCatching { sessionBridge.startSession(mcpToml) }
                .onFailure { error ->
                    if (!isSessionAlreadyActiveError(error)) {
                        throw error
                    }
                }
            if (seedMessages.isNotEmpty()) {
                sessionBridge.seed(seedMessages)
            }
            sessionIsActive = true
            _isSessionReady.value = true
        }
    }

    private suspend fun buildSeedMessagesForChat(currentMessage: String): List<ConversationSeedMessage> {
        val terminalEntries = repository.entries.first()
        val seededEntries =
            terminalEntries
                .filterChatConversationEntries()
                .let { entries ->
                    val lastEntry = entries.lastOrNull()
                    if (lastEntry?.entryType == ENTRY_TYPE_INPUT && lastEntry.content == currentMessage) {
                        entries.dropLast(1)
                    } else {
                        entries
                    }
                }.takeLast(MAX_SESSION_SEED_MESSAGES)

        return seededEntries.map { entry ->
            ConversationSeedMessage(
                role =
                    when (entry.entryType) {
                        ENTRY_TYPE_INPUT -> "user"
                        else -> "assistant"
                    },
                content = entry.content,
            )
        }
    }

    private fun List<TerminalEntry>.filterChatConversationEntries(): List<TerminalEntry> {
        val seededEntries = mutableListOf<TerminalEntry>()
        var expectingChatResponse = false

        for (entry in this) {
            when (entry.entryType) {
                ENTRY_TYPE_INPUT -> {
                    val isChatMessage = CommandRegistry.parseAndTranslate(entry.content) is CommandResult.ChatMessage
                    expectingChatResponse = isChatMessage
                    if (isChatMessage) {
                        seededEntries += entry
                    }
                }

                ENTRY_TYPE_RESPONSE -> {
                    if (expectingChatResponse) {
                        seededEntries += entry
                        expectingChatResponse = false
                    }
                }

                else -> expectingChatResponse = false
            }
        }

        return seededEntries
    }

    /**
     * Resets the native live session and starts fresh for future turns.
     */
    fun resetAgentSession() {
        viewModelScope.launch {
            resetAgentSessionInternal(notifyUser = true)
        }
    }

    private suspend fun resetAgentSessionInternal(notifyUser: Boolean) {
        repository.clear()
        forceResetSessionInternal()
        backgroundLogger.resetForNewSession()
        if (notifyUser) {
            repository.append(content = SESSION_RESET_CONFIRMATION, entryType = ENTRY_TYPE_SYSTEM)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun forceResetSessionInternal() {
        sessionLifecycleMutex.withLock {
            runCatching { sessionBridge.destroy() }
                .onFailure {
                    logRepository.append(LogSeverity.WARN, TAG, "Session reset failed: ${it.message}")
                }
            sessionIsActive = false
            pendingSessionDestroyAfterDaemonRestart = false
            _isSessionReady.value = false
        }
    }

    /**
     * Cancels the currently running agent turn.
     *
     * Signals the Rust-side cancellation token. The [KotlinSessionListener]
     * will receive [ConversationSessionListener.onCancelled] and transition the
     * streaming state to [StreamingPhase.CANCELLED].
     */
    @Suppress("TooGenericExceptionCaught")
    fun cancelAgentTurn() {
        try {
            sessionBridge.cancel()
        } catch (e: Exception) {
            logRepository.append(LogSeverity.WARN, TAG, "Cancel failed: ${e.message}")
        }
    }

    fun approveTermuxCommand(requestId: String) {
        val approval = pendingTermuxApprovalState.value?.takeIf { it.id == requestId } ?: return
        pendingTermuxApprovalState.value = null

        viewModelScope.launch {
            app.termuxAuditRepository.transition(approval.id, TermuxAuditState.APPROVED, "User allowed once.")
            repository.append(
                content = "Termux approval allowed once: ${approval.commandPreview}",
                entryType = ENTRY_TYPE_SYSTEM,
            )
            val result =
                withContext(Dispatchers.IO) {
                    app.termuxExecutionClient.execute(
                        TermuxExecutionRequest(
                            argv = approval.argv,
                            workingDirectory = approval.workingDirectory,
                            approval =
                                TermuxExecutionApproval(
                                    fingerprint = approval.fingerprint,
                                    risk = approval.risk,
                                ),
                        ),
                    )
                }
            val executionSummary = formatTermuxApprovedExecutionResult(approval, result)
            repository.append(content = executionSummary, entryType = result.toEntryType())
            app.termuxAuditRepository.transition(
                id = approval.id,
                state = if (result is TermuxExecutionResult.Success && result.snapshot.success) {
                    TermuxAuditState.EXECUTED
                } else {
                    TermuxAuditState.FAILED
                },
                reason = result.toAuditReason(),
            )

            val followUp =
                buildTermuxApprovalFollowUp(
                    approval = approval,
                    result = result,
                )
            executeAgentTurn(followUp)
        }
    }

    fun rejectTermuxCommand(requestId: String) {
        val approval = pendingTermuxApprovalState.value?.takeIf { it.id == requestId } ?: return
        pendingTermuxApprovalState.value = null
        viewModelScope.launch {
            app.termuxAuditRepository.transition(
                id = approval.id,
                state = TermuxAuditState.DENIED,
                reason = "User chose Try safer way.",
            )
            repository.append(
                content = "Termux command was not run. Trying a safer way: ${approval.commandPreview}",
                entryType = ENTRY_TYPE_SYSTEM,
            )
            executeAgentTurn(buildTermuxDenialFollowUp(approval))
        }
    }

    /**
     * Checks whether the first enabled agent has a usable chat provider.
     *
     * Mirrors the `resolveEffectiveDefaults` pattern from
     * [ZeroClawDaemonService][com.zeroclaw.android.service.ZeroClawDaemonService].
     * Providers with [ProviderAuthType.URL_ONLY], [ProviderAuthType.URL_AND_OPTIONAL_KEY],
     * or [ProviderAuthType.NONE] are considered configured without an API key.
     * Providers requiring a key ([ProviderAuthType.API_KEY_ONLY],
     * [ProviderAuthType.API_KEY_OR_OAUTH]) are only considered configured when
     * a non-blank key exists in the repository.
     *
     * @return True if a chat provider is ready for use.
     */
    private suspend fun isChatProviderConfigured(): Boolean {
        if (cachedSettings.value.defaultProvider == "on-device") return true
        val agents = agentRepository.agents.first()
        val primary =
            agents.firstOrNull {
                it.isEnabled && it.provider.isNotBlank() && it.modelName.isNotBlank()
            } ?: return false

        val providerInfo = ProviderRegistry.findById(primary.provider) ?: return false
        return when (providerInfo.authType) {
            ProviderAuthType.URL_ONLY,
            ProviderAuthType.URL_AND_OPTIONAL_KEY,
            ProviderAuthType.NONE,
            ProviderAuthType.ON_DEVICE,
            -> true
            ProviderAuthType.API_KEY_ONLY,
            ProviderAuthType.API_KEY_OR_OAUTH,
            -> {
                val key = apiKeyRepository.getByProvider(primary.provider)
                key != null && key.key.isNotBlank()
            }
        }
    }

    private suspend fun executeOnDeviceTool(
        tool: OnDeviceTool,
        prompt: String,
        images: List<ProcessedImage>,
        displayInput: String,
    ) {
        loadingState.value = true
        try {
            when (tool) {
                OnDeviceTool.INFER,
                OnDeviceTool.SUMMARIZE,
                OnDeviceTool.PROOFREAD,
                OnDeviceTool.REWRITE,
                -> runOnDeviceTextTool(tool, prompt, displayInput)

                OnDeviceTool.DESCRIBE_IMAGE ->
                    runOnDeviceImageTool(
                        prompt = prompt,
                        images = images,
                        displayInput = displayInput,
                    )

                OnDeviceTool.CAMERA_CAPTURE,
                OnDeviceTool.SCREEN_CAPTURE,
                -> Unit
            }
        } finally {
            loadingState.value = false
        }
    }

    private suspend fun runOnDeviceTextTool(
        tool: OnDeviceTool,
        prompt: String,
        displayInput: String,
    ) {
        val trimmedPrompt = prompt.trim()
        if (trimmedPrompt.isBlank()) {
            repository.append(
                content = "${tool.label} needs some text first.",
                entryType = ENTRY_TYPE_SYSTEM,
            )
            return
        }

        repository.append(
            content = displayInput.ifBlank { trimmedPrompt },
            entryType = ENTRY_TYPE_INPUT,
        )

        val featureName = tool.label.lowercase()
        var usedFallback = false
        val resultText =
            runCatching {
                runCatching {
                    when (tool) {
                        OnDeviceTool.INFER ->
                            app.onDeviceInferenceBridge.generateText(trimmedPrompt).getOrThrow()

                        OnDeviceTool.SUMMARIZE -> collectStreamingText {
                            app.onDeviceSummarizerBridge.summarize(trimmedPrompt)
                        }

                        OnDeviceTool.PROOFREAD ->
                            app.onDeviceProofreaderBridge.proofread(trimmedPrompt).getOrThrow()

                        OnDeviceTool.REWRITE -> collectStreamingText {
                            app.onDeviceRewriterBridge.rewrite(
                                text = trimmedPrompt,
                                style = RewriteStyle.REPHRASE,
                            )
                        }

                        else -> ""
                    }
                }.getOrElse { error ->
                    usedFallback = true
                    cloudFallbackNotice(
                        featureName = featureName,
                        reason = error.message,
                    )
                    executeCloudTextFallback(tool, trimmedPrompt)
                }
            }.getOrElse { error ->
                repository.append(
                    content = error.message ?: "Unable to complete ${tool.label.lowercase()} request.",
                    entryType = ENTRY_TYPE_ERROR,
                )
                return
            }

        repository.append(
            content = buildToolResultLabel(tool = tool, usedFallback = usedFallback, content = resultText),
            entryType = ENTRY_TYPE_RESPONSE,
        )
    }

    private suspend fun runOnDeviceImageTool(
        prompt: String,
        images: List<ProcessedImage>,
        displayInput: String,
    ) {
        if (images.isEmpty()) {
            repository.append(
                content = "Attach an image, capture one from the camera, or use screen capture first.",
                entryType = ENTRY_TYPE_SYSTEM,
            )
            return
        }

        val effectivePrompt = prompt.trim().ifBlank { DEFAULT_IMAGE_PROMPT }
        repository.append(
            content = displayInput.ifBlank { effectivePrompt },
            entryType = ENTRY_TYPE_INPUT,
        )

        val firstImage = images.first()
        var usedFallback = false
        val resultText =
            runCatching {
                val bitmap = decodeProcessedImage(firstImage)
                if (bitmap == null) {
                    usedFallback = true
                    cloudFallbackNotice(
                        featureName = "image description",
                        reason = "Unable to decode the selected image locally.",
                    )
                    executeCloudImageFallback(effectivePrompt, listOf(firstImage))
                } else {
                    runCatching {
                        bitmap.useBitmap {
                            collectStreamingText {
                                app.onDeviceImageDescriberBridge.describe(it)
                            }
                        }
                    }.getOrElse { error ->
                        usedFallback = true
                        cloudFallbackNotice(
                            featureName = "image description",
                            reason = error.message,
                        )
                        executeCloudImageFallback(effectivePrompt, listOf(firstImage))
                    }
                }
            }.getOrElse { error ->
                pendingImagesState.value = emptyList()
                repository.append(
                    content = error.message ?: "Unable to describe the selected image.",
                    entryType = ENTRY_TYPE_ERROR,
                )
                return
            }

        pendingImagesState.value = emptyList()
        repository.append(
            content = buildToolResultLabel(
                tool = OnDeviceTool.DESCRIBE_IMAGE,
                usedFallback = usedFallback,
                content = resultText,
            ),
            entryType = ENTRY_TYPE_RESPONSE,
        )
    }

    private suspend fun executeCloudTextFallback(
        tool: OnDeviceTool,
        prompt: String,
    ): String {
        if (!isChatProviderConfigured()) {
            return NO_PROVIDER_WARNING
        }
        return app.daemonBridge.send(
            when (tool) {
                OnDeviceTool.SUMMARIZE ->
                    "Summarize the following text concisely:\n\n$prompt"

                OnDeviceTool.PROOFREAD ->
                    "Proofread the following text. Return only the corrected version:\n\n$prompt"

                OnDeviceTool.REWRITE ->
                    "Rewrite the following text more clearly. Return only the rewritten text:\n\n$prompt"

                OnDeviceTool.INFER ->
                    prompt

                else -> prompt
            },
        )
    }

    private suspend fun executeCloudImageFallback(
        prompt: String,
        images: List<ProcessedImage>,
    ): String = app.visionBridge.send(prompt, images)

    private suspend fun collectStreamingText(flowProvider: () -> kotlinx.coroutines.flow.Flow<String>): String {
        val builder = StringBuilder()
        flowProvider().collect { chunk ->
            builder.append(chunk)
        }
        return builder.toString().trim().ifBlank { EMPTY_RESPONSE_FALLBACK }
    }

    private suspend fun cloudFallbackNotice(
        featureName: String,
        reason: String?,
    ) {
        repository.append(
            content = buildString {
                append("Cloud fallback")
                if (!reason.isNullOrBlank()) {
                    append(": ")
                    append(reason)
                } else {
                    append(" for ")
                    append(featureName)
                }
            },
            entryType = ENTRY_TYPE_SYSTEM,
        )
    }

    private fun buildToolResultLabel(
        tool: OnDeviceTool,
        usedFallback: Boolean,
        content: String,
    ): String {
        val channel = if (usedFallback) "Cloud fallback" else "On-device"
        return "[$channel ${tool.label.lowercase()}]\n$content"
    }

    private fun decodeProcessedImage(image: ProcessedImage): Bitmap? =
        runCatching {
            val bytes = Base64.decode(image.base64Data, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()

    private inline fun <T> Bitmap.useBitmap(block: (Bitmap) -> T): T =
        try {
            block(this)
        } finally {
            recycle()
        }

    /**
     * Handles a local action that does not require FFI evaluation.
     *
     * @param action The action identifier (e.g. "help", "clear").
     */
    private fun handleLocalAction(
        action: String,
        args: List<String>,
        rawArgsText: String,
        displayText: String,
    ) {
        when (action) {
            "help" -> showHelp(displayText.ifBlank { "/help" })
            "clear" -> clearTerminal()
            "local infer" -> performOnDeviceTool(OnDeviceTool.INFER, rawArgsText)
            "local summarize" -> performOnDeviceTool(OnDeviceTool.SUMMARIZE, rawArgsText)
            "local proofread" -> performOnDeviceTool(OnDeviceTool.PROOFREAD, rawArgsText)
            "local rewrite" -> performOnDeviceTool(OnDeviceTool.REWRITE, rawArgsText)
            "local describe" -> performOnDeviceTool(OnDeviceTool.DESCRIBE_IMAGE, rawArgsText)
            "camera" -> performOnDeviceTool(OnDeviceTool.CAMERA_CAPTURE, rawArgsText)
            "screen capture" -> performOnDeviceTool(OnDeviceTool.SCREEN_CAPTURE, rawArgsText)
            "termux status" -> showTermuxStatus(displayText.ifBlank { "/termux status" })
            "termux start" -> startTermuxBridge(displayText.ifBlank { "/termux start" })
            "termux setup" -> runTermuxSetup(displayText.ifBlank { "/termux setup" })
            "termux doctor" -> showTermuxDoctor(displayText.ifBlank { "/termux doctor" })
            "termux smoke" -> executeTermuxSmokeTest(displayText.ifBlank { "/termux smoke" })
            "termux capabilities" -> showTermuxCapabilities(displayText.ifBlank { "/termux capabilities" })
            "script" -> showScriptPlaceholder(displayText.ifBlank { "/script ${args.joinToString(" ")}".trim() })
        }
    }

    /**
     * Generates and persists the help text listing all available commands.
     */
    private fun showHelp(displayText: String) {
        viewModelScope.launch {
            repository.append(content = displayText, entryType = ENTRY_TYPE_INPUT)
            val helpText =
                buildString {
                    appendLine("Available commands:")
                    appendLine()
                    for (command in CommandRegistry.commands) {
                        val usage =
                            if (command.usage.isNotEmpty()) {
                                " ${command.usage}"
                            } else {
                                ""
                            }
                        appendLine("  /${command.name}$usage")
                        appendLine("    ${command.description}")
                    }
                    appendLine()
                    append("Any other input is sent as a chat message.")
                }
            repository.append(content = helpText, entryType = ENTRY_TYPE_SYSTEM)
        }
    }

    private fun showScriptPlaceholder(displayText: String) {
        viewModelScope.launch {
            repository.append(content = displayText, entryType = ENTRY_TYPE_INPUT)
            repository.append(
                content = "Rhai runtime integration is not enabled yet. Use Settings > Skill Permissions as the placeholder seam for future grants and execution controls.",
                entryType = ENTRY_TYPE_SYSTEM,
            )
        }
    }

    private fun showTermuxStatus(displayText: String) {
        viewModelScope.launch {
            repository.append(content = displayText, entryType = ENTRY_TYPE_INPUT)
            loadingState.value = true
            try {
                val status =
                    withContext(Dispatchers.IO) {
                        app.termuxRuntimeStatusProvider.currentStatus()
                    }
                repository.append(
                    content = formatTermuxStatus(status),
                    entryType = ENTRY_TYPE_SYSTEM,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: "Unable to inspect Termux status."
                logRepository.append(LogSeverity.ERROR, TAG, "Termux status failed: $message")
                repository.append(content = message, entryType = ENTRY_TYPE_ERROR)
            } finally {
                loadingState.value = false
            }
        }
    }

    private fun startTermuxBridge(displayText: String) {
        viewModelScope.launch {
            repository.append(content = displayText, entryType = ENTRY_TYPE_INPUT)
            loadingState.value = true
            try {
                val result =
                    withContext(Dispatchers.IO) {
                        app.termuxBridgeSupervisor.ensureStarted()
                    }
                val status =
                    withContext(Dispatchers.IO) {
                        app.termuxRuntimeStatusProvider.currentStatus()
                    }
                repository.append(
                    content = formatTermuxStartResult(result, status),
                    entryType =
                        if (result.status == TermuxBootstrapLaunchStatus.STARTED) {
                            ENTRY_TYPE_SYSTEM
                        } else {
                            ENTRY_TYPE_ERROR
                        },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: "Unable to start the Termux bridge."
                logRepository.append(LogSeverity.ERROR, TAG, "Termux bridge startup failed: $message")
                repository.append(content = message, entryType = ENTRY_TYPE_ERROR)
            } finally {
                loadingState.value = false
            }
        }
    }

    private fun runTermuxSetup(displayText: String) {
        viewModelScope.launch {
            repository.append(content = displayText, entryType = ENTRY_TYPE_INPUT)
            loadingState.value = true
            try {
                val initialStatus =
                    withContext(Dispatchers.IO) {
                        app.termuxRuntimeStatusProvider.currentStatus()
                    }
                val startResult =
                    if (
                        initialStatus.packageState.availability == TermuxPackageAvailability.INSTALLED &&
                        initialStatus.bootstrapState.availability != TermuxBootstrapAvailability.UNAVAILABLE
                    ) {
                        withContext(Dispatchers.IO) {
                            app.termuxBridgeSupervisor.ensureStarted()
                        }
                    } else {
                        null
                    }
                if (startResult?.status == TermuxBootstrapLaunchStatus.STARTED) {
                    delay(TERMUX_SETUP_BRIDGE_WAIT_MS)
                }
                val finalStatus =
                    withContext(Dispatchers.IO) {
                        app.termuxRuntimeStatusProvider.currentStatus()
                    }
                val needsManualRecovery =
                    finalStatus.packageState.availability == TermuxPackageAvailability.INSTALLED &&
                        finalStatus.health.status != TermuxHealthStatus.READY
                val recoveryScript = termuxStorageResetRecoveryScript()
                val scriptCopied =
                    needsManualRecovery && copyTermuxSetupScriptToClipboard(recoveryScript)
                val termuxOpened =
                    needsManualRecovery && openTermuxApp()
                repository.append(
                    content =
                        formatTermuxSetupResult(
                            initialStatus = initialStatus,
                            startResult = startResult,
                            finalStatus = finalStatus,
                            scriptCopied = scriptCopied,
                            termuxOpened = termuxOpened,
                        ),
                    entryType =
                        if (finalStatus.isReady) {
                            ENTRY_TYPE_SYSTEM
                        } else {
                            ENTRY_TYPE_ERROR
                        },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: "Unable to run Termux setup automation."
                logRepository.append(LogSeverity.ERROR, TAG, "Termux setup failed: $message")
                repository.append(content = message, entryType = ENTRY_TYPE_ERROR)
            } finally {
                loadingState.value = false
            }
        }
    }

    private fun showTermuxDoctor(displayText: String) {
        viewModelScope.launch {
            repository.append(content = displayText, entryType = ENTRY_TYPE_INPUT)
            loadingState.value = true
            try {
                val status =
                    withContext(Dispatchers.IO) {
                        app.termuxRuntimeStatusProvider.currentStatus()
                    }
                repository.append(
                    content = formatTermuxDoctor(status),
                    entryType = ENTRY_TYPE_SYSTEM,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: "Unable to run Termux diagnostics."
                logRepository.append(LogSeverity.ERROR, TAG, "Termux doctor failed: $message")
                repository.append(content = message, entryType = ENTRY_TYPE_ERROR)
            } finally {
                loadingState.value = false
            }
        }
    }

    private fun copyTermuxSetupScriptToClipboard(script: String): Boolean =
        try {
            val clipboard = app.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText("Zero-Assist Termux setup", script))
            clipboard != null
        } catch (e: RuntimeException) {
            logRepository.append(LogSeverity.WARN, TAG, "Unable to copy Termux setup script: ${e.message}")
            false
        }

    private fun openTermuxApp(): Boolean =
        try {
            val intent =
                app.packageManager
                    .getLaunchIntentForPackage(TermuxRuntimeContract.TERMUX_PACKAGE_NAME)
                    ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (intent == null) {
                false
            } else {
                app.startActivity(intent)
                true
            }
        } catch (e: RuntimeException) {
            logRepository.append(LogSeverity.WARN, TAG, "Unable to open Termux: ${e.message}")
            false
        }

    private fun showTermuxCapabilities(displayText: String) {
        viewModelScope.launch {
            repository.append(content = displayText, entryType = ENTRY_TYPE_INPUT)
            loadingState.value = true
            try {
                val status =
                    withContext(Dispatchers.IO) {
                        app.termuxRuntimeStatusProvider.currentStatus()
                    }
                if (!status.isReady) {
                    repository.append(
                        content =
                            buildString {
                                appendLine(formatTermuxStatus(status))
                                append("Capabilities: not fetched until the Termux bridge is ready.")
                            },
                        entryType = ENTRY_TYPE_SYSTEM,
                    )
                    return@launch
                }

                val capabilities =
                    withContext(Dispatchers.IO) {
                        app.termuxCapabilitiesClient.fetchCapabilities()
                    }
                val termuxTools =
                    withContext(Dispatchers.IO) {
                        runCatching {
                            app.toolsBridge
                                .listTools()
                                .filter { tool -> tool.source == "termux" || tool.name.startsWith("termux_") }
                        }.getOrDefault(emptyList())
                    }
                when (capabilities) {
                    is TermuxCapabilitiesResult.Success ->
                        repository.append(
                            content = formatTermuxCapabilities(capabilities.snapshot, termuxTools),
                            entryType = ENTRY_TYPE_SYSTEM,
                        )
                    is TermuxCapabilitiesResult.Failure ->
                        repository.append(content = capabilities.reason, entryType = ENTRY_TYPE_ERROR)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: "Unable to inspect Termux capabilities."
                logRepository.append(LogSeverity.ERROR, TAG, "Termux capabilities failed: $message")
                repository.append(content = message, entryType = ENTRY_TYPE_ERROR)
            } finally {
                loadingState.value = false
            }
        }
    }

    private fun executeTermuxSmokeTest(displayText: String) {
        viewModelScope.launch {
            repository.append(content = displayText, entryType = ENTRY_TYPE_INPUT)
            loadingState.value = true
            try {
                val status =
                    withContext(Dispatchers.IO) {
                        app.termuxRuntimeStatusProvider.currentStatus()
                    }
                if (!status.isReady) {
                    repository.append(
                        content =
                            buildString {
                                appendLine(formatTermuxStatus(status))
                                append("Smoke: skipped until the Termux bridge is ready.")
                            },
                        entryType = ENTRY_TYPE_SYSTEM,
                    )
                    return@launch
                }

                val result =
                    withContext(Dispatchers.IO) {
                        app.termuxExecutionClient.execute(
                            TermuxExecutionRequest(
                                argv = listOf("python3", "--version"),
                                timeoutSeconds = 15,
                            ),
                        )
                    }
                repository.append(
                    content = formatTermuxSmokeResult(result),
                    entryType =
                        if (
                            result is TermuxExecutionResult.Success &&
                            result.snapshot.success
                        ) {
                            ENTRY_TYPE_SYSTEM
                        } else {
                            ENTRY_TYPE_ERROR
                        },
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: "Unable to run Termux smoke test."
                logRepository.append(LogSeverity.ERROR, TAG, "Termux smoke test failed: $message")
                repository.append(content = message, entryType = ENTRY_TYPE_ERROR)
            } finally {
                loadingState.value = false
            }
        }
    }

    /**
     * Clears all terminal history and adds a confirmation message.
     */
    private fun clearTerminal() {
        repository.clear()
        viewModelScope.launch {
            resetAgentSessionInternal(notifyUser = false)
            repository.append(content = CLEAR_CONFIRMATION, entryType = ENTRY_TYPE_SYSTEM)
        }
    }

    /**
     * Close (hide) the background process display box.
     */
    fun closeBackgroundProcess() {
        backgroundLogger.setVisible(false)
    }

    /**
     * Toggle expansion of the background process box.
     */
    fun toggleBackgroundProcessExpansion() {
        val current = backgroundLogger.getCurrentState()
        backgroundLogger.setExpanded(!current.isExpanded)
    }

    /**
     * Clear all background process entries.
     */
    fun clearBackgroundProcesses() {
        backgroundLogger.clear()
    }

    /**
     * Show the background process box.
     */
    fun showBackgroundProcess() {
        backgroundLogger.setVisible(true)
    }

    /**
     * Get the background process logger for direct access (advanced use).
     */
    fun getBackgroundLogger(): BackgroundProcessLogger = backgroundLogger

    private fun isSessionAlreadyActiveError(error: Throwable): Boolean {
        val detail = error.message?.lowercase().orEmpty()
        return detail.contains("session already active") || detail.contains("session is already active")
    }

    private fun shouldRetryWithFreshSession(error: Throwable): Boolean {
        app.daemonBridge.handleDaemonUnavailable(error.message)
        return shouldRetryWithFreshSessionDetail(
            errorMessage = error.message,
            daemonRunning = app.daemonBridge.serviceState.value == ServiceState.RUNNING,
        )
    }

    /**
     * Heuristic check: does this input look like a short message body
     * (e.g. "say hi", "just hi", "hello", "ok thanks") that should be combined
     * with a pending message context rather than treated as a new device control request?
     */
    private fun looksLikeMessageBody(text: String): Boolean {
        val lower = text.trim().lowercase()
        if (lower.length > 80) return false
        // Short phrases that look like message content
        val messageBodyPatterns = listOf(
            Regex("""^(?:say|just|send|type|tell them|write)\s+.+"""),
            Regex("""^(?:hi|hello|hey|ok|okay|thanks|thank you|sure|yes|no|yeah|yep|nope|lol|haha|👍|🙏).{0,40}$"""),
            Regex("""^.{1,60}$"""), // Any short text that isn't a command
        )
        // Exclude things that look like device commands
        val commandPrefixes = listOf(
            "open ", "launch ", "start ", "press ", "tap ", "click ",
            "back", "home", "recents", "flashlight", "notifications",
            "quick settings", "search ", "scroll ", "type ",
            "set text", "volume", "brightness",
        )
        if (commandPrefixes.any { lower.startsWith(it) }) return false
        return messageBodyPatterns.any { it.matches(lower) }
    }

    /**
     * Inserts the welcome banner as the first terminal entry.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun addWelcomeMessage() {
        viewModelScope.launch {
            val version =
                try {
                    sessionBridge.version()
                } catch (e: Exception) {
                    logRepository.append(
                        LogSeverity.WARN,
                        TAG,
                        "Failed to read version: ${e.message}",
                    )
                    "unknown"
                }
            val banner =
                if (isChatProviderConfigured()) {
                    "Zero-Assist Terminal v$version \u2014 Type /help for commands"
                } else {
                    "Zero-Assist Terminal v$version \u2014 Admin Console " +
                        "(no chat provider) \u2014 Type /help for commands"
                }
            repository.append(content = banner, entryType = ENTRY_TYPE_SYSTEM)
        }
    }

    /**
     * Appends a non-blank input to the history buffer.
     *
     * Duplicate consecutive entries are suppressed to keep the history clean.
     *
     * @param text The input text to record.
     */
    private fun appendToHistory(text: String) {
        if (text.isBlank()) return
        val current = _history.value
        if (current.lastOrNull() == text) return
        _history.value = current + text
    }

    /**
     * Detects and handles a bind result from the REPL.
     *
     * When the user runs `bind("channel", "identity")` in the terminal,
     * the REPL returns a structured message. This method parses it,
     * persists the binding to Room, and restarts the daemon so the
     * channel picks up the new allowlist entry.
     *
     * @param response The raw REPL response string.
     */
    private suspend fun handleBindResult(response: String) {
        val match = BIND_RESULT_PATTERN.find(response) ?: return
        val (userId, channelKey, fieldName) = match.destructured

        val channels = app.channelConfigRepository.channels.first()
        val channel = channels.find { it.type.tomlKey == channelKey } ?: return

        val currentList = channel.configValues[fieldName].orEmpty()
        val entries = currentList.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (userId in entries) return

        val updatedList = (entries + userId).joinToString(", ")
        val updatedValues = channel.configValues.toMutableMap()
        updatedValues[fieldName] = updatedList

        val updatedChannel = channel.copy(configValues = updatedValues)
        app.channelConfigRepository.save(
            updatedChannel,
            app.channelConfigRepository.getSecrets(channel.id),
        )

        restartDaemonWithCurrentConfig()
    }

    /**
     * Restarts the daemon by sending [ZeroClawDaemonService.ACTION_START].
     *
     * The service rebuilds its config from the current Room/DataStore state,
     * picking up any changes made by [handleBindResult].
     */
    @Suppress("TooGenericExceptionCaught")
    private fun restartDaemonWithCurrentConfig() {
        try {
            val intent =
                Intent(app, ZeroClawDaemonService::class.java)
                    .setAction(ZeroClawDaemonService.ACTION_START)
            app.startForegroundService(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restart daemon after bind: ${e.message}")
        }
    }

    /**
     * Emits a [RefreshCommand] to trigger immediate data refresh in other
     * ViewModels after a successful mutating REPL command.
     *
     * @param expression The Rhai expression that was successfully evaluated.
     */
    private fun emitRefreshIfNeeded(expression: String) {
        val command = refreshCommandForExpression(expression)
        if (command != null) {
            app.refreshCommands.tryEmit(command)
        }
    }

    /**
     * Callback adapter that translates bridge session events into
     * [StreamingState] updates and terminal repository entries.
     *
     * All methods are called from the tokio runtime thread. State updates
     * use [MutableStateFlow.update] which is thread-safe.
     */
    private data class PendingToolCompletion(
        val processId: String,
        val success: Boolean,
        val durationMs: Long,
    )

    private data class PendingMessageContext(
        val recipient: String?,
        val targetPackageName: String?,
        val targetAppQuery: String?,
    )

    private fun handleTermuxApprovalToolOutput(
        name: String,
        output: String,
    ) {
        val approval = TermuxApprovalRequest.fromToolOutput(name, output) ?: return
        viewModelScope.launch {
            app.termuxAuditRepository.recordRequested(
                risk = approval.risk,
                commandPreview = approval.commandPreview,
                reason = approval.reason,
                workingDirectory = approval.workingDirectory,
                fingerprint = approval.fingerprint,
                id = approval.id,
            )
            pendingTermuxApprovalState.value = approval
        }
    }

    private fun TermuxExecutionResult.toEntryType(): String =
        when (this) {
            is TermuxExecutionResult.Success ->
                if (snapshot.success) ENTRY_TYPE_RESPONSE else ENTRY_TYPE_ERROR
            is TermuxExecutionResult.Failure -> ENTRY_TYPE_ERROR
        }

    private fun TermuxExecutionResult.toAuditReason(): String =
        when (this) {
            is TermuxExecutionResult.Success ->
                "Exit code ${snapshot.exitCode ?: "n/a"}; status ${snapshot.status ?: "unknown"}."
            is TermuxExecutionResult.Failure -> reason
        }

    private fun buildTermuxApprovalFollowUp(
        approval: TermuxApprovalRequest,
        result: TermuxExecutionResult,
    ): String =
        buildString {
            appendLine("Termux approval result:")
            appendLine(buildTermuxApprovalResultJson(approval, result).toString())
            append("Continue from this result. Do not rerun or alter the command unless you ask again.")
        }

    private fun buildTermuxDenialFollowUp(approval: TermuxApprovalRequest): String =
        buildString {
            appendLine("Termux approval result:")
            appendLine(buildTermuxDenialResultJson(approval).toString())
            append(
                "Find a safer path without running this exact command. " +
                    "Prefer capability discovery, read-only inspection, or asking a narrower follow-up.",
            )
        }

    private fun buildTermuxApprovalResultJson(
        approval: TermuxApprovalRequest,
        result: TermuxExecutionResult,
    ): JSONObject {
        val payload =
            JSONObject()
                .put("approved", true)
                .put("user_choice", "allow_once")
                .put("request_id", approval.id)
                .put("command", approval.argv.firstOrNull().orEmpty())
                .put("arguments", JSONArray(approval.argv.drop(1)))
                .put("command_preview", approval.commandPreview)
                .put("working_directory", approval.workingDirectory ?: JSONObject.NULL)
                .put("risk", approval.risk.name.lowercase())
                .put("fingerprint", approval.fingerprint)

        when (result) {
            is TermuxExecutionResult.Success -> {
                val snapshot = result.snapshot
                payload
                    .put("success", snapshot.success)
                    .put("status", snapshot.status ?: JSONObject.NULL)
                    .put("exit_code", snapshot.exitCode ?: JSONObject.NULL)
                    .put("duration_ms", snapshot.durationMs ?: JSONObject.NULL)
                    .put("stdout", snapshot.stdout.trim().take(MAX_APPROVED_OUTPUT_CHARS))
                    .put("stderr", snapshot.stderr.trim().take(MAX_APPROVED_OUTPUT_CHARS))
            }
            is TermuxExecutionResult.Failure -> {
                payload
                    .put("success", false)
                    .put("status", "client_failure")
                    .put("error", result.reason)
            }
        }
        return payload
    }

    private fun buildTermuxDenialResultJson(approval: TermuxApprovalRequest): JSONObject =
        JSONObject()
            .put("approved", false)
            .put("user_choice", "try_safer_way")
            .put("blocked", false)
            .put("request_id", approval.id)
            .put("command", approval.argv.firstOrNull().orEmpty())
            .put("arguments", JSONArray(approval.argv.drop(1)))
            .put("command_preview", approval.commandPreview)
            .put("working_directory", approval.workingDirectory ?: JSONObject.NULL)
            .put("risk", approval.risk.name.lowercase())
            .put("reason", approval.reason ?: JSONObject.NULL)
            .put("instruction", "User denied this command. Find a safer alternative.")

    private fun formatTermuxApprovedExecutionResult(
        approval: TermuxApprovalRequest,
        result: TermuxExecutionResult,
    ): String =
        buildString {
            appendLine("Termux approved execution:")
            appendLine("  Command: ${approval.commandPreview}")
            appendLine("  Risk: ${approval.risk.name}")
            appendLine("  Working directory: ${approval.workingDirectory}")
            when (result) {
                is TermuxExecutionResult.Success -> {
                    val snapshot = result.snapshot
                    appendLine("  Result: ${if (snapshot.success) "passed" else "failed"}")
                    appendLine("  Exit code: ${snapshot.exitCode ?: "n/a"}")
                    snapshot.durationMs?.let { appendLine("  Duration: ${it}ms") }
                    if (snapshot.stdout.isNotBlank()) {
                        appendLine("  Stdout: ${snapshot.stdout.trim().take(MAX_APPROVED_OUTPUT_CHARS)}")
                    }
                    if (snapshot.stderr.isNotBlank()) {
                        appendLine("  Stderr: ${snapshot.stderr.trim().take(MAX_APPROVED_OUTPUT_CHARS)}")
                    }
                }
                is TermuxExecutionResult.Failure -> {
                    appendLine("  Result: failed")
                    appendLine("  Error: ${result.reason}")
                }
            }
        }.trimEnd()

    private inner class KotlinSessionListener(
        private val generation: Long,
    ) : ConversationSessionListener {
        private val logger = getBackgroundProcessLogger()
        private var mainProcessId: String? = null
        private val toolProcessIds = mutableMapOf<String, ArrayDeque<String>>()
        private val pendingToolCompletions = mutableMapOf<String, ArrayDeque<PendingToolCompletion>>()
        private val toolCallCounter = java.util.concurrent.atomic.AtomicLong(0L)

        init {
            // Log the main analysis process when listener starts
            mainProcessId = logger.logOperation(
                ProcessType.ANALYSIS,
                "Processing request",
                "",
            )
        }

        /**
         * True while this listener still belongs to the live turn.
         *
         * A stray callback from an abandoned/overlapping native session_send
         * call (e.g. after an automatic retry, or an overlapping call from
         * another entry point sharing the same native session) must never
         * mutate [_streamingState] on behalf of a turn the UI has already
         * moved on from.
         */
        private fun isCurrentTurn(): Boolean = generation == turnGeneration.get()

        override fun onThinking(text: String) {
            if (!isCurrentTurn()) return
            val trimmed = text.trim()
            val match = THINKING_PHASE_REGEX.matchEntire(trimmed)
            if (match != null) {
                val roundStr = match.groupValues[1]
                val roundNum = roundStr.toIntOrNull() ?: 1
                _streamingState.update { current ->
                    current.copy(
                        phase = StreamingPhase.CALLING_PROVIDER,
                        providerRound = roundNum,
                    )
                }
                return
            }

            mainProcessId?.let {
                logger.updateProcess(
                    it,
                    "Analyzing: ${text.take(50)}..." + if (text.length > 50) " →" else "",
                )
            }
            if (text.isNotBlank()) {
                _streamingState.update { current ->
                    current.copy(
                        phase = StreamingPhase.THINKING,
                        thinkingText = if (current.thinkingText.isBlank()) text else "${current.thinkingText}\n$text",
                    )
                }
            }
        }

        override fun onResponseChunk(text: String) {
            if (!isCurrentTurn()) return
            _streamingState.update { current ->
                current.copy(
                    phase = StreamingPhase.RESPONDING,
                    responseText = current.responseText + text,
                )
            }
        }

        override fun onToolStart(
            name: String,
            argumentsHint: String,
        ) {
            if (!isCurrentTurn()) return
            // Log tool execution
            val toolProcessId = logger.logOperation(
                ProcessType.TOOL_EXEC,
                "Executing: $name",
                argumentsHint,
            )
            toolProcessIds.getOrPut(name) { ArrayDeque() }.addLast(toolProcessId)

            val callId = toolCallCounter.getAndIncrement()
            _streamingState.update { current ->
                current.copy(
                    phase = StreamingPhase.TOOL_EXECUTING,
                    activeTools = current.activeTools + ToolProgress(name, argumentsHint, callId),
                )
            }
        }

        override fun onToolResult(
            name: String,
            success: Boolean,
            durationSecs: ULong,
        ) {
            if (!isCurrentTurn()) return
            val processId = popToolProcessId(name)
            if (processId != null) {
                pendingToolCompletions.getOrPut(name) { ArrayDeque() }.addLast(
                    PendingToolCompletion(
                        processId = processId,
                        success = success,
                        durationMs = durationSecs.toLong() * 1000L,
                    ),
                )
            }

            _streamingState.update { current ->
                val match = current.activeTools.firstOrNull { it.name == name }
                val toolHint = match?.hint ?: ""
                val matchCallId = match?.callId ?: 0L
                val canvasFrame =
                    if (name == "canvas") {
                        parseCanvasFrameFromHint(toolHint)
                    } else {
                        null
                    }
                current.copy(
                    activeTools = current.activeTools.filter { it.name != name },
                    toolResults =
                        current.toolResults +
                            ToolResultEntry(
                                name = name,
                                success = success,
                                durationSecs = durationSecs.toLong(),
                                hint = toolHint,
                                callId = matchCallId,
                                canvasFrame = canvasFrame,
                            ),
                )
            }
        }

        override fun onToolOutput(
            name: String,
            output: String,
        ) {
            if (!isCurrentTurn()) return
            val details = compactToolOutput(output)
            handleTermuxApprovalToolOutput(name, output)
            popPendingToolCompletion(name)?.let { completion ->
                if (completion.success) {
                    logger.completeProcess(
                        completion.processId,
                        completion.durationMs,
                        details,
                    )
                } else {
                    logger.failProcess(
                        completion.processId,
                        details ?: "Tool returned error",
                        completion.durationMs,
                    )
                }
            }

            _streamingState.update { current ->
                val updated =
                    current.toolResults.map { entry ->
                        if (entry.name == name && entry.output.isEmpty()) {
                            entry.copy(output = output)
                        } else {
                            entry
                        }
                    }
                current.copy(toolResults = updated)
            }
        }

        private fun popToolProcessId(name: String): String? {
            val queue = toolProcessIds[name] ?: return null
            val processId = queue.pollFirst()
            if (queue.isEmpty()) {
                toolProcessIds.remove(name)
            }
            return processId
        }

        private fun popPendingToolCompletion(name: String): PendingToolCompletion? {
            val queue = pendingToolCompletions[name] ?: return null
            val completion = queue.pollFirst()
            if (queue.isEmpty()) {
                pendingToolCompletions.remove(name)
            }
            return completion
        }

        private fun compactToolOutput(output: String): String? {
            val trimmed = output.trim()
            if (trimmed.isEmpty()) return null
            return if (trimmed.length > MAX_TOOL_OUTPUT_DETAILS_CHARS) {
                trimmed.take(MAX_TOOL_OUTPUT_DETAILS_CHARS) + "\n..."
            } else {
                trimmed
            }
        }

        private fun parseCanvasFrameFromHint(hint: String): CanvasFrame? {
            return runCatching {
                val json = JSONObject(hint)
                val content = json.optString("content", "")
                if (content.isEmpty()) return@runCatching null
                CanvasFrame(
                    frameId = json.optString("canvas_id", "default"),
                    contentType = json.optString("content_type", "html"),
                    content = content,
                    timestamp = java.time.Instant.now().toString(),
                )
            }.getOrNull()
        }

        override fun onProgress(message: String) {
            if (!isCurrentTurn()) return
            // Update main process with current progress
            mainProcessId?.let {
                logger.updateProcess(it, "Progress: ${message.take(50)}" + if (message.length > 50) "..." else "")
            }
            _streamingState.update { current ->
                current
            }
        }

        override fun onCompaction(summary: String) {
            if (!isCurrentTurn()) return
            // Update process for memory compaction operation
            mainProcessId?.let {
                logger.updateProcess(it, "Compacting memory: ${summary.take(45)}" + if (summary.length > 45) "..." else "")
            }
            _streamingState.update { current ->
                current.copy(phase = StreamingPhase.COMPACTING)
            }
        }

        override fun onComplete(fullResponse: String) {
            try {
                // Complete the main analysis process
                mainProcessId?.let {
                    logger.completeProcess(it)
                }

                val cleaned = stripToolCallTags(fullResponse)
                val stripped =
                    if (cachedSettings.value.stripThinkingTags) {
                        stripThinkingTags(cleaned)
                    } else {
                        cleaned
                    }
                val display = stripped.ifBlank { EMPTY_RESPONSE_FALLBACK }

                viewModelScope.launch {
                    repository.append(content = display, entryType = ENTRY_TYPE_RESPONSE)
                }
            } finally {
                // Only the turn the UI still considers "live" is allowed to
                // clear/replace the streaming phase. A stale generation's
                // completion still gets its response appended above (no
                // answer is silently dropped) but must not touch the phase
                // a newer turn (or the watchdog) may already own.
                if (isCurrentTurn()) {
                    _streamingState.update { StreamingState(phase = StreamingPhase.COMPLETE) }
                    app.refreshCommands.tryEmit(RefreshCommand.Cost)
                }
            }
        }

        override fun onError(error: String) {
            val sanitized = ErrorSanitizer.sanitizeMessage(error)

            viewModelScope.launch {
                repository.append(content = sanitized, entryType = ENTRY_TYPE_ERROR)
                logRepository.append(LogSeverity.ERROR, TAG, "Agent session error: $sanitized")
            }

            if (isCurrentTurn()) {
                _streamingState.update {
                    StreamingState(phase = StreamingPhase.ERROR, errorMessage = sanitized)
                }
            }
        }

        override fun onCancelled() {
            viewModelScope.launch {
                repository.append(
                    content = "Request cancelled.",
                    entryType = ENTRY_TYPE_SYSTEM,
                )
            }

            if (isCurrentTurn()) {
                _streamingState.update {
                    StreamingState(phase = StreamingPhase.CANCELLED)
                }
            }
        }
    }

    /** Constants for [TerminalViewModel]. */
    companion object {
        private const val TAG = "Terminal"
        private const val IMAGE_DISPLAY_MODEL_DIRECTIVE =
            "The client renders images natively. For this image-display request, do not use device control, " +
                "browser navigation, or page links. If you must answer through the model, return direct HTTP(S) " +
                "image URLs only as Markdown image blocks (`![description](url)`), one per image.\n\nUser request: "

        /** Timeout in milliseconds before upstream Flow collection stops. */
        private const val STOP_TIMEOUT_MS = 5_000L

        /**
         * Maximum time an agent turn may sit in an active [StreamingPhase]
         * with no terminal callback before the watchdog force-resolves it.
         * Generous enough to cover slow tool loops / multi-round provider
         * calls, but bounded so the UI can never be stuck indefinitely.
         */
        private const val TURN_WATCHDOG_TIMEOUT_MS = 120_000L

        /** Small grace period for the Termux bridge process to bind localhost after launch. */
        private const val TERMUX_SETUP_BRIDGE_WAIT_MS = 2_000L

        /** Maximum number of images per message (matches FFI-side limit). */
        private const val MAX_IMAGES = 5

        /** Maximum number of historical messages to re-seed into the native session. */
        private const val MAX_SESSION_SEED_MESSAGES = 50  // Reduced from 200 for 75% token savings

        /** Maximum tool output text retained in the background process details. */
        private const val MAX_TOOL_OUTPUT_DETAILS_CHARS = 2_000

        /** Maximum approved Termux stdout/stderr shown inline before handing back to the model. */
        private const val MAX_APPROVED_OUTPUT_CHARS = 4_000

        /** Sentinel value indicating no history selection is active. */
        private const val NO_HISTORY_SELECTION = -1

        /** Entry type constant for user input entries. */
        private const val ENTRY_TYPE_INPUT = "input"

        /** Entry type constant for daemon response entries. */
        private const val ENTRY_TYPE_RESPONSE = "response"

        /** Entry type constant for error entries. */
        private const val ENTRY_TYPE_ERROR = "error"

        /** Entry type constant for system message entries. */
        private const val ENTRY_TYPE_SYSTEM = "system"

        /** Displayed when the model response is empty after stripping markup. */
        private const val EMPTY_RESPONSE_FALLBACK =
            "The model did not generate a text response."

        /** Confirmation message shown after clearing the terminal. */
        private const val CLEAR_CONFIRMATION = "Terminal cleared."

        /** Confirmation message shown after manually resetting the native session. */
        private const val SESSION_RESET_CONFIRMATION = "Agent session reset."

        /** Warning shown when user sends a chat message without a configured provider. */
        private const val NO_PROVIDER_WARNING =
            "No chat provider configured \u2014 use /help to see admin commands, " +
                "or add a provider in Settings > API Keys."

        private const val WHATSAPP_PACKAGE_NAME = "com.whatsapp"

        /** Default prompt used when the user requests image description without typing text. */
        private const val DEFAULT_IMAGE_PROMPT = "Describe this image."

        /** Pattern matching successful REPL bind results. */
        val BIND_RESULT_PATTERN: Regex =
            Regex("""Bound (.+) to (\w+) \((\w+)\)\. Restart daemon to apply\.""")

        /**
         * Removes chain-of-thought and internal reasoning tags from a model response.
         *
         * Strips `<think>`, `<thinking>`, `<commentary>`, `<tool_output>`,
         * `<analysis>`, `<reflection>`, and `<inner_monologue>` blocks
         * emitted by reasoning models.
         *
         * @param text Raw model response.
         * @return Response with reasoning blocks removed and whitespace trimmed.
         */
        fun stripThinkingTags(text: String): String =
            TerminalResponseSanitizer.stripThinkingTags(text)

        /**
         * Removes tool-call markup from a model response.
         *
         * Some models (notably Qwen) emit raw `<tool_call>` tags in their
         * text content when they attempt a function call with no tools
         * available or produce a malformed tool invocation.
         *
         * @param text Raw model response.
         * @return Response with tool-call blocks removed and whitespace trimmed.
         */
        fun stripToolCallTags(text: String): String =
            TerminalResponseSanitizer.stripToolCallTags(text)

        /**
         * Returns whether a failed native turn should rebuild the singleton
         * session and retry once.
         *
         * The Rust layer reports missing-session failures as
         * `"no active session; call session_start first"`, so we match that
         * exact wording in addition to older generic variants.
         */
        internal fun shouldRetryWithFreshSessionDetail(
            errorMessage: String?,
            daemonRunning: Boolean,
        ): Boolean =
            TerminalSessionRecoveryPolicy.shouldRetryWithFreshSession(
                errorMessage = errorMessage,
                daemonRunning = daemonRunning,
            )

        internal fun refreshCommandForExpression(expression: String): RefreshCommand? =
            TerminalRefreshCommandResolver.resolve(expression)

        internal fun termuxStorageResetRecoveryScript(): String =
            """
            mkdir -p ~/.termux
            touch ~/.termux/termux.properties
            sed -i '/^allow-external-apps[[:space:]]*=/d' ~/.termux/termux.properties
            printf 'allow-external-apps = true\n' >> ~/.termux/termux.properties
            termux-reload-settings

            pkg update -y
            pkg install -y python
            python3 --version
            """.trimIndent()

        internal fun formatTermuxStatus(status: TermuxRuntimeStatus): String =
            buildString {
                appendLine("Termux status:")
                appendLine("  Package: ${termuxPackageLine(status)}")
                appendLine("  RUN_COMMAND: ${termuxPermissionLine(status)}")
                appendLine("  Service: ${termuxBootstrapLine(status)}")
                appendLine("  Bridge: ${termuxHealthLine(status)}")
                status.health.details.endpoint?.let { appendLine("  Endpoint: $it") }
                status.health.details.workspace?.let { appendLine("  Workspace: $it") }
                appendLine("  proot-distro: ${termuxProotLine(status)}")
                append("  Next action: ${termuxNextStep(status)}")
            }.trimEnd()

        internal fun formatTermuxSetupResult(
            initialStatus: TermuxRuntimeStatus,
            startResult: TermuxBootstrapLaunchResult?,
            finalStatus: TermuxRuntimeStatus,
            scriptCopied: Boolean,
            termuxOpened: Boolean,
        ): String =
            buildString {
                appendLine("Termux automated setup:")
                appendLine("  Before: ${termuxHealthLine(initialStatus)}")
                if (startResult != null) {
                    appendLine("  Start request: ${startResult.status.name.lowercase()} - ${startResult.reason}")
                } else {
                    appendLine("  Start request: skipped - ${termuxNextStep(initialStatus)}")
                }
                appendLine("  After: ${termuxHealthLine(finalStatus)}")
                if (finalStatus.isReady) {
                    append("  Next action: Run /termux capabilities or ask the AI to use Termux tools.")
                } else {
                    appendLine("  Recovery script: ${if (scriptCopied) "copied to clipboard" else "copy from below"}")
                    appendLine("  Termux app: ${if (termuxOpened) "opened" else "open Termux manually"}")
                    appendLine("  In Termux: paste and run this once, then return here and run /termux setup again.")
                    appendLine()
                    append(termuxStorageResetRecoveryScript())
                }
            }.trimEnd()

        internal fun formatTermuxDoctor(status: TermuxRuntimeStatus): String =
            buildString {
                appendLine("Termux doctor:")
                appendLine("  ${termuxCheckLabel(status.packageState.availability == TermuxPackageAvailability.INSTALLED)} Package: ${termuxPackageLine(status)}")
                appendLine("  ${termuxCheckLabel(status.permissionState.availability == TermuxPermissionAvailability.GRANTED)} RUN_COMMAND permission: ${termuxPermissionLine(status)}")
                appendLine("  ${termuxCheckLabel(status.bootstrapState.availability == TermuxBootstrapAvailability.AVAILABLE)} RUN_COMMAND service: ${termuxBootstrapLine(status)}")
                appendLine("  ${termuxCheckLabel(status.health.status == TermuxHealthStatus.READY)} Bridge health: ${termuxHealthLine(status)}")
                appendLine("  ${termuxCheckLabel(status.health.details.proot.available == true, warnWhenFalse = true)} proot-distro: ${termuxProotLine(status)}")
                append("  Next action: ${termuxNextStep(status)}")
            }.trimEnd()

        internal fun formatTermuxStartResult(
            result: TermuxBootstrapLaunchResult,
            status: TermuxRuntimeStatus,
        ): String =
            buildString {
                appendLine("Termux bridge startup:")
                appendLine("  Request: ${result.status.name.lowercase()} - ${result.reason}")
                appendLine("  Bridge: ${termuxHealthLine(status)}")
                status.health.details.endpoint?.let { appendLine("  Endpoint: $it") }
                append("  Next action: ${termuxNextStep(status)}")
            }.trimEnd()

        internal fun formatTermuxCapabilities(
            snapshot: TermuxCapabilitiesSnapshot,
            termuxTools: List<ToolSpec> = emptyList(),
        ): String =
            buildString {
                appendLine("Termux capabilities:")
                appendLine("  Bridge: ${snapshot.bridgeVersion ?: "unknown"} at ${snapshot.endpoint}")
                snapshot.workspaceRoot?.let { appendLine("  Workspace: $it") }
                snapshot.pythonVersion?.let { version ->
                    appendLine("  Python: $version${snapshot.pythonExecutable?.let { " ($it)" }.orEmpty()}")
                }
                appendLine("  proot-distro: ${termuxProotLine(snapshot.proot)}")
                appendLine(
                    "  Execution: " +
                        "${snapshot.limits.executionMode ?: "argv_only_low_risk"}, " +
                        "approval ${if (snapshot.limits.approvalRequired) "required" else "not required"}, " +
                        "timeout ${snapshot.limits.timeoutSeconds ?: "unknown"}s/" +
                        "${snapshot.limits.maxTimeoutSeconds ?: "unknown"}s max, " +
                        "output ${snapshot.limits.maxOutputBytes ?: "unknown"} bytes",
                )
                appendLine("  Commands:")
                if (snapshot.commands.isEmpty()) {
                    appendLine("    none reported")
                } else {
                    snapshot.commands.forEach { command ->
                        appendLine(
                            "    ${command.name}: " +
                                if (command.available) {
                                    "available${command.version?.let { " - $it" }.orEmpty()}"
                                } else {
                                    "missing"
                                },
                        )
                    }
                }
                appendLine("  AI tools:")
                if (termuxTools.isEmpty()) {
                    appendLine("    termux_get_capabilities / termux_run metadata unavailable")
                } else {
                    termuxTools.sortedBy { it.name }.forEach { tool ->
                        appendLine(
                            "    ${tool.name}: " +
                                if (tool.isActive) {
                                    "active"
                                } else {
                                    "inactive - ${tool.inactiveReason.ifBlank { "not ready" }}"
                                },
                        )
                    }
                }
            }.trimEnd()

        internal fun formatTermuxSmokeResult(result: TermuxExecutionResult): String =
            when (result) {
                is TermuxExecutionResult.Success ->
                    formatTermuxSmokeSnapshot(result.snapshot)
                is TermuxExecutionResult.Failure ->
                    buildString {
                        appendLine("Termux smoke:")
                        appendLine("  Result: failed")
                        append("  Detail: ${result.reason}")
                    }.trimEnd()
            }

        private fun formatTermuxSmokeSnapshot(snapshot: TermuxExecutionSnapshot): String =
            buildString {
                appendLine("Termux smoke:")
                appendLine("  Command: ${snapshot.argv.joinToString(" ")}")
                appendLine("  Result: ${if (snapshot.success) "passed" else "failed"}")
                snapshot.exitCode?.let { appendLine("  Exit code: $it") }
                snapshot.durationMs?.let { appendLine("  Duration: ${it}ms") }
                snapshot.workingDirectory?.let { appendLine("  Working directory: $it") }
                val output = snapshot.stdout.ifBlank { snapshot.stderr }.trim()
                appendLine("  Output: ${output.ifBlank { "<empty>" }}")
                if (snapshot.stdoutTruncated || snapshot.stderrTruncated) {
                    appendLine("  Truncated: output exceeded display limits.")
                }
                append("  Next action: ${if (snapshot.success) "Ask the AI to use termux_get_capabilities or termux_run." else "Run /termux doctor."}")
            }.trimEnd()

        private fun termuxPackageLine(status: TermuxRuntimeStatus): String =
            when (status.packageState.availability) {
                TermuxPackageAvailability.INSTALLED ->
                    "installed${status.packageState.versionName?.let { " ($it)" }.orEmpty()}"
                TermuxPackageAvailability.NOT_VISIBLE ->
                    "not visible to Android package visibility checks"
                TermuxPackageAvailability.NOT_INSTALLED ->
                    "not installed"
            }

        private fun termuxPermissionLine(status: TermuxRuntimeStatus): String =
            when (status.permissionState.availability) {
                TermuxPermissionAvailability.GRANTED ->
                    "granted"
                TermuxPermissionAvailability.UNKNOWN ->
                    "unknown"
                TermuxPermissionAvailability.DENIED ->
                    "denied"
            }

        private fun termuxBootstrapLine(status: TermuxRuntimeStatus): String =
            when (status.bootstrapState.availability) {
                TermuxBootstrapAvailability.AVAILABLE ->
                    "available"
                TermuxBootstrapAvailability.UNKNOWN ->
                    "unknown"
                TermuxBootstrapAvailability.UNAVAILABLE ->
                    "unavailable"
            }

        private fun termuxHealthLine(status: TermuxRuntimeStatus): String =
            when (status.health.status) {
                TermuxHealthStatus.READY ->
                    "ready - ${status.health.reason.ifBlank { "Zero-Assist Termux bridge is ready." }}"
                TermuxHealthStatus.UNAVAILABLE ->
                    "not ready - ${status.health.reason.ifBlank { status.inactiveReason() }}"
                TermuxHealthStatus.UNKNOWN ->
                    "unknown - ${status.health.reason.ifBlank { "health has not been checked" }}"
            }

        private fun termuxProotLine(status: TermuxRuntimeStatus): String = termuxProotLine(status.health.details.proot)

        private fun termuxProotLine(proot: com.zeroclaw.android.service.termux.TermuxProotState): String =
            when {
                proot.available == true && proot.distros.isNotEmpty() ->
                    "available (${proot.distros.joinToString()})"
                proot.available == true ->
                    "available"
                proot.available == false ->
                    "optional, not installed"
                else ->
                    "unknown"
            }

        private fun termuxCheckLabel(
            pass: Boolean,
            warnWhenFalse: Boolean = false,
        ): String =
            when {
                pass -> "PASS"
                warnWhenFalse -> "WARN"
                else -> "FAIL"
            }

        private fun termuxNextStep(status: TermuxRuntimeStatus): String =
            when {
                status.packageState.availability != TermuxPackageAvailability.INSTALLED ->
                    "Install Termux from F-Droid or GitHub, then rerun /termux status."
                status.permissionState.availability != TermuxPermissionAvailability.GRANTED ->
                    "Grant Zero-Assist the Termux RUN_COMMAND permission and keep allow-external-apps enabled."
                status.bootstrapState.availability == TermuxBootstrapAvailability.UNAVAILABLE ->
                    "Update Termux and confirm RunCommandService is available."
                status.health.status != TermuxHealthStatus.READY ->
                    "Run /termux setup to repair storage-reset settings or restart the bridge."
                else ->
                    "Run /termux capabilities or ask the AI to use termux_get_capabilities."
            }

        private val WHATSAPP_MESSAGE_SEPARATORS = listOf("::", "--message")
        private val THINKING_PHASE_REGEX = Regex("""^Thinking(?:\s*\(round\s*(\d+)\))?\.\.\.$""", RegexOption.IGNORE_CASE)

        /**
         * Maps a persisted [TerminalEntry] to a display [TerminalBlock].
         *
         * Response entries whose content starts with `{` or `[` are
         * classified as [TerminalBlock.Structured] for JSON rendering.
         *
         * @param entry The persisted terminal entry.
         * @return The corresponding display block.
         */
        fun toBlock(entry: TerminalEntry): TerminalBlock = TerminalBlockMapper.toBlock(entry)
    }
}


/**
 * Immutable snapshot of the terminal REPL screen state.
 *
 * @property blocks Ordered list of terminal blocks for the scrollback buffer.
 * @property isLoading True while waiting for an FFI response.
 * @property pendingImages Images staged for the next message.
 * @property isProcessingImages True while images are being downscaled and encoded.
 */
@Immutable
data class TerminalState(
    val blocks: List<TerminalBlock> = emptyList(),
    val isLoading: Boolean = false,
    val pendingImages: List<ProcessedImage> = emptyList(),
    val isProcessingImages: Boolean = false,
    val pendingTermuxApproval: TermuxApprovalRequest? = null,
)
