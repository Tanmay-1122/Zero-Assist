/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Foreground-service wake phrase detector backed by the local on-device speech recognizer.
 *
 * This does not use a cloud hotword service. It listens only while the guarded foreground service
 * is active, matches short English wake phrases locally, emits a wake event, then releases the mic.
 */
class LocalSpeechWakeupDetector(
    private val localSpeechRecognizer: LocalSpeechRecognizer,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Main.immediate,
    wakePhrases: Set<String> = DEFAULT_WAKE_PHRASES,
) : VoiceWakeupDetector {
    private val matcher = WakePhraseMatcher(wakePhrases)
    private val _status = MutableStateFlow(localSpeechRecognizer.status.value.toWakeupStatus())
    private val _wakeEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    private var wakeJob: Job? = null

    override val status = _status.asStateFlow()
    override val wakeEvents = _wakeEvents.asSharedFlow()

    init {
        scope.launch(dispatcher) {
            localSpeechRecognizer.status.collect { speechStatus ->
                _status.value = speechStatus.toWakeupStatus()
            }
        }
    }

    override suspend fun startForegroundWakeup(): VoiceWakeupStartResult =
        withContext(dispatcher) {
            val wakeupStatus = localSpeechRecognizer.status.value.toWakeupStatus()
            _status.value = wakeupStatus
            if (!wakeupStatus.available || !wakeupStatus.foregroundServiceReady) {
                return@withContext VoiceWakeupStartResult.Unavailable(wakeupStatus.message)
            }
            if (wakeJob?.isActive == true) {
                return@withContext VoiceWakeupStartResult.Started
            }

            wakeJob =
                scope.launch(dispatcher) {
                    listenForWakePhrase()
                }
            VoiceWakeupStartResult.Started
        }

    override suspend fun stopForegroundWakeup() {
        withContext(dispatcher) {
            val job = wakeJob
            wakeJob = null
            localSpeechRecognizer.stop()
            job?.cancelAndJoin()
        }
    }

    private suspend fun listenForWakePhrase() {
        var restartBackoffMs = RESTART_BACKOFF_INITIAL_MS
        while (currentCoroutineContext().isActive) {
            var wakePhraseDetected = false
            var transcriptObserved = false
            localSpeechRecognizer
                .listen()
                .catch {
                    transcriptObserved = false
                }
                .collect { event ->
                    val transcript =
                        when (event) {
                            is SpeechRecognitionEvent.PartialTranscript -> event.text
                            is SpeechRecognitionEvent.FinalTranscript -> event.text
                            is SpeechRecognitionEvent.Error -> null
                        }
                    if (transcript != null) {
                        transcriptObserved = true
                    }
                    if (transcript != null && matcher.matches(transcript)) {
                        wakePhraseDetected = true
                        _wakeEvents.tryEmit(Unit)
                        localSpeechRecognizer.stop()
                    }
                }
            if (wakePhraseDetected) {
                break
            }
            delay(restartBackoffMs)
            restartBackoffMs =
                if (transcriptObserved) {
                    RESTART_BACKOFF_INITIAL_MS
                } else {
                    (restartBackoffMs * 2).coerceAtMost(RESTART_BACKOFF_MAX_MS)
                }
        }
    }

    private class WakePhraseMatcher(
        wakePhrases: Set<String>,
    ) {
        private val normalizedPhrases = wakePhrases.map { phrase -> normalize(phrase) }

        fun matches(transcript: String): Boolean {
            val normalized = normalize(transcript)
            return normalizedPhrases.any { phrase -> normalized.contains(phrase) }
        }

        private fun normalize(value: String): String =
            value
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), " ")
                .trim()
                .replace(Regex("\\s+"), " ")
    }

    private companion object {
        val DEFAULT_WAKE_PHRASES = setOf(
            "hey zero",
            "hey zero assist",
            "zero assist",
        )
        const val RESTART_BACKOFF_INITIAL_MS = 1_500L
        const val RESTART_BACKOFF_MAX_MS = 8_000L
    }
}

private fun LocalSpeechEngineStatus.toWakeupStatus(): VoiceWakeupDetectorStatus =
    when (this) {
        LocalSpeechEngineStatus.Initializing ->
            VoiceWakeupDetectorStatus(
                available = false,
                foregroundServiceReady = false,
                requiresRecordAudioPermission = true,
                message = "Local wake phrase detector is still starting.",
            )
        LocalSpeechEngineStatus.Ready ->
            VoiceWakeupDetectorStatus(
                available = true,
                foregroundServiceReady = true,
                requiresRecordAudioPermission = true,
                message = "Local wake phrase detector is ready.",
            )
        LocalSpeechEngineStatus.MissingModel ->
            VoiceWakeupDetectorStatus(
                available = false,
                foregroundServiceReady = false,
                requiresRecordAudioPermission = true,
                message = "Install an offline English speech recognition service on this phone.",
            )
        LocalSpeechEngineStatus.PermissionRequired ->
            VoiceWakeupDetectorStatus(
                available = true,
                foregroundServiceReady = true,
                requiresRecordAudioPermission = true,
                message = "Microphone permission is required for wake-up mode.",
            )
        is LocalSpeechEngineStatus.Unavailable ->
            VoiceWakeupDetectorStatus(
                available = false,
                foregroundServiceReady = false,
                requiresRecordAudioPermission = true,
                message = reason,
            )
    }
