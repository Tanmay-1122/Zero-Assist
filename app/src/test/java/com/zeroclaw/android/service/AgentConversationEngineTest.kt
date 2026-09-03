/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.data.repository.ApiKeyRepository
import com.zeroclaw.android.model.Agent
import com.zeroclaw.android.model.ApiKey
import com.zeroclaw.android.model.ThinkingLevel
import com.zeroclaw.android.model.ToolSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("AgentConversationEngine")
class AgentConversationEngineTest {
    @Test
    @DisplayName("Offline Mode skips tool and channel catalogs")
    fun `offline mode skips tool and channel catalogs`() =
        runTest {
            val localEngine = FakeLocalInferenceEngine()
            val tools = FakeToolCatalogBridge(tools = listOf(testTool(name = "shell")))
            val channels =
                FakeChannelStatusBridge(
                    channels = listOf(ChannelStatus(typeName = "discord", displayName = "Discord", isEnabled = true)),
                )
            val engine =
                testEngine(
                    toolCatalogBridge = tools,
                    channelStatusBridge = channels,
                    onDeviceEngine = localEngine,
                    offlineMemoryContextProvider = OfflineMemoryContextProvider { "- remembers local preference" },
                )

            engine.sendMessage(
                agent = testAgent(provider = "on-device"),
                userMessage = "hello",
                onChunk = {},
                onComplete = {},
                onError = { message -> throw AssertionError(message) },
            )

            assertEquals(0, tools.listCalls)
            assertEquals(0, channels.listCalls)
            assertTrue(localEngine.lastSystemPrompt.orEmpty().contains("remembers local preference"))
            assertTrue(localEngine.lastTools.isEmpty())
        }

    @Test
    @DisplayName("delivers chunks while native send is still running")
    fun `delivers chunks while native send is still running`() =
        runTest {
            val firstChunkDelivered = CompletableDeferred<Unit>()
            val bridge =
                FakeConversationSessionBridge(
                    afterFirstChunk = {
                        runBlocking {
                            withTimeout(1_000) {
                                firstChunkDelivered.await()
                            }
                        }
                    },
                )
            val engine = testEngine(bridge = bridge)
            val chunks = mutableListOf<String>()

            engine.sendMessage(
                agent = testAgent(),
                userMessage = "hello",
                onChunk = { chunk ->
                    chunks += chunk
                    if (chunk == "Hel") {
                        firstChunkDelivered.complete(Unit)
                    }
                },
                onComplete = {},
                onError = { message -> throw AssertionError(message) },
            )

            assertEquals(listOf("Hel", "lo"), chunks)
            assertTrue(bridge.afterFirstChunkCompleted)
        }

    @Test
    @DisplayName("reuses active native session for same agent")
    fun `reuses active native session for same agent`() =
        runTest {
            val bridge = FakeConversationSessionBridge()
            val engine = testEngine(bridge = bridge)
            val agent = testAgent()

            engine.sendMessage(
                agent = agent,
                userMessage = "one",
                onChunk = {},
                onComplete = {},
                onError = { message -> throw AssertionError(message) },
            )
            engine.sendMessage(
                agent = agent,
                userMessage = "two",
                onChunk = {},
                onComplete = {},
                onError = { message -> throw AssertionError(message) },
            )

            assertEquals(1, bridge.startCount)
            assertEquals(0, bridge.seedCount)
            assertEquals(listOf("one", "two"), bridge.sentMessages)
        }

    @Test
    @DisplayName("fires typing after session setup before native send")
    fun `fires typing after session setup before native send`() =
        runTest {
            val events = mutableListOf<String>()
            val bridge = FakeConversationSessionBridge(events = events)
            val engine = testEngine(bridge = bridge)

            engine.sendMessage(
                agent = testAgent(),
                userMessage = "hello",
                onChunk = { events += "chunk:$it" },
                onComplete = {},
                onError = { message -> throw AssertionError(message) },
                onTypingStarted = { events += "typing" },
            )

            assertEquals(listOf("typing", "send", "chunk:Hel", "chunk:lo"), events)
        }

    @Test
    @DisplayName("session key fingerprints API key instead of retaining raw secret")
    fun `session key fingerprints API key instead of retaining raw secret`() =
        runTest {
            val secret = "sk-raw-secret-for-test"
            val bridge = FakeConversationSessionBridge()
            val engine = testEngine(
                apiKeyRepository = TestApiKeyRepository(initialKey = secret),
                bridge = bridge,
            )

            engine.sendMessage(
                agent = testAgent(),
                userMessage = "hello",
                onChunk = {},
                onComplete = {},
                onError = { message -> throw AssertionError(message) },
            )

            val field = AgentConversationEngine::class.java.getDeclaredField("activeSessionKey")
            field.isAccessible = true
            val sessionKeyText = field.get(engine).toString()
            assertFalse(sessionKeyText.contains(secret))
            assertEquals(listOf(secret), bridge.startedApiKeys)
        }

