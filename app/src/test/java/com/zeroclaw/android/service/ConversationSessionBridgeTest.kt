/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.ffi.FfiSessionListener
import com.zeroclaw.ffi.SessionMessage
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ConversationSessionBridge")
class ConversationSessionBridgeTest {
    private lateinit var bridge: ConversationSessionBridge

    @BeforeEach
    fun setUp() {
        mockkStatic("com.zeroclaw.ffi.Zeroclaw_androidKt")
        bridge = ConversationSessionBridge()
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun lifecycleCallsDelegateToGeneratedFfi() {
        every { com.zeroclaw.ffi.sessionStart() } returns Unit
        every { com.zeroclaw.ffi.sessionCancel() } returns Unit
        every { com.zeroclaw.ffi.sessionClear() } returns Unit
        every { com.zeroclaw.ffi.sessionDestroy() } returns Unit

        bridge.startSession()
        bridge.cancel()
        bridge.clear()
        bridge.destroy()

        verify(exactly = 1) { com.zeroclaw.ffi.sessionStart() }
        verify(exactly = 1) { com.zeroclaw.ffi.sessionCancel() }
        verify(exactly = 1) { com.zeroclaw.ffi.sessionClear() }
        verify(exactly = 1) { com.zeroclaw.ffi.sessionDestroy() }
    }

    @Test
    fun seedMapsAppMessagesToGeneratedFfiMessages() {
        val seedSlot = slot<List<SessionMessage>>()
        every { com.zeroclaw.ffi.sessionSeed(capture(seedSlot)) } returns Unit

        bridge.seed(
            listOf(
                ConversationSeedMessage(role = "user", content = "hello"),
                ConversationSeedMessage(role = "assistant", content = "hi"),
            ),
        )

        assertEquals(listOf("user", "assistant"), seedSlot.captured.map { it.role })
        assertEquals(listOf("hello", "hi"), seedSlot.captured.map { it.content })
    }

    @Test
    fun sendForwardsCallbacksThroughAppOwnedListener() {
        val listenerSlot = slot<FfiSessionListener>()
        every {
            com.zeroclaw.ffi.sessionSend(
                message = "hello",
                imageData = listOf("image"),
                mimeTypes = listOf("image/png"),
                listener = capture(listenerSlot),
            )
        } returns Unit

        val events = mutableListOf<String>()
        bridge.send(
            message = "hello",
            imageData = listOf("image"),
            mimeTypes = listOf("image/png"),
            listener =
                object : ConversationSessionListener {
                    override fun onThinking(text: String) {
                        events += "thinking:$text"
                    }

                    override fun onResponseChunk(text: String) {
                        events += "chunk:$text"
                    }

                    override fun onToolStart(
                        name: String,
                        argumentsHint: String,
                    ) {
                        events += "tool-start:$name:$argumentsHint"
                    }

                    override fun onToolResult(
                        name: String,
                        success: Boolean,
                        durationSecs: ULong,
                    ) {
                        events += "tool-result:$name:$success:$durationSecs"
                    }

                    override fun onToolOutput(
                        name: String,
                        output: String,
                    ) {
                        events += "tool-output:$name:$output"
                    }

                    override fun onProgress(message: String) {
                        events += "progress:$message"
                    }

                    override fun onCompaction(summary: String) {
                        events += "compaction:$summary"
                    }

                    override fun onComplete(fullResponse: String) {
                        events += "complete:$fullResponse"
                    }

                    override fun onError(error: String) {
                        events += "error:$error"
                    }

                    override fun onCancelled() {
                        events += "cancelled"
                    }
                },
        )

        listenerSlot.captured.onThinking("plan")
        listenerSlot.captured.onResponseChunk("part")
        listenerSlot.captured.onToolStart("shell", "{}")
        listenerSlot.captured.onToolResult("shell", true, 2u)
        listenerSlot.captured.onToolOutput("shell", "ok")
        listenerSlot.captured.onProgress("working")
        listenerSlot.captured.onCompaction("summary")
        listenerSlot.captured.onComplete("done")
        listenerSlot.captured.onError("bad")
        listenerSlot.captured.onCancelled()

        assertTrue("thinking:plan" in events)
        assertTrue("chunk:part" in events)
        assertTrue("tool-start:shell:{}" in events)
        assertTrue("tool-result:shell:true:2" in events)
        assertTrue("tool-output:shell:ok" in events)
        assertTrue("progress:working" in events)
        assertTrue("compaction:summary" in events)
        assertTrue("complete:done" in events)
        assertTrue("error:bad" in events)
        assertTrue("cancelled" in events)
    }

    @Test
    fun utilityCallsDelegateToGeneratedFfi() {
        every { com.zeroclaw.ffi.evalRepl("version()") } returns "0.0.37"
        every { com.zeroclaw.ffi.getVersion() } returns "0.0.37"
        every {
            com.zeroclaw.ffi.autoClassifyAndMatchAgent("fix it", "[]")
        } returns """{"best_agent":"Coder","confidence":0.91}"""

        assertEquals("0.0.37", bridge.evalReplExpression("version()"))
        assertEquals("0.0.37", bridge.version())
        assertEquals(
            """{"best_agent":"Coder","confidence":0.91}""",
            bridge.autoClassifyAndMatchAgent("fix it", "[]"),
        )
    }
}
