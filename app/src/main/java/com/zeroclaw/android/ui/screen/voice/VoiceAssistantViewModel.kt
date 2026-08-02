/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.voice

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.zeroclaw.android.model.VoiceAssistantCommand
import com.zeroclaw.android.model.VoiceAssistantSessionState
import com.zeroclaw.android.model.VoiceAssistantUiState
import com.zeroclaw.android.model.VoiceImportState
import com.zeroclaw.android.model.VoiceModel
import com.zeroclaw.android.model.VoiceModelSource
import com.zeroclaw.android.model.VoiceModelStatus
import com.zeroclaw.android.model.VoicePreviewState
import com.zeroclaw.android.service.LocalSpeechRecognizer
import com.zeroclaw.android.service.LocalSpeechEngineStatus
import com.zeroclaw.android.service.LocalSpeechSynthesizer
import com.zeroclaw.android.service.LocalVoiceCatalogRepository
import com.zeroclaw.android.service.LocalVoiceStorage
import com.zeroclaw.android.service.defaultLaunchFailureMessage
import com.zeroclaw.android.service.InMemoryVoiceOutputPreferences
import com.zeroclaw.android.service.InMemoryVoiceWakeupPreferences
import com.zeroclaw.android.service.MissingLocalSpeechRecognizer
import com.zeroclaw.android.service.MissingLocalSpeechSynthesizer
import com.zeroclaw.android.service.MissingVoiceAssistantConversation
import com.zeroclaw.android.service.MissingVoiceContactLookup
import com.zeroclaw.android.service.SpeechRecognitionEvent
import com.zeroclaw.android.service.SpeechSynthesisResult
import com.zeroclaw.android.service.VoiceAssistantConversation
import com.zeroclaw.android.service.VoiceAssistantConversationResult
import com.zeroclaw.android.service.VoiceContactLookup
import com.zeroclaw.android.service.VoiceContactLookupResult
import com.zeroclaw.android.service.VoiceDownloadInstallResult
import com.zeroclaw.android.service.VoiceDownloadManager
import com.zeroclaw.android.service.VoiceAssistantCommandLaunchResult
import com.zeroclaw.android.service.VoiceFastPathResult
import com.zeroclaw.android.service.VoiceFastPathRouter
import com.zeroclaw.android.service.VoicePreviewController
import com.zeroclaw.android.service.VoiceOutputPreferences
import com.zeroclaw.android.service.VoicePackageImportFile
import com.zeroclaw.android.service.VoicePackageImportResult
import com.zeroclaw.android.service.VoicePackageImportSelectionRequest
import com.zeroclaw.android.service.VoicePerformanceMode
import com.zeroclaw.android.service.MissingVoiceWakeupDetector
import com.zeroclaw.android.service.MissingVoiceWakeupServiceController
import com.zeroclaw.android.service.VoiceWakeupForegroundStartDecision
import com.zeroclaw.android.service.VoiceWakeupForegroundStartGuard
import com.zeroclaw.android.service.VoiceWakeupDetector
import com.zeroclaw.android.service.VoiceWakeupDetectorStatus
import com.zeroclaw.android.service.VoiceWakeupPreferences
import com.zeroclaw.android.service.VoiceWakeupServiceCommandResult
import com.zeroclaw.android.service.VoiceWakeupServiceController
import com.zeroclaw.android.service.VoiceMicSessionCoordinator
import com.zeroclaw.android.service.VoiceTurnTrace
import com.zeroclaw.android.service.VoiceTurnTraceRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class VoiceAssistantViewModel private constructor(
    private val voiceCatalogRepository: LocalVoiceCatalogRepository,
    private val localVoiceStorage: LocalVoiceStorage? = null,
    localSpeechRecognizerProvider: () -> LocalSpeechRecognizer,
    private val voiceContactLookup: VoiceContactLookup = MissingVoiceContactLookup,
    private val voiceAssistantConversation: VoiceAssistantConversation = MissingVoiceAssistantConversation,
    private val voiceDownloadManager: VoiceDownloadManager? = null,
    private val voiceOutputPreferences: VoiceOutputPreferences = InMemoryVoiceOutputPreferences(),
    private val voiceWakeupPreferences: VoiceWakeupPreferences = InMemoryVoiceWakeupPreferences(),
    voiceWakeupDetectorProvider: () -> VoiceWakeupDetector,
    private val voiceWakeupServiceController: VoiceWakeupServiceController = MissingVoiceWakeupServiceController,
    localSpeechSynthesizerProvider: () -> LocalSpeechSynthesizer,
    activateSpeechOnInit: Boolean,
    activateWakeupOnInit: Boolean,
) : ViewModel() {
    constructor(
        voiceCatalogRepository: LocalVoiceCatalogRepository,
        localVoiceStorage: LocalVoiceStorage? = null,
        localSpeechRecognizer: LocalSpeechRecognizer = MissingLocalSpeechRecognizer(),
        voiceContactLookup: VoiceContactLookup = MissingVoiceContactLookup,
        voiceAssistantConversation: VoiceAssistantConversation = MissingVoiceAssistantConversation,
        voiceDownloadManager: VoiceDownloadManager? = null,
        voiceOutputPreferences: VoiceOutputPreferences = InMemoryVoiceOutputPreferences(),
        voiceWakeupPreferences: VoiceWakeupPreferences = InMemoryVoiceWakeupPreferences(),
        voiceWakeupDetector: VoiceWakeupDetector = MissingVoiceWakeupDetector(),
        voiceWakeupServiceController: VoiceWakeupServiceController = MissingVoiceWakeupServiceController,
        localSpeechSynthesizer: LocalSpeechSynthesizer = MissingLocalSpeechSynthesizer(),
    ) : this(
        voiceCatalogRepository = voiceCatalogRepository,
        localVoiceStorage = localVoiceStorage,
        localSpeechRecognizerProvider = { localSpeechRecognizer },
        voiceContactLookup = voiceContactLookup,
        voiceAssistantConversation = voiceAssistantConversation,
        voiceDownloadManager = voiceDownloadManager,
        voiceOutputPreferences = voiceOutputPreferences,
        voiceWakeupPreferences = voiceWakeupPreferences,
        voiceWakeupDetectorProvider = { voiceWakeupDetector },
        voiceWakeupServiceController = voiceWakeupServiceController,
        localSpeechSynthesizerProvider = { localSpeechSynthesizer },
        activateSpeechOnInit = true,
        activateWakeupOnInit = true,
    )

    private val localSpeechRecognizerHolder = lazy(localSpeechRecognizerProvider)
    private val localSpeechSynthesizerHolder = lazy(localSpeechSynthesizerProvider)
    private val voiceWakeupDetectorHolder = lazy(voiceWakeupDetectorProvider)
    private val localSpeechRecognizer: LocalSpeechRecognizer
        get() = localSpeechRecognizerHolder.value
    private val localSpeechSynthesizer: LocalSpeechSynthesizer
        get() = localSpeechSynthesizerHolder.value
    private val voiceWakeupDetector: VoiceWakeupDetector
        get() = voiceWakeupDetectorHolder.value
    private val popupVisible = MutableStateFlow(false)
    private val sessionState = MutableStateFlow(VoiceAssistantSessionState.MissingVoice)
    private val wakeupRequested = MutableStateFlow(false)
    private val wakeupDetectorStatus = MutableStateFlow(VoiceWakeupDetectorStatus.Unavailable)
    private val speechRecognitionStatus =
        MutableStateFlow<LocalSpeechEngineStatus>(
            LocalSpeechEngineStatus.Unavailable(SPEECH_RECOGNITION_NOT_STARTED_MESSAGE),
        )
    private val transcriptState = MutableStateFlow(VoiceAssistantTranscriptState())
    private var recognitionJob: Job? = null
    private var recognitionTurnId = 0L
    private var activeConversationJob: Job? = null
    private var voicePrewarmJob: Job? = null
    private var speechRecognitionStatusJob: Job? = null
    private var wakeupDetectorStatusJob: Job? = null
    private var prewarmedVoiceId: String? = null
    private var prewarmingVoiceId: String? = null
    private var pendingContactCall: PendingContactCall? = null
    private var wakeupServiceStarted = false
    private var wakeupServicePausedForVoiceTurn = false
    private var activeVoiceTrace: VoiceTurnTrace? = null
    private var lastSpokenResponse: String? = null
    private var activeConversationKind: ActiveConversationKind = ActiveConversationKind.None
    private val micSessionCoordinator = VoiceMicSessionCoordinator()
    private val voicePreviewControllerHolder =
        lazy {
            VoicePreviewController(
                voiceCatalogRepository = voiceCatalogRepository,
                synthesizer = localSpeechSynthesizer,
            )
        }
    private val voicePreviewController: VoicePreviewController
        get() = voicePreviewControllerHolder.value

    init {
        viewModelScope.launch {
            voiceWakeupPreferences.wakeupRequested.collect { requested ->
                wakeupRequested.value = requested
            }
        }
        if (activateSpeechOnInit) {
            activateLocalSpeechCapabilities()
        }
        if (activateWakeupOnInit) {
            activateWakeupStatus()
        }
    }

    val voices = voiceCatalogRepository.voices
    val selectedVoiceId = voiceCatalogRepository.selectedVoiceId
    private val _previewState = MutableStateFlow<VoicePreviewState>(VoicePreviewState.Idle)
    val previewState = _previewState.asStateFlow()
    private val _importState = MutableStateFlow<VoiceImportState>(VoiceImportState.Idle)
    val importState = _importState.asStateFlow()
    private val _voiceCommands = MutableSharedFlow<VoiceAssistantCommand>(extraBufferCapacity = 1)
    val voiceCommands = _voiceCommands.asSharedFlow()
    private val _contactPermissionRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val contactPermissionRequests = _contactPermissionRequests.asSharedFlow()
    val voiceDiagnostics = VoiceTurnTraceRecorder.turns
    val voicePerformanceMode = voiceOutputPreferences.performanceMode

    private val wakeupReadiness =
        combine(wakeupRequested, wakeupDetectorStatus) { requested, detectorStatus ->
            WakeupReadiness(
                requested = requested,
                available = detectorStatus.available && detectorStatus.foregroundServiceReady,
                statusMessage = wakeupStatusMessage(requested, detectorStatus),
            )
        }

    private val voiceSelectionState =
        combine(
            voiceCatalogRepository.voices,
            voiceCatalogRepository.selectedVoiceId,
        ) { voices, selectedId ->
            val selectedVoice =
                voices.firstOrNull { voice ->
                    voice.id == selectedId && voice.status == VoiceModelStatus.Installed
            }
            VoiceSelectionState(
                selectedVoice = selectedVoice,
            )
        }

    private val baseUiState =
        combine(
            popupVisible,
            sessionState,
            wakeupReadiness,
            voiceSelectionState,
            speechRecognitionStatus,
        ) { visible, session, wakeup, voiceSelection, speechStatus ->
            val selectedVoice = voiceSelection.selectedVoice
            VoiceAssistantUiState(
                popupVisible = visible,
                sessionState =
                    when {
                        selectedVoice == null &&
                            session != VoiceAssistantSessionState.Processing ->
                            VoiceAssistantSessionState.MissingVoice
                        selectedVoice != null && session == VoiceAssistantSessionState.MissingVoice ->
                            VoiceAssistantSessionState.Idle
                        else -> session
                    },
                selectedVoice = selectedVoice,
                wakeupEnabled = wakeup.requested,
                wakeupAvailable = wakeup.available,
                wakeupStatusMessage = wakeup.statusMessage,
                speechRecognitionAvailable = speechStatus.canStartListening,
                speechRecognitionStatusMessage =
                    speechStatus
                        .assistantStatusMessage()
                        .takeIf { selectedVoice != null && speechStatus != LocalSpeechEngineStatus.Ready },
            )
        }

    val uiState =
        combine(
            baseUiState,
            transcriptState,
        ) { baseState, transcript ->
            baseState.copy(
                partialTranscript = transcript.partialTranscript,
                lastTranscript = transcript.lastTranscript,
                statusMessage = transcript.statusMessage,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = VoiceAssistantUiState(),
        )

    fun openPopup() {
        activateLocalSpeechCapabilities()
        activateWakeupStatus()
        val trace =
            activeVoiceTrace
                ?: VoiceTurnTrace.start("popup").also { activeVoiceTrace = it }
        trace.markOnce("popup_opened")
        popupVisible.value = true
        sessionState.value =
            if (voiceCatalogRepository.selectedVoice() == null) {
                VoiceAssistantSessionState.MissingVoice
            } else {
                VoiceAssistantSessionState.Idle
            }
        prewarmSelectedVoice(trace)
    }

    fun closePopup() {
        val trace = activeVoiceTrace
        popupVisible.value = false
        stopListening()
        cancelActiveConversation()
        stopLocalSpeechSynthesizerIfInitialized()
        voicePrewarmJob?.cancel()
        sessionState.value = VoiceAssistantSessionState.Idle
        trace?.complete("popup_closed")
        clearVoiceTrace(trace)
    }

    fun clearVoiceDiagnostics() {
        VoiceTurnTraceRecorder.clear()
    }

    fun setVoicePerformanceMode(mode: VoicePerformanceMode) {
        voiceOutputPreferences.setPerformanceMode(mode)
        transcriptState.update {
            it.copy(statusMessage = "Speech mode set to ${mode.displayLabel()}.")
        }
    }

    fun activateVoiceSettingsStatus() {
        activateWakeupStatus()
    }

    private fun activateLocalSpeechCapabilities() {
        if (speechRecognitionStatusJob?.isActive == true) {
            return
        }
        speechRecognitionStatus.value = localSpeechRecognizer.status.value
        speechRecognitionStatusJob =
            viewModelScope.launch {
                localSpeechRecognizer.status.collect { status ->
                    speechRecognitionStatus.value = status
                }
            }
    }

    private fun activateWakeupStatus() {
        if (wakeupDetectorStatusJob?.isActive == true) {
            return
        }
        val statusFlow = voiceWakeupDetector.status
        if (statusFlow is StateFlow<*>) {
            (statusFlow.value as? VoiceWakeupDetectorStatus)?.let { status ->
                wakeupDetectorStatus.value = status
            }
        }
        wakeupDetectorStatusJob =
            viewModelScope.launch {
                statusFlow.collect { status ->
                    wakeupDetectorStatus.value = status
                }
            }
    }

    private fun stopLocalSpeechRecognizerIfInitialized() {
        if (localSpeechRecognizerHolder.isInitialized()) {
            localSpeechRecognizer.stop()
        }
    }

    private fun stopLocalSpeechSynthesizerIfInitialized() {
        if (localSpeechSynthesizerHolder.isInitialized()) {
            localSpeechSynthesizer.stop()
        }
    }

    private fun stopVoicePreviewIfInitialized() {
        if (voicePreviewControllerHolder.isInitialized()) {
            voicePreviewController.stop()
        }
    }

    fun startListening() {
        if (recognitionJob?.isActive == true ||
            sessionState.value == VoiceAssistantSessionState.Listening
        ) {
            return
        }
        val trace =
            activeVoiceTrace
                ?: VoiceTurnTrace.start("popup").also {
                    activeVoiceTrace = it
                    it.markOnce("popup_opened", "implicit=true")
                }
        trace.mark("mic_tapped", "session=${sessionState.value}")
        if (voiceCatalogRepository.selectedVoice() == null) {
            sessionState.value = VoiceAssistantSessionState.MissingVoice
            trace.complete("missing_voice")
            clearVoiceTrace(trace)
            transcriptState.update {
                it.copy(statusMessage = "Download or import an English voice before listening.")
            }
            return
        }
        activateLocalSpeechCapabilities()
        prewarmSelectedVoice(trace)
        val speechStatus = localSpeechRecognizer.status.value
        if (!speechStatus.canStartListening) {
            sessionState.value = VoiceAssistantSessionState.Idle
            trace.mark("speech_status_blocked", speechStatus.javaClass.simpleName)
            trace.complete("speech_unavailable")
            clearVoiceTrace(trace)
            transcriptState.update {
                it.copy(
                    partialTranscript = "",
                    statusMessage = speechStatus.assistantStatusMessage(),
                )
            }
            return
        }

        pauseWakeupForForegroundVoiceTurn(trace)
        cancelActiveConversation()
        trace.mark("active_tts_stop_requested")
        localSpeechSynthesizer.stop()
        recognitionJob?.cancel()
        trace.mark("previous_recognizer_stop_requested")
        localSpeechRecognizer.stop()
        val turnId = nextRecognitionTurnId()
        sessionState.value = VoiceAssistantSessionState.Listening
        transcriptState.value =
            VoiceAssistantTranscriptState(
                lastTranscript = transcriptState.value.lastTranscript,
            )
        recognitionJob =
            viewModelScope.launch {
                localSpeechRecognizer
                    .listen(trace)
                    .onCompletion {
                        if (
                            turnId == recognitionTurnId &&
                            sessionState.value == VoiceAssistantSessionState.Listening
                        ) {
                            sessionState.value = VoiceAssistantSessionState.Idle
                        }
                    }.collect { event ->
                        handleRecognitionEvent(event, turnId)
                    }
            }
    }

    fun submitTextPrompt(prompt: String) {
        val transcript = prompt.trim()
        if (transcript.isBlank()) return

        val trace =
            activeVoiceTrace
                ?: VoiceTurnTrace.start("typed").also {
                    activeVoiceTrace = it
                    it.markOnce("popup_opened", "implicit=true")
                }
        trace.mark("typed_prompt_submitted", "length=${transcript.length}")
        popupVisible.value = true

        invalidateRecognitionTurn()
        recognitionJob?.cancel()
        recognitionJob = null
        stopLocalSpeechRecognizerIfInitialized()
        cancelActiveConversation()
        stopLocalSpeechSynthesizerIfInitialized()

        appendUserCard(transcript)
        if (tryHandleVoiceFastPath(transcript, trace)) {
            return
        }
        startVoiceConversation(transcript, trace)
    }

    fun stopListening() {
        stopListening(resumeWakeup = true)
    }

    private fun stopListening(resumeWakeup: Boolean) {
        val trace = activeVoiceTrace
        val wasListening =
            recognitionJob != null || sessionState.value == VoiceAssistantSessionState.Listening
        if (wasListening) {
            trace?.mark("listening_stop_requested", "resumeWakeup=$resumeWakeup")
        }
        invalidateRecognitionTurn()
        recognitionJob?.cancel()
        recognitionJob = null
        stopLocalSpeechRecognizerIfInitialized()
        sessionState.value =
            if (voiceCatalogRepository.selectedVoice() == null) {
                VoiceAssistantSessionState.MissingVoice
            } else {
                VoiceAssistantSessionState.Idle
            }
        if (resumeWakeup) {
            if (wasListening || wakeupServicePausedForVoiceTurn) {
                resumeWakeupAfterForegroundVoiceTurn(trace)
            }
        } else if (wasListening || wakeupServicePausedForVoiceTurn) {
            wakeupServicePausedForVoiceTurn = false
            micSessionCoordinator.finishPopupDictation(
                resumeWakeup = false,
                wakeupRequested = false,
                startWakeup = { VoiceWakeupServiceCommandResult.Accepted },
                trace = trace,
            )
        } else {
            wakeupServicePausedForVoiceTurn = false
        }
        if (wasListening) {
            trace?.complete("listening_stopped")
            clearVoiceTrace(trace)
        }
    }

    fun finishListening() {
        if (recognitionJob?.isActive != true) {
            return
        }
        activeVoiceTrace?.mark("finish_listening_requested")
        localSpeechRecognizer.finish()
    }

    fun cancelAssistantTurn() {
        val hadActiveConversation = activeConversationJob?.isActive == true
        cancelActiveConversation()
        stopLocalSpeechSynthesizerIfInitialized()
        if (!hadActiveConversation) {
            return
        }
        sessionState.value =
            if (voiceCatalogRepository.selectedVoice() == null) {
                VoiceAssistantSessionState.MissingVoice
            } else {
                VoiceAssistantSessionState.Idle
            }
        transcriptState.update {
            it.copy(
                partialTranscript = "",
                statusMessage = "Voice request cancelled.",
            )
        }
        appendActionCard(message = "Voice request cancelled.", canCancel = false)
        val trace = activeVoiceTrace
        resumeWakeupAfterForegroundVoiceTurn(trace)
        trace?.complete("assistant_turn_cancelled")
        clearVoiceTrace(trace)
    }

    fun microphonePermissionDenied() {
        sessionState.value =
            if (voiceCatalogRepository.selectedVoice() == null) {
                VoiceAssistantSessionState.MissingVoice
            } else {
                VoiceAssistantSessionState.Idle
            }
        transcriptState.update {
            it.copy(
                partialTranscript = "",
                statusMessage = "Microphone permission is required for local voice mode.",
            )
        }
        appendErrorCard(
            message = "Microphone permission is required for local voice mode.",
            canRetry = false,
        )
    }

    fun commandLaunchFailed(
        command: VoiceAssistantCommand,
        failureMessage: String? = null,
    ) {
        popupVisible.value = true
        sessionState.value =
            if (voiceCatalogRepository.selectedVoice() == null) {
                VoiceAssistantSessionState.MissingVoice
            } else {
                VoiceAssistantSessionState.Idle
            }
        transcriptState.update {
            it.copy(
                partialTranscript = "",
                lastTranscript = command.text.ifBlank { it.lastTranscript },
                statusMessage = failureMessage ?: command.defaultLaunchFailureMessage(),
            )
        }
        appendErrorCard(
            message = failureMessage ?: command.defaultLaunchFailureMessage(),
            canRetry = true,
        )
    }

    fun commandLaunchProgress(
        command: VoiceAssistantCommand,
        progressMessage: String,
    ) {
        val message = progressMessage.trim()
        if (message.isEmpty()) {
            return
        }
        popupVisible.value = true
        sessionState.value =
            if (voiceCatalogRepository.selectedVoice() == null) {
                VoiceAssistantSessionState.MissingVoice
            } else {
                VoiceAssistantSessionState.Processing
            }
        transcriptState.update {
            it.copy(
                partialTranscript = "",
                lastTranscript = command.text.ifBlank { it.lastTranscript },
                statusMessage = message,
            )
        }
        appendActionCard(message = message, canCancel = true)
    }

    private fun appendUserCard(text: String) {
        val normalized = text.trim()
        if (normalized.isBlank()) {
            return
        }
        transcriptState.update {
            it.copy(lastTranscript = normalized)
        }
    }

    private fun appendTextAnswerCard(text: String) {
        updateStatusMessage(text)
    }

    private fun appendActionCard(
        message: String,
        canCancel: Boolean,
    ) {
        updateStatusMessage(message)
    }

    private fun appendErrorCard(
        message: String,
        canRetry: Boolean,
    ) {
        updateStatusMessage(message)
    }

    private fun updateStatusMessage(message: String) {
        val normalized = message.trim()
        if (normalized.isBlank()) {
            return
        }
        transcriptState.update {
            it.copy(statusMessage = normalized)
        }
    }

    fun runCommandLaunch(
        command: VoiceAssistantCommand,
        launchCommand: suspend (
            VoiceAssistantCommand,
            suspend (String) -> Unit,
        ) -> VoiceAssistantCommandLaunchResult,
        onSuccessWithoutFinalMessage: () -> Unit = {},
    ) {
        cancelActiveConversation()
        var commandJob: Job? = null
        commandJob =
            viewModelScope.launch(start = CoroutineStart.LAZY) {
                try {
                    val result =
                        launchCommand(command) { message ->
                            commandLaunchProgress(command, message)
                        }
                    handleCommandLaunchResult(
                        command = command,
                        result = result,
                        onSuccessWithoutFinalMessage = onSuccessWithoutFinalMessage,
                    )
                } catch (error: CancellationException) {
                    handleCommandLaunchResult(
                        command = command,
                        result =
                            VoiceAssistantCommandLaunchResult(
                                success = false,
                                failureMessage = VOICE_REQUEST_CANCELLED_MESSAGE,
                                finalMessage = VOICE_REQUEST_CANCELLED_MESSAGE,
                                shouldSpeak = false,
                            ),
                        onSuccessWithoutFinalMessage = onSuccessWithoutFinalMessage,
                    )
                    throw error
                } finally {
                    if (activeConversationJob === commandJob) {
                        activeConversationJob = null
                        activeConversationKind = ActiveConversationKind.None
                    }
                }
            }
        activeConversationJob = commandJob
        activeConversationKind = ActiveConversationKind.Command
        commandJob.start()
    }

    fun contactsPermissionResult(granted: Boolean) {
        val pending = pendingContactCall ?: return
        if (!granted) {
            pendingContactCall = null
            transcriptState.update {
                it.copy(statusMessage = "Contacts permission is required to call ${pending.contactName}.")
            }
            appendErrorCard(
                message = "Contacts permission is required to call ${pending.contactName}.",
                canRetry = true,
            )
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            resolveContactCall(pending.transcript, pending.contactName)
        }
    }

    fun downloadVoice(voiceId: String) {
        val manager = voiceDownloadManager
        if (manager == null) {
            if (voiceCatalogRepository.startDownload(voiceId)) {
                voiceCatalogRepository.markDownloadFailed(
                    voiceId = voiceId,
                    reason = "Voice download manager is not connected.",
                )
            }
            return
        }

        viewModelScope.launch(Dispatchers.IO) {
            when (val result = manager.downloadVoice(voiceId)) {
                is VoiceDownloadInstallResult.Failure -> {
                    transcriptState.update {
                        it.copy(statusMessage = result.message)
                    }
                }
                is VoiceDownloadInstallResult.Success -> {
                    sessionState.value = VoiceAssistantSessionState.Idle
                    transcriptState.update {
                        it.copy(statusMessage = "Voice downloaded for local playback.")
                    }
                }
            }
        }
    }

    fun selectVoice(voiceId: String) {
        if (voiceCatalogRepository.selectVoice(voiceId)) {
            sessionState.value = VoiceAssistantSessionState.Idle
            if (popupVisible.value) {
                prewarmSelectedVoice(activeVoiceTrace)
            }
        }
    }

    fun previewVoice(voiceId: String) {
        _previewState.value = VoicePreviewState.Speaking(voiceId)
        viewModelScope.launch {
            _previewState.value = voicePreviewController.preview(voiceId)
        }
    }

    fun stopPreview() {
        voicePreviewController.stop()
        _previewState.value = VoicePreviewState.Idle
    }

    fun deleteVoice(voiceId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val deletedVoice = voiceCatalogRepository.deleteVoice(voiceId)
            if (deletedVoice == null) {
                transcriptState.update {
                    it.copy(statusMessage = "Installed voice was not found.")
                }
                return@launch
            }

            localSpeechSynthesizer.stop()
            stopVoicePreviewIfInitialized()
            _previewState.value = VoicePreviewState.Idle

            val storageDeleted =
                runCatching {
                    localVoiceStorage?.deleteStoredVoice(deletedVoice.modelUri) ?: true
                }.getOrDefault(false)
            sessionState.value =
                if (voiceCatalogRepository.selectedVoice() == null) {
                    VoiceAssistantSessionState.MissingVoice
                } else {
                    VoiceAssistantSessionState.Idle
                }
            transcriptState.update {
                it.copy(
                    statusMessage =
                        if (storageDeleted) {
                            "${deletedVoice.displayName} deleted from this phone."
                        } else {
                            "${deletedVoice.displayName} was removed from the list, " +
                                "but its local files could not be deleted."
                        },
                )
            }
        }
    }

    fun importVoice(
        displayName: String,
        modelUri: String,
        sizeBytes: Long,
    ) {
        importVoiceFiles(
            listOf(
                VoicePackageImportFile(
                    displayName = displayName,
                    sourceUri = modelUri,
                    declaredSizeBytes = sizeBytes,
                ),
            ),
        )
    }

    fun importVoiceFiles(
        files: List<VoicePackageImportFile>,
    ) {
        _importState.value = VoiceImportState.Importing
        viewModelScope.launch(Dispatchers.IO) {
            if (files.isEmpty()) {
                _importState.value = VoiceImportState.Failed("Select a voice package or Piper voice files.")
                return@launch
            }
            val name = files.first().displayName.ifBlank { "Imported Voice" }
            val storedPackage =
                if (localVoiceStorage == null) {
                    ImportedVoicePackage(
                        displayName = name,
                        modelUri = files.first().sourceUri,
                        sizeBytes = files.sumOf { file -> file.declaredSizeBytes.coerceAtLeast(0L) },
                        sampleText = "This imported voice is ready for local playback.",
                        runtimeType = null,
                    )
                } else {
                    when (
                        val importResult =
                            localVoiceStorage.importSelectedFiles(
                                VoicePackageImportSelectionRequest(
                                    localeTag = "en-US",
                                    files = files,
                                ),
                            )
                    ) {
                        is VoicePackageImportResult.Failure -> {
                            _importState.value = VoiceImportState.Failed(importResult.message)
                            return@launch
                        }
                        is VoicePackageImportResult.Success ->
                            ImportedVoicePackage(
                                displayName = importResult.voicePackage.displayName,
                                modelUri = importResult.voicePackage.localModelUri,
                                sizeBytes = importResult.voicePackage.sizeBytes,
                                sampleText = importResult.voicePackage.sampleText,
                                runtimeType = importResult.voicePackage.runtimeType,
                            )
                    }
                }
            val id = importedVoiceId(storedPackage.displayName, storedPackage.modelUri)
            val imported =
                VoiceModel(
                    id = id,
                    displayName = storedPackage.displayName,
                    toneLabel = "Custom",
                    localeTag = "en-US",
                    description =
                        storedPackage.runtimeType?.let { runtime ->
                            "Imported English voice package for $runtime local playback."
                        } ?: "Imported ready-made English voice model.",
                    sizeBytes = storedPackage.sizeBytes,
                    source = VoiceModelSource.IMPORTED,
                    status = VoiceModelStatus.Installed,
                    sampleText = storedPackage.sampleText,
                    modelUri = storedPackage.modelUri,
                )
            val importedSuccessfully = voiceCatalogRepository.importInstalledVoice(imported)
            if (importedSuccessfully) {
                sessionState.value = VoiceAssistantSessionState.Idle
                _importState.value = VoiceImportState.Imported(storedPackage.displayName)
            } else {
                _importState.value = VoiceImportState.Failed("Imported voice metadata is invalid.")
            }
        }
    }

    fun setWakeupEnabled(enabled: Boolean) {
        wakeupRequested.value = enabled
        if (enabled) {
            activateWakeupStatus()
        }
        viewModelScope.launch {
            voiceWakeupPreferences.setWakeupRequested(enabled)
        }
        val detectorStatus = wakeupDetectorStatus.value
        syncWakeupService(
            requested = enabled,
            detectorStatus = detectorStatus,
            updateUserMessage = true,
        )
    }

    private fun syncWakeupService(
        requested: Boolean,
        detectorStatus: VoiceWakeupDetectorStatus,
        updateUserMessage: Boolean,
    ) {
        if (!requested) {
            wakeupServicePausedForVoiceTurn = false
            if (wakeupServiceStarted) {
                voiceWakeupServiceController.stopWakeup()
                wakeupServiceStarted = false
                micSessionCoordinator.noteWakeupStopped()
            }
            if (updateUserMessage) {
                updateWakeupStatusMessage(WAKEUP_DISABLED_MESSAGE)
            }
            return
        }

        if (micSessionCoordinator.isPopupListening()) {
            if (wakeupServiceStarted) {
                voiceWakeupServiceController.stopWakeup()
                wakeupServiceStarted = false
            }
            micSessionCoordinator.deferWakeupUntilPopupEnds()
            wakeupServicePausedForVoiceTurn = true
            if (updateUserMessage) {
                updateWakeupStatusMessage("Wake-up preference saved. It will resume after this voice turn.")
            }
            return
        }

        when (
            val decision =
                VoiceWakeupForegroundStartGuard.evaluate(
                    status = detectorStatus,
                    hasRecordAudioPermission = voiceWakeupServiceController.hasRecordAudioPermission(),
                )
        ) {
            VoiceWakeupForegroundStartDecision.Ready -> {
                val result =
                    if (!wakeupServiceStarted) {
                        voiceWakeupServiceController.startWakeup()
                    } else {
                        VoiceWakeupServiceCommandResult.Accepted
                    }
                when (result) {
                    VoiceWakeupServiceCommandResult.Accepted -> {
                        wakeupServiceStarted = true
                        micSessionCoordinator.noteWakeupStarted()
                        if (updateUserMessage) {
                            updateWakeupStatusMessage(wakeupStartedMessage(detectorStatus))
                        }
                    }
                    is VoiceWakeupServiceCommandResult.Failed -> {
                        wakeupServiceStarted = false
                        micSessionCoordinator.noteWakeupStopped()
                        if (updateUserMessage) {
                            updateWakeupStatusMessage("Wake-up preference saved. ${result.message}")
                        }
                    }
                }
            }
            is VoiceWakeupForegroundStartDecision.Blocked -> {
                if (wakeupServiceStarted) {
                    voiceWakeupServiceController.stopWakeup()
                    wakeupServiceStarted = false
                    micSessionCoordinator.noteWakeupStopped()
                }
                if (updateUserMessage) {
                    updateWakeupStatusMessage(wakeupBlockedMessage(decision.message, detectorStatus))
                }
            }
        }
    }

    private fun pauseWakeupForForegroundVoiceTurn(trace: VoiceTurnTrace? = null) {
        val result =
            micSessionCoordinator.beginPopupDictation(
                wakeupRequested = wakeupRequested.value,
                wakeupServiceStarted = wakeupServiceStarted,
                stopWakeup = voiceWakeupServiceController::stopWakeup,
                trace = trace,
            )
        wakeupServicePausedForVoiceTurn = result.wakeupPaused
        wakeupServiceStarted = result.wakeupServiceStarted
    }

    private fun resumeWakeupAfterForegroundVoiceTurn(trace: VoiceTurnTrace? = null) {
        val result =
            micSessionCoordinator.finishPopupDictation(
                resumeWakeup = wakeupServicePausedForVoiceTurn,
                wakeupRequested = wakeupRequested.value,
                startWakeup = ::startWakeupIfReady,
                trace = trace,
            )
        wakeupServicePausedForVoiceTurn = false
        wakeupServiceStarted = result.wakeupServiceStarted
    }

    private fun startWakeupIfReady(): VoiceWakeupServiceCommandResult {
        val decision =
            VoiceWakeupForegroundStartGuard.evaluate(
                status = wakeupDetectorStatus.value,
                hasRecordAudioPermission = voiceWakeupServiceController.hasRecordAudioPermission(),
            )
        return when (decision) {
            VoiceWakeupForegroundStartDecision.Ready ->
                if (wakeupServiceStarted) {
                    VoiceWakeupServiceCommandResult.Accepted
                } else {
                    voiceWakeupServiceController.startWakeup()
                }
            is VoiceWakeupForegroundStartDecision.Blocked ->
                VoiceWakeupServiceCommandResult.Failed(decision.message)
        }
    }

    private fun clearVoiceTrace(trace: VoiceTurnTrace?) {
        if (trace != null && activeVoiceTrace === trace) {
            activeVoiceTrace = null
        }
    }

    private fun updateWakeupStatusMessage(message: String) {
        transcriptState.update {
            it.copy(statusMessage = message)
        }
    }

    private fun VoicePerformanceMode.displayLabel(): String =
        when (this) {
            VoicePerformanceMode.AUTO -> "Auto"
            VoicePerformanceMode.FAST -> "Fast"
            VoicePerformanceMode.BALANCED -> "Balanced"
            VoicePerformanceMode.QUALITY -> "Quality"
        }

    companion object {
        fun factory(
            repository: LocalVoiceCatalogRepository,
            localVoiceStorage: LocalVoiceStorage? = null,
            localSpeechRecognizerProvider: () -> LocalSpeechRecognizer = { MissingLocalSpeechRecognizer() },
            voiceContactLookup: VoiceContactLookup = MissingVoiceContactLookup,
            voiceAssistantConversation: VoiceAssistantConversation = MissingVoiceAssistantConversation,
            localSpeechSynthesizerProvider: () -> LocalSpeechSynthesizer = { MissingLocalSpeechSynthesizer() },
            voiceDownloadManager: VoiceDownloadManager? = null,
            voiceOutputPreferences: VoiceOutputPreferences = InMemoryVoiceOutputPreferences(),
            voiceWakeupPreferences: VoiceWakeupPreferences = InMemoryVoiceWakeupPreferences(),
            voiceWakeupDetectorProvider: () -> VoiceWakeupDetector = { MissingVoiceWakeupDetector() },
            voiceWakeupServiceController: VoiceWakeupServiceController = MissingVoiceWakeupServiceController,
        ): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                override fun <T : ViewModel> create(modelClass: Class<T>): T {
                    if (modelClass.isAssignableFrom(VoiceAssistantViewModel::class.java)) {
                        @Suppress("UNCHECKED_CAST")
                        return VoiceAssistantViewModel(
                            voiceCatalogRepository = repository,
                            localVoiceStorage = localVoiceStorage,
                            localSpeechRecognizerProvider = localSpeechRecognizerProvider,
                            voiceContactLookup = voiceContactLookup,
                            voiceAssistantConversation = voiceAssistantConversation,
                            voiceDownloadManager = voiceDownloadManager,
                            voiceOutputPreferences = voiceOutputPreferences,
                            voiceWakeupPreferences = voiceWakeupPreferences,
                            voiceWakeupDetectorProvider = voiceWakeupDetectorProvider,
                            voiceWakeupServiceController = voiceWakeupServiceController,
                            localSpeechSynthesizerProvider = localSpeechSynthesizerProvider,
                            activateSpeechOnInit = false,
                            activateWakeupOnInit = false,
                        ) as T
                    }
                    throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
                }
            }

        private fun importedVoiceId(
            displayName: String,
            modelUri: String,
        ): String {
            val slug =
                displayName
                    .lowercase()
                    .replace(Regex("[^a-z0-9]+"), "-")
                    .trim('-')
                    .ifBlank { "voice" }
            val suffix = Integer.toHexString(modelUri.hashCode())
            return "imported-$slug-$suffix"
        }
    }

    private suspend fun handleRecognitionEvent(
        event: SpeechRecognitionEvent,
        turnId: Long,
    ) {
        val trace = activeVoiceTrace
        if (turnId != recognitionTurnId) {
            trace?.mark("ui_recognition_event_ignored", "reason=stale_turn")
            return
        }
        when (event) {
            is SpeechRecognitionEvent.PartialTranscript -> {
                trace?.markOnce("ui_first_partial")
                transcriptState.update {
                    it.copy(
                        partialTranscript = event.text,
                        statusMessage = null,
                    )
                }
            }
            is SpeechRecognitionEvent.FinalTranscript -> {
                invalidateRecognitionTurn()
                recognitionJob = null
                stopLocalSpeechRecognizerIfInitialized()
                sessionState.value = VoiceAssistantSessionState.Idle
                val transcript = event.text.trim()
                trace?.mark("ui_final_transcript", "blank=${transcript.isBlank()}")
                if (transcript.isBlank()) {
                    transcriptState.value =
                        VoiceAssistantTranscriptState(
                            lastTranscript = transcript,
                            statusMessage = "No speech was recognized.",
                        )
                    appendErrorCard("No speech was recognized.", canRetry = false)
                    resumeWakeupAfterForegroundVoiceTurn(trace)
                    trace?.complete("blank_transcript")
                    clearVoiceTrace(trace)
                    return
                }
                appendUserCard(transcript)
                if (tryHandleVoiceFastPath(transcript, trace)) {
                    return
                }
                startVoiceConversation(transcript, trace)
            }
            is SpeechRecognitionEvent.Error -> {
                invalidateRecognitionTurn()
                recognitionJob = null
                sessionState.value =
                    if (voiceCatalogRepository.selectedVoice() == null) {
                        VoiceAssistantSessionState.MissingVoice
                    } else {
                        VoiceAssistantSessionState.Idle
                    }
                transcriptState.update {
                    it.copy(
                        partialTranscript = "",
                        statusMessage = event.message,
                    )
                }
                appendErrorCard(event.message, canRetry = false)
                trace?.mark("ui_recognition_error")
                resumeWakeupAfterForegroundVoiceTurn(trace)
                trace?.complete("recognition_error")
                clearVoiceTrace(trace)
            }
        }
    }

    private fun nextRecognitionTurnId(): Long {
        recognitionTurnId += 1
        return recognitionTurnId
    }

    private fun invalidateRecognitionTurn() {
        recognitionTurnId += 1
    }

    private fun tryHandleVoiceFastPath(
        transcript: String,
        trace: VoiceTurnTrace?,
    ): Boolean =
        when (val result = VoiceFastPathRouter.route(transcript, lastSpokenResponse)) {
            null -> false
            is VoiceFastPathResult.Command -> {
                startFastPathCommand(result.command, result.statusMessage, trace)
                true
            }
            is VoiceFastPathResult.ContactCall -> {
                startContactCallFastPath(
                    transcript = result.transcript,
                    contactName = result.contactName,
                    trace = trace,
                )
                true
            }
            is VoiceFastPathResult.SpokenResponse -> {
                startFastPathSpokenResponse(
                    transcript = transcript,
                    response = result.message,
                    trace = trace,
                )
                true
            }
        }

    private fun startFastPathCommand(
        command: VoiceAssistantCommand,
        statusMessage: String,
        trace: VoiceTurnTrace?,
    ) {
        cancelActiveConversation()
        trace?.mark("fast_path_command", command.fastPathKind())
        popupVisible.value = true
        sessionState.value =
            if (voiceCatalogRepository.selectedVoice() == null) {
                VoiceAssistantSessionState.MissingVoice
            } else {
                VoiceAssistantSessionState.Processing
            }
        transcriptState.value =
            VoiceAssistantTranscriptState(
                lastTranscript = command.text,
                statusMessage = statusMessage,
            )
        appendActionCard(message = statusMessage, canCancel = command.localDeviceAction != null || command.uiAgentGoal != null)
        if (!_voiceCommands.tryEmit(command)) {
            transcriptState.value =
                VoiceAssistantTranscriptState(
                    lastTranscript = command.text,
                    statusMessage = "Voice command could not be queued.",
                )
            appendErrorCard("Voice command could not be queued.", canRetry = true)
            sessionState.value = voiceAwareIdleState()
            resumeWakeupAfterForegroundVoiceTurn(trace)
            trace?.complete("fast_path_command_queue_failed")
            clearVoiceTrace(trace)
        }
    }

    private fun startContactCallFastPath(
        transcript: String,
        contactName: String,
        trace: VoiceTurnTrace?,
    ) {
        cancelActiveConversation()
        trace?.mark("fast_path_contact_call")
        popupVisible.value = true
        sessionState.value =
            if (voiceCatalogRepository.selectedVoice() == null) {
                VoiceAssistantSessionState.MissingVoice
            } else {
                VoiceAssistantSessionState.Processing
            }
        transcriptState.value =
            VoiceAssistantTranscriptState(
                lastTranscript = transcript,
                statusMessage = "Looking up $contactName.",
            )
        appendActionCard(message = "Looking up $contactName.", canCancel = false)
        viewModelScope.launch(Dispatchers.IO) {
            resolveContactCall(transcript, contactName, trace)
        }
    }

    private fun startFastPathSpokenResponse(
        transcript: String,
        response: String,
        trace: VoiceTurnTrace?,
    ) {
        cancelActiveConversation()
        trace?.mark("fast_path_spoken_response")
        popupVisible.value = true
        var responseJob: Job? = null
        responseJob =
            viewModelScope.launch {
                try {
                    sessionState.value = VoiceAssistantSessionState.Processing
                    transcriptState.value =
                        VoiceAssistantTranscriptState(
                            lastTranscript = transcript,
                            statusMessage = response,
                        )
                    val statusMessage = speakVoiceResponse(response, trace = trace)
                    lastSpokenResponse = response
                    transcriptState.value =
                        VoiceAssistantTranscriptState(
                            lastTranscript = transcript,
                            statusMessage = statusMessage,
                        )
                    appendTextAnswerCard(statusMessage)
                    sessionState.value = voiceAwareIdleState()
                    resumeWakeupAfterForegroundVoiceTurn(trace)
                    trace?.complete("fast_path_spoken_response")
                    clearVoiceTrace(trace)
                } finally {
                    if (activeConversationJob === responseJob) {
                        activeConversationJob = null
                    }
                }
            }
        activeConversationJob = responseJob
    }

    private fun startVoiceConversation(
        transcript: String,
        trace: VoiceTurnTrace?,
    ) {
        cancelActiveConversation()
        var conversationJob: Job? = null
        conversationJob =
            viewModelScope.launch {
                try {
                    handleVoiceConversation(transcript, trace)
                } finally {
                    if (activeConversationJob === conversationJob) {
                        activeConversationJob = null
                    }
                }
            }
        activeConversationJob = conversationJob
    }

    private fun cancelActiveConversation() {
        voiceAssistantConversation.cancel()
        activeConversationJob?.cancel(CancellationException("Voice request cancelled."))
        activeConversationJob = null
        activeConversationKind = ActiveConversationKind.None
    }

    private suspend fun handleCommandLaunchResult(
        command: VoiceAssistantCommand,
        result: VoiceAssistantCommandLaunchResult,
        onSuccessWithoutFinalMessage: () -> Unit,
    ) {
        val trace = activeVoiceTrace
        trace?.mark(
            "command_launch_result",
            "success=${result.success} shouldSpeak=${result.shouldSpeak}",
        )
        val finalMessage = result.finalMessage?.trim().orEmpty()
        if (finalMessage.isBlank()) {
            if (result.success) {
                popupVisible.value = false
                sessionState.value = voiceAwareIdleState()
                transcriptState.update {
                    it.copy(
                        partialTranscript = "",
                        lastTranscript = command.text.ifBlank { it.lastTranscript },
                    )
                }
                onSuccessWithoutFinalMessage()
                resumeWakeupAfterForegroundVoiceTurn(trace)
                trace?.complete("command_completed")
                clearVoiceTrace(trace)
            } else {
                commandLaunchFailed(command, result.failureMessage)
                resumeWakeupAfterForegroundVoiceTurn(trace)
                trace?.complete("command_failed")
                clearVoiceTrace(trace)
            }
            return
        }

        popupVisible.value = true
        transcriptState.update {
            it.copy(
                partialTranscript = "",
                lastTranscript = command.text.ifBlank { it.lastTranscript },
                statusMessage = finalMessage,
            )
        }
        if (result.success) {
            appendActionCard(message = finalMessage, canCancel = false)
        } else {
            appendErrorCard(message = finalMessage, canRetry = true)
        }
        lastSpokenResponse = finalMessage

        if (!result.shouldSpeak) {
            sessionState.value = voiceAwareIdleState()
            resumeWakeupAfterForegroundVoiceTurn(trace)
            trace?.complete(if (result.success) "command_completed" else "command_failed")
            clearVoiceTrace(trace)
            return
        }

        val selectedVoice = voiceCatalogRepository.selectedVoice()
        if (selectedVoice == null) {
            sessionState.value = VoiceAssistantSessionState.MissingVoice
            resumeWakeupAfterForegroundVoiceTurn(trace)
            trace?.complete("command_missing_voice")
            clearVoiceTrace(trace)
            return
        }

        sessionState.value = VoiceAssistantSessionState.Speaking
        val displayMessage =
            when (
                val speechResult =
                    localSpeechSynthesizer.speak(
                        finalMessage,
                        selectedVoice,
                        trace ?: VoiceTurnTrace.noop(),
                    )
            ) {
                SpeechSynthesisResult.Completed -> finalMessage
                SpeechSynthesisResult.Cancelled -> "Speech playback was cancelled."
                is SpeechSynthesisResult.Failed ->
                    "$finalMessage Local speech playback failed: ${speechResult.message}"
            }
        transcriptState.update {
            it.copy(
                partialTranscript = "",
                statusMessage = displayMessage,
            )
        }
        if (displayMessage != finalMessage) {
            appendErrorCard(message = displayMessage, canRetry = false)
        }
        sessionState.value = voiceAwareIdleState()
        resumeWakeupAfterForegroundVoiceTurn(trace)
        trace?.complete(if (result.success) "command_completed" else "command_failed")
        clearVoiceTrace(trace)
    }

    private suspend fun handleVoiceConversation(
        transcript: String,
        trace: VoiceTurnTrace?,
    ) {
        if (transcript.isBlank()) {
            transcriptState.value =
                VoiceAssistantTranscriptState(
                    lastTranscript = transcript,
                    statusMessage = "No speech was recognized.",
                )
            appendErrorCard("No speech was recognized.", canRetry = false)
            sessionState.value = VoiceAssistantSessionState.Idle
            resumeWakeupAfterForegroundVoiceTurn(trace)
            trace?.complete("blank_transcript")
            clearVoiceTrace(trace)
            return
        }

        trace?.mark("voice_processing_started")
        sessionState.value = VoiceAssistantSessionState.Processing
        transcriptState.value =
            VoiceAssistantTranscriptState(
                lastTranscript = transcript,
                statusMessage = "ZeroClaw is handling your voice request.",
            )
        coroutineScope {
            val selectedVoice = voiceCatalogRepository.selectedVoice()
            val sentenceQueue =
                if (selectedVoice == null) {
                    null
                } else {
                    Channel<String>(Channel.UNLIMITED)
                }
            val streamedSpeech =
                if (selectedVoice == null || sentenceQueue == null) {
                    null
                } else {
                    startStreamedSpeech(this, selectedVoice, sentenceQueue, trace)
                }
            try {
                val statusMessage =
                    when (
                        val result =
                            voiceAssistantConversation.submitTranscript(
                                transcript = transcript,
                                onNextSpokenSentence = { sentence ->
                                    trace?.markOnce("ui_first_complete_spoken_sentence")
                                    sentenceQueue?.trySend(sentence)
                                },
                                trace = trace ?: VoiceTurnTrace.noop(),
                            )
                    ) {
                        VoiceAssistantConversationResult.IgnoredBlank -> {
                            trace?.complete("ignored_blank")
                            "No speech was recognized.".also { message ->
                                appendErrorCard(message, canRetry = false)
                            }
                        }
                        is VoiceAssistantConversationResult.Failed -> {
                            sentenceQueue?.close()
                            streamedSpeech?.result?.cancel()
                            trace?.complete("conversation_failed")
                            result.message.also { message ->
                                appendErrorCard(message, canRetry = true)
                            }
                        }
                        is VoiceAssistantConversationResult.Success -> {
                            sentenceQueue?.close()
                            val spokenStatus =
                                speakVoiceResponse(result.spokenResponse, streamedSpeech, trace)
                            lastSpokenResponse = result.spokenResponse
                            trace?.complete("success")
                            spokenStatus.also { message ->
                                appendTextAnswerCard(message)
                            }
                        }
                    }

                transcriptState.value =
                    VoiceAssistantTranscriptState(
                        lastTranscript = transcript,
                        statusMessage = statusMessage,
                    )
            } catch (error: CancellationException) {
                sentenceQueue?.close()
                streamedSpeech?.result?.cancel()
                stopLocalSpeechSynthesizerIfInitialized()
                if (popupVisible.value) {
                    transcriptState.value =
                        VoiceAssistantTranscriptState(
                            lastTranscript = transcript,
                            statusMessage = "Voice request cancelled.",
                        )
                }
                appendActionCard(message = "Voice request cancelled.", canCancel = false)
                trace?.complete("cancelled")
                throw error
            } finally {
                sessionState.value =
                    if (voiceCatalogRepository.selectedVoice() == null) {
                        VoiceAssistantSessionState.MissingVoice
                    } else {
                        VoiceAssistantSessionState.Idle
                    }
                resumeWakeupAfterForegroundVoiceTurn(trace)
                clearVoiceTrace(trace)
            }
        }
    }

    private fun voiceAwareIdleState(): VoiceAssistantSessionState =
        if (voiceCatalogRepository.selectedVoice() == null) {
            VoiceAssistantSessionState.MissingVoice
        } else {
            VoiceAssistantSessionState.Idle
        }

    private fun startStreamedSpeech(
        scope: CoroutineScope,
        voice: VoiceModel,
        sentenceQueue: Channel<String>,
        trace: VoiceTurnTrace?,
    ): StreamedSpeechPlayback =
        StreamedSpeechPlayback(
            result =
                scope.async {
                    val spokenText = StringBuilder()
                    sessionState.value = VoiceAssistantSessionState.Speaking
                    for (sentence in sentenceQueue) {
                        val spokenSentence = sentence.trim()
                        if (spokenSentence.isBlank()) {
                            continue
                        }
                        trace?.markOnce("first_tts_speak_started", "voice=${voice.id}")
                        when (
                            val result =
                                localSpeechSynthesizer.speak(
                                    spokenSentence,
                                    voice,
                                    trace ?: VoiceTurnTrace.noop(),
                                )
                        ) {
                            SpeechSynthesisResult.Completed -> {
                                trace?.markOnce("first_tts_speak_completed")
                                if (spokenText.isNotEmpty()) {
                                    spokenText.append(' ')
                                }
                                spokenText.append(spokenSentence)
                            }
                            SpeechSynthesisResult.Cancelled -> {
                                trace?.mark("tts_speak_cancelled")
                                return@async StreamedSpeechResult(
                                    spokenText = spokenText.toString(),
                                    result = SpeechSynthesisResult.Cancelled,
                                )
                            }
                            is SpeechSynthesisResult.Failed -> {
                                trace?.mark("tts_speak_failed", result.message)
                                return@async StreamedSpeechResult(
                                    spokenText = spokenText.toString(),
                                    result = result,
                                )
                            }
                        }
                    }
                    StreamedSpeechResult(
                        spokenText = spokenText.toString(),
                        result = SpeechSynthesisResult.Completed,
                    )
                },
        )

    private suspend fun speakVoiceResponse(
        response: String,
        streamedSpeech: StreamedSpeechPlayback? = null,
        trace: VoiceTurnTrace? = null,
    ): String {
        val selectedVoice = voiceCatalogRepository.selectedVoice()
        if (selectedVoice == null) {
            return response
        }

        transcriptState.update {
            it.copy(
                partialTranscript = "",
                statusMessage = response,
            )
        }
        sessionState.value = VoiceAssistantSessionState.Speaking
        val streamedSpeechResult = streamedSpeech?.result?.await()
        if (streamedSpeechResult?.result == SpeechSynthesisResult.Cancelled) {
            return "Speech playback was cancelled."
        }

        val textToSpeak =
            response.remainingAfter(streamedSpeechResult?.spokenText.orEmpty())
        if (textToSpeak.isBlank()) {
            val failure = streamedSpeechResult?.result as? SpeechSynthesisResult.Failed
            return if (failure == null) {
                response
            } else {
                "$response Local speech playback failed: ${failure.message}"
            }
        }

        trace?.markOnce("first_tts_speak_started", "voice=${selectedVoice.id}")
        return when (
            val result =
                localSpeechSynthesizer.speak(
                    textToSpeak,
                    selectedVoice,
                    trace ?: VoiceTurnTrace.noop(),
                )
        ) {
            SpeechSynthesisResult.Completed -> {
                trace?.markOnce("first_tts_speak_completed")
                response
            }
            SpeechSynthesisResult.Cancelled -> "Speech playback was cancelled."
            is SpeechSynthesisResult.Failed -> {
                trace?.mark("tts_speak_failed", result.message)
                "$response Local speech playback failed: ${result.message}"
            }
        }
    }

    private fun prewarmSelectedVoice(trace: VoiceTurnTrace? = null) {
        val selectedVoice = voiceCatalogRepository.selectedVoice() ?: return
        // Imported (Piper/ONNX) voices must also be pre-warmed: the ONNX model
        // cold-load can take several seconds on low-end devices. Skipping it
        // forces the load to happen inside the first synthesis timeout window,
        // which causes "Custom voice synthesis timed out before audio was ready."
        // Previously this early-returned with reason=custom_voice_lazy_load.
        if (prewarmedVoiceId == selectedVoice.id) {
            trace?.mark("tts_prepare_skipped", "voice=${selectedVoice.id} reason=already_warm")
            return
        }
        if (voicePrewarmJob?.isActive == true && prewarmingVoiceId == selectedVoice.id) {
            trace?.mark("tts_prepare_skipped", "voice=${selectedVoice.id} reason=already_preparing")
            return
        }

        voicePrewarmJob?.cancel()
        prewarmingVoiceId = selectedVoice.id
        trace?.mark("tts_prepare_started", "voice=${selectedVoice.id}")
        voicePrewarmJob =
            viewModelScope.launch {
                when (
                    val result =
                        localSpeechSynthesizer.prepare(
                            selectedVoice,
                            trace ?: VoiceTurnTrace.noop(),
                        )
                ) {
                    SpeechSynthesisResult.Completed -> {
                        trace?.mark("tts_prepare_completed", "voice=${selectedVoice.id}")
                        prewarmedVoiceId = selectedVoice.id
                    }
                    SpeechSynthesisResult.Cancelled ->
                        trace?.mark("tts_prepare_cancelled", "voice=${selectedVoice.id}")
                    is SpeechSynthesisResult.Failed ->
                        trace?.mark("tts_prepare_failed", result.message)
                }
            }
    }

    override fun onCleared() {
        stopListening(resumeWakeup = false)
        cancelActiveConversation()
        stopLocalSpeechSynthesizerIfInitialized()
        voicePrewarmJob?.cancel()
        stopVoicePreviewIfInitialized()
        super.onCleared()
    }

    private suspend fun resolveContactCall(
        transcript: String,
        contactName: String,
        trace: VoiceTurnTrace? = null,
    ) {
        when (val result = voiceContactLookup.findPhoneDialUri(contactName)) {
            is VoiceContactLookupResult.Found -> {
                pendingContactCall = null
                trace?.mark("fast_path_contact_resolved")
                transcriptState.value =
                    VoiceAssistantTranscriptState(
                        lastTranscript = transcript,
                        statusMessage = "Opening phone dialer for ${result.displayName}.",
                    )
                appendActionCard(
                    message = "Opening phone dialer for ${result.displayName}.",
                    canCancel = false,
                )
                val command =
                    VoiceAssistantCommand(
                        text = transcript,
                        phoneDialUri = result.phoneDialUri,
                    )
                if (!_voiceCommands.tryEmit(command)) {
                    sessionState.value = voiceAwareIdleState()
                    transcriptState.value =
                        VoiceAssistantTranscriptState(
                            lastTranscript = transcript,
                            statusMessage = "Voice command could not be queued.",
                        )
                    appendErrorCard("Voice command could not be queued.", canRetry = true)
                    resumeWakeupAfterForegroundVoiceTurn(trace)
                    trace?.complete("fast_path_command_queue_failed")
                    clearVoiceTrace(trace)
                }
            }
            VoiceContactLookupResult.PermissionRequired -> {
                pendingContactCall =
                    PendingContactCall(
                        transcript = transcript,
                        contactName = contactName,
                    )
                trace?.mark("contact_permission_required")
                transcriptState.value =
                    VoiceAssistantTranscriptState(
                        lastTranscript = transcript,
                        statusMessage = "Contacts permission is required to call $contactName.",
                    )
                appendErrorCard(
                    message = "Contacts permission is required to call $contactName.",
                    canRetry = true,
                )
                sessionState.value = voiceAwareIdleState()
                resumeWakeupAfterForegroundVoiceTurn(trace)
                trace?.complete("contact_permission_required")
                clearVoiceTrace(trace)
                _contactPermissionRequests.tryEmit(Unit)
            }
            is VoiceContactLookupResult.NotFound -> {
                pendingContactCall = null
                trace?.mark("contact_not_found")
                transcriptState.value =
                    VoiceAssistantTranscriptState(
                        lastTranscript = transcript,
                        statusMessage = "No local phone contact found for ${result.contactName}.",
                    )
                appendErrorCard(
                    message = "No local phone contact found for ${result.contactName}.",
                    canRetry = true,
                )
                sessionState.value = voiceAwareIdleState()
                resumeWakeupAfterForegroundVoiceTurn(trace)
                trace?.complete("contact_not_found")
                clearVoiceTrace(trace)
            }
            is VoiceContactLookupResult.Failed -> {
                pendingContactCall = null
                trace?.mark("contact_lookup_failed")
                transcriptState.value =
                    VoiceAssistantTranscriptState(
                        lastTranscript = transcript,
                        statusMessage = result.message,
                    )
                appendErrorCard(message = result.message, canRetry = true)
                sessionState.value = voiceAwareIdleState()
                resumeWakeupAfterForegroundVoiceTurn(trace)
                trace?.complete("contact_lookup_failed")
                clearVoiceTrace(trace)
            }
        }
    }
}

