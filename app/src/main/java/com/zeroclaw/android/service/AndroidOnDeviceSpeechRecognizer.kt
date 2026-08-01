/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.AppOpsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import java.util.Locale
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Android STT driver for the assistant popup.
 *
 * Popup dictation can opt into the installed system recognizer for better
 * one-shot accuracy. Strict local consumers, such as wake-word detection, keep
 * the on-device/offline preference.
 */
class AndroidOnDeviceSpeechRecognizer(
    context: Context,
    private val allowNetworkRecognition: Boolean = false,
) : LocalSpeechRecognizer {
    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(AudioManager::class.java)
    private val appOpsManager = appContext.getSystemService(AppOpsManager::class.java)
    private val _status = MutableStateFlow(resolveStatus())

    override val status = _status.asStateFlow()

    @Volatile
    private var activeSession: ActiveRecognitionSession? = null

    @Volatile
    private var activeAudioFocusSession: AudioFocusSession? = null

    @SuppressLint("MissingPermission")
    override fun listen(): Flow<SpeechRecognitionEvent> =
        listen(VoiceTurnTrace.noop())

    @SuppressLint("MissingPermission")
    override fun listen(trace: VoiceTurnTrace): Flow<SpeechRecognitionEvent> =
        callbackFlow {
            trace.mark("recognizer_listen_requested", "network=$allowNetworkRecognition")
            val readiness = refreshStatus()
            if (readiness != LocalSpeechEngineStatus.Ready) {
                trace.mark("recognizer_not_ready", readiness.javaClass.simpleName)
                trySend(SpeechRecognitionEvent.Error(readiness.message()))
                trace.complete("recognizer_not_ready")
                close()
                return@callbackFlow
            }

            val recognizerSession = createRecognizer()
            if (recognizerSession == null) {
                _status.value = LocalSpeechEngineStatus.MissingModel
                trace.mark("recognizer_missing_service")
                trySend(SpeechRecognitionEvent.Error(_status.value.message()))
                trace.complete("recognizer_missing_service")
                close()
                return@callbackFlow
            }

            val preflight = microphonePreflight()
            trace.mark("mic_preflight", preflight.detail)
            if (preflight.blockedMessage != null) {
                recognizerSession.recognizer.destroy()
                trySend(SpeechRecognitionEvent.Error(preflight.blockedMessage))
                trace.complete("mic_preflight_blocked")
                close()
                return@callbackFlow
            }

            val audioFocusSession = requestSpeechRecognitionAudioFocus(trace)
            if (audioFocusSession == null) {
                recognizerSession.recognizer.destroy()
                trySend(
                    SpeechRecognitionEvent.Error(
                        "The microphone is busy with another audio session. Try again in a moment.",
                    ),
                )
                trace.complete("audio_focus_denied")
                close()
                return@callbackFlow
            }
            setActiveAudioFocus(audioFocusSession)

            val languageTags = recognizerLanguageTags().iterator()
            var latestPartialTranscript = ""
            var speechBegan = false
            var noSpeechRetryUsed = false
            var networkOfflineRetryUsed = false

            fun startRecognizerAttempt(session: RecognitionSession): Boolean {
                if (!languageTags.hasNext()) {
                    session.recognizer.destroy()
                    return false
                }
                val languageTag = languageTags.next()
                val recognizer = session.recognizer
                val activeRecognitionSession =
                    ActiveRecognitionSession(
                        recognizer = recognizer,
                        languageTag = languageTag,
                    )
                speechBegan = false
                activeSession = activeRecognitionSession
                trace.mark(
                    "recognizer_starting",
                    "language=${languageTag ?: "default"} offline=${session.preferOffline}",
                )
                recognizer.setRecognitionListener(
                    object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            if (!isCurrentSession(activeRecognitionSession)) return
                            Log.d(TAG, "Speech recognizer ready")
                            trace.markOnce("recognizer_ready")
                        }

                        override fun onBeginningOfSpeech() {
                            if (!isCurrentSession(activeRecognitionSession)) return
                            Log.d(TAG, "Speech input began")
                            speechBegan = true
                            trace.markOnce("speech_begin")
                        }

                        override fun onRmsChanged(rmsdB: Float) = Unit

                        override fun onBufferReceived(buffer: ByteArray?) = Unit

                        override fun onEndOfSpeech() {
                            if (!isCurrentSession(activeRecognitionSession)) return
                            Log.d(TAG, "Speech input ended")
                            trace.markOnce("speech_end")
                        }

                        override fun onError(error: Int) {
                            if (!isCurrentSession(activeRecognitionSession)) {
                                trace.mark("recognizer_late_error_ignored", "code=$error")
                                return
                            }
                            Log.w(TAG, "Speech recognizer error code=$error")
                            trace.mark("recognizer_error", "code=$error")
                            val fallbackTranscript = latestPartialTranscript.trim()
                            if (shouldPromotePartialTranscriptAfterRecognizerError(error, speechBegan, fallbackTranscript)) {
                                releaseRecognizer(recognizer, cancel = false)
                                Log.d(TAG, "Using partial speech transcript after recognizer error")
                                trace.mark(
                                    "recognizer_partial_promoted_after_error",
                                    "code=$error lowConfidence=true",
                                )
                                trySend(SpeechRecognitionEvent.FinalTranscript(fallbackTranscript))
                                close()
                                return
                            }
                            if (activeRecognitionSession.isAppInitiatedStop && error == SpeechRecognizer.ERROR_CLIENT) {
                                releaseRecognizer(recognizer, cancel = false)
                                trace.mark("recognizer_expected_client_error_suppressed")
                                trace.complete("recognizer_stopped")
                                close()
                                return
                            }
                            if (error.isLanguageAvailabilityError()) {
                                releaseRecognizer(recognizer, cancel = false)
                                val retryRecognizer = createRecognizer()
                                if (retryRecognizer != null && startRecognizerAttempt(retryRecognizer)) {
                                    return
                                }
                            } else {
                                releaseRecognizer(recognizer, cancel = false)
                            }
                            if (
                                shouldRetryOfflineAfterNetworkError(
                                    error = error,
                                    currentAttemptPreferOffline = session.preferOffline,
                                    networkRecognitionEnabled = allowNetworkRecognition,
                                    retryAlreadyUsed = networkOfflineRetryUsed,
                                )
                            ) {
                                networkOfflineRetryUsed = true
                                trace.mark(
                                    "recognizer_network_offline_retry",
                                    "language=${languageTag ?: "default"}",
                                )
                                val retryRecognizer = createOfflineRetryRecognizer()
                                if (retryRecognizer != null && startRecognizerAttempt(retryRecognizer)) {
                                    return
                                }
                            }
                            if (
                                error == SpeechRecognizer.ERROR_NO_MATCH &&
                                !speechBegan &&
                                !noSpeechRetryUsed
                            ) {
                                noSpeechRetryUsed = true
                                trace.mark(
                                    "recognizer_no_speech_retry",
                                    "count=1 language=${languageTag ?: "default"}",
                                )
                                val retryRecognizer = createRecognizer()
                                if (retryRecognizer != null && startRecognizerAttempt(retryRecognizer)) {
                                    return
                                }
                            }
                            if (error == SpeechRecognizer.ERROR_NO_MATCH && fallbackTranscript.isNotBlank()) {
                                Log.d(TAG, "Using partial speech transcript after no-match error")
                                trace.mark("recognizer_partial_promoted_after_no_match")
                                trySend(SpeechRecognitionEvent.FinalTranscript(fallbackTranscript))
                            } else {
                                trySend(
                                    SpeechRecognitionEvent.Error(
                                        speechRecognizerErrorMessage(
                                            error = error,
                                            networkRecognitionEnabled = allowNetworkRecognition,
                                        ),
                                    ),
                                )
                                trace.complete("recognizer_error_$error")
                            }
                            close()
                        }

                        override fun onResults(results: Bundle?) {
                            if (!isCurrentSession(activeRecognitionSession)) {
                                trace.mark("recognizer_late_results_ignored")
                                return
                            }
                            val transcript =
                                results.bestTranscript(fallbackTranscript = latestPartialTranscript)
                            Log.d(TAG, "Speech recognizer final transcript present=${transcript.isNotBlank()}")
                            trace.mark("recognizer_final", "present=${transcript.isNotBlank()}")
                            releaseRecognizer(recognizer, cancel = false)
                            if (transcript.isBlank()) {
                                trySend(SpeechRecognitionEvent.Error("No speech was recognized."))
                                trace.complete("recognizer_blank_final")
                            } else {
                                trySend(SpeechRecognitionEvent.FinalTranscript(transcript))
                            }
                            close()
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            if (!isCurrentSession(activeRecognitionSession)) {
                                trace.mark("recognizer_late_partial_ignored")
                                return
                            }
                            val transcript = partialResults.bestTranscript()
                            if (transcript.isNotBlank()) {
                                latestPartialTranscript = transcript
                                Log.d(TAG, "Speech recognizer partial transcript received")
                                trace.markOnce("recognizer_first_partial")
                                trySend(SpeechRecognitionEvent.PartialTranscript(transcript))
                            }
                        }

                        override fun onEvent(
                            eventType: Int,
                            params: Bundle?,
                        ) = Unit
                    },
                )
                recognizer.startListening(
                    recognizerIntent(
                        languageTag = languageTag,
                        preferOffline = session.preferOffline,
                    ),
                )
                return true
            }

            if (!startRecognizerAttempt(recognizerSession)) {
                trace.mark("recognizer_no_attempt")
                trySend(SpeechRecognitionEvent.Error(_status.value.message()))
                trace.complete("recognizer_no_attempt")
                clearAudioFocus(audioFocusSession)
                close()
                return@callbackFlow
            }

            awaitClose {
                clearActiveRecognizer(RecognitionSessionState.StoppingByPopupClose)
                clearAudioFocus(audioFocusSession)
            }
        }

    override fun stop() {
        clearActiveRecognizer(RecognitionSessionState.StoppingByPopupClose)
        clearAudioFocus()
    }

    override fun finish() {
        val session = activeSession ?: return
        if (session.state != RecognitionSessionState.Listening) {
            return
        }
        session.state = RecognitionSessionState.StoppingByUser
        runCatching {
            session.recognizer.stopListening()
        }.onFailure { error ->
            Log.w(TAG, "Speech recognizer could not finalize listening", error)
        }
    }

    private fun refreshStatus(): LocalSpeechEngineStatus {
        val resolved = resolveStatus()
        _status.value = resolved
        return resolved
    }

    private fun resolveStatus(): LocalSpeechEngineStatus {
        if (appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return LocalSpeechEngineStatus.PermissionRequired
        }
        return if (
            hasStrictOnDeviceRecognizer() ||
            SpeechRecognizer.isRecognitionAvailable(appContext)
        ) {
            LocalSpeechEngineStatus.Ready
        } else {
            LocalSpeechEngineStatus.MissingModel
        }
    }

    private fun createRecognizer(): RecognitionSession? {
        if (allowNetworkRecognition && SpeechRecognizer.isRecognitionAvailable(appContext)) {
            Log.d(TAG, "Using configured default speech recognition service")
            return RecognitionSession(
                recognizer = SpeechRecognizer.createSpeechRecognizer(appContext),
                preferOffline = false,
            )
        }
        if (hasStrictOnDeviceRecognizer()) {
            Log.d(TAG, "Using Android on-device speech recognition service")
            return RecognitionSession(
                recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext),
                preferOffline = true,
            )
        }
        if (SpeechRecognizer.isRecognitionAvailable(appContext)) {
            Log.d(TAG, "Using configured default speech recognition service with offline preference")
            return RecognitionSession(
                recognizer = SpeechRecognizer.createSpeechRecognizer(appContext),
                preferOffline = true,
            )
        }
        return null
    }

    private fun createOfflineRetryRecognizer(): RecognitionSession? {
        if (hasStrictOnDeviceRecognizer()) {
            Log.d(TAG, "Retrying speech recognition with Android on-device service")
            return RecognitionSession(
                recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext),
                preferOffline = true,
            )
        }
        if (SpeechRecognizer.isRecognitionAvailable(appContext)) {
            Log.d(TAG, "Retrying speech recognition with offline preference")
            return RecognitionSession(
                recognizer = SpeechRecognizer.createSpeechRecognizer(appContext),
                preferOffline = true,
            )
        }
        return null
    }

    private fun clearActiveRecognizer(state: RecognitionSessionState) {
        val session = activeSession ?: return
        session.state = state
        releaseRecognizer(session.recognizer, cancel = true)
    }

    private fun releaseRecognizer(
        recognizer: SpeechRecognizer,
        cancel: Boolean,
    ) {
        val session = activeSession
        if (session?.recognizer === recognizer) {
            session.state = RecognitionSessionState.Terminal
            activeSession = null
        }
        if (cancel) {
            recognizer.cancel()
        }
        recognizer.destroy()
    }

    private fun isCurrentSession(session: ActiveRecognitionSession): Boolean =
        activeSession === session

    private fun requestSpeechRecognitionAudioFocus(trace: VoiceTurnTrace): AudioFocusSession? {
        val manager = audioManager
        if (manager == null) {
            trace.mark("audio_focus_skipped", "reason=no_audio_manager")
            return AudioFocusSession { }
        }
        val listener =
            AudioManager.OnAudioFocusChangeListener { change ->
                trace.mark("audio_focus_changed", audioFocusChangeName(change))
            }
        val request =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
                .setAudioAttributes(
                    AudioAttributes
                        .Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                ).setOnAudioFocusChangeListener(listener)
                .setWillPauseWhenDucked(true)
                .build()
        val result = manager.requestAudioFocus(request)
        trace.mark("audio_focus_requested", audioFocusRequestResultName(result))
        return if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            AudioFocusSession {
                manager.abandonAudioFocusRequest(request)
                trace.mark("audio_focus_released")
            }
        } else {
            null
        }
    }

    private fun setActiveAudioFocus(session: AudioFocusSession) {
        clearAudioFocus()
        activeAudioFocusSession = session
    }

    private fun clearAudioFocus(session: AudioFocusSession? = null) {
        val active = activeAudioFocusSession ?: return
        if (session == null || active === session) {
            activeAudioFocusSession = null
            active.release()
        }
    }

    private fun microphonePreflight(): MicrophonePreflight {
        val hasPermission =
            appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        val appOpMode = recordAudioAppOpMode()
        val modeName = audioManager?.mode?.let(::audioManagerModeName) ?: "unavailable"
        val microphoneMuted = audioManager?.isMicrophoneMute ?: false
        val detail =
            "permission=$hasPermission appOp=${appOpMode ?: "unknown"} " +
                "audioMode=$modeName microphoneMuted=$microphoneMuted"

        val blockedMessage =
            when {
                !hasPermission -> "Microphone permission is required for local voice mode."
                appOpMode == "ignored" || appOpMode == "errored" ->
                    "Android is blocking microphone access for Zero-Assist. Check microphone privacy and app permission settings."
                microphoneMuted -> "The microphone is muted in Android audio settings."
                else -> null
            }
        return MicrophonePreflight(
            detail = detail,
            blockedMessage = blockedMessage,
        )
    }

    private fun recordAudioAppOpMode(): String? =
        runCatching {
            val manager = appOpsManager ?: return@runCatching null
            val mode =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    manager.unsafeCheckOpNoThrow(
                        AppOpsManager.OPSTR_RECORD_AUDIO,
                        Process.myUid(),
                        appContext.packageName,
                    )
                } else {
                    @Suppress("DEPRECATION")
                    manager.checkOpNoThrow(
                        AppOpsManager.OPSTR_RECORD_AUDIO,
                        Process.myUid(),
                        appContext.packageName,
                    )
                }
            appOpsModeName(mode)
        }.getOrNull()

    private fun hasStrictOnDeviceRecognizer(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)

    private fun recognizerIntent(
        languageTag: String?,
        preferOffline: Boolean,
    ): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            if (languageTag != null) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, languageTag)
            }
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, MAX_TRANSCRIPT_RESULTS)
            putExtra(RecognizerIntent.EXTRA_ENABLE_BIASING_DEVICE_CONTEXT, true)
            putStringArrayListExtra(
                RecognizerIntent.EXTRA_BIASING_STRINGS,
                assistantSpeechBiasingStrings(),
            )
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, MIN_INPUT_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, MAYBE_DONE_MS)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, DONE_MS)
            if (preferOffline) {
                putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            } else {
                putExtra(
                    RecognizerIntent.EXTRA_ENABLE_FORMATTING,
                    RecognizerIntent.FORMATTING_OPTIMIZE_QUALITY,
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    val allowedLanguages = ArrayList(recognizerLanguageTags().filterNotNull())
                    putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                    putExtra(
                        RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH,
                        RecognizerIntent.LANGUAGE_SWITCH_BALANCED,
                    )
                    putStringArrayListExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES,
                        allowedLanguages,
                    )
                    putStringArrayListExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES,
                        allowedLanguages,
                    )
                }
            }
        }

    private fun recognizerLanguageTags(): List<String?> = assistantEnglishLanguageTags(Locale.getDefault())

    private fun Bundle?.bestTranscript(fallbackTranscript: String = ""): String =
        bestSpeechTranscript(
            candidates =
                this
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    .orEmpty(),
            confidenceScores = this?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES),
            fallbackTranscript = fallbackTranscript,
        )

    private fun LocalSpeechEngineStatus.message(): String =
        when (this) {
            LocalSpeechEngineStatus.Initializing ->
                "Local speech recognition is still starting."
            LocalSpeechEngineStatus.Ready ->
                "Local speech recognition is ready."
            LocalSpeechEngineStatus.MissingModel ->
                if (allowNetworkRecognition) {
                    "Install or enable a speech recognition service on this phone."
                } else {
                    "Install an offline English speech recognition service on this phone."
                }
            LocalSpeechEngineStatus.PermissionRequired ->
                "Microphone permission is required for local voice mode."
            is LocalSpeechEngineStatus.Unavailable -> reason
        }

    private companion object {
        const val TAG = "AndroidSpeechRecognizer"
        const val MAX_TRANSCRIPT_RESULTS = 5
        const val MIN_INPUT_MS = 1_000
        const val MAYBE_DONE_MS = 1_500
        const val DONE_MS = 2_500
    }

    private data class RecognitionSession(
        val recognizer: SpeechRecognizer,
        val preferOffline: Boolean,
    )

    private data class ActiveRecognitionSession(
        val recognizer: SpeechRecognizer,
        val languageTag: String?,
        var state: RecognitionSessionState = RecognitionSessionState.Listening,
    ) {
        val isAppInitiatedStop: Boolean
            get() =
                state == RecognitionSessionState.StoppingByUser ||
                    state == RecognitionSessionState.StoppingByPopupClose
    }

    private enum class RecognitionSessionState {
        Listening,
        StoppingByUser,
        StoppingByPopupClose,
        Terminal,
    }

    private data class MicrophonePreflight(
        val detail: String,
        val blockedMessage: String?,
    )

    private fun interface AudioFocusSession {
        fun release()
    }
}