    @Test
    @DisplayName("injects active tool schemas into native session prompt")
    fun `injects active tool schemas into native session prompt`() =
        runTest {
            val bridge = FakeConversationSessionBridge()
            val engine =
                testEngine(
                    bridge = bridge,
                    toolCatalogBridge =
                        FakeToolCatalogBridge(
                            tools =
                                listOf(
                                    testTool(
                                        name = "shell",
                                        description = "Run a shell command",
                                        parametersJson = "{\"type\":\"object\"}",
                                    ),
                                    testTool(
                                        name = "disabled_tool",
                                        description = "Should not appear",
                                        isActive = false,
                                    ),
                                ),
                        ),
                )

            engine.sendMessage(
                agent = testAgent(systemPrompt = "Custom agent rules."),
                userMessage = "hello",
                onChunk = {},
                onComplete = {},
                onError = { message -> throw AssertionError(message) },
            )

            val prompt = bridge.startedPrompts.single()
            assertTrue(prompt.contains("## CRITICAL: No Tool Narration"))
            assertTrue(prompt.contains("## CRITICAL: Tool Honesty"))
            assertTrue(prompt.contains("- **shell**: Run a shell command"))
            assertTrue(prompt.contains("Parameters: `{\"type\":\"object\"}`"))
            assertTrue(prompt.contains("<tool_call>"))
            assertTrue(prompt.contains("Custom agent rules."))
            assertTrue(prompt.contains("## Current Date & Time"))
            assertFalse(prompt.contains("disabled_tool"))
        }

    @Test
    @DisplayName("continues without tools when tool listing fails")
    fun `continues without tools when tool listing fails`() =
        runTest {
            val bridge = FakeConversationSessionBridge()
            val engine =
                testEngine(
                    bridge = bridge,
                    toolCatalogBridge = FakeToolCatalogBridge(failure = IllegalStateException("boom")),
                )
            val chunks = mutableListOf<String>()

            engine.sendMessage(
                agent = testAgent(),
                userMessage = "hello",
                onChunk = { chunks += it },
                onComplete = {},
                onError = { message -> throw AssertionError(message) },
            )

            assertEquals(listOf("Hel", "lo"), chunks)
            assertTrue(bridge.startedPrompts.single().contains("Tool metadata is unavailable for this turn."))
        }

    @Test
    @DisplayName("injects active channel status into native session prompt")
    fun `injects active channel status into native session prompt`() =
        runTest {
            val bridge = FakeConversationSessionBridge()
            val engine =
                testEngine(
                    bridge = bridge,
                    channelStatusBridge =
                        FakeChannelStatusBridge(
                            channels =
                                listOf(
                                    ChannelStatus(
                                        typeName = "mqtt",
                                        displayName = "MQTT",
                                        isEnabled = true,
                                        details = "SOP listener subscribed to: sensors/#",
                                    ),
                                    ChannelStatus(
                                        typeName = "discord",
                                        displayName = "Discord",
                                        isEnabled = false,
                                    ),
                                ),
                        ),
                )

            engine.sendMessage(
                agent = testAgent(),
                userMessage = "is mqtt enabled?",
                onChunk = {},
                onComplete = {},
                onError = { message -> throw AssertionError(message) },
            )

            val prompt = bridge.startedPrompts.single()
            assertTrue(prompt.contains("## Channels"))
            assertTrue(prompt.contains("- MQTT: enabled (SOP listener subscribed to: sensors/#)"))
            assertTrue(prompt.contains("Channels are runtime integrations, not callable tools."))
            assertFalse(prompt.contains("Discord: enabled"))
        }

    @Test
    @DisplayName("recreates native session when active tool schema changes")
    fun `recreates native session when active tool schema changes`() =
        runTest {
            val bridge = FakeConversationSessionBridge()
            val tools = FakeToolCatalogBridge(tools = listOf(testTool(parametersJson = "{\"a\":1}")))
            val engine = testEngine(bridge = bridge, toolCatalogBridge = tools)
            val agent = testAgent()

            engine.sendMessage(
                agent = agent,
                userMessage = "one",
                onChunk = {},
                onComplete = {},
                onError = { message -> throw AssertionError(message) },
            )
            tools.tools = listOf(testTool(parametersJson = "{\"a\":2}"))
            engine.sendMessage(
                agent = agent,
                userMessage = "two",
                onChunk = {},
                onComplete = {},
                onError = { message -> throw AssertionError(message) },
            )

            assertEquals(2, bridge.startCount)
            assertEquals(listOf("one", "two"), bridge.sentMessages)
        }

