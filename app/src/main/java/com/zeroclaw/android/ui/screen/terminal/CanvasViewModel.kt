/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.terminal

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.zeroclaw.android.data.model.CanvasFrame
import com.zeroclaw.android.service.CanvasWebSocketClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

private const val TAG = "CanvasViewModel"

/**
 * ViewModel for managing Canvas visualization.
 *
 * Maintains connections to multiple Canvas WebSocket endpoints, manages
 * frame history, and provides UI state for rendering sessions.
 *
 * @param application Application context.
 */
class CanvasViewModel(application: Application) : AndroidViewModel(application) {

    private val _activeCanvas = MutableStateFlow<String?>(null)
    val activeCanvas: StateFlow<String?> = _activeCanvas.asStateFlow()

    private val clients = mutableMapOf<String, CanvasWebSocketClient>()

    private val _currentFrames = MutableStateFlow<List<CanvasFrame>>(emptyList())
    val currentFrames: StateFlow<List<CanvasFrame>> = _currentFrames.asStateFlow()

    private val _displayFrame = MutableStateFlow<CanvasFrame?>(null)
    val displayFrame: StateFlow<CanvasFrame?> = _displayFrame.asStateFlow()

    private val _frameIndex = MutableStateFlow(0)
    val frameIndex: StateFlow<Int> = _frameIndex.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * Subscribe to a Canvas session. Automatically manages WebSocket connection.
     *
     * @param canvasId The Canvas ID to subscribe to.
     * @param baseUrl The base URL of the ZeroClaw API.
     */
    fun subscribeToCanvas(canvasId: String, baseUrl: String = "http://localhost:8080") {
        Log.d(TAG, "Subscribing to Canvas: $canvasId")

        _activeCanvas.value = canvasId

        // Reuse existing client if available
        if (clients.containsKey(canvasId)) {
            val client = clients[canvasId]!!
            _isConnected.value = client.isConnected.value
            return
        }

        val client = CanvasWebSocketClient(baseUrl, canvasId, viewModelScope)

        viewModelScope.launch {
            combine(
                client.frames,
                client.currentFrame,
                client.isConnected,
                client.error,
            ) { frames, currentFrame, isConnected, error ->
                _currentFrames.value = frames
                _displayFrame.value = currentFrame
                _isConnected.value = isConnected
                if (error != null) _error.value = error
            }.collect { }
        }

        clients[canvasId] = client
        client.connect()
    }

    /**
     * Unsubscribe from the current Canvas session.
     */
    fun unsubscribeFromCanvas() {
        val canvasId = _activeCanvas.value ?: return
        Log.d(TAG, "Unsubscribing from Canvas: $canvasId")

        clients[canvasId]?.disconnect()
        _activeCanvas.value = null
        _currentFrames.value = emptyList()
        _displayFrame.value = null
        _frameIndex.value = 0
    }

    /**
     * Navigate to a specific frame in the history.
     *
     * @param index Index of the frame to display.
     */
    fun navigateToFrame(index: Int) {
        val frames = _currentFrames.value
        if (index >= 0 && index < frames.size) {
            _frameIndex.value = index
            _displayFrame.value = frames[index]
        }
    }

    /**
     * Navigate to the next frame.
     */
    fun nextFrame() {
        val frames = _currentFrames.value
        val nextIndex = (_frameIndex.value + 1).coerceAtMost(frames.size - 1)
        navigateToFrame(nextIndex)
    }

    /**
     * Navigate to the previous frame.
     */
    fun previousFrame() {
        val frames = _currentFrames.value
        val prevIndex = (_frameIndex.value - 1).coerceAtLeast(0)
        navigateToFrame(prevIndex)
    }

    /**
     * Jump to the latest frame.
     */
    fun goToLatest() {
        val frames = _currentFrames.value
        if (frames.isNotEmpty()) {
            navigateToFrame(frames.size - 1)
        }
    }

    /**
     * Clear the current Canvas session.
     */
    fun clearCanvas() {
        val canvasId = _activeCanvas.value ?: return
        val client = clients[canvasId]
        client?.clear()
    }

    override fun onCleared() {
        super.onCleared()
        clients.values.forEach { it.disconnect() }
        clients.clear()
    }
}
