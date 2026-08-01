/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zeroclaw.android.ZeroClawApplication
import com.zeroclaw.android.service.DownloadError
import com.zeroclaw.android.service.DownloadedModel
import com.zeroclaw.android.service.EngineState
import com.zeroclaw.android.service.LocalInferenceEngine
import com.zeroclaw.android.service.LocalModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * UI state representing the combined snapshot of the LiteRT model catalog screen.
 *
 * @property catalog         Full list of models available for download.
 * @property downloadedModels Models that have been fully downloaded to the device.
 * @property engineState     Current lifecycle state of the inference engine.
 * @property loadedModelId   ID of the model currently loaded in the engine, or `null`.
 * @property downloadingId   ID of the model currently downloading, or `null`.
 * @property downloadProgress Download progress in [0.0, 1.0], or `null`.
 * @property downloadError   Most recent download failure reason, or `null`.
 * @property freeSpaceBytes  Free bytes available in the model storage directory.
 * @property errorMessage    Non-null when an operation (e.g., model init) failed.
 */
data class LiteRTUiState(
    val catalog: List<LocalModel> = emptyList(),
    val downloadedModels: List<DownloadedModel> = emptyList(),
    val engineState: EngineState = EngineState.UNINITIALIZED,
    val loadedModelId: String? = null,
    val downloadingId: String? = null,
    val downloadProgress: Float? = null,
    val downloadError: DownloadError? = null,
    val freeSpaceBytes: Long = 0L,
    val totalMemoryBytes: Long = 0L,
    val errorMessage: String? = null,
)

/**
 * ViewModel for the on-device LiteRT LM model management screen.
 *
 * Merges four independent [StateFlow]s from [LocalInferenceEngine] into a
 * single [LiteRTUiState] stream consumed by [LiteRTModelsScreen]. All
 * mutable engine operations (download, delete, load) are dispatched through
 * coroutines on [viewModelScope] so the UI never blocks.
 *
 * @param application Application context for accessing ZeroClawApplication.
 */
class LiteRTViewModel(
    application: Application,
) : AndroidViewModel(application) {
    private val app = application as ZeroClawApplication
    private val engine: LocalInferenceEngine = app.liteRtInferenceEngine

    private val _errorMessage = MutableStateFlow<String?>(null)

    /**
     * Merged UI state combining engine streams and local error signal.
     *
     * Stays alive while any subscriber is active (5 s grace period after last
     * subscriber drops to survive config changes).
     */
    val uiState: StateFlow<LiteRTUiState> =
        combine(
            engine.engineState,
            engine.downloadingModelId,
            engine.downloadProgress,
            engine.downloadError,
            _errorMessage,
        ) { engineState, downloadingId, downloadProgress, downloadError, error ->
            LiteRTUiState(
                catalog = engine.getAvailableModels(),
                downloadedModels = engine.getDownloadedModels(),
                engineState = engineState,
                loadedModelId = engine.currentModelId,
                downloadingId = downloadingId,
                downloadProgress = downloadProgress,
                downloadError = downloadError,
                freeSpaceBytes = engine.getFreeSpaceBytes(),
                totalMemoryBytes = engine.totalMemoryBytes,
                errorMessage = error,
            )
        }.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            initialValue = LiteRTUiState(
                catalog = engine.getAvailableModels(),
                downloadedModels = engine.getDownloadedModels(),
                freeSpaceBytes = engine.getFreeSpaceBytes(),
                totalMemoryBytes = engine.totalMemoryBytes,
            ),
        )

    /** Observable pass-through — needed for screens collecting engineState directly. */
    val engineState: StateFlow<EngineState> = engine.engineState

    // ---------------------------------------------------------------------------
    // Download operations
    // ---------------------------------------------------------------------------

    /**
     * Starts downloading [model] from HuggingFace, showing progress in [uiState].
     *
     * No-op if another download is already running (the engine handles this via
     * [LocalInferenceEngine.cancelDownload] → [LocalInferenceEngine.startDownload]).
     */
    fun downloadModel(model: LocalModel) {
        clearError()
        engine.startDownload(model)
    }

    /** Cancels any in-progress model download. */
    fun cancelDownload() {
        engine.cancelDownload()
    }

    /**
     * Deletes the downloaded files for [modelId] from disk, releasing the engine
     * first if this model is currently loaded.
     */
    fun deleteModel(modelId: String) {
        clearError()
        viewModelScope.launch {
            runCatching { engine.deleteModel(modelId) }
                .onFailure { _errorMessage.value = it.message ?: "Delete failed" }
        }
    }

    // ---------------------------------------------------------------------------
    // Engine operations
    // ---------------------------------------------------------------------------

    /**
     * Initialises the inference engine with [model] and [contextTokens].
     *
     * Updates [uiState.errorMessage] if initialisation fails (e.g. out-of-memory).
     */
    fun loadModel(model: DownloadedModel, contextTokens: Int = 0) {
        clearError()
        viewModelScope.launch {
            runCatching { engine.initialize(model, contextTokens) }
                .onFailure { _errorMessage.value = it.message ?: "Failed to load model" }
        }
    }

    /** Releases the currently loaded engine and frees GPU/CPU memory. */
    fun unloadModel() {
        engine.releaseInBackground()
    }

    /** Dismisses a displayed [LiteRTUiState.errorMessage]. */
    fun clearError() {
        _errorMessage.value = null
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
    }
}
