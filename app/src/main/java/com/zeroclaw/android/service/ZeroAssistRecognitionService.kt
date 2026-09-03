/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

/**
 * Speech recognizer advertised to Android when Zero-Assist is selected as the
 * device assistant.
 *
 * The assistant popup still owns its main STT flow. This service exists so the
 * OS can treat Zero-Assist as a complete voice interaction app and, when asked
 * directly by the system, only delegates to Android's strict on-device
 * recognizer.
 */
class ZeroAssistRecognitionService : RecognitionService() {
    private var activeRecognizer: SpeechRecognizer? = null

    @SuppressLint("MissingPermission")
    override fun onStartListening(
        recognizerIntent: Intent?,
        listener: Callback,
    ) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            listener.error(SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS)
            return
        }
        if (!hasStrictOnDeviceRecognizer()) {
            listener.error(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE)
            return
        }

        val recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(this)
        activeRecognizer = recognizer
        recognizer.setRecognitionListener(
            object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    listener.readyForSpeech(params ?: Bundle.EMPTY)
                }

                override fun onBeginningOfSpeech() {
                    listener.beginningOfSpeech()
                }

                override fun onRmsChanged(rmsdB: Float) {
                    listener.rmsChanged(rmsdB)
                }

                override fun onBufferReceived(buffer: ByteArray?) {
                    listener.bufferReceived(buffer ?: ByteArray(0))
                }

                override fun onEndOfSpeech() {
                    listener.endOfSpeech()
                }

                override fun onError(error: Int) {
                    clearRecognizer(recognizer)
                    listener.error(error)
                }

                override fun onResults(results: Bundle?) {
                    clearRecognizer(recognizer)
                    listener.results(results ?: Bundle.EMPTY)
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    listener.partialResults(partialResults ?: Bundle.EMPTY)
                }

                override fun onEvent(
                    eventType: Int,
                    params: Bundle?,
                ) = Unit
            },
        )
        recognizer.startListening(recognizerIntent?.toOfflineEnglishIntent() ?: offlineEnglishIntent())
    }

    override fun onStopListening(listener: Callback) {
        activeRecognizer?.stopListening()
    }

    override fun onCancel(listener: Callback) {
        activeRecognizer?.cancel()
        clearRecognizer(activeRecognizer)
    }

    override fun onDestroy() {
        clearRecognizer(activeRecognizer)
        super.onDestroy()
    }

    private fun clearRecognizer(recognizer: SpeechRecognizer?) {
        if (activeRecognizer === recognizer) {
            activeRecognizer = null
        }
        recognizer?.destroy()
    }

    private fun hasStrictOnDeviceRecognizer(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(this)

    private fun Intent.toOfflineEnglishIntent(): Intent =
        Intent(this).apply {
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            if (!hasExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL)) {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
            }
            if (!hasExtra(RecognizerIntent.EXTRA_LANGUAGE)) {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
            }
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        }

    private fun offlineEnglishIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            ).putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.US.toLanguageTag())
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
}
