/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import android.app.AppOpsManager
import android.media.AudioManager
import android.speech.SpeechRecognizer
import java.util.Locale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("AndroidOnDeviceSpeechRecognizer")
class AndroidOnDeviceSpeechRecognizerTest {
    @Test
    fun `maps unsupported language error to clear offline English message`() {
        assertEquals(
            "English speech recognition is not supported by the installed speech service.",
            speechRecognizerErrorMessage(SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED),
        )
    }

    @Test
    fun `maps unavailable language error to install offline pack message`() {
        assertEquals(
            "Install the offline English speech recognition language pack on this phone.",
            speechRecognizerErrorMessage(SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE),
        )
    }

    @Test
    fun `prioritizes regional English when device locale is not English`() {
        assertEquals(
            listOf("en-IN", "en-US", "en-GB", null),
            assistantEnglishLanguageTags(Locale("hi", "IN")),
        )
    }

    @Test
    fun `keeps English device locale first without duplicate language tags`() {
        assertEquals(
            listOf("en-GB", "en-IN", "en-US", null),
            assistantEnglishLanguageTags(Locale.UK),
        )
    }

    @Test
    fun `uses confidence scores when Android provides alternatives`() {
        assertEquals(
            "open settings",
            bestSpeechTranscript(
                candidates = listOf("open sittings", "open settings"),
                confidenceScores = floatArrayOf(0.52f, 0.91f),
            ),
        )
    }

    @Test
    fun `falls back to partial transcript when final candidates are blank`() {
        assertEquals(
            "open settings",
            bestSpeechTranscript(
                candidates = listOf("", "   "),
                confidenceScores = null,
                fallbackTranscript = " open settings ",
            ),
        )
    }

    @Test
    fun `promotes partial transcript after client error once speech began`() {
        assertTrue(
            shouldPromotePartialTranscriptAfterRecognizerError(
                error = SpeechRecognizer.ERROR_CLIENT,
                speechBegan = true,
                fallbackTranscript = "open settings",
            ),
        )
    }

    @Test
    fun `does not promote partial transcript after client error before speech begins`() {
        assertFalse(
            shouldPromotePartialTranscriptAfterRecognizerError(
                error = SpeechRecognizer.ERROR_CLIENT,
                speechBegan = false,
                fallbackTranscript = "open settings",
            ),
        )
    }

    @Test
    fun `retries network recognizer offline once after network error`() {
        assertTrue(
            shouldRetryOfflineAfterNetworkError(
                error = SpeechRecognizer.ERROR_NETWORK,
                currentAttemptPreferOffline = false,
                networkRecognitionEnabled = true,
                retryAlreadyUsed = false,
            ),
        )
        assertFalse(
            shouldRetryOfflineAfterNetworkError(
                error = SpeechRecognizer.ERROR_NETWORK,
                currentAttemptPreferOffline = true,
                networkRecognitionEnabled = true,
                retryAlreadyUsed = false,
            ),
        )
        assertFalse(
            shouldRetryOfflineAfterNetworkError(
                error = SpeechRecognizer.ERROR_NETWORK,
                currentAttemptPreferOffline = false,
                networkRecognitionEnabled = true,
                retryAlreadyUsed = true,
            ),
        )
    }

    @Test
    fun `names record audio app-op modes for diagnostics`() {
        assertEquals("allowed", appOpsModeName(AppOpsManager.MODE_ALLOWED))
        assertEquals("ignored", appOpsModeName(AppOpsManager.MODE_IGNORED))
        assertEquals("errored", appOpsModeName(AppOpsManager.MODE_ERRORED))
    }

    @Test
    fun `names audio focus request results for diagnostics`() {
        assertEquals(
            "granted",
            audioFocusRequestResultName(AudioManager.AUDIOFOCUS_REQUEST_GRANTED),
        )
        assertEquals(
            "failed",
            audioFocusRequestResultName(AudioManager.AUDIOFOCUS_REQUEST_FAILED),
        )
    }
}
