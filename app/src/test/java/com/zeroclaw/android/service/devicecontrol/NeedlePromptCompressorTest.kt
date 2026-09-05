package com.zeroclaw.android.service.devicecontrol

import com.zeroclaw.android.service.needle.NeedlePromptCompressor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("NeedlePromptCompressor token budget")
class NeedlePromptCompressorTest {

    private val compressor = NeedlePromptCompressor()

    private fun pathologicalRequest(): PlannerRequest {
        val longLabel = "VeryLongButtonLabelThatKeepsGoingAndGoing"
        val nodes = (0 until 25).joinToString("\n") { i ->
            "[$i] Button \"$longLabel$i\" [click] (540,${120 + i}) " +
                "id:com.example:id/button_$i acts:click,longclick,focus"
        }
        val screen = buildString {
            appendLine("PKG: com.instagram.android")
            appendLine("SCREEN_CHANGED: true")
            appendLine("KEYBOARD_VISIBLE: true")
            appendLine("NODES (42 total, showing 25 ranked):")
            appendLine(nodes)
        }
        val history = (1..6).map { i ->
            "ClickText('some very long element label number $i') → OK " +
                "fp(hash=123,pkg=com.instagram.android)"
        }
        return PlannerRequest(
            requestId = "test",
            goal = "Open Instagram and send a really long message about dinner plans " +
                "tomorrow evening to Rohit Sharma please",
            step = 7,
            maxSteps = 30,
            currentPackage = "com.instagram.android",
            screen = screen,
            previousAction = "ClickText",
            previousResult = "Action succeeded",
            failureCount = 2,
            actionHistory = history,
        )
    }

    @Test
    fun `pathological input fits the dynamic token budget`() {
        val input = compressor.compress(pathologicalRequest())
        val tokens = NeedlePromptCompressor.estimateTokens(input.toUserText())
        assertTrue(
            tokens <= NeedlePromptCompressor.MAX_DYNAMIC_TOKENS,
            "expected <= ${NeedlePromptCompressor.MAX_DYNAMIC_TOKENS} tokens, got $tokens",
        )
    }

    @Test
    fun `goal is never dropped`() {
        val input = compressor.compress(pathologicalRequest())
        assertTrue(input.goal.isNotBlank())
        assertTrue(input.toUserText().contains("GOAL:"))
    }

    @Test
    fun `history keeps at most two entries`() {
        val input = compressor.compress(pathologicalRequest())
        assertTrue(input.historyLines.size <= NeedlePromptCompressor.MAX_HISTORY_ENTRIES)
    }

    @Test
    fun `nodes are capped stripped and short`() {
        val input = compressor.compress(pathologicalRequest())
        assertTrue(input.nodeLines.size <= NeedlePromptCompressor.MAX_NODES)
        input.nodeLines.forEach { line ->
            assertTrue(
                line.length <= NeedlePromptCompressor.MAX_NODE_LINE_CHARS + 1,
                "node line too long: $line",
            )
            assertTrue(!line.contains("id:"), "view id leaked: $line")
            assertTrue(!line.contains("acts:"), "actions leaked: $line")
            assertTrue(!Regex("\\(\\d+,\\d+\\)").containsMatchIn(line), "coords leaked: $line")
        }
    }

    @Test
    fun `state line carries package step failures and keyboard flag`() {
        val input = compressor.compress(pathologicalRequest())
        assertTrue(input.stateLine.contains("STEP:7/30"))
        assertTrue(input.stateLine.contains("FAIL:2"))
        assertTrue(input.stateLine.contains("+kbd"))
    }

    @Test
    fun `short input passes through unmodified`() {
        val screen = "PKG: com.example\nNODES (2 total):\n" +
            "[0] Button \"Search\" [click]\n[1] EditText \"Query\" [edit]"
        val request = PlannerRequest(
            requestId = "t",
            goal = "Tap Search",
            step = 1,
            maxSteps = 30,
            currentPackage = "com.example",
            screen = screen,
            previousAction = null,
            previousResult = null,
            failureCount = 0,
            actionHistory = listOf("OpenApp(com.example) → OK"),
        )
        val input = compressor.compress(request)
        assertEquals("Tap Search", input.goal)
        assertEquals(2, input.nodeLines.size)
        assertEquals(1, input.historyLines.size)
    }
}
