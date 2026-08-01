/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.agents

import android.app.Application
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zeroclaw.android.ZeroClawApplication
import com.zeroclaw.android.data.repository.AgentRepository
import com.zeroclaw.android.model.Agent
import com.zeroclaw.android.model.AgentChatMessage
import com.zeroclaw.android.model.AgentLiveState
import com.zeroclaw.android.model.OnDeviceCaptureRequest
import com.zeroclaw.android.model.OnDeviceTool
import com.zeroclaw.android.model.AgentMessageType
import com.zeroclaw.android.model.AgentRole
import com.zeroclaw.android.model.AgentStatus
import com.zeroclaw.android.model.ApprovalState
import com.zeroclaw.android.model.ProcessedImage
import com.zeroclaw.android.model.ThinkingLevel
import com.zeroclaw.android.service.AgentConversationEngine
import com.zeroclaw.android.service.AgentGroupChatSink
import com.zeroclaw.android.service.AgentStatusRepository
import com.zeroclaw.android.service.ConversationSessionListener
import com.zeroclaw.android.service.DaemonServiceBridge
import com.zeroclaw.android.service.ConversationSessionManager
import com.zeroclaw.android.service.RepositoryChannelStatusBridge
import com.zeroclaw.android.service.ThinkingLevelManager
import com.zeroclaw.android.util.ErrorSanitizer
import com.zeroclaw.android.util.ImageProcessor
import com.zeroclaw.android.ui.screen.terminal.StreamingPhase
import com.zeroclaw.android.ui.screen.terminal.StreamingState
import com.zeroclaw.android.ui.screen.terminal.ToolProgress
import com.zeroclaw.android.ui.screen.terminal.ToolResultEntry
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

private const val ACTIVE_FAMILY_SENTINEL = "Active"
private const val DEFAULT_LOCAL_IMAGE_PROMPT = "Describe this image."
private const val LOCAL_DEVICE_SENDER_ID = "local-device"

/**
 * State for the agent group chat screen.
 */
data class AgentGroupChatState(
    val messages: List<AgentChatMessage> = emptyList(),
    val typingAgentIds: Set<String> = emptySet(),
    val isMasterPresent: Boolean = false,
    val agents: Map<String, Agent> = emptyMap(),
    val activeAgents: List<Agent> = emptyList(),
    val liveStates: Map<String, AgentLiveState> = emptyMap(),
    val masterStreamingState: StreamingState = StreamingState.idle(),
) {
    val masterAgent: Agent?
        get() = activeAgents.find { it.isMaster || it.role == AgentRole.MASTER }

    val subAgents: List<Agent>
        get() = activeAgents.filterNot { it.isMaster || it.role == AgentRole.MASTER }

    val pendingApprovals: List<AgentChatMessage>
        get() = messages.filter { it.requiresApproval && it.approvalState == ApprovalState.PENDING }

    val hasPendingApprovals: Boolean
        get() = pendingApprovals.isNotEmpty()

    fun liveStateFor(agentId: String): AgentLiveState? = liveStates[agentId]
}

/**
 * ViewModel for the agent group chat screen and daemon chat-event sink.
 */
