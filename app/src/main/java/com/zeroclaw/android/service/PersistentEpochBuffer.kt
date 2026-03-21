/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import android.content.Context
import android.util.Log
import com.zeroclaw.android.model.BufferedMessage
import com.zeroclaw.android.util.LogSanitizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A persistent ring-buffer for storing outgoing messages with epoch identifiers.
 *
 * Ensures "Zero-Message-Drop" (ZMD) by persisting messages to internal storage
 * before attempting delivery to the native daemon. If the daemon crashes or
 * the Android process is killed, undelivered messages are replayed in order
 * on the next successful daemon startup.
 *
 * The buffer uses a fixed-capacity ring structure (oldest messages dropped if
 * full) and stores its state as a JSON file in the application's private
 * files directory.
 *
 * @param context Application or service context for file access.
 * @param maxCapacity Maximum number of messages to keep in the buffer.
 *   Defaults to 512.
 */
class PersistentEpochBuffer(
    context: Context,
    private val maxCapacity: Int = DEFAULT_MAX_CAPACITY,
) {
    private val currentFile = File(context.filesDir, "zmd_epoch_current.json")
    private val backupFile = File(context.filesDir, "zmd_epoch_backup.json")
    private val json = Json { ignoreUnknownKeys = true }

    // Lazy load to avoid I/O on the main thread during instantiation
    private val messages = mutableListOf<BufferedMessage>()
    private val lock = Any()

    /** Flow indicating if there are pending messages in the buffer. */
    private val _hasPending = MutableStateFlow(false)

    /** Public state flow of [hasPending] status. */
    val hasPending: StateFlow<Boolean> = _hasPending.asStateFlow()

    init {
        load()
        updateState()
    }

    /**
     * Enqueues a new message with a unique epoch identifier.
     *
     * Persists the updated buffer to disk synchronously to guarantee durability
     * before the calling layer attempts delivery.
     *
     * @param text The raw message content.
     * @return The epoch identifier assigned to the message.
     */
    fun enqueue(text: String): Long {
        val epoch = System.currentTimeMillis()
        synchronized(lock) {
            messages.add(BufferedMessage(epoch = epoch, text = text))
            if (messages.size > maxCapacity) {
                val dropped = messages.removeAt(0)
                Log.w(TAG, "Buffer full — dropping oldest message (epoch ${dropped.epoch})")
            }
        }
        save()
        updateState()
        return epoch
    }

    /**
     * Removes a message from the buffer after successful delivery.
     *
     * @param epoch The identifier of the message to remove.
     */
    fun dequeue(epoch: Long) {
        synchronized(lock) {
            if (messages.removeAll { it.epoch == epoch }) {
                save()
                updateState()
            }
        }
    }

    /**
     * Removes a message from the buffer after successful delivery.
     *
     * @param id The identifier of the message to remove.
     */
    fun remove(id: String) {
        synchronized(lock) {
            if (messages.removeAll { it.id == id }) {
                save()
                updateState()
            }
        }
    }


    /**
     * Clears all messages from the buffer and persists the empty state.
     */
    fun clear() {
        synchronized(lock) {
            messages.clear()
        }
        save()
        updateState()
    }

    /**
     * Returns all currently buffered messages in chronological order.
     *
     * @return A list of [BufferedMessage] objects.
     */
    fun getAll(): List<BufferedMessage> = synchronized(lock) { messages.toList() }

    private fun save() {
        try {
            val content = synchronized(lock) { json.encodeToString(messages) }
            // Write to backup first
            if (currentFile.exists()) {
                currentFile.copyTo(backupFile, overwrite = true)
            }
            currentFile.writeText(content)
        } catch (e: Exception) {
            when (e) {
                is java.io.IOException, is kotlinx.serialization.SerializationException -> {
                    val safeMsg = LogSanitizer.sanitizeLogMessage(e.message ?: "Unknown I/O error")
                    Log.e(TAG, "Failed to save message buffer: $safeMsg")
                }
                else -> throw e
            }
        }
    }

    private fun load() {
        try {
            if (!currentFile.exists() && backupFile.exists()) {
                backupFile.copyTo(currentFile)
            }
            if (currentFile.exists()) {
                val content = currentFile.readText()
                val loaded = json.decodeFromString<List<BufferedMessage>>(content)
                synchronized(lock) {
                    messages.clear()
                    messages.addAll(loaded)
                }
            }
        } catch (e: Exception) {
            when (e) {
                is java.io.IOException, is kotlinx.serialization.SerializationException -> {
                    val safeMsg = LogSanitizer.sanitizeLogMessage(e.message ?: "Unknown I/O error")
                    Log.e(TAG, "Failed to load message buffer: $safeMsg")
                }
                else -> throw e
            }
            // If current is corrupt, try backup
            if (backupFile.exists()) {
                try {
                    val content = backupFile.readText()
                    val loaded = json.decodeFromString<List<BufferedMessage>>(content)
                    synchronized(lock) {
                        messages.clear()
                        messages.addAll(loaded)
                    }
                } catch (e2: Exception) {
                    when (e2) {
                        is java.io.IOException, is kotlinx.serialization.SerializationException -> {
                            val safeMsg2 =
                                LogSanitizer.sanitizeLogMessage(e2.message ?: "Unknown I/O error")
                            Log.e(TAG, "Failed to load backup message buffer: $safeMsg2")
                        }
                        else -> throw e2
                    }
                }
            }
        }
    }

    private fun updateState() {
        _hasPending.value = synchronized(lock) { messages.isNotEmpty() }
    }

    /** Companion object for [PersistentEpochBuffer] constants. */
    companion object {
        private const val TAG = "PersistentEpochBuffer"
        private const val DEFAULT_MAX_CAPACITY = 512
    }
}
