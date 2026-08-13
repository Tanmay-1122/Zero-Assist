/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import android.util.Log
import com.zeroclaw.android.data.ProviderRegistry
import com.zeroclaw.android.data.repository.ApiKeyRepository
import com.zeroclaw.android.media.MediaIntentClassifier
import com.zeroclaw.android.media.NativeImageSearch
import com.zeroclaw.android.model.Agent
import com.zeroclaw.android.model.ProviderAuthType
import com.zeroclaw.android.model.ThinkingLevel
import com.zeroclaw.android.model.ToolSpec
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Single conversation message tracked for one agent's in-memory chat history.
 */
data class ConversationMessage(
    val role: String,
    val content: String,
)

/**
 * Rehydrates the singleton native session for a specific agent and streams the
 * response back to the caller while maintaining per-agent history in Kotlin.
 *
 * **Performance optimizations:**
 * - Caches tool catalog and channel list to avoid rebuilding on every request
 * - Caches session prompts by fingerprint
 * - Reduces unnecessary session recreations by smart key comparison
 */
class AgentConversationEngine(
    private val apiKeyRepository: ApiKeyRepository,
    private val sessionBridge: ConversationSessionBridge = ConversationSessionBridge(),
    private val toolCatalogBridge: ToolCatalogBridge = ToolsBridge(),
    private val channelStatusBridge: ChannelStatusBridge = EmptyChannelStatusBridge,
    private val onDeviceEngine: LocalInferenceEngine? = null,
    private val offlineMemoryContextProvider: OfflineMemoryContextProvider =
        OfflineMemoryContextProvider { null },
) {
    private val histories = linkedMapOf<String, MutableList<ConversationMessage>>()
    private val sessionMutex = Mutex()
    private var activeSessionKey: SessionKey? = null

    // ===== Performance caches =====
    private data class ToolCatalogCache(
        val tools: List<ToolSpec>,
        val timestamp: Long,
        val fingerprint: String,
    )

    private data class ChannelListCache(
        val channels: List<ChannelStatus>,
        val timestamp: Long,
        val fingerprint: String,
    )

    private data class SessionPromptCache(
        val prompt: SessionPrompt,
        val timestamp: Long,
    )

    @Volatile
    private var toolCatalogCache: ToolCatalogCache? = null

    @Volatile
    private var channelListCache: ChannelListCache? = null

    private val sessionPromptCache = linkedMapOf<String, SessionPromptCache>()
    private val promptCacheMutex = Mutex()

    /**
     * Ensures an agent has an initialized history starting with its system prompt.
     */
    fun initAgent(agent: Agent) {
        histories.getOrPut(agent.id) {
            mutableListOf(
                ConversationMessage(
                    role = ROLE_SYSTEM,
                    content = resolvedSystemPrompt(agent),
                ),
            )
        }
    }

    /**
     * Returns the current in-memory history for one agent.
     */
    fun getHistory(agentId: String): List<ConversationMessage> =
        histories[agentId]?.toList().orEmpty()

    /**
     * Replaces the current in-memory history for one agent.
     */
    fun replaceHistory(
        agent: Agent,
        messages: List<ConversationMessage>,
    ) {
        val sanitized =
            messages
                .filter { it.content.isNotBlank() }
                .ifEmpty {
                    listOf(
                        ConversationMessage(
                            role = ROLE_SYSTEM,
                            content = resolvedSystemPrompt(agent),
                        ),
                    )
                }
                .toMutableList()

        if (sanitized.firstOrNull()?.role != ROLE_SYSTEM) {
            sanitized.add(
                index = 0,
                element = ConversationMessage(
                    role = ROLE_SYSTEM,
                    content = resolvedSystemPrompt(agent),
                ),
            )
        }

        histories[agent.id] = sanitized
        // Always force session recreation when history is replaced to prevent old messages from persisting
        if (activeSessionKey?.agentId == agent.id) {
            Log.d(TAG, "Invalidating session for agent ${agent.id} due to history replacement")
            invalidateActiveSession()
        }
    }

    /**
     * Sends a user/task message to a specific agent and streams real chunks from
     * the native session API.
     */
    suspend fun sendMessage(
        agent: Agent,
        userMessage: String,
        onChunk: suspend (String) -> Unit,
        onComplete: suspend (String) -> Unit,
        onError: suspend (String) -> Unit,
        listener: ConversationSessionListener? = null,
        onTypingStarted: suspend () -> Unit = {},
        thinkingLevelOverride: ThinkingLevel? = null,
    ) {
        val parsedMessage = ThinkingLevelManager.parseDirective(userMessage)
        val messageForModel = parsedMessage.message.trim()
        if (messageForModel.isEmpty()) {
            onError("Cannot send an empty message.")
            return
        }
        val effectiveThinkingLevel = parsedMessage.level ?: thinkingLevelOverride ?: agent.thinkingLevel

        sessionMutex.withLock {
            initAgent(agent)

            // Handle explicit image-display requests in the client so the result is
            // rendered inline instead of sending the model to browser/device tools.
            val isImageDisplayRequest = MediaIntentClassifier.isImageDisplayRequest(messageForModel)
            if (isImageDisplayRequest) {
                onTypingStarted()
                val nativeResponse = NativeImageSearch.search(messageForModel).getOrNull()
                if (!nativeResponse.isNullOrBlank()) {
                    histories[agent.id] = (
                        histories.getValue(agent.id) +
                            ConversationMessage(role = ROLE_USER, content = messageForModel) +
                            ConversationMessage(role = ROLE_ASSISTANT, content = nativeResponse)
                        ).toMutableList()
                    onChunk(nativeResponse)
                    onComplete(nativeResponse)
                    return@withLock
                }
            }

            val modelMessage = if (isImageDisplayRequest) {
                IMAGE_DISPLAY_MODEL_DIRECTIVE + messageForModel
            } else {
                messageForModel
            }

            val providerInfo = ProviderRegistry.findById(agent.provider)
            val keyRecord = apiKeyRepository.getByProviderFresh(agent.provider)
            val credentials = resolveCredentials(agent, providerInfo?.authType, keyRecord)
            if (credentials.errorMessage != null) {
                onError(credentials.errorMessage)
                return
            }

            // Route on-device agents through the local inference engine.
            if (agent.provider == "on-device" && onDeviceEngine != null) {
                val existingHistory = histories.getValue(agent.id).toList()
                val sessionPrompt =
                    buildOfflineSessionPrompt(
                        agent = agent,
                        thinkingLevel = effectiveThinkingLevel,
                        query = modelMessage,
                    )
                sendOnDeviceMessage(
                    agent,
                    modelMessage,
                    existingHistory,
                    sessionPrompt,
                    onChunk,
                    onComplete,
                    onError,
                    onTypingStarted,
                )
                return@withLock
            }

            val existingHistory = histories.getValue(agent.id).toList()
            val sessionPrompt = buildSessionPrompt(agent, effectiveThinkingLevel)

            var reportedError: String? = null
            var completionText = ""
            val streamedResponse = StringBuilder()

            try {
                withContext(Dispatchers.IO) {
                    ensureNativeSession(agent, credentials, existingHistory, sessionPrompt)
                }
                onTypingStarted()

                val eventChannel = Channel<StreamEvent>(Channel.UNLIMITED)
                coroutineScope {
                    val sendJob =
                        async(Dispatchers.IO) {
                            try {
                                sessionBridge.send(
                                    message = modelMessage,
                                    imageData = emptyList(),
                                    mimeTypes = emptyList(),
                                    listener = eventChannel.asSessionListener(),
                                )
                            } finally {
                                eventChannel.close()
                            }
                        }

                    for (event in eventChannel) {
                        when (event) {
                            is StreamEvent.Thinking -> listener?.onThinking(event.text)
                            is StreamEvent.ResponseChunk -> {
                                if (event.text.isNotEmpty()) {
                                    streamedResponse.append(event.text)
                                    listener?.onResponseChunk(event.text)
                                    onChunk(event.text)
                                }
                            }
                            is StreamEvent.ToolStart -> {
                                ToolHealthMonitor.onToolStart(event.name)
                                listener?.onToolStart(event.name, event.argumentsHint)
                            }
                            is StreamEvent.ToolResult -> {
                                ToolHealthMonitor.onToolResult(
                                    name = event.name,
                                    success = event.success,
                                    durationSecs = event.durationSecs,
                                )
                                listener?.onToolResult(event.name, event.success, event.durationSecs)
                            }
                            is StreamEvent.ToolOutput -> listener?.onToolOutput(event.name, event.output)
                            is StreamEvent.Progress -> listener?.onProgress(event.message)
                            is StreamEvent.Compaction -> listener?.onCompaction(event.summary)
                            is StreamEvent.Complete -> {
                                completionText = event.fullResponse
                                listener?.onComplete(event.fullResponse)
                            }
                            is StreamEvent.Error -> {
                                reportedError = event.error
                                ToolHealthMonitor.onError(error = event.error)
                                listener?.onError(event.error)
                                onError(event.error)
                            }
                            StreamEvent.Cancelled -> {
                                reportedError = CANCELLED_MESSAGE
                                ToolHealthMonitor.onError(error = CANCELLED_MESSAGE)
                                listener?.onCancelled()
                                onError(CANCELLED_MESSAGE)
                            }
                        }
                    }

                    sendJob.await()
                }

                // Only refresh history on errors to avoid unnecessary FFI overhead.
                // On successful completion, just append the new messages directly.
                if (reportedError != null) {
                    val refreshedHistory =
                        withContext(Dispatchers.IO) {
                            sessionBridge.history().map { sessionMessage ->
                                ConversationMessage(
                                    role = sessionMessage.role,
                                    content = sessionMessage.content,
                                )
                        }
                    }

                    if (refreshedHistory.isNotEmpty()) {
                        histories[agent.id] = normalizeRefreshedHistory(agent, refreshedHistory).toMutableList()
                    }
                } else {
                    // Normal path: append new messages without expensive refresh
                    histories[agent.id] =
                        (
                            existingHistory +
                                ConversationMessage(role = ROLE_USER, content = messageForModel) +
                                ConversationMessage(
                                    role = ROLE_ASSISTANT,
                                    content = completionText.ifBlank { streamedResponse.toString() },
                                )
                        ).toMutableList()
                }

                val finalText = completionText.ifBlank { streamedResponse.toString() }.trim()
                if (finalText.isNotEmpty()) {
                    onComplete(finalText)
                } else if (reportedError == null) {
                    onError(EMPTY_RESPONSE_MESSAGE)
                }
            } catch (error: Exception) {
                invalidateActiveSession()
                val message = reportedError ?: error.message ?: UNKNOWN_ERROR_MESSAGE
                if (reportedError == null) {
                    onError(message)
                }
            }
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun sendOnDeviceMessage(
        agent: Agent,
        message: String,
        existingHistory: List<ConversationMessage>,
        sessionPrompt: SessionPrompt,
        onChunk: suspend (String) -> Unit,
        onComplete: suspend (String) -> Unit,
        onError: suspend (String) -> Unit,
        onTypingStarted: suspend () -> Unit,
    ) {
        val engine = onDeviceEngine ?: return onError("On-device engine not available.")
        try {
            if (engine.engineState.value != EngineState.READY) {
                val downloaded = engine.getDownloadedModels().firstOrNull()
                if (downloaded != null) {
                    onTypingStarted()
                    engine.initialize(downloaded)
                } else {
                    onError("No on-device model downloaded. Download one in Settings > On-Device AI.")
                    return
                }
            }
            onTypingStarted()
            val messages = existingHistory
                .filter { it.role == ROLE_USER || it.role == ROLE_ASSISTANT }
                .takeLast(MAX_OFFLINE_HISTORY_MESSAGES)
                .map { InferenceMessage(role = it.role, content = it.content) } +
                InferenceMessage(role = "user", content = message)
            val response = engine.chat(
                messages = messages,
                systemPrompt = sessionPrompt.content,
            )
            if (response.isNotEmpty()) {
                histories.getOrPut(agent.id) { mutableListOf() }.apply {
                    add(ConversationMessage(role = ROLE_USER, content = message))
                    add(ConversationMessage(role = ROLE_ASSISTANT, content = response))
                }
                onComplete(response)
            } else {
                onError(EMPTY_RESPONSE_MESSAGE)
            }
        } catch (e: Exception) {
            onError(e.message ?: UNKNOWN_ERROR_MESSAGE)
        }
    }

    /** Builds the intentionally small prompt used by Offline Mode. */
    private suspend fun buildOfflineSessionPrompt(
        agent: Agent,
        thinkingLevel: ThinkingLevel,
        query: String,
    ): SessionPrompt {
        val memoryContext = offlineMemoryContextProvider.contextFor(query)
        val content = buildOfflineModePrompt(resolvedSystemPrompt(agent), memoryContext)
        return SessionPrompt(
            content = content,
            agentPromptFingerprint = fingerprint(resolvedSystemPrompt(agent)),
            thinkingLevel = thinkingLevel,
            toolFingerprint = OFFLINE_FINGERPRINT,
            channelFingerprint = OFFLINE_FINGERPRINT,
        )
    }

    /**
     * Clears the in-memory history for one agent.
     */
    fun clearHistory(agentId: String) {
        histories.remove(agentId)
        if (activeSessionKey?.agentId == agentId) {
            invalidateActiveSession()
        }
    }

    /**
     * Invalidates all caches. Call this when settings change or tools are updated.
     */
    fun invalidateCaches() {
        toolCatalogCache = null
        channelListCache = null
        sessionPromptCache.clear()
        Log.d(TAG, "All caches invalidated")
    }

    private fun ensureNativeSession(
        agent: Agent,
        credentials: ResolvedCredentials,
        existingHistory: List<ConversationMessage>,
        sessionPrompt: SessionPrompt,
    ) {
        val sessionKey = SessionKey.from(agent, credentials, sessionPrompt)

        // Smart comparison: skip recreation if key matches
        if (activeSessionKey == sessionKey) {
            Log.d(TAG, "Reusing existing session for agent ${agent.id}")
            return
        }

        safeDestroySession()
        activeSessionKey = null

        val startTime = System.currentTimeMillis()
        sessionBridge.startCustomSession(
            providerName = agent.provider,
            model = agent.modelName,
            apiKey = credentials.apiKey,
            baseUrl = credentials.baseUrl,
            temperature = agent.temperature?.toDouble(),
            thinkingLevel = sessionPrompt.thinkingLevel.directiveName,
            systemPrompt = sessionPrompt.content,
        )

        val seedMessages =
            existingHistory
                .filterNot { message -> message.role == ROLE_SYSTEM }
                .filter { message -> message.role == ROLE_USER || message.role == ROLE_ASSISTANT }
                .takeLast(MAX_SEEDED_MESSAGES)
                .map { message ->
                    ConversationSeedMessage(
                        role = message.role,
                        content = message.content,
                    )
                }
        if (seedMessages.isNotEmpty()) {
            sessionBridge.seed(seedMessages)
        }

        activeSessionKey = sessionKey
        val duration = System.currentTimeMillis() - startTime
        Log.d(TAG, "Session created for ${agent.id} in ${duration}ms (seeded ${seedMessages.size} messages)")
    }

    private suspend fun buildSessionPrompt(
        agent: Agent,
        thinkingLevel: ThinkingLevel,
    ): SessionPrompt {
        // Generate cache key
        val cacheKey = "${agent.id}:${thinkingLevel.name}"

        // Check cache first
        promptCacheMutex.withLock {
            sessionPromptCache[cacheKey]?.let { cached ->
                val age = System.currentTimeMillis() - cached.timestamp
                if (age < PROMPT_CACHE_VALIDITY_MS) {
                    Log.d(TAG, "Using cached session prompt for ${agent.id} (age: ${age}ms)")
                    return cached.prompt
                }
            }
        }

        val startTime = System.currentTimeMillis()

        // Get or refresh tool catalog cache
        val toolsResult = getCachedOrFetchTools()
        val activeTools =
            toolsResult
                .getOrElse { emptyList() }
                .filter { it.isActive }
                .sortedBy { it.name }

        // Get or refresh channel list cache
        val channelsResult = getCachedOrFetchChannels()
        val activeChannels =
            channelsResult
                .getOrElse { emptyList() }
                .filter { it.isEnabled }
                .sortedBy { it.typeName }

        val toolFingerprint =
            if (toolsResult.isSuccess) {
                toolCatalogCache?.fingerprint ?: fingerprint("tools-unavailable")
            } else {
                fingerprint("tools-unavailable")
            }

        val channelFingerprint =
            if (channelsResult.isSuccess) {
                channelListCache?.fingerprint ?: fingerprint("channels-unavailable")
            } else {
                fingerprint("channels-unavailable")
            }

        val now = LocalDateTime.now()

        val prompt = SessionPrompt(
            content =
                buildString {
                    appendLine("Instructions: Use tools when needed. Report actual results only. No narration.")
                    appendLine()
                    appendChannelsSection(activeChannels, channelsResult.exceptionOrNull())
                    appendToolsSection(activeTools, toolsResult.exceptionOrNull())
                    appendLine("Safety: Ask before destructive actions.")
                    appendLine()
                    appendLine("## Identity")
                    appendLine(resolvedSystemPrompt(agent))
                    appendLine()
                    appendLine("## Context")
                    appendLine(
                        "Date: ${now.toLocalDate()} | Time: ${
                            now.toLocalTime().truncatedTo(ChronoUnit.SECONDS)
                        }",
                    )
                },
            agentPromptFingerprint = fingerprint(resolvedSystemPrompt(agent)),
            thinkingLevel = thinkingLevel,
            toolFingerprint = toolFingerprint,
            channelFingerprint = channelFingerprint,
        )

        // Cache the result
        promptCacheMutex.withLock {
            sessionPromptCache[cacheKey] = SessionPromptCache(
                prompt = prompt,
                timestamp = System.currentTimeMillis(),
            )
            // LRU eviction: keep only the most recent 10 prompts
            if (sessionPromptCache.size > MAX_CACHED_PROMPTS) {
                sessionPromptCache.remove(sessionPromptCache.keys.first())
            }
        }

        val duration = System.currentTimeMillis() - startTime
        Log.d(TAG, "Session prompt built and cached in ${duration}ms")

        return prompt
    }

    /**
     * Gets tools from cache or fetches fresh if cache is stale.
     */
    private suspend fun getCachedOrFetchTools(): Result<List<ToolSpec>> {
        toolCatalogCache?.let { cached ->
            val age = System.currentTimeMillis() - cached.timestamp
            if (age < TOOL_CACHE_VALIDITY_MS) {
                Log.d(TAG, "Using cached tool catalog (age: ${age}ms)")
                return Result.success(cached.tools)
            }
        }

        return runCatching {
            toolCatalogBridge.listTools().also { tools ->
                val fingerprint = fingerprint(
                    tools.joinToString(separator = "\n") { tool ->
                        "${tool.name}\u0000${tool.description}\u0000${tool.parametersJson}"
                    },
                )
                toolCatalogCache = ToolCatalogCache(
                    tools = tools,
                    timestamp = System.currentTimeMillis(),
                    fingerprint = fingerprint,
                )
                Log.d(TAG, "Tool catalog refreshed (${tools.size} tools)")
            }
        }
    }

    /**
     * Gets channels from cache or fetches fresh if cache is stale.
     */
    private suspend fun getCachedOrFetchChannels(): Result<List<ChannelStatus>> {
        channelListCache?.let { cached ->
            val age = System.currentTimeMillis() - cached.timestamp
            if (age < CHANNEL_CACHE_VALIDITY_MS) {
                Log.d(TAG, "Using cached channel list (age: ${age}ms)")
                return Result.success(cached.channels)
            }
        }

        return runCatching {
            channelStatusBridge.listChannels().also { channels ->
                val fingerprint = fingerprint(
                    channels.joinToString(separator = "\n") { channel ->
                        "${channel.typeName}\u0000${channel.displayName}\u0000${channel.details}"
                    },
                )
                channelListCache = ChannelListCache(
                    channels = channels,
                    timestamp = System.currentTimeMillis(),
                    fingerprint = fingerprint,
                )
                Log.d(TAG, "Channel list refreshed (${channels.size} channels)")
            }
        }
    }

    private fun StringBuilder.appendChannelsSection(
        activeChannels: List<ChannelStatus>,
        channelListError: Throwable?,
    ) {
        if (channelListError != null || activeChannels.isEmpty()) {
            return // Skip if unavailable or empty
        }

        appendLine("Channels: ${activeChannels.joinToString { it.displayName }}")
        appendLine()
    }

    private fun StringBuilder.appendToolsSection(
        activeTools: List<ToolSpec>,
        toolListError: Throwable?,
    ) {
        if (toolListError != null) {
            return // Skip if unavailable
        }

        if (activeTools.isEmpty()) {
            return // Skip if no tools
        }

        appendLine("## Tools")
        activeTools.forEach { tool ->
            appendLine("- ${tool.name}: ${tool.description}")
        }
        appendLine()
    }

    private fun normalizeRefreshedHistory(
        agent: Agent,
        messages: List<ConversationMessage>,
    ): List<ConversationMessage> =
        buildList {
            add(
                ConversationMessage(
                    role = ROLE_SYSTEM,
                    content = resolvedSystemPrompt(agent),
                ),
            )
            messages
                .filterNot { message -> message.role == ROLE_SYSTEM }
                .forEach(::add)
        }

    private fun invalidateActiveSession() {
        safeDestroySession()
        activeSessionKey = null
    }

    private fun resolvedSystemPrompt(agent: Agent): String =
        agent.systemPrompt
            .trim()
            .ifEmpty {
                "You are ${agent.name.ifBlank { agent.role.displayName }}, " +
                    "a ${agent.role.displayName.lowercase()} agent. " +
                    "Answer clearly, stay on task, and be helpful."
            }

    private fun resolveCredentials(
        agent: Agent,
        authType: ProviderAuthType?,
        keyRecord: com.zeroclaw.android.model.ApiKey?,
    ): ResolvedCredentials {
        val baseUrl =
            keyRecord?.baseUrl
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?: ProviderRegistry.findById(agent.provider)
                    ?.defaultBaseUrl
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() }

        val apiKey =
            keyRecord?.key
                ?.trim()
                ?.takeIf { it.isNotEmpty() }

        return when (authType ?: ProviderAuthType.API_KEY_ONLY) {
            ProviderAuthType.NONE ->
                ResolvedCredentials(apiKey = null, baseUrl = baseUrl, errorMessage = null)

            ProviderAuthType.URL_ONLY ->
                if (baseUrl == null) {
                    ResolvedCredentials(
                        apiKey = null,
                        baseUrl = null,
                        errorMessage = "No base URL configured for ${agent.name}.",
                    )
                } else {
                    ResolvedCredentials(apiKey = null, baseUrl = baseUrl, errorMessage = null)
                }

            ProviderAuthType.URL_AND_OPTIONAL_KEY ->
                if (baseUrl == null) {
                    ResolvedCredentials(
                        apiKey = apiKey,
                        baseUrl = null,
                        errorMessage = "No base URL configured for ${agent.name}.",
                    )
                } else {
                    ResolvedCredentials(apiKey = apiKey, baseUrl = baseUrl, errorMessage = null)
                }

            ProviderAuthType.API_KEY_ONLY, ProviderAuthType.API_KEY_OR_OAUTH ->
                if (apiKey == null) {
                    ResolvedCredentials(
                        apiKey = null,
                        baseUrl = baseUrl,
                        errorMessage = "No API key configured for ${agent.name}.",
                    )
                } else {
                    ResolvedCredentials(apiKey = apiKey, baseUrl = baseUrl, errorMessage = null)
                }

            ProviderAuthType.ON_DEVICE ->
                ResolvedCredentials(apiKey = null, baseUrl = null, errorMessage = null)
        }
    }

    private fun safeDestroySession() {
        runCatching { sessionBridge.destroy() }
    }

    private fun Channel<StreamEvent>.asSessionListener(): ConversationSessionListener =
        object : ConversationSessionListener {
            override fun onThinking(text: String) {
                trySend(StreamEvent.Thinking(text))
            }

            override fun onResponseChunk(text: String) {
                trySend(StreamEvent.ResponseChunk(text))
            }

            override fun onToolStart(name: String, argumentsHint: String) {
                trySend(StreamEvent.ToolStart(name, argumentsHint))
            }

            override fun onToolResult(name: String, success: Boolean, durationSecs: ULong) {
                trySend(StreamEvent.ToolResult(name, success, durationSecs))
            }

            override fun onToolOutput(name: String, output: String) {
                trySend(StreamEvent.ToolOutput(name, output))
            }

            override fun onProgress(message: String) {
                trySend(StreamEvent.Progress(message))
            }

            override fun onCompaction(summary: String) {
                trySend(StreamEvent.Compaction(summary))
            }

            override fun onComplete(fullResponse: String) {
                trySend(StreamEvent.Complete(fullResponse))
            }

            override fun onError(error: String) {
                trySend(StreamEvent.Error(error))
            }

            override fun onCancelled() {
                trySend(StreamEvent.Cancelled)
            }
        }

    private sealed class StreamEvent {
        data class Thinking(val text: String) : StreamEvent()

        data class ResponseChunk(val text: String) : StreamEvent()

        data class ToolStart(
            val name: String,
            val argumentsHint: String,
        ) : StreamEvent()

        data class ToolResult(
            val name: String,
            val success: Boolean,
            val durationSecs: ULong,
        ) : StreamEvent()

        data class ToolOutput(
            val name: String,
            val output: String,
        ) : StreamEvent()

        data class Progress(val message: String) : StreamEvent()

        data class Compaction(val summary: String) : StreamEvent()

        data class Complete(val fullResponse: String) : StreamEvent()

        data class Error(val error: String) : StreamEvent()

        data object Cancelled : StreamEvent()
    }

    private data class SessionKey(
        val agentId: String,
        val provider: String,
        val model: String,
        val apiKeyFingerprint: String?,
        val baseUrl: String?,
        val temperature: Float?,
        val agentPromptFingerprint: String,
        val thinkingLevel: ThinkingLevel,
        val toolFingerprint: String,
        val channelFingerprint: String,
    ) {
        companion object {
            fun from(
                agent: Agent,
                credentials: ResolvedCredentials,
                sessionPrompt: SessionPrompt,
            ): SessionKey =
                SessionKey(
                    agentId = agent.id,
                    provider = agent.provider,
                    model = agent.modelName,
                    apiKeyFingerprint = apiKeyFingerprint(credentials.apiKey),
                    baseUrl = credentials.baseUrl,
                    temperature = agent.temperature,
                    agentPromptFingerprint = sessionPrompt.agentPromptFingerprint,
                    thinkingLevel = sessionPrompt.thinkingLevel,
                    toolFingerprint = sessionPrompt.toolFingerprint,
                    channelFingerprint = sessionPrompt.channelFingerprint,
                )
        }
    }

    private data class SessionPrompt(
        val content: String,
        val agentPromptFingerprint: String,
        val thinkingLevel: ThinkingLevel,
        val toolFingerprint: String,
        val channelFingerprint: String,
    )

    private data class ResolvedCredentials(
        val apiKey: String?,
        val baseUrl: String?,
        val errorMessage: String?,
    )

    private companion object {
        private const val TAG = "AgentConversationEngine"
        private const val ROLE_SYSTEM = "system"
        private const val ROLE_USER = "user"
        private const val ROLE_ASSISTANT = "assistant"
        private const val MAX_SEEDED_MESSAGES = 50  // Reduced from 200 for 75% token savings
        private const val MAX_OFFLINE_HISTORY_MESSAGES = 12
        private const val OFFLINE_FINGERPRINT = "offline"
        private const val CANCELLED_MESSAGE = "Request cancelled."
        private const val EMPTY_RESPONSE_MESSAGE = "The model did not generate a text response."
        private const val UNKNOWN_ERROR_MESSAGE = "The request failed."
        private const val IMAGE_DISPLAY_MODEL_DIRECTIVE =
            "The client renders images natively. For this image-display request, do not use device control, " +
                "browser navigation, or page links. If you must answer through the model, return direct HTTP(S) " +
                "image URLs only as Markdown image blocks (`![description](url)`), one per image.\n\nUser request: "

        // Cache validity periods
        private const val TOOL_CACHE_VALIDITY_MS = 30_000L // 30 seconds
        private const val CHANNEL_CACHE_VALIDITY_MS = 30_000L // 30 seconds
        private const val PROMPT_CACHE_VALIDITY_MS = 60_000L // 60 seconds
        private const val MAX_CACHED_PROMPTS = 10 // LRU limit

        private fun apiKeyFingerprint(apiKey: String?): String? =
            apiKey
                ?.takeIf { it.isNotEmpty() }
                ?.let { fingerprint("api-key:$it") }

        private fun fingerprint(value: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
                .joinToString(separator = "") { byte -> "%02x".format(byte) }
    }
}