private fun VoiceAssistantCommand.fastPathKind(): String =
    when {
        phoneDialUri != null -> "phone_dial"
        deviceAction != null -> "device_action"
        localDeviceAction != null -> "local_device_action"
        uiAgentGoal != null -> "ui_agent_goal"
        else -> "command"
    }

private data class ImportedVoicePackage(
    val displayName: String,
    val modelUri: String,
    val sizeBytes: Long,
    val sampleText: String,
    val runtimeType: String?,
)

private data class VoiceAssistantTranscriptState(
    val partialTranscript: String = "",
    val lastTranscript: String = "",
    val statusMessage: String? = null,
)

private data class PendingContactCall(
    val transcript: String,
    val contactName: String,
)

private data class WakeupReadiness(
    val requested: Boolean,
    val available: Boolean,
    val statusMessage: String,
)

private data class VoiceSelectionState(
    val selectedVoice: VoiceModel?,
)

private data class StreamedSpeechPlayback(
    val result: Deferred<StreamedSpeechResult>,
)

private data class StreamedSpeechResult(
    val spokenText: String,
    val result: SpeechSynthesisResult,
)

private enum class ActiveConversationKind {
    None,
    Command,
}

private const val WAKEUP_UNAVAILABLE_MESSAGE =
    "Background wake word is unavailable until a local detector is bundled."
