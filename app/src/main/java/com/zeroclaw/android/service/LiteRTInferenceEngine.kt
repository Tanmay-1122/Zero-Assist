/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.StatFs
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.OpenApiTool
import com.google.ai.edge.litertlm.SamplerConfig
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.time.Duration.Companion.milliseconds

/**
 * [LocalInferenceEngine] implementation backed by the LiteRT LM SDK.
 *
 * Runs Gemma 4 and Qwen3 models entirely on-device using the device's GPU
 * (with automatic CPU fallback). Model files are downloaded from HuggingFace
 * and stored in the app's private `filesDir/litert_models/` tree.
 *
 * **Architecture notes**
 * - Engine lifecycle: GPU drain delay is applied between model switches to let
 *   the OpenCL driver reclaim buffers before new ones are allocated.
 * - Idle release: After each [chat] call the engine is released after
 *   [IDLE_RELEASE_MS] of inactivity to free GPU memory for other workloads.
 * - Download: Uses a plain [HttpURLConnection] with progress reporting via
 *   [ModelDownloadForegroundService]. Partial downloads are written to a `.tmp`
 *   file and atomically renamed on success.
 * - Tool calling: [LocalTool] instances are adapted to [OpenApiTool] using a
 *   [runBlocking] bridge because the LiteRT SDK calls [execute] on its own
 *   JNI worker thread and expects a synchronous result.
 *
 * @param context Android application context used for file paths, memory info,
 *   and the download foreground service.
 */
@Suppress("TooManyFunctions")
class LiteRTInferenceEngine(private val context: Context) : LocalInferenceEngine {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null
    private var idleReleaseJob: Job? = null

    private var engine: Engine? = null
    private var conversation: com.google.ai.edge.litertlm.Conversation? = null

    override var currentModelId: String? = null
        private set
    private var currentContextTokens: Int = 0

    override val totalMemoryBytes: Long
        get() = getMemoryInfo().totalMem

    private val _engineState = MutableStateFlow(EngineState.UNINITIALIZED)
    override val engineState: StateFlow<EngineState> = _engineState

    private val _currentModelId = MutableStateFlow<String?>(null)
    override val currentModelIdFlow: StateFlow<String?> = _currentModelId

    private val _downloadingModelId = MutableStateFlow<String?>(null)
    override val downloadingModelId: StateFlow<String?> = _downloadingModelId

    private val _downloadProgress = MutableStateFlow<Float?>(null)
    override val downloadProgress: StateFlow<Float?> = _downloadProgress

    private val _downloadError = MutableStateFlow<DownloadError?>(null)
    override val downloadError: StateFlow<DownloadError?> = _downloadError

    // ---------------------------------------------------------------------------
    // Engine lifecycle
    // ---------------------------------------------------------------------------

    /**
     * Initialises the LiteRT engine with [model] and [contextTokens].
     *
     * Attempts GPU first; falls back to CPU on any GPU initialisation failure.
     * If the requested [contextTokens] is unsupported by the model it retries
     * with the model's default context window.
     *
     * @throws InsufficientMemoryException when free RAM < [MIN_MEMORY_HEADROOM_BYTES].
     */
    override suspend fun initialize(model: DownloadedModel, contextTokens: Int) {
        withContext(Dispatchers.IO) {
            idleReleaseJob?.cancel()

            // Fast-path: already loaded with the same settings.
            if (currentModelId == model.id &&
                currentContextTokens == contextTokens &&
                _engineState.value == EngineState.READY
            ) return@withContext

            _engineState.value = EngineState.INITIALIZING
            try {
                val modelFile = File(model.filePath)
                if (!modelFile.exists() || modelFile.length() < MIN_MODEL_FILE_BYTES) {
                    throw IllegalStateException("Model file missing or too small: ${model.filePath}")
                }

                // Release the existing engine before measuring available memory so its
                // GPU working-set no longer counts against the headroom check.
                val hadExistingEngine = engine != null
                release()
                _engineState.value = EngineState.INITIALIZING

                if (hadExistingEngine) {
                    // engine.close() is asynchronous with respect to the OpenCL driver's
                    // buffer reclaim. Reduced delay from 750ms to 200ms for faster re-init.
                    System.gc()
                    delay(GPU_DRAIN_DELAY_MS.milliseconds)
                }

                val initStartTime = System.currentTimeMillis()
                val availMem = getAvailableMemoryBytes()
                if (availMem < MIN_MEMORY_HEADROOM_BYTES) throw InsufficientMemoryException()

                fun initWithBackend(backend: Backend, maxTokens: Int?): Engine {
                    val config = EngineConfig(
                        modelPath = model.filePath,
                        backend = backend,
                        cacheDir = context.cacheDir.absolutePath,
                        maxNumTokens = maxTokens,
                    )
                    val e = Engine(config)
                    e.initialize()
                    return e
                }

                val requestedTokens = if (contextTokens > 0) contextTokens else null

                val newEngine = try {
                    try {
                        initWithBackend(Backend.GPU(), requestedTokens)
                    } catch (e: Exception) {
                        initWithBackend(Backend.CPU(), requestedTokens)
                    }
                } catch (e: Exception) {
                    // Context size not supported — retry with model default.
                    if (requestedTokens != null) {
                        try {
                            initWithBackend(Backend.GPU(), null)
                        } catch (e2: Exception) {
                            initWithBackend(Backend.CPU(), null)
                        }
                    } else {
                        throw e
                    }
                }

                engine = newEngine
                conversation = newEngine.createConversation()
                currentModelId = model.id
                _currentModelId.value = model.id
                currentContextTokens = contextTokens
                _engineState.value = EngineState.READY

                val initDuration = System.currentTimeMillis() - initStartTime
                Log.d(TAG, "LiteRT engine initialized in ${initDuration}ms (model: ${model.id})")
            } catch (e: Exception) {
                _engineState.value = EngineState.ERROR
                throw e
            }
        }
    }