    @Test
    @DisplayName("applies and strips inline thinking directive")
    fun `applies and strips inline thinking directive`() =
        runTest {
            val bridge = FakeConversationSessionBridge()
            val engine = testEngine(bridge = bridge)

            engine.sendMessage(
                agent = testAgent(),
                userMessage = "/think:max explain this",
                onChunk = {},
                onComplete = {},
                onError = { message -> throw AssertionError(message) },
            )

            assertEquals(listOf("explain this"), bridge.sentMessages)
            assertTrue(bridge.startedPrompts.single().contains("## Thinking Level"))
            assertTrue(bridge.startedPrompts.single().contains("Max"))
            assertEquals(listOf("max"), bridge.startedThinkingLevels)
        }

    @Test
    @DisplayName("uses explicit thinking override when no inline directive is present")
    fun `uses explicit thinking override when no inline directive is present`() =
        runTest {
            val bridge = FakeConversationSessionBridge()
            val engine = testEngine(bridge = bridge)

            engine.sendMessage(
                agent = testAgent(),
                userMessage = "explain this",
                thinkingLevelOverride = ThinkingLevel.MEDIUM,
                onChunk = {},
                onComplete = {},
                onError = { message -> throw AssertionError(message) },
            )

            assertTrue(bridge.startedPrompts.single().contains("Medium"))
            assertFalse(bridge.startedPrompts.single().contains("Think step by step"))
            assertEquals(listOf("medium"), bridge.startedThinkingLevels)
        }

    @Test
    @DisplayName("does not feed Rust system prompt back as agent role")
    fun `does not feed Rust system prompt back as agent role`() =
        runTest {
            val bridge = FakeConversationSessionBridge()
            val engine = testEngine(bridge = bridge)
            val firstAgent = testAgent(id = "agent-1", name = "First Agent")
            val secondAgent = testAgent(id = "agent-2", name = "Second Agent")

            engine.sendMessage(
                agent = firstAgent,
                userMessage = "one",
                onChunk = {},
                onComplete = {},
                onError = { message -> throw AssertionError(message) },
            )
            engine.sendMessage(
                agent = secondAgent,
                userMessage = "two",
                onChunk = {},
                onComplete = {},
                onError = { message -> throw AssertionError(message) },
            )
            engine.sendMessage(
                agent = firstAgent,
                userMessage = "three",
                onChunk = {},
                onComplete = {},
                onError = { message -> throw AssertionError(message) },
            )

            val firstAgentPrompt =
                "You are First Agent, a general agent. Answer clearly, stay on task, and be helpful."
            assertTrue(bridge.startedPrompts[0].contains(firstAgentPrompt))
            assertTrue(bridge.startedPrompts[2].contains(firstAgentPrompt))
            assertEquals(firstAgentPrompt, engine.getHistory(firstAgent.id).first().content)
        }

    private fun testEngine(
        apiKeyRepository: ApiKeyRepository = TestApiKeyRepository(),
        bridge: ConversationSessionBridge = FakeConversationSessionBridge(),
        toolCatalogBridge: ToolCatalogBridge = FakeToolCatalogBridge(),
        channelStatusBridge: ChannelStatusBridge = FakeChannelStatusBridge(),
        onDeviceEngine: LocalInferenceEngine? = null,
        offlineMemoryContextProvider: OfflineMemoryContextProvider = OfflineMemoryContextProvider { null },
    ): AgentConversationEngine =
        AgentConversationEngine(
            apiKeyRepository = apiKeyRepository,
            sessionBridge = bridge,
            toolCatalogBridge = toolCatalogBridge,
            channelStatusBridge = channelStatusBridge,
            onDeviceEngine = onDeviceEngine,
            offlineMemoryContextProvider = offlineMemoryContextProvider,
        )

    private fun testAgent(
        id: String = "agent-1",
        name: String = "Test Agent",
        systemPrompt: String = "",
        provider: String = "openai",
    ): Agent =
        Agent(
            id = id,
            name = name,
            provider = provider,
            modelName = "gpt-4o",
            systemPrompt = systemPrompt,
        )

    private fun testTool(
        name: String = "tool",
        description: String = "Tool description",
        parametersJson: String = "{}",
        isActive: Boolean = true,
    ): ToolSpec =
        ToolSpec(
            name = name,
            description = description,
            source = "test",
            parametersJson = parametersJson,
            isActive = isActive,
            inactiveReason = if (isActive) "" else "disabled",
        )
}

private class FakeToolCatalogBridge(
    var tools: List<ToolSpec> = emptyList(),
    private val failure: Throwable? = null,
) : ToolCatalogBridge {
    var listCalls = 0

    override suspend fun listTools(): List<ToolSpec> {
        listCalls++
        failure?.let { throw it }
        return tools
    }
}

