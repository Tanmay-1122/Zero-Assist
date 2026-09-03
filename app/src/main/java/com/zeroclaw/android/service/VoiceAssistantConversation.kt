/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

sealed interface VoiceAssistantConversationResult {
    data class Success(
        val spokenResponse: String,
    ) : VoiceAssistantConversationResult

    data object IgnoredBlank : VoiceAssistantConversationResult

    data class Failed(
        val message: String,
    ) : VoiceAssistantConversationResult
}

interface VoiceAssistantConversation {
    suspend fun submitTranscript(transcript: String): VoiceAssistantConversationResult

    suspend fun submitTranscript(
        transcript: String,
        onNextSpokenSentence: (String) -> Unit,
    ): VoiceAssistantConversationResult =
        submitTranscript(transcript)

    suspend fun submitTranscript(
        transcript: String,
        onNextSpokenSentence: (String) -> Unit,
        trace: VoiceTurnTrace,
    ): VoiceAssistantConversationResult =
        submitTranscript(transcript, onNextSpokenSentence)

    fun cancel() = Unit
}

class ZeroClawVoiceAssistantConversation(
    private val sessionBridge: ConversationSessionBridge = ConversationSessionBridge(),
) : VoiceAssistantConversation {
    private val sessionMutex = Mutex()

    override suspend fun submitTranscript(transcript: String): VoiceAssistantConversationResult =
        submitTranscript(
            transcript = transcript,
            onNextSpokenSentence = {},
            trace = VoiceTurnTrace.noop(),
        )

    override suspend fun submitTranscript(
        transcript: String,
        onNextSpokenSentence: (String) -> Unit,
    ): VoiceAssistantConversationResult =
        submitTranscript(
            transcript = transcript,
            onNextSpokenSentence = onNextSpokenSentence,
            trace = VoiceTurnTrace.noop(),
        )

    override suspend fun submitTranscript(
        transcript: String,
        onNextSpokenSentence: (String) -> Unit,
        trace: VoiceTurnTrace,
    ): VoiceAssistantConversationResult {
        val trimmedTranscript = transcript.trim()
        if (trimmedTranscript.isBlank()) {
            trace.mark("conversation_blank_transcript")
            return VoiceAssistantConversationResult.IgnoredBlank
        }

        return sessionMutex.withLock {
            withContext(Dispatchers.IO) {
                sendVoiceTurn(trimmedTranscript, onNextSpokenSentence, trace)
            }
        }
    }

    private fun sendVoiceTurn(
        transcript: String,
        onNextSpokenSentence: (String) -> Unit,
        trace: VoiceTurnTrace,
    ): VoiceAssistantConversationResult {
        val streamedResponse = StringBuilder()
        var completionText = ""
        var reportedError: String? = null
        var streamedSpokenUpTo = 0
        var streamedSentenceCount = 0

        return try {
            trace.mark("native_session_start_requested")
            ensureSessionStarted()
            trace.mark("native_session_ready")
            trace.mark("conversation_send_started")
            sessionBridge.send(
                message = voiceModeMessage(transcript),
                imageData = emptyList(),
                mimeTypes = emptyList(),
                listener =
                    object : ConversationSessionListener {
                        override fun onResponseChunk(text: String) {
                            if (text.isNotEmpty()) {
                                trace.markOnce("conversation_first_token")
                                streamedResponse.append(text)
                                val batch =
                                    nextCompleteSpokenSentences(
                                        text = streamedResponse.toString(),
                                        startIndex = streamedSpokenUpTo,
                                        remainingSentenceBudget =
                                            MAX_SPOKEN_RESPONSE_SENTENCES - streamedSentenceCount,
                                    )
                                streamedSpokenUpTo = batch.consumedUpTo
                                streamedSentenceCount += batch.sentences.size
                                batch.sentences.forEach { sentence ->
                                    trace.markOnce("conversation_first_complete_sentence")
                                    onNextSpokenSentence(sentence)
                                }
                            }
                        }

                        override fun onComplete(fullResponse: String) {
                            trace.mark("conversation_complete")
                            completionText = fullResponse
                        }

                        override fun onError(error: String) {
                            trace.mark("conversation_error", "message=${error.take(80)}")
                            reportedError = error
                        }

                        override fun onCancelled() {
                            trace.mark("conversation_cancelled")
                            reportedError = "Voice request cancelled."
                        }
                    },
            )

            val spokenResponse = spokenResponseText(completionText.ifBlank { streamedResponse.toString() })
            when {
                spokenResponse.isNotBlank() ->
                    VoiceAssistantConversationResult.Success(spokenResponse)
                reportedError != null ->
                    VoiceAssistantConversationResult.Failed(reportedError.orEmpty())
                else ->
                    VoiceAssistantConversationResult.Failed("ZeroClaw did not return a spoken response.")
            }
        } catch (error: Exception) {
            trace.mark("conversation_exception", error::class.java.simpleName)
            VoiceAssistantConversationResult.Failed(
                reportedError ?: error.message ?: "ZeroClaw voice request failed.",
            )
        }
    }

    private fun ensureSessionStarted() {
        if (sessionBridge.isSessionActive()) return
        runCatching { sessionBridge.startSession() }
    }

    override fun cancel() {
        sessionBridge.cancel()
    }
}