internal fun appOpsModeName(mode: Int): String =
    when (mode) {
        AppOpsManager.MODE_ALLOWED -> "allowed"
        AppOpsManager.MODE_IGNORED -> "ignored"
        AppOpsManager.MODE_ERRORED -> "errored"
        AppOpsManager.MODE_DEFAULT -> "default"
        else -> "mode_$mode"
    }

internal fun audioManagerModeName(mode: Int): String =
    when (mode) {
        AudioManager.MODE_NORMAL -> "normal"
        AudioManager.MODE_RINGTONE -> "ringtone"
        AudioManager.MODE_IN_CALL -> "in_call"
        AudioManager.MODE_IN_COMMUNICATION -> "in_communication"
        else -> "mode_$mode"
    }

internal fun audioFocusRequestResultName(result: Int): String =
    when (result) {
        AudioManager.AUDIOFOCUS_REQUEST_GRANTED -> "granted"
        AudioManager.AUDIOFOCUS_REQUEST_FAILED -> "failed"
        AudioManager.AUDIOFOCUS_REQUEST_DELAYED -> "delayed"
        else -> "result_$result"
    }

private fun audioFocusChangeName(change: Int): String =
    when (change) {
        AudioManager.AUDIOFOCUS_GAIN -> "gain"
        AudioManager.AUDIOFOCUS_LOSS -> "loss"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> "loss_transient"
        AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> "loss_transient_can_duck"
        else -> "change_$change"
    }

