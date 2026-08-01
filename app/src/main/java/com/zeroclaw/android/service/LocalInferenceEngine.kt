/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service

import kotlinx.coroutines.flow.StateFlow

// ---------------------------------------------------------------------------
// Data models
// ---------------------------------------------------------------------------

/**
 * A model available in the LiteRT LM catalog that can be downloaded and run
 * entirely on-device without any cloud connectivity.
 *
 * @property id            Unique stable identifier used for directory names and preferences.
 * @property displayName   Human-readable label shown in UI.
 * @property fileName      The `.litertlm` file name inside the model's storage sub-directory.
 * @property sizeBytes     Expected download size in bytes (used for disk-space checks).
 * @property downloadUrl   Direct URL to the `.litertlm` model file on HuggingFace.
 * @property gpuMemoryMb   Approximate GPU working-set overhead in MB beyond the raw file size.
 * @property defaultContextTokens  Default KV-cache context window used at initialization.
 * @property maxContextTokens      Upper bound advertised by the model.
 * @property kvPerTokenBytes       Additional memory (bytes) per *extra* context token beyond default.
 * @property isRecommended         Whether the model is highlighted as the recommended choice.
 */
data class LocalModel(
    val id: String,
    val displayName: String,
    val fileName: String,
    val sizeBytes: Long,
    val downloadUrl: String,
    val gpuMemoryMb: Int,
    val defaultContextTokens: Int,
    val maxContextTokens: Int,
    val kvPerTokenBytes: Int,
    val isRecommended: Boolean = false,
)

/**
 * A [LocalModel] that has already been downloaded to the device's local storage.
 *
 * @property id          The model's stable identifier (matches [LocalModel.id]).
 * @property displayName Human-readable label.
 * @property filePath    Absolute path to the `.litertlm` file on disk.
 * @property sizeBytes   Actual size of the file on disk.
 */
data class DownloadedModel(
    val id: String,
    val displayName: String,
    val filePath: String,
    val sizeBytes: Long,
)

/**
 * A single message in a multi-turn conversation handed to the inference engine.
 *
 * @property role    Either `"user"` or `"model"`.
 * @property content The text body of this turn.
 */
data class InferenceMessage(
    val role: String,
    val content: String,
)

/**
 * A tool definition that the on-device model may invoke during inference.
 *
 * @property name                  The tool's identifier as the model will see it.
 * @property descriptionJsonString A complete OpenAPI-style JSON object describing the tool.
 * @property execute               Receives JSON-encoded arguments and returns the JSON result.
 */
data class LocalTool(
    val name: String,
    val descriptionJsonString: String,
    val execute: suspend (jsonArgs: String) -> String,
)

// ---------------------------------------------------------------------------
// Engine lifecycle states
// ---------------------------------------------------------------------------

/** Lifecycle state of a [LocalInferenceEngine] instance. */
enum class EngineState {
    /** Engine has not been initialized with a model yet. */
    UNINITIALIZED,

    /** Engine is loading the model into memory. */
    INITIALIZING,

    /** Engine is ready to accept inference requests. */
    READY,

    /** Engine encountered an unrecoverable error during initialization. */
    ERROR,
}

/** Device-relative performance tier for a given model / context-token combination. */
enum class DevicePerformance {
    GOOD,
    OK,
    POOR,
}

// ---------------------------------------------------------------------------
// Error types
// ---------------------------------------------------------------------------

/** Thrown when the device does not have enough free RAM to load the model. */
class InsufficientMemoryException : Exception("Not enough memory to load model")

/** Thrown when inference does not complete within the allotted timeout. */
class InferenceTimeoutException : Exception("Inference timed out")

/** Thrown when the user requests inference but no model has been downloaded yet. */
class NoModelDownloadedException : Exception("No on-device model downloaded")

/** Reasons why a model download may fail. */
enum class DownloadError {
    NOT_ENOUGH_DISK_SPACE,
    NETWORK_ERROR,
    DOWNLOAD_INCOMPLETE,
}

// ---------------------------------------------------------------------------
// Memory / GPU estimation helpers
// ---------------------------------------------------------------------------

/**
 * Estimates GPU memory (MB) required for [model] with the given [contextTokens].
 *
 * Combines the raw file size, base GPU overhead, and the KV-cache cost of any
 * extra context tokens beyond the model's default window.
 */