object MissingVoiceAssistantConversation : VoiceAssistantConversation {
    override suspend fun submitTranscript(transcript: String): VoiceAssistantConversationResult =
        VoiceAssistantConversationResult.Failed("Voice assistant conversation is not connected.")
}

internal fun voiceModeMessage(transcript: String): String =
    "[Voice] $transcript — " +
        "Reply in 1-2 short spoken sentences. " +
        "No markdown, no lists, no code blocks. " +
        "Use natural phrasing with contractions."

internal fun spokenResponseText(text: String): String =
    sanitizeSpokenResponse(text)
        .takeSpokenSentences(MAX_SPOKEN_RESPONSE_SENTENCES)
        .takeSpokenChars(MAX_SPOKEN_RESPONSE_CHARS)

internal fun firstCompleteSpokenSentenceText(text: String): String? =
    nextCompleteSpokenSentences(
        text = text,
        startIndex = 0,
        remainingSentenceBudget = 1,
    ).sentences.firstOrNull()

internal fun nextCompleteSpokenSentences(
    text: String,
    startIndex: Int,
    remainingSentenceBudget: Int,
): SpokenSentenceBatch {
    val normalized = sanitizeSpokenResponse(text)
    if (remainingSentenceBudget <= 0 || startIndex >= normalized.length) {
        return SpokenSentenceBatch(emptyList(), normalized.length)
    }

    var consumedUpTo = startIndex.coerceAtLeast(0)
    val sentences = mutableListOf<String>()
    val remaining = normalized.drop(consumedUpTo)
    for (match in SPOKEN_SENTENCE_PATTERN.findAll(remaining)) {
        if (sentences.size >= remainingSentenceBudget) {
            break
        }
        sentences += match.value.trim().takeSpokenChars(MAX_EARLY_SPOKEN_SENTENCE_CHARS)
        consumedUpTo = startIndex + match.range.last + 1
    }
    return SpokenSentenceBatch(sentences, consumedUpTo)
}

internal data class SpokenSentenceBatch(
    val sentences: List<String>,
    val consumedUpTo: Int,
)

private fun sanitizeSpokenResponse(text: String): String =
    text
        .replace(Regex("```[\\s\\S]*?```"), "I prepared the code or detailed output.")
        .replace(Regex("[#*_`>]+"), "")
        .lineSequence()
        .map { line -> line.trim() }
        .filter { line -> line.isNotBlank() }
        .joinToString(" ")
        .replace(Regex("\\s+"), " ")
        .trim()

private fun String.takeSpokenSentences(maxSentences: Int): String {
    val sentences =
        SPOKEN_SENTENCE_PATTERN
            .findAll(this)
            .map { match -> match.value.trim() }
            .take(maxSentences)
            .toList()
    return sentences.ifEmpty { listOf(this) }.joinToString(" ").trim()
}

private fun String.takeSpokenChars(maxChars: Int): String {
    if (length <= maxChars) {
        return this
    }
    val clipped = take(maxChars).trimEnd()
    val lastSpace = clipped.lastIndexOf(' ')
    val wordSafe =
        if (lastSpace >= MIN_WORD_SAFE_TRIM_INDEX) {
            clipped.take(lastSpace)
        } else {
            clipped
        }
    return wordSafe.trimEnd(',', ';', ':') + "..."
}

private val SPOKEN_SENTENCE_PATTERN = Regex("""[^.!?]+[.!?]+(?=\s|$)""")
private const val MAX_SPOKEN_RESPONSE_SENTENCES = 2
private const val MAX_SPOKEN_RESPONSE_CHARS = 260
private const val MAX_EARLY_SPOKEN_SENTENCE_CHARS = 180
private const val MIN_WORD_SAFE_TRIM_INDEX = 120