private fun Int.isLanguageAvailabilityError(): Boolean =
    this == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ||
        this == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE

internal fun shouldPromotePartialTranscriptAfterRecognizerError(
    error: Int,
    speechBegan: Boolean,
    fallbackTranscript: String,
): Boolean =
    speechBegan &&
        fallbackTranscript.isNotBlank() &&
        (
            error == SpeechRecognizer.ERROR_CLIENT ||
                error == SpeechRecognizer.ERROR_NETWORK ||
                error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
        )

internal fun shouldRetryOfflineAfterNetworkError(
    error: Int,
    currentAttemptPreferOffline: Boolean,
    networkRecognitionEnabled: Boolean,
    retryAlreadyUsed: Boolean,
): Boolean =
    networkRecognitionEnabled &&
        !currentAttemptPreferOffline &&
        !retryAlreadyUsed &&
        (
            error == SpeechRecognizer.ERROR_NETWORK ||
                error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT
        )

internal fun speechRecognizerErrorMessage(
    error: Int,
    networkRecognitionEnabled: Boolean = false,
): String =
    when (error) {
        SpeechRecognizer.ERROR_AUDIO -> "Microphone audio could not be captured."
        SpeechRecognizer.ERROR_CLIENT -> "Local speech recognition was cancelled."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
            "Microphone permission is required for local voice mode."
        SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED ->
            "English speech recognition is not supported by the installed speech service."
        SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
            "Install the offline English speech recognition language pack on this phone."
        SpeechRecognizer.ERROR_NETWORK,
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
            if (networkRecognitionEnabled) {
                "Speech recognition needs a working network connection or downloaded offline model."
            } else {
                "Offline speech recognition is not available yet, and network speech is disabled."
            }
        SpeechRecognizer.ERROR_NO_MATCH -> "No speech was recognized."
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
            "Local speech recognition is already listening."
        SpeechRecognizer.ERROR_SERVER,
        SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
            "The installed speech recognition service stopped unexpectedly."
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech was heard."
        SpeechRecognizer.ERROR_TOO_MANY_REQUESTS ->
            "Speech recognition is cooling down. Try again in a moment."
        SpeechRecognizer.ERROR_CANNOT_CHECK_SUPPORT ->
            "The installed speech recognition service cannot confirm offline English support."
        SpeechRecognizer.ERROR_CANNOT_LISTEN_TO_DOWNLOAD_EVENTS ->
            "The installed speech recognition service cannot report language-pack downloads."
        else -> "Local speech recognition failed with code $error."
    }