private const val WAKEUP_REQUESTED_UNAVAILABLE_MESSAGE =
    "Wake-up preference saved. Background wake word is unavailable until a local detector is bundled."
private const val WAKEUP_DISABLED_MESSAGE = "Wake-up request disabled."
private const val WAKEUP_STARTED_PREFIX = "Wake-up service requested."
private const val VOICE_REQUEST_CANCELLED_MESSAGE = "Voice request cancelled."
private const val SPEECH_RECOGNITION_NOT_STARTED_MESSAGE =
    "Local speech recognition starts when the assistant opens."

private fun String.remainingAfter(spokenPrefix: String): String {
    val normalizedPrefix = spokenPrefix.removeSuffix("...").trim()
    if (normalizedPrefix.isBlank()) {
        return trim()
    }
    return if (startsWith(normalizedPrefix, ignoreCase = true)) {
        drop(normalizedPrefix.length).trimStart()
    } else {
        trim()
    }
}

private fun wakeupStatusMessage(
    requested: Boolean,
    detectorStatus: VoiceWakeupDetectorStatus,
): String {
    if (detectorStatus.available && detectorStatus.foregroundServiceReady) {
        return detectorStatus.message
    }
    return if (requested) {
        if (detectorStatus.message == WAKEUP_UNAVAILABLE_MESSAGE) {
            WAKEUP_REQUESTED_UNAVAILABLE_MESSAGE
        } else {
            "Wake-up preference saved. ${detectorStatus.message}"
        }
    } else {
        detectorStatus.message
    }
}

