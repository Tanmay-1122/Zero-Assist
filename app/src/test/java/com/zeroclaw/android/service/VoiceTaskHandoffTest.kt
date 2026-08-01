/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.charset.StandardCharsets
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VoiceTaskHandoffTest {
    @Test
    fun `submitTranscript queues trimmed local voice task`() =
        runTest {
            val requests = Channel<VoiceTaskRequest>(Channel.BUFFERED)
            val handoff = ChannelVoiceTaskHandoff(requests)

            val result = handoff.submitTranscript("  call mom  ")
            val request = requests.receive()

            assertEquals(VoiceTaskHandoffResult.Accepted, result)
            assertEquals("call mom", request.text)
            assertEquals("voice_assistant", request.source)
        }

    @Test
    fun `submitTranscript ignores blank transcript`() =
        runTest {
            val requests = Channel<VoiceTaskRequest>(Channel.BUFFERED)
            val handoff = ChannelVoiceTaskHandoff(requests)

            val result = handoff.submitTranscript("   ")

            assertEquals(VoiceTaskHandoffResult.IgnoredBlank, result)
        }

    @Test
    fun `submitTranscript reports failed queue`() =
        runTest {
            val requests = Channel<VoiceTaskRequest>(Channel.BUFFERED)
            requests.close()
            val handoff = ChannelVoiceTaskHandoff(requests)

            val result = handoff.submitTranscript("call mom")

            assertTrue(result is VoiceTaskHandoffResult.Failed)
            assertEquals(
                "Could not queue voice task for the main app.",
                (result as VoiceTaskHandoffResult.Failed).message,
            )
        }

    @Test
    fun `voice command collectors delegate launch lifecycle to shared view model`() {
        val sourceRoot = findMainSourceRoot()
        val appShellSource =
            readUtf8(
                sourceRoot.resolve("com/zeroclaw/android/navigation/ZeroClawAppShell.kt"),
            )
        val standaloneSource =
            readUtf8(
                sourceRoot.resolve("com/zeroclaw/android/ui/screen/voice/VoiceAssistantStandaloneHost.kt"),
            )
        val mainActivitySource =
            readUtf8(sourceRoot.resolve("com/zeroclaw/android/MainActivity.kt"))

        assertTrue(appShellSource.contains("voiceAssistantViewModel.voiceCommands.collect"))
        assertTrue(appShellSource.contains("voiceAssistantViewModel.runCommandLaunch("))
        assertTrue(appShellSource.contains("launchCommand ="))
        assertTrue(standaloneSource.contains("voiceAssistantViewModel.runCommandLaunch("))
        assertFalse(mainActivitySource.contains("VoiceAssistantCommandLauncher.launch("))
    }

    private fun readUtf8(path: Path): String =
        String(Files.readAllBytes(path), StandardCharsets.UTF_8)

    private fun findMainSourceRoot(): Path {
        val cwd = Paths.get("").toAbsolutePath()
        val candidates =
            generateSequence(cwd) { path -> path.parent }
                .flatMap { path ->
                    sequenceOf(
                        path.resolve("app/src/main/java"),
                        path.resolve("src/main/java"),
                    )
                }

        return candidates.firstOrNull { path -> Files.isDirectory(path) }
            ?: error("Could not locate Android main source root from $cwd")
    }
}