private class FakeChannelStatusBridge(
    var channels: List<ChannelStatus> = emptyList(),
    private val failure: Throwable? = null,
) : ChannelStatusBridge {
    var listCalls = 0

    override suspend fun listChannels(): List<ChannelStatus> {
        listCalls++
        failure?.let { throw it }
        return channels
    }
}

private class FakeLocalInferenceEngine : LocalInferenceEngine {
    override val engineState = MutableStateFlow(EngineState.READY)
    override val downloadingModelId = MutableStateFlow<String?>(null)
    override val downloadProgress = MutableStateFlow<Float?>(null)
    override val downloadError = MutableStateFlow<DownloadError?>(null)
    override val currentModelId: String? = "test-model"
    override val currentModelIdFlow = MutableStateFlow<String?>(currentModelId)
    override val totalMemoryBytes: Long = 8L * 1024 * 1024 * 1024

    var lastSystemPrompt: String? = null
    var lastTools: List<LocalTool> = emptyList()

    override suspend fun initialize(model: DownloadedModel, contextTokens: Int) = Unit

    override suspend fun release() = Unit

    override fun releaseInBackground() = Unit

    override suspend fun chat(
        messages: List<InferenceMessage>,
        systemPrompt: String?,
        tools: List<LocalTool>,
    ): String {
        lastSystemPrompt = systemPrompt
        lastTools = tools
        return "offline response"
    }

    override fun getDownloadedModels(): List<DownloadedModel> = emptyList()

    override fun getAvailableModels(): List<LocalModel> = emptyList()

    override fun getFreeSpaceBytes(): Long = 0L

    override fun startDownload(model: LocalModel) = Unit

    override fun cancelDownload() = Unit

    override suspend fun deleteModel(modelId: String) = Unit
}

private class FakeConversationSessionBridge(
    private val afterFirstChunk: (() -> Unit)? = null,
    private val events: MutableList<String>? = null,
) : ConversationSessionBridge() {
    var startCount = 0
        private set
    var seedCount = 0
        private set
    var afterFirstChunkCompleted = false
        private set

    val sentMessages = mutableListOf<String>()
    val startedPrompts = mutableListOf<String>()
    val startedApiKeys = mutableListOf<String?>()
    val startedThinkingLevels = mutableListOf<String?>()

    private var history = mutableListOf<ConversationSeedMessage>()

    override fun startCustomSession(
        providerName: String,
        model: String,
        apiKey: String?,
        baseUrl: String?,
        temperature: Double?,
        thinkingLevel: String?,
        systemPrompt: String,
    ) {
        startCount += 1
        startedPrompts += systemPrompt
        startedApiKeys += apiKey
        startedThinkingLevels += thinkingLevel
        history = mutableListOf(ConversationSeedMessage(role = "system", content = "RUST:$systemPrompt"))
    }

    override fun seed(messages: List<ConversationSeedMessage>) {
        seedCount += 1
        history.addAll(messages)
    }

    override fun send(
        message: String,
        imageData: List<String>,
        mimeTypes: List<String>,
        listener: ConversationSessionListener,
    ) {
        events?.add("send")
        sentMessages += message
        history += ConversationSeedMessage(role = "user", content = message)
        listener.onResponseChunk("Hel")
        afterFirstChunk?.invoke()
        afterFirstChunkCompleted = true
        listener.onResponseChunk("lo")
        history += ConversationSeedMessage(role = "assistant", content = "Hello")
        listener.onComplete("Hello")
    }

    override fun history(): List<ConversationSeedMessage> = history.toList()

    override fun destroy() {
        history = mutableListOf()
    }
}

private class TestApiKeyRepository(
    initialKey: String = "sk-test",
) : ApiKeyRepository {
    private val storedKeys =
        MutableStateFlow(
            listOf(
                ApiKey(
                    id = "openai-key",
                    provider = "openai",
                    key = initialKey,
                ),
            ),
        )

    override val keys: Flow<List<ApiKey>> = storedKeys

    override suspend fun getById(id: String): ApiKey? =
        storedKeys.value.firstOrNull { key -> key.id == id }

    override suspend fun save(apiKey: ApiKey) {
        storedKeys.value = storedKeys.value.filterNot { key -> key.id == apiKey.id } + apiKey
    }

    override suspend fun delete(id: String) {
        storedKeys.value = storedKeys.value.filterNot { key -> key.id == id }
    }

    override suspend fun exportAll(passphrase: String): String = ""

    override suspend fun importFrom(
        encryptedPayload: String,
        passphrase: String,
    ): Int = 0

    override suspend fun getByProvider(provider: String): ApiKey? =
        storedKeys.value.firstOrNull { key -> key.provider.equals(provider, ignoreCase = true) }
}