private fun wakeupStartedMessage(detectorStatus: VoiceWakeupDetectorStatus): String =
    "$WAKEUP_STARTED_PREFIX ${detectorStatus.message}"

private fun wakeupBlockedMessage(
    blockedMessage: String,
    detectorStatus: VoiceWakeupDetectorStatus,
): String =
    if (blockedMessage == detectorStatus.message) {
        wakeupStatusMessage(requested = true, detectorStatus = detectorStatus)
    } else {
        "Wake-up preference saved. $blockedMessage"
    }

private val LocalSpeechEngineStatus.canStartListening: Boolean
    get() =
        when (this) {
            LocalSpeechEngineStatus.Ready,
            LocalSpeechEngineStatus.PermissionRequired -> true
            LocalSpeechEngineStatus.Initializing,
            LocalSpeechEngineStatus.MissingModel,
            is LocalSpeechEngineStatus.Unavailable -> false
        }

private fun LocalSpeechEngineStatus.assistantStatusMessage(): String =
    when (this) {
        LocalSpeechEngineStatus.Initializing ->
            "Local speech recognition is still starting."
        LocalSpeechEngineStatus.Ready ->
            "Local speech recognition is ready."
        LocalSpeechEngineStatus.MissingModel ->
            "Install an offline English speech recognition service on this phone."
        LocalSpeechEngineStatus.PermissionRequired ->
            "Microphone permission is required for local voice mode."
        is LocalSpeechEngineStatus.Unavailable -> reason
    }