internal fun assistantEnglishLanguageTags(defaultLocale: Locale = Locale.getDefault()): List<String?> {
    val tags = linkedSetOf<String?>()
    if (defaultLocale.country.isNotBlank()) {
        tags += "en-${defaultLocale.country.uppercase(Locale.US)}"
    }
    if (defaultLocale.language.equals(Locale.ENGLISH.language, ignoreCase = true)) {
        tags += defaultLocale.toLanguageTag()
    }
    tags += "en-IN"
    tags += Locale.US.toLanguageTag()
    tags += Locale.UK.toLanguageTag()
    tags += null
    return tags.toList()
}

internal fun bestSpeechTranscript(
    candidates: List<String>,
    confidenceScores: FloatArray?,
    fallbackTranscript: String = "",
): String {
    val indexedCandidates =
        candidates.mapIndexedNotNull { index, candidate ->
            candidate.trim().takeIf { it.isNotBlank() }?.let { transcript ->
                index to transcript
            }
        }
    if (indexedCandidates.isEmpty()) {
        return fallbackTranscript.trim()
    }

    var bestCandidate = indexedCandidates.first()
    var bestConfidence = confidenceScores.validConfidenceAt(bestCandidate.first)
    for (candidate in indexedCandidates.drop(1)) {
        val candidateConfidence = confidenceScores.validConfidenceAt(candidate.first)
        if (candidateConfidence != null &&
            (bestConfidence == null || candidateConfidence > bestConfidence)
        ) {
            bestCandidate = candidate
            bestConfidence = candidateConfidence
        }
    }
    return bestCandidate.second
}

private fun assistantSpeechBiasingStrings(): ArrayList<String> =
    arrayListOf(
        "Zero Assist",
        "ZeroClaw",
        "agent",
        "agents",
        "summarize",
        "notes",
        "terminal",
        "plugin",
        "plugins",
        "workspace",
        "settings",
        "open settings",
        "call",
        "set timer",
        "set alarm",
        "reminder",
    )

private fun FloatArray?.validConfidenceAt(index: Int): Float? =
    this
        ?.takeIf { index in it.indices }
        ?.get(index)
        ?.takeIf { it >= 0f }