    /** Releases all native resources. Safe to call when already released. */
    override suspend fun release() {
        withContext(Dispatchers.IO) {
            val convToClose = conversation
            val engineToClose = engine
            conversation = null
            engine = null
            currentModelId = null
            _currentModelId.value = null
            _engineState.value = EngineState.UNINITIALIZED
            runCatching { convToClose?.close() }
            runCatching { engineToClose?.close() }
        }
    }

    /**
     * Schedules a background release. Used from non-suspend contexts such as UI
     * event handlers where the caller cannot `await`.
     */
    override fun releaseInBackground() {
        idleReleaseJob?.cancel()
        idleReleaseJob = scope.launch { release() }
    }

    // ---------------------------------------------------------------------------
    // Inference
    // ---------------------------------------------------------------------------

    /**
     * Runs a multi-turn chat round with the loaded model.
     *
     * The last `"user"` entry in [messages] is submitted as the new turn;
     * earlier entries form the conversation history. Optional [tools] are
     * exposed to the model via [OpenApiTool].
     *
     * Qwen3 `<think>…</think>` blocks are stripped before the result is returned.
     *
     * **Performance:** Optimized with reduced timeout from 2min to 45sec for faster
     * failure feedback. Engine stays warm for 30min instead of 5min.
     *
     * @throws IllegalStateException if the engine is not [EngineState.READY].
     * @throws InferenceTimeoutException if inference exceeds [INFERENCE_TIMEOUT_MS].
     */
    @Suppress("TooGenericExceptionCaught")
    override suspend fun chat(
        messages: List<InferenceMessage>,
        systemPrompt: String?,
        tools: List<LocalTool>,
    ): String = withContext(Dispatchers.IO) {
        val startTime = System.currentTimeMillis()
        idleReleaseJob?.cancel()
        try {
            val currentEngine = engine ?: throw IllegalStateException("LiteRT engine not initialized")

            val lastUserIndex = messages.indexOfLast { it.role == "user" }
            if (lastUserIndex < 0) throw IllegalStateException("No user message in conversation")

            val sanitizedSystem = sanitizeForLiteRt(systemPrompt)
            val history = messages.subList(0, lastUserIndex).map { msg ->
                val text = sanitizeForLiteRt(msg.content) ?: ""
                when (msg.role) {
                    "user" -> Message.user(text)
                    else -> Message.model(text)
                }
            }

            val toolProviders = tools.map { tool(LocalToolOpenApiAdapter(it)) }
            val config = ConversationConfig(
                systemInstruction = sanitizedSystem?.let { Contents.of(it) },
                initialMessages = history,
                tools = toolProviders,
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.8),
                // Only enable automaticToolCalling when tools are present; plain-text
                // responses are mis-parsed as function calls when the flag is always on.
                automaticToolCalling = toolProviders.isNotEmpty(),
            )

            val prev = conversation
            conversation = null
            runCatching { prev?.close() }
            val conv = currentEngine.createConversation(config)
            conversation = conv

            val lastMessage = sanitizeForLiteRt(messages[lastUserIndex].content) ?: ""
            val response = try {
                withTimeout(INFERENCE_TIMEOUT_MS.milliseconds) {
                    conv.sendMessage(lastMessage)
                }
            } catch (e: TimeoutCancellationException) {
                throw InferenceTimeoutException()
            }

            val result = stripThinkBlocks(response.toString())
            val duration = System.currentTimeMillis() - startTime
            Log.d(TAG, "LiteRT inference completed in ${duration}ms")
            result
        } finally {
            scheduleIdleRelease()
        }
    }

    // ---------------------------------------------------------------------------
    // Model management
    // ---------------------------------------------------------------------------

    /** Returns all catalog models whose `.litertlm` file exists in local storage. */
    override fun getDownloadedModels(): List<DownloadedModel> {
        val modelsDir = File(modelStorageDirectory())
        if (!modelsDir.exists()) return emptyList()
        return ZERO_ASSIST_MODEL_CATALOG.mapNotNull { catalogModel ->
            val modelDir = File(modelsDir, catalogModel.id)
            val modelFile = File(modelDir, catalogModel.fileName)
            if (modelFile.exists()) {
                DownloadedModel(
                    id = catalogModel.id,
                    displayName = catalogModel.displayName,
                    filePath = modelFile.absolutePath,
                    sizeBytes = modelFile.length(),
                )
            } else {
                null
            }
        }
    }

    override fun getAvailableModels(): List<LocalModel> = ZERO_ASSIST_MODEL_CATALOG

    override fun getFreeSpaceBytes(): Long {
        val dir = File(modelStorageDirectory()).also { it.mkdirs() }
        return StatFs(dir.absolutePath).availableBytes
    }

    /**
     * Starts a coroutine-based background download of [model].
     *
     * Progress is reported via [downloadProgress] (0.0–1.0). The download is
     * written to a `.tmp` file and atomically renamed to the final name on
     * success. The foreground service [ModelDownloadForegroundService] is
     * started once a live HTTP connection is established, ensuring the service
     * does not time out if the network is unreachable.
     */
    @Suppress("TooGenericExceptionCaught", "LongMethod")
    override fun startDownload(model: LocalModel) {
        cancelDownload()
        downloadJob = scope.launch {
            _downloadingModelId.value = model.id
            _downloadProgress.value = 0f
            _downloadError.value = null
            var tempFile: File? = null
            var notificationStarted = false

            try {
                val modelDir = File(modelStorageDirectory(), model.id).also { it.mkdirs() }
                val targetFile = File(modelDir, model.fileName)
                tempFile = File(modelDir, "${model.fileName}.tmp")

                val freeSpace = getFreeSpaceBytes()
                if (freeSpace < model.sizeBytes + DOWNLOAD_SPACE_BUFFER_BYTES) {
                    _downloadError.value = DownloadError.NOT_ENOUGH_DISK_SPACE
                    return@launch
                }

                @Suppress("DEPRECATION")
                val connection = URL(model.downloadUrl).openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 30_000
                connection.readTimeout = 60_000
                connection.connect()

                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    connection.disconnect()
                    throw IOException("Download failed: HTTP $responseCode")
                }

                // Only start the foreground service once we have a live connection,
                // preventing ForegroundServiceDidNotStartInTimeException on fast failures.
                startDownloadNotification()
                notificationStarted = true

                val contentLength = connection.contentLengthLong.takeIf { it > 0 } ?: model.sizeBytes
                val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                var totalBytesRead = 0L
                var lastNotifiedPercent = -1

                connection.inputStream.use { input ->
                    tempFile.outputStream().use { output ->
                        while (true) {
                            ensureActive()
                            val bytesRead = input.read(buffer)
                            if (bytesRead <= 0) break
                            output.write(buffer, 0, bytesRead)
                            totalBytesRead += bytesRead
                            val percent = (totalBytesRead * 100 / contentLength).toInt().coerceIn(1, 100)
                            if (percent != lastNotifiedPercent) {
                                lastNotifiedPercent = percent
                                _downloadProgress.value = percent / 100f
                                updateDownloadNotificationProgress(percent)
                            }
                        }
                    }
                }
                connection.disconnect()

                val downloadedSize = tempFile.length()
                if (downloadedSize < contentLength * DOWNLOAD_COMPLETENESS_THRESHOLD) {
                    tempFile.delete()
                    throw IOException("Download incomplete: got $downloadedSize bytes, expected ~$contentLength")
                }

                if (!tempFile.renameTo(targetFile)) {
                    tempFile.copyTo(targetFile, overwrite = true)
                    tempFile.delete()
                }
            } catch (e: Throwable) {
                if (tempFile?.exists() == true) tempFile.delete()
                if (e is CancellationException) throw e
                _downloadError.value = DownloadError.NETWORK_ERROR
            } finally {
                _downloadingModelId.value = null
                _downloadProgress.value = null
                if (notificationStarted) stopDownloadNotification()
            }
        }
    }

    override fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
    }

    /** Deletes all files for [modelId] from disk, releasing the engine first if needed. */
    override suspend fun deleteModel(modelId: String) {
        withContext(Dispatchers.IO) {
            // Wait for any pending idle-release so native teardown doesn't race with
            // deleteRecursively().
            idleReleaseJob?.cancelAndJoin()
            idleReleaseJob = null
            if (currentModelId == modelId) release()
            File(modelStorageDirectory(), modelId).deleteRecursively()
        }
    }

    // ---------------------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------------------

    /** Schedules a deferred [release] after [IDLE_RELEASE_MS] of inactivity. */
    private fun scheduleIdleRelease() {
        idleReleaseJob?.cancel()
        idleReleaseJob = scope.launch {
            delay(IDLE_RELEASE_MS.milliseconds)
            release()
        }
    }

    /** Absolute path for the directory where model sub-directories are stored. */
    private fun modelStorageDirectory(): String = context.filesDir.absolutePath + "/litert_models"

    // Memory helpers -----------------------------------------------------------

    private fun getMemoryInfo(): ActivityManager.MemoryInfo {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
    }

    private fun getAvailableMemoryBytes(): Long = getMemoryInfo().availMem

    // Notification helpers (download foreground service) ----------------------

    private fun startDownloadNotification() {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                DOWNLOAD_CHANNEL_ID,
                "Model Download",
                NotificationManager.IMPORTANCE_LOW,
            )
            nm.createNotificationChannel(channel)
            // Post a sticky notification so the download can run while the app is backgrounded.
            val notification = android.app.Notification.Builder(context, DOWNLOAD_CHANNEL_ID)
                .setContentTitle("Zero-Assist")
                .setContentText("Downloading on-device model…")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setProgress(100, 0, true)
                .build()
            nm.notify(DOWNLOAD_NOTIFICATION_ID, notification)
        } catch (_: Exception) { }
    }

    private fun updateDownloadNotificationProgress(percent: Int) {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val notification = android.app.Notification.Builder(context, DOWNLOAD_CHANNEL_ID)
                .setContentTitle("Zero-Assist")
                .setContentText("Downloading on-device model… $percent%")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setProgress(100, percent, false)
                .build()
            nm.notify(DOWNLOAD_NOTIFICATION_ID, notification)
        } catch (_: Exception) { }
    }

    private fun stopDownloadNotification() {
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(DOWNLOAD_NOTIFICATION_ID)
        } catch (_: Exception) { }
    }

    // ---------------------------------------------------------------------------
    // Inner types
    // ---------------------------------------------------------------------------

    /**
     * Bridges a suspending [LocalTool] to the LiteRT SDK's synchronous [OpenApiTool].
     *
     * The LiteRT engine calls [execute] on its own JNI worker thread inside a
     * `Dispatchers.IO` coroutine, so using [runBlocking] here is safe — we are
     * not blocking the main thread.
     */
    private class LocalToolOpenApiAdapter(private val localTool: LocalTool) : OpenApiTool {
        override fun getToolDescriptionJsonString(): String = localTool.descriptionJsonString
        override fun execute(paramsJsonString: String): String =
            runBlocking { localTool.execute(paramsJsonString) }
    }

    // ---------------------------------------------------------------------------
    // Constants
    // ---------------------------------------------------------------------------

    companion object {
        private const val TAG = "LiteRTInferenceEngine"

        /**
         * How long the engine stays in memory after the last [chat] call.
         * Increased from 5 min to 30 min to keep engine warm during active sessions.
         */
        private const val IDLE_RELEASE_MS = 30L * 60 * 1000 // 30 min

        /**
         * Maximum time allowed for a single [chat] round.
         * Reduced from 2 min to 45 sec for faster feedback to user.
         */
        private const val INFERENCE_TIMEOUT_MS = 45_000L // 45 sec

        /** Minimum free RAM required before loading a model (512 MB). */
        private const val MIN_MEMORY_HEADROOM_BYTES = 512L * 1024 * 1024

        /** Extra disk space buffer beyond model file size needed before download (500 MB). */
        private const val DOWNLOAD_SPACE_BUFFER_BYTES = 500L * 1024 * 1024

        /**
         * Milliseconds to wait after closing the previous engine before allocating a new one.
         * Reduced from 750ms to 200ms for faster re-initialization.
         */
        private const val GPU_DRAIN_DELAY_MS = 200L

        /** I/O buffer size for the download stream (64 KB). */
        private const val DOWNLOAD_BUFFER_SIZE = 65_536

        /** Minimum fraction of expected bytes for a download to be considered complete. */
        private const val DOWNLOAD_COMPLETENESS_THRESHOLD = 0.95

        /** Minimum file size (1 MB) considered a valid model file. */
        private const val MIN_MODEL_FILE_BYTES = 1_000_000L

        /** Notification channel ID for model download progress. */
        private const val DOWNLOAD_CHANNEL_ID = "zero_assist_model_download"

        /** Notification ID for the ongoing download notification. */
        const val DOWNLOAD_NOTIFICATION_ID = 8042
    }
}
