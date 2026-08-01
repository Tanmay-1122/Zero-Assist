/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("VoiceAssistantConversation")
class VoiceAssistantConversationTest {
    @Test
    fun `submitTranscript reports each complete spoken sentence while response streams`() =
        runTest {
            val bridge =
                StreamingConversationSessionBridge(
                    chunks =
                        listOf(
                            "Done.",
                            " I updated the file.",
                            " Here is extra detail that should not be spoken.",
                        ),
                )
            val conversation = ZeroClawVoiceAssistantConversation(sessionBridge = bridge)
            val spokenSentences = mutableListOf<String>()

            val result =
                conversation.submitTranscript(
                    transcript = "update it",
                    onNextSpokenSentence = { sentence -> spokenSentences += sentence },
                )

            assertEquals(listOf("Done.", "I updated the file."), spokenSentences)
            assertEquals(
                VoiceAssistantConversationResult.Success("Done. I updated the file."),
                result,
            )
        }

    @Test
    fun `submitTranscript does not emit partial sentences split across chunks`() =
        runTest {
            val bridge =
                StreamingConversationSessionBridge(
                    chunks =
                        listOf(
                            "This is",
                            " still partial",
                            ". Now complete.",
                        ),
                )
            val conversation = ZeroClawVoiceAssistantConversation(sessionBridge = bridge)
            val spokenSentences = mutableListOf<String>()

            conversation.submitTranscript(
                transcript = "update it",
                onNextSpokenSentence = { sentence -> spokenSentences += sentence },
            )

            assertEquals(listOf("This is still partial.", "Now complete."), spokenSentences)
        }

    @Test
    fun `spokenResponseText keeps spoken output short`() {
        val spoken =
            spokenResponseText(
                "First short sentence. Second short sentence. Third sentence should stay silent.",
            )

        assertEquals("First short sentence. Second short sentence.", spoken)
    }

    @Test
    fun `cancel forwards to the session bridge`() {
        val bridge = StreamingConversationSessionBridge(chunks = emptyList())
        val conversation = ZeroClawVoiceAssistantConversation(sessionBridge = bridge)

        conversation.cancel()

        assertTrue(bridge.cancelCalled)
    }

    private class StreamingConversationSessionBridge(
        private val chunks: List<String>,
    ) : ConversationSessionBridge() {
        var cancelCalled = false

        override fun startSession(configToml: String?) = Unit

        override fun send(
            message: String,
            imageData: List<String>,
            mimeTypes: List<String>,
            listener: ConversationSessionListener,
        ) {
            chunks.forEach { chunk -> listener.onResponseChunk(chunk) }
            listener.onComplete(chunks.joinToString(separator = ""))
        }

        override fun cancel() {
            cancelCalled = true
        }
    }
}