class AgentGroupChatViewModel(
    application: Application,
) : AndroidViewModel(application), AgentGroupChatSink {
    private val app = application as ZeroClawApplication
    private val agentRepository: AgentRepository = app.agentRepository
    private val daemonBridge: DaemonServiceBridge = app.daemonBridge
    private val agentStatusRepository: AgentStatusRepository = app.agentStatusRepository
    private val conversationSessionManager: ConversationSessionManager = app.conversationSessionManager
    private val conversationEngine =
        AgentConversationEngine(
            apiKeyRepository = app.apiKeyRepository,
            toolCatalogBridge = app.toolsBridge,
            channelStatusBridge = RepositoryChannelStatusBridge(app.channelConfigRepository),
            onDeviceEngine = app.liteRtInferenceEngine,
        )
    private val delegationGate =
        MasterDelegationGate(
            emitMessage = ::addMessage,
            updateMessage = ::updateMessage,
        )

    private var messageObservationJob: Job? = null
    private var lastRequestedFamilyId: String? = null

    private val _familyId = MutableStateFlow("")
    val familyId: StateFlow<String> = _familyId.asStateFlow()

    private val _messages = MutableStateFlow<List<AgentChatMessage>>(emptyList())
    val messages: StateFlow<List<AgentChatMessage>> = _messages.asStateFlow()

    private val _typingAgentIds = MutableStateFlow<Set<String>>(emptySet())
    val typingAgents: StateFlow<Set<String>> = _typingAgentIds.asStateFlow()

    // Backward-compatible alias for existing UI code.
    val typingAgentIds: StateFlow<Set<String>> = typingAgents

    private val _userInputText = MutableStateFlow("")
    val userInputText: StateFlow<String> = _userInputText.asStateFlow()

    private val _selectedMentionTarget = MutableStateFlow<Agent?>(null)
    val selectedMentionTarget: StateFlow<Agent?> = _selectedMentionTarget.asStateFlow()

    private val pendingImagesState = MutableStateFlow<List<ProcessedImage>>(emptyList())
    val pendingImages: StateFlow<List<ProcessedImage>> = pendingImagesState.asStateFlow()

    private val processingImagesState = MutableStateFlow(false)
    val isProcessingImages: StateFlow<Boolean> = processingImagesState.asStateFlow()
    private val _cameraCaptureRequest = MutableStateFlow<OnDeviceCaptureRequest?>(null)
    val cameraCaptureRequest: StateFlow<OnDeviceCaptureRequest?> = _cameraCaptureRequest.asStateFlow()
    private val _screenCaptureRequest = MutableStateFlow<OnDeviceCaptureRequest?>(null)
    val screenCaptureRequest: StateFlow<OnDeviceCaptureRequest?> = _screenCaptureRequest.asStateFlow()
    private var pendingCameraPrompt: String = ""
    private var pendingScreenPrompt: String = ""

    val activeAgents: StateFlow<List<Agent>> =
        agentRepository.activeAgentsByPriority.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
            initialValue = emptyList(),
        )

    val liveStates: StateFlow<Map<String, AgentLiveState>> = daemonBridge.agentLiveStates

    val pendingApproval: StateFlow<AgentChatMessage?> =
        messages
            .map { currentMessages ->
                currentMessages.lastOrNull { it.requiresApproval && it.approvalState == ApprovalState.PENDING }
            }.stateIn(
                scope = viewModelScope,
                started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
                initialValue = null,
            )

    private val _masterStreamingState = MutableStateFlow(StreamingState.idle())
    private val toolCallCounter = java.util.concurrent.atomic.AtomicLong(0L)

    val chatState: StateFlow<AgentGroupChatState> = combine(
        messages,
        typingAgents,
        agentRepository.agents,
        activeAgents,
        liveStates,
        _masterStreamingState,
    ) { args ->
        @Suppress("UNCHECKED_CAST")
        val currentMessages = args[0] as List<AgentChatMessage>
        @Suppress("UNCHECKED_CAST")
        val currentTypingAgents = args[1] as Set<String>
        @Suppress("UNCHECKED_CAST")
        val allAgents = args[2] as List<Agent>
        @Suppress("UNCHECKED_CAST")
        val currentActiveAgents = args[3] as List<Agent>
        @Suppress("UNCHECKED_CAST")
        val currentLiveStates = args[4] as Map<String, AgentLiveState>
        val currentMasterState = args[5] as StreamingState

        AgentGroupChatState(
            messages = currentMessages,
            typingAgentIds = currentTypingAgents,
            isMasterPresent = currentActiveAgents.any { it.isMaster || it.role == AgentRole.MASTER },
            agents = allAgents.associateBy { it.id },
            activeAgents = currentActiveAgents,
            liveStates = currentLiveStates,
            masterStreamingState = currentMasterState,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = AgentGroupChatState(),
    )

    init {
        daemonBridge.agentGroupChatSink = this

        viewModelScope.launch {
            activeAgents.collect { agents ->
                agents.forEach(conversationEngine::initAgent)
                val persistedMessages = _messages.value
                if (_familyId.value.isNotBlank()) {
                    conversationSessionManager.rebuildConversationEngineHistories(
                        conversationEngine = conversationEngine,
                        agents = agents,
                        messages = persistedMessages,
                    )
                }
            }
        }
    }

    fun setFamilyId(familyId: String) {
        if (lastRequestedFamilyId == familyId && _familyId.value.isNotBlank()) return
        lastRequestedFamilyId = familyId
        viewModelScope.launch {
            val resolvedFamilyId = conversationSessionManager.resolveRequestedFamilyId(familyId)
            bindFamily(resolvedFamilyId)
        }
    }

    fun addMessage(message: AgentChatMessage) {
        viewModelScope.launch {
            addMessageInternal(message)
        }
    }

    fun addMessages(messages: List<AgentChatMessage>) {
        viewModelScope.launch {
            addMessagesInternal(messages)
        }
    }

    fun updateMessage(messageId: String, update: (AgentChatMessage) -> AgentChatMessage) {
        viewModelScope.launch {
            updateMessageInternal(messageId, update)
        }
    }

    fun clearMessages() {
        val currentFamilyId = _familyId.value
        if (currentFamilyId.isBlank()) return
        viewModelScope.launch {
            conversationSessionManager.clearMessages(currentFamilyId)
        }
    }

    suspend fun saveCurrentSession(reason: String) {
        if (_familyId.value.isBlank()) return
        conversationSessionManager.finalizeActiveConversation(reason = reason)
    }

    fun updateUserInput(text: String) {
        _userInputText.value = text

        val selectedTarget = _selectedMentionTarget.value ?: return
        if (!matchesMentionToken(text, selectedTarget.name)) {
            _selectedMentionTarget.value =
                resolveMentionTarget(
                    text = text,
                    selectedTarget = null,
                    agents = activeAgents.value,
                ).agent
        }
    }

    fun selectMentionTarget(agent: Agent?) {
        _selectedMentionTarget.value = agent
    }

    /**
     * Attach images from camera/gallery for the next group chat message.
     *
     * @param uris List of image URIs from the photo picker.
     */
    fun attachImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        viewModelScope.launch {
            processingImagesState.value = true
            try {
                val contentResolver = app.contentResolver
                val processed = ImageProcessor.process(contentResolver, uris)
                val current = pendingImagesState.value
                pendingImagesState.value = (current + processed).take(5)
            } finally {
                processingImagesState.value = false
            }
        }
    }

    /**
     * Remove a pending image at the given index.
     *
     * @param index Zero-based index into the pending images list.
     */
    fun removeImage(index: Int) {
        val current = pendingImagesState.value
        if (index in current.indices) {
            pendingImagesState.value = current.toMutableList().apply { removeAt(index) }
        }
    }

    fun performOnDeviceTool(tool: OnDeviceTool) {
        val prompt = _userInputText.value.trim()
        when (tool) {
            OnDeviceTool.CAMERA_CAPTURE -> {
                pendingCameraPrompt = prompt
                _cameraCaptureRequest.value = OnDeviceCaptureRequest(prompt = prompt)
            }

            OnDeviceTool.SCREEN_CAPTURE -> {
                pendingScreenPrompt = prompt
                _screenCaptureRequest.value = OnDeviceCaptureRequest(prompt = prompt)
            }

            else ->
                viewModelScope.launch {
                    executeOnDeviceTool(
                        tool = tool,
                        prompt = prompt,
                        images = pendingImagesState.value,
                    )
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
            val captureResult = app.screenCaptureBridge.captureScreen(app)
            captureResult.fold(
                onSuccess = { captured ->
                    executeOnDeviceTool(
                        tool = OnDeviceTool.DESCRIBE_IMAGE,
                        prompt = pendingScreenPrompt,
                        images = listOf(captured),
                    )
                },
                onFailure = { error ->
                    addMessageInternal(
                        AgentChatMessage.systemEvent(
                            "Screen capture failed: ${error.message ?: "Unknown error"}",
                        ),
                    )
                },
            )
            pendingScreenPrompt = ""
        }
    }

    fun sendUserMessage(text: String, mentionedAgentId: String? = null) {
        val parsedThinking = ThinkingLevelManager.parseDirective(text)
        val requestedThinkingLevel = parsedThinking.level
        val cleanedContent = parsedThinking.message.trim()
        if (cleanedContent.isBlank()) return

        val explicitTarget = mentionedAgentId?.let(::findActiveAgentById)
        val resolvedMention =
            explicitTarget ?: resolveMentionTarget(
                text = cleanedContent,
                selectedTarget = _selectedMentionTarget.value,
                agents = activeAgents.value,
            ).agent
        val targetAgent = resolvedMention ?: masterAgent()

        addMessage(
            AgentChatMessage.userMessage(
                content = cleanedContent,
                targetAgentId = targetAgent?.id,
            ),
        )
        _userInputText.value = ""
        _selectedMentionTarget.value = null

        if (targetAgent == null) {
            addMessage(
                AgentChatMessage.systemEvent(
                    "No active Master agent is available yet. Create or enable one in Agents first.",
                ),
            )
            return
        }

        val routedPrompt =
            resolvedMention?.let { target ->
                removeMentionToken(cleanedContent, target.name).ifBlank { cleanedContent }
            } ?: cleanedContent

        viewModelScope.launch {
            resetTransientSessionState()
            processAgentTurn(
                agent = targetAgent,
                prompt = routedPrompt,
                originatingUserMessage = cleanedContent,
                statusTask =
                    if (resolvedMention != null) {
                        "Replying to your direct message"
                    } else {
                        "Processing your request"
                    },
                allowDelegation = targetAgent.isMaster || targetAgent.role == AgentRole.MASTER,
                thinkingLevel = requestedThinkingLevel,
            )
        }
    }

    // Backward-compatible alias for the previous API.
    fun submitUserMessage(content: String) {
        sendUserMessage(content, _selectedMentionTarget.value?.id)
    }

    fun startNewChat() {
        viewModelScope.launch {
            val nextFamilyId = conversationSessionManager.startNewSession(_familyId.value.ifBlank { null })
            _messages.value = emptyList()
            resetTransientSessionState()
            resetConversationEngineHistories()
            bindFamily(nextFamilyId)
        }
    }

    fun approveTask(messageId: String) {
        if (!delegationGate.resolveApproval(messageId, approved = true)) {
            updateMessage(messageId) { message ->
                message.copy(approvalState = ApprovalState.APPROVED)
            }
        }
    }

    fun approveMessage(messageId: String) {
        approveTask(messageId)
    }

    fun rejectTask(messageId: String) {
        if (!delegationGate.resolveApproval(messageId, approved = false)) {
            updateMessage(messageId) { message ->
                message.copy(approvalState = ApprovalState.REJECTED)
            }
        }
    }

    fun rejectMessage(messageId: String) {
        rejectTask(messageId)
    }

    suspend fun delegateTaskWithApproval(
        masterAgent: Agent,
        subAgent: Agent,
        task: String,
        rejectionResponse: String = "Delegation was rejected. I will re-plan instead.",
    ): Boolean =
        delegationGate.delegateTaskWithApproval(
            masterAgent = masterAgent,
            subAgent = subAgent,
            task = task,
            rejectionResponse = rejectionResponse,
        )

    override fun onAgentAssigning(fromAgent: Agent, toAgent: Agent, taskSummary: String) {
        viewModelScope.launch {
            if (fromAgent.isMaster || fromAgent.role == AgentRole.MASTER) {
                agentStatusRepository.updateStatus(
                    agentId = fromAgent.id,
                    status = AgentStatus.DELEGATING,
                    task = "Delegating to ${toAgent.name}",
                )
                processAgentTurn(
                    agent = toAgent,
                    prompt = taskSummary,
                    originatingUserMessage = taskSummary,
                    statusTask = taskSummary,
                    allowDelegation = false,
                )
            } else {
                addMessageInternal(
                    AgentChatMessage.taskAssignment(
                        senderId = fromAgent.id,
                        senderName = fromAgent.name,
                        senderAvatar = fromAgent.avatar,
                        senderColor = fromAgent.accentColor,
                        senderRole = fromAgent.role,
                        content = taskSummary,
                        targetAgentId = toAgent.id,
                    ),
                )
            }
        }
    }

    override fun onAgentStatusUpdate(agent: Agent, status: String) {
        addMessage(
            AgentChatMessage.statusUpdate(
                senderId = agent.id,
                senderName = agent.name,
                senderAvatar = agent.avatar,
                senderColor = agent.accentColor,
                senderRole = agent.role,
                content = status,
            ),
        )
    }

    override fun onAgentResult(agent: Agent, summary: String) {
        addMessage(
            AgentChatMessage.summary(
                senderId = agent.id,
                senderName = agent.name,
                senderAvatar = agent.avatar,
                senderColor = agent.accentColor,
                senderRole = agent.role,
                content = summary,
            ),
        )
    }

    override fun onAgentTypingStart(agentId: String) {
        _typingAgentIds.value = _typingAgentIds.value + agentId
    }

    override fun onAgentTypingStop(agentId: String) {
        _typingAgentIds.value = _typingAgentIds.value - agentId
    }

    override fun onAgentStreamingChunk(messageId: String, chunk: String) {
        if (chunk.isEmpty()) return
        viewModelScope.launch {
            updateMessageInternal(messageId) { message ->
                if (message.isStreaming) {
                    message.copy(content = message.content + chunk)
                } else {
                    message.copy(content = message.content + chunk, isStreaming = true)
                }
            }
        }
    }

    private suspend fun startStreamingMessage(
        senderId: String,
        senderName: String,
        senderAvatar: String,
        senderColor: Long,
        senderRole: AgentRole,
        messageType: AgentMessageType,
        targetAgentId: String? = null,
    ): String {
        val message =
            AgentChatMessage(
                senderId = senderId,
                senderName = senderName,
                senderAvatar = senderAvatar,
                senderColor = senderColor,
                senderRole = senderRole,
                content = "",
                messageType = messageType,
                targetAgentId = targetAgentId,
                isStreaming = true,
            )

        addMessageInternal(message)
        onAgentTypingStop(senderId)
        return message.id
    }

    fun appendToStreamingMessage(messageId: String, text: String) {
        onAgentStreamingChunk(messageId, text)
    }

    private suspend fun completeStreamingMessage(messageId: String) {
        updateMessageInternal(messageId) { message ->
            message.copy(isStreaming = false)
        }
    }

    override fun onCleared() {
        messageObservationJob?.cancel()
        if (daemonBridge.agentGroupChatSink === this) {
            daemonBridge.agentGroupChatSink = null
        }
        delegationGate.cancelPendingApprovals()
        super.onCleared()
    }

    private fun findActiveAgentById(agentId: String): Agent? =
        activeAgents.value.firstOrNull { it.id == agentId }

    private fun masterAgent(): Agent? =
        activeAgents.value.firstOrNull { it.isMaster || it.role == AgentRole.MASTER }

    private suspend fun bindFamily(resolvedFamilyId: String) {
        if (_familyId.value == resolvedFamilyId && messageObservationJob != null) return

        messageObservationJob?.cancel()
        _familyId.value = resolvedFamilyId
        resetTransientSessionState()

        val persistedMessages = conversationSessionManager.getMessagesForFamily(resolvedFamilyId)
        _messages.value = persistedMessages
        conversationSessionManager.rebuildConversationEngineHistories(
            conversationEngine = conversationEngine,
            agents = activeAgents.value,
            messages = persistedMessages,
        )

        messageObservationJob =
            viewModelScope.launch {
                conversationSessionManager.observeMessagesForFamily(resolvedFamilyId).collect { persisted ->
                    _messages.value = persisted
                }
            }
    }

    private suspend fun addMessageInternal(message: AgentChatMessage) {
        val currentFamilyId = _familyId.value.ifBlank { return }
        conversationSessionManager.persistMessage(currentFamilyId, message)
    }

    private suspend fun addMessagesInternal(messages: List<AgentChatMessage>) {
        val currentFamilyId = _familyId.value.ifBlank { return }
        conversationSessionManager.persistMessages(currentFamilyId, messages)
    }

    private suspend fun updateMessageInternal(
        messageId: String,
        update: (AgentChatMessage) -> AgentChatMessage,
    ) {
        val currentFamilyId = _familyId.value.ifBlank { return }
        conversationSessionManager.updateMessage(currentFamilyId, messageId, update)
    }

    private fun resetTransientSessionState() {
        _typingAgentIds.value = emptySet()
        _userInputText.value = ""
        _selectedMentionTarget.value = null
        pendingImagesState.value = emptyList()
        processingImagesState.value = false
    }

    private fun resetConversationEngineHistories() {
        activeAgents.value.forEach { agent ->
            conversationEngine.replaceHistory(agent, emptyList())
            agentStatusRepository.updateStatus(
                agentId = agent.id,
                status = AgentStatus.IDLE,
                task = "",
            )
        }
    }

    private suspend fun executeOnDeviceTool(
        tool: OnDeviceTool,
        prompt: String,
        images: List<ProcessedImage>,
    ) {
        when (tool) {
            OnDeviceTool.INFER,
            OnDeviceTool.SUMMARIZE,
            OnDeviceTool.PROOFREAD,
            OnDeviceTool.REWRITE,
            -> executeOnDeviceTextTool(tool, prompt)

            OnDeviceTool.DESCRIBE_IMAGE ->
                executeOnDeviceImageTool(
                    prompt = prompt,
                    images = images,
                )

            OnDeviceTool.CAMERA_CAPTURE,
            OnDeviceTool.SCREEN_CAPTURE,
            -> Unit
        }
    }

    private suspend fun executeOnDeviceTextTool(
        tool: OnDeviceTool,
        prompt: String,
    ) {
        val trimmedPrompt = prompt.trim()
        if (trimmedPrompt.isBlank()) {
            addMessageInternal(
                AgentChatMessage.systemEvent("${tool.label} needs some text first."),
            )
            return
        }

        addMessageInternal(AgentChatMessage.userMessage(content = trimmedPrompt))
        resetLocalToolDraft()

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
                                style = com.zeroclaw.android.ui.screen.terminal.RewriteStyle.REPHRASE,
                            )
                        }

                        else -> ""
                    }
                }.getOrElse { error ->
                    usedFallback = true
                    executeCloudTextFallback(tool, trimmedPrompt, featureName, error.message)
                }
            }.getOrElse { error ->
                addMessageInternal(
                    AgentChatMessage.systemEvent(
                        error.message ?: "Unable to complete ${tool.label.lowercase()} request.",
                    ),
                )
                return
            }

        addMessageInternal(
            AgentChatMessage.onDeviceResult(
                content = resultText,
                senderName = if (usedFallback) "Cloud fallback" else "On-device",
            ),
        )
    }

    private suspend fun executeOnDeviceImageTool(
        prompt: String,
        images: List<ProcessedImage>,
    ) {
        if (images.isEmpty()) {
            addMessageInternal(
                AgentChatMessage.systemEvent(
                    "Attach an image, capture one from the camera, or use screen capture first.",
                ),
            )
            return
        }

        val effectivePrompt = prompt.trim().ifBlank { DEFAULT_LOCAL_IMAGE_PROMPT }
        addMessageInternal(AgentChatMessage.userMessage(content = effectivePrompt))
        resetLocalToolDraft()

        val firstImage = images.first()
        var usedFallback = false
        val resultText =
            runCatching {
                val bitmap = decodeProcessedImage(firstImage)
                if (bitmap == null) {
                    usedFallback = true
                    executeCloudImageFallback(
                        prompt = effectivePrompt,
                        images = listOf(firstImage),
                        reason = "Unable to decode the selected image locally.",
                    )
                } else {
                    runCatching {
                        bitmap.useBitmap {
                            collectStreamingText {
                                app.onDeviceImageDescriberBridge.describe(it)
                            }
                        }
                    }.getOrElse { error ->
                        usedFallback = true
                        executeCloudImageFallback(
                            prompt = effectivePrompt,
                            images = listOf(firstImage),
                            reason = error.message,
                        )
                    }
                }
            }.getOrElse { error ->
                addMessageInternal(
                    AgentChatMessage.systemEvent(
                        error.message ?: "Unable to describe the selected image.",
                    ),
                )
                return
            }

        addMessageInternal(
            AgentChatMessage.onDeviceResult(
                content = resultText,
                senderName = if (usedFallback) "Cloud fallback" else "On-device",
            ),
        )
    }

    private suspend fun executeCloudTextFallback(
        tool: OnDeviceTool,
        prompt: String,
        featureName: String,
        reason: String?,
    ): String {
        addMessageInternal(
            AgentChatMessage.systemEvent(
                buildString {
                    append("Cloud fallback")
                    if (!reason.isNullOrBlank()) {
                        append(": ")
                        append(reason)
                    } else {
                        append(" for ")
                        append(featureName)
                    }
                },
            ),
        )
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
        reason: String?,
    ): String {
        addMessageInternal(
            AgentChatMessage.systemEvent(
                buildString {
                    append("Cloud fallback")
                    if (!reason.isNullOrBlank()) {
                        append(": ")
                        append(reason)
                    } else {
                        append(" for image description")
                    }
                },
            ),
        )
        return app.visionBridge.send(prompt, images)
    }

    private suspend fun collectStreamingText(
        flowProvider: () -> kotlinx.coroutines.flow.Flow<String>,
    ): String {
        val builder = StringBuilder()
        flowProvider().collect { chunk ->
            builder.append(chunk)
        }
        return builder.toString().trim().ifBlank { "No response generated." }
    }

    private fun resetLocalToolDraft() {
        _userInputText.value = ""
        _selectedMentionTarget.value = null
        pendingImagesState.value = emptyList()
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

    private suspend fun processAgentTurn(
        agent: Agent,
        prompt: String,
        originatingUserMessage: String,
        statusTask: String,
        allowDelegation: Boolean,
        thinkingLevel: ThinkingLevel? = null,
    ) {
        val visibleTask = statusTask.trim().ifBlank { "Processing your request" }
        conversationEngine.initAgent(agent)
        onAgentTypingStart(agent.id)
        agentStatusRepository.updateStatus(
            agentId = agent.id,
            status = AgentStatus.THINKING,
            task = visibleTask,
        )

        var streamingMessageId: String? = null
        var finalResponse = ""
        var reportedError: String? = null
        val isMaster = agent.isMaster || agent.role == AgentRole.MASTER

        if (isMaster) {
            _masterStreamingState.value = StreamingState(phase = StreamingPhase.THINKING)
        }

        try {
            conversationEngine.sendMessage(
                agent = agent,
                userMessage = prompt,
                thinkingLevelOverride = thinkingLevel,
                listener = if (isMaster) {
                    object : ConversationSessionListener {
                        override fun onThinking(text: String) {
                            _masterStreamingState.value = _masterStreamingState.value.copy(
                                phase = StreamingPhase.THINKING,
                                thinkingText = _masterStreamingState.value.thinkingText + text
                            )
                        }
                        override fun onResponseChunk(text: String) {
                            _masterStreamingState.value = _masterStreamingState.value.copy(
                                phase = StreamingPhase.RESPONDING,
                            )
                        }
                        override fun onToolStart(name: String, argumentsHint: String) {
                            val callId = toolCallCounter.getAndIncrement()
                            _masterStreamingState.value = _masterStreamingState.value.copy(
                                phase = StreamingPhase.TOOL_EXECUTING,
                                activeTools = _masterStreamingState.value.activeTools + ToolProgress(name, argumentsHint, callId)
                            )
                        }
                        override fun onToolResult(name: String, success: Boolean, durationSecs: ULong) {
                            val current = _masterStreamingState.value
                            val match = current.activeTools.firstOrNull { it.name == name }
                            val toolHint = match?.hint ?: ""
                            val matchCallId = match?.callId ?: 0L
                            _masterStreamingState.value = current.copy(
                                activeTools = current.activeTools.filter { it.name != name },
                                toolResults = current.toolResults + ToolResultEntry(name, success, durationSecs.toLong(), hint = toolHint, callId = matchCallId)
                            )
                        }
                        override fun onToolOutput(name: String, output: String) {
                            _masterStreamingState.value = _masterStreamingState.value.copy(
                                toolResults = _masterStreamingState.value.toolResults.map { entry ->
                                    if (entry.name == name && entry.output.isEmpty()) entry.copy(output = output) else entry
                                }
                            )
                        }
                        override fun onProgress(message: String) {
                            // no-op for master streaming state here
                        }
                        override fun onCompaction(summary: String) {
                            _masterStreamingState.value = _masterStreamingState.value.copy(phase = StreamingPhase.COMPACTING)
                        }
                        override fun onComplete(fullResponse: String) {
                            _masterStreamingState.value = StreamingState(phase = StreamingPhase.COMPLETE)
                        }
                        override fun onError(error: String) {
                            _masterStreamingState.value = StreamingState(phase = StreamingPhase.ERROR, errorMessage = error)
                        }
                        override fun onCancelled() {
                            _masterStreamingState.value = StreamingState(phase = StreamingPhase.CANCELLED)
                        }
                    }
                } else null,
                onChunk = { chunk ->
                    val messageId =
                        streamingMessageId ?: startStreamingMessage(
                            senderId = agent.id,
                            senderName = agent.name,
                            senderAvatar = agent.avatar,
                            senderColor = agent.accentColor,
                            senderRole = agent.role,
                            messageType = AgentMessageType.SUMMARY,
                        ).also { createdMessageId ->
                            streamingMessageId = createdMessageId
                        }

                    onAgentStreamingChunk(messageId, chunk)
                    agentStatusRepository.updateStatus(
                        agentId = agent.id,
                        status = AgentStatus.THINKING,
                        task = visibleTask,
                    )
                },
                onComplete = { completedText ->
                    finalResponse = completedText.trim()
                    val messageId =
                        streamingMessageId ?: startStreamingMessage(
                            senderId = agent.id,
                            senderName = agent.name,
                            senderAvatar = agent.avatar,
                            senderColor = agent.accentColor,
                            senderRole = agent.role,
                            messageType = AgentMessageType.SUMMARY,
                        ).also { createdMessageId ->
                            streamingMessageId = createdMessageId
                        }

                    if (_messages.value.firstOrNull { it.id == messageId }?.content.isNullOrBlank() &&
                        finalResponse.isNotBlank()
                    ) {
                        onAgentStreamingChunk(messageId, finalResponse)
                    }
                    completeStreamingMessage(messageId)
                },
                onError = { error ->
                    val sanitized = ErrorSanitizer.sanitizeMessage(error)
                    reportedError = sanitized
                    val existingMessageId = streamingMessageId
                    if (existingMessageId == null) {
                        addMessageInternal(AgentChatMessage.systemEvent("${agent.name}: $sanitized"))
                    } else {
                        updateMessageInternal(existingMessageId) { message ->
                            if (message.content.isBlank()) {
                                message.copy(content = sanitized, isStreaming = false)
                            } else {
                                message.copy(isStreaming = false)
                            }
                        }
                        if (_messages.value.firstOrNull { it.id == existingMessageId }?.content?.trim() != sanitized) {
                            addMessageInternal(AgentChatMessage.systemEvent("${agent.name}: $sanitized"))
                        }
                    }
                    agentStatusRepository.updateStatus(
                        agentId = agent.id,
                        status = AgentStatus.ERROR,
                        task = sanitized,
                    )
                },
            )
        } finally {
            onAgentTypingStop(agent.id)
        }

        if (reportedError != null) {
            return
        }

        agentStatusRepository.updateStatus(
            agentId = agent.id,
            status = AgentStatus.DONE,
            task = "",
        )

        if (allowDelegation && finalResponse.isNotBlank()) {
            maybeHandleMasterDelegation(
                masterAgent = agent,
                masterResponse = finalResponse,
                originatingUserMessage = originatingUserMessage,
            )
        }
    }

    private suspend fun maybeHandleMasterDelegation(
        masterAgent: Agent,
        masterResponse: String,
        originatingUserMessage: String,
    ) {
        val delegationCandidate =
            detectDelegationCandidate(
                masterAgent = masterAgent,
                masterResponse = masterResponse,
                originatingUserMessage = originatingUserMessage,
            ) ?: return

        agentStatusRepository.updateStatus(
            agentId = masterAgent.id,
            status = AgentStatus.DELEGATING,
            task = "Delegating to ${delegationCandidate.subAgent.name}",
        )

        processAgentTurn(
            agent = delegationCandidate.subAgent,
            prompt = delegationCandidate.taskPrompt,
            originatingUserMessage = originatingUserMessage,
            statusTask = delegationCandidate.taskSummary,
            allowDelegation = false,
        )
    }

    private fun detectDelegationCandidate(
        masterAgent: Agent,
        masterResponse: String,
        originatingUserMessage: String,
    ): DelegationCandidate? {
        val subAgents =
            activeAgents.value.filterNot { candidate ->
                candidate.id == masterAgent.id || candidate.isMaster || candidate.role == AgentRole.MASTER
            }
        if (subAgents.isEmpty()) return null

        val normalizedResponse = normalizeWhitespace(masterResponse)
        val responseSentences = splitSentences(normalizedResponse)

        for (subAgent in subAgents.sortedByDescending { it.name.length }) {
            val patterns =
                listOf(
                    Regex("(?i)\\bI(?:'ll| will) ask\\s+@?${Regex.escape(subAgent.name)}\\b"),
                    Regex("(?i)\\bDelegating to\\s+@?${Regex.escape(subAgent.name)}\\b"),
                    Regex("(?i)\\bAssigning to\\s+@?${Regex.escape(subAgent.name)}\\b"),
                    Regex("(?i)(^|\\s)@${Regex.escape(subAgent.name)}(?=\\s|$|[.,!?])"),
                )

            val matchingSentenceIndex =
                responseSentences.indexOfFirst { sentence ->
                    patterns.any { pattern -> pattern.containsMatchIn(sentence) }
                }
            if (matchingSentenceIndex < 0) continue

            val candidateText =
                buildString {
                    append(responseSentences[matchingSentenceIndex])
                    val nextSentence = responseSentences.getOrNull(matchingSentenceIndex + 1)
                    if (!nextSentence.isNullOrBlank()) {
                        append(' ')
                        append(nextSentence)
                    }
                }

            val taskSummary =
                extractTaskSummary(
                    candidateText = candidateText,
                    subAgent = subAgent,
                    fallback = originatingUserMessage,
                )

            return DelegationCandidate(
                subAgent = subAgent,
                taskSummary = taskSummary,
                taskPrompt =
                    buildDelegationPrompt(
                        masterAgent = masterAgent,
                        subAgent = subAgent,
                        taskSummary = taskSummary,
                        originatingUserMessage = originatingUserMessage,
                    ),
            )
        }

        return null
    }

    private fun extractTaskSummary(
        candidateText: String,
        subAgent: Agent,
        fallback: String,
    ): String {
        val cleaned =
            normalizeWhitespace(
                candidateText
                    .replace(
                        Regex(
                            "(?i)\\b(?:I(?:'ll| will) ask|Delegating to|Assigning to)\\s+@?${Regex.escape(subAgent.name)}\\b[:,-]*",
                        ),
                        "",
                    ).replace(Regex("(?i)^to\\s+"), "")
                    .trim(),
            )

        val summarySource = cleaned.ifBlank { fallback.trim() }
        return limitSentences(summarySource, maxSentences = 2)
    }

    private fun buildDelegationPrompt(
        masterAgent: Agent,
        subAgent: Agent,
        taskSummary: String,
        originatingUserMessage: String,
    ): String =
        buildString {
            appendLine("You are ${subAgent.name}, working in a multi-agent group chat.")
            appendLine("${masterAgent.name} delegated this task to you.")
            appendLine("Task: $taskSummary")
            if (originatingUserMessage.isNotBlank()) {
                appendLine("Original user request: $originatingUserMessage")
            }
            append("Respond directly to the user with your work, findings, or next steps.")
        }

    private fun buildRejectionPrompt(
        subAgent: Agent,
        taskSummary: String,
        originatingUserMessage: String,
    ): String =
        buildString {
            appendLine("The user rejected delegating work to ${subAgent.name}.")
            appendLine("Rejected task: $taskSummary")
            if (originatingUserMessage.isNotBlank()) {
                appendLine("Original user request: $originatingUserMessage")
            }
            append("Re-plan and respond directly to the user. If delegation is still needed, explain it clearly.")
        }

    private fun splitSentences(text: String): List<String> =
        normalizeWhitespace(text)
            .split(Regex("(?<=[.!?])\\s+"))
            .map(String::trim)
            .filter(String::isNotEmpty)

    private fun limitSentences(
        text: String,
        maxSentences: Int,
    ): String {
        val sentences = splitSentences(text)
        return when {
            sentences.isEmpty() -> normalizeWhitespace(text)
            sentences.size <= maxSentences -> sentences.joinToString(" ")
            else -> sentences.take(maxSentences).joinToString(" ")
        }
    }

    private fun normalizeWhitespace(text: String): String = text.replace(Regex("\\s+"), " ").trim()

    private data class DelegationCandidate(
        val subAgent: Agent,
        val taskSummary: String,
        val taskPrompt: String,
    )
}
