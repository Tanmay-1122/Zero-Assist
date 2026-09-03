/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import android.util.Log
import com.zeroclaw.android.data.model.CanvasFrame
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.util.concurrent.TimeUnit

private const val TAG = "CanvasWebSocketClient"

/**
 * WebSocket client for real-time Canvas rendering.
 *
 * Maintains a persistent connection to the ZeroClaw Canvas API endpoint
 * and streams frame updates to a StateFlow for UI consumption.
 *
 * Handles automatic reconnection with exponential backoff on disconnect.
 *
 * @param baseUrl Base URL of the ZeroClaw API (e.g., "http://localhost:8080").
 * @param canvasId The Canvas ID to subscribe to.
 * @param scope CoroutineScope for async operations.
 */
class CanvasWebSocketClient(
    private val baseUrl: String,
    private val canvasId: String,
    private val scope: CoroutineScope,
) : WebSocketListener() {

    private val client = OkHttpClient.Builder()
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .connectTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private var webSocket: WebSocket? = null
    private var reconnectAttempts = 0
    private var isManuallyClosed = false

    private val _frames = MutableStateFlow<List<CanvasFrame>>(emptyList())
    val frames: StateFlow<List<CanvasFrame>> = _frames.asStateFlow()

    private val _currentFrame = MutableStateFlow<CanvasFrame?>(null)
    val currentFrame: StateFlow<CanvasFrame?> = _currentFrame.asStateFlow()

    private val _isConnected = MutableStateFlow(false)
    val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /**
     * Connect to the Canvas WebSocket endpoint.
     */
    fun connect() {
        if (isManuallyClosed) {
            isManuallyClosed = false
            reconnectAttempts = 0
        }

        val wsUrl = baseUrl.replace("http://", "ws://").replace("https://", "wss://")
        val url = "$wsUrl/ws/canvas/$canvasId"

        Log.d(TAG, "Connecting to Canvas: $url")

        val request = Request.Builder()
            .url(url)
            .build()

        webSocket = client.newWebSocket(request, this)
        client.dispatcher.executorService.shutdown()
    }

    /**
     * Disconnect from the WebSocket.
     */
    fun disconnect() {
        isManuallyClosed = true
        webSocket?.close(1000, "User closed")
        webSocket = null
        _isConnected.value = false
    }

    /**
     * Called when the WebSocket connection is successfully established.
     */
    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
        Log.d(TAG, "Canvas WebSocket opened: $canvasId")
        _isConnected.value = true
        _error.value = null
        reconnectAttempts = 0
    }

    /**
     * Called when a message is received from the server.
     */
    override fun onMessage(webSocket: WebSocket, text: String) {
        scope.launch(Dispatchers.Default) {
            try {
                val frame = json.decodeFromString<CanvasFrame>(text)
                Log.d(TAG, "Received frame: ${frame.frameId} (${frame.contentType})")

                _frames.value = _frames.value + frame
                _currentFrame.value = frame
                _error.value = null
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse frame: ${e.message}")
                _error.value = "Failed to parse frame: ${e.message}"
            }
        }
    }

    /**
     * Called when the WebSocket connection closes.
     */
    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
        Log.d(TAG, "Canvas WebSocket closing: code=$code, reason=$reason")
        _isConnected.value = false
    }

    /**
     * Called when the WebSocket connection has closed.
     */
    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
        Log.d(TAG, "Canvas WebSocket closed: code=$code, reason=$reason")
        _isConnected.value = false

        if (!isManuallyClosed && code != 1000) {
            scheduleReconnect()
        }
    }

    /**
     * Called when an error occurs on the WebSocket.
     */
    override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
        Log.e(TAG, "Canvas WebSocket error: ${t.message}")
        _isConnected.value = false
        _error.value = t.message ?: "Unknown error"

        if (!isManuallyClosed) {
            scheduleReconnect()
        }
    }

    /**
     * Called when a binary message is received (not used for Canvas).
     */
    override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
        // Canvas only sends text frames
    }

    /**
     * Schedule a reconnection attempt with exponential backoff.
     */
    private fun scheduleReconnect() {
        if (isManuallyClosed) return

        reconnectAttempts++
        val delayMs = minOf(1000L * (1 shl reconnectAttempts), 30000L) // Max 30s

        Log.d(TAG, "Scheduling reconnect in ${delayMs}ms (attempt $reconnectAttempts)")

        scope.launch {
            kotlinx.coroutines.delay(delayMs)
            if (!isManuallyClosed && !_isConnected.value) {
                connect()
            }
        }
    }

    /**
     * Clear all frames for this canvas.
     */
    fun clear() {
        _frames.value = emptyList()
        _currentFrame.value = null
    }
}