fun estimateGpuMemoryMb(model: LocalModel, contextTokens: Int): Int {
    val modelFileMb = (model.sizeBytes / (1024 * 1024)).toInt()
    val extraTokens = contextTokens - model.defaultContextTokens
    val extraMemoryMb = (extraTokens.toLong() * model.kvPerTokenBytes) / (1024 * 1024)
    return modelFileMb + model.gpuMemoryMb + extraMemoryMb.toInt()
}

/**
 * Classifies device performance for [estimatedGpuMemoryMb] given total device RAM.
 *
 * The thresholds mirror Kai's heuristics:
 * - ≥ 2.5× headroom → [DevicePerformance.GOOD]
 * - ≥ 1.85× headroom → [DevicePerformance.OK]
 * - otherwise → [DevicePerformance.POOR]
 */
fun calculateDevicePerformance(totalMemoryBytes: Long, estimatedGpuMemoryMb: Int): DevicePerformance {
    val gpuMemoryBytes = estimatedGpuMemoryMb.toLong() * 1024 * 1024
    val ratio = totalMemoryBytes.toDouble() / gpuMemoryBytes
    return when {
        ratio >= 2.5 -> DevicePerformance.GOOD
        ratio >= 1.85 -> DevicePerformance.OK
        else -> DevicePerformance.POOR
    }
}

// ---------------------------------------------------------------------------
// Interface
// ---------------------------------------------------------------------------

/**
 * Common contract for an on-device language-model inference engine powered by
 * the LiteRT LM SDK.
 *
 * Implementations manage engine lifecycle (init / release), model downloads,
 * and multi-turn chat inference with optional tool calling.
 *
 * **Thread safety:** All suspend functions must be safe to call from the main thread;
 * implementations are responsible for dispatching to an appropriate background dispatcher.
 */
interface LocalInferenceEngine {
    // ---- Observable state ------------------------------------------------

    /** Current engine lifecycle state. */
    val engineState: StateFlow<EngineState>

    /** ID of the model currently being downloaded, or `null` if no download is active. */
    val downloadingModelId: StateFlow<String?>

    /** Download progress in [0.0, 1.0], or `null` when no download is active. */
    val downloadProgress: StateFlow<Float?>

    /** Reason for the most recent download failure, or `null` if none. */
    val downloadError: StateFlow<DownloadError?>

    /** ID of the model currently loaded into the engine, or `null`. */
    val currentModelId: String?

    /** Observable flow of the currently loaded model ID. */
    val currentModelIdFlow: StateFlow<String?>

    /** The device's total physical RAM in bytes. */
    val totalMemoryBytes: Long

    // ---- Engine lifecycle ------------------------------------------------

    /**
     * Loads [model] into the inference engine with an optional [contextTokens] window.
     *
     * If a different model is already loaded it will be released first. GPU drain
     * delay is applied between releases to allow the OpenCL driver to reclaim memory.
     *
     * @throws InsufficientMemoryException if the device does not have enough free RAM.
     */
    suspend fun initialize(model: DownloadedModel, contextTokens: Int = 0)

    /** Releases the loaded model and frees all associated native resources. */
    suspend fun release()

    /**
     * Fire-and-forget background release. Suitable for non-suspend callers (e.g.
     * UI button handlers) that need to free GPU memory before the next model load.
     */
    fun releaseInBackground()

    // ---- Inference -------------------------------------------------------

    /**
     * Sends a multi-turn [messages] list to the loaded model and returns the
     * complete response text.
     *
     * The last `"user"` message in [messages] is sent as the new turn; earlier
     * messages provide history context. Optional [tools] enable the model to call
     * back into the app for structured data.
     *
     * @throws IllegalStateException if the engine is not in [EngineState.READY].
     * @throws InferenceTimeoutException if the model takes too long to respond.
     */
    suspend fun chat(
        messages: List<InferenceMessage>,
        systemPrompt: String?,
        tools: List<LocalTool> = emptyList(),
    ): String

    // ---- Model management ------------------------------------------------

    /** Returns all catalog models that have been fully downloaded to the device. */
    fun getDownloadedModels(): List<DownloadedModel>

    /** Returns the full model catalog regardless of download state. */
    fun getAvailableModels(): List<LocalModel>

    /** Returns the number of bytes free in the model storage directory. */
    fun getFreeSpaceBytes(): Long

    /**
     * Starts downloading [model] in the background, updating [downloadingModelId],
     * [downloadProgress], and [downloadError] as the transfer progresses.
     */
    fun startDownload(model: LocalModel)

    /** Cancels any in-progress download and cleans up the partial file. */
    fun cancelDownload()

    /** Deletes the downloaded model files for the given [modelId] from disk. */
    suspend fun deleteModel(modelId: String)
}
