package com.zeroclaw.android.service.devicecontrol

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

@DisplayName("NeedleResponseParser native JSON mapping")
class NeedleResponseParserTest {

    private fun callJson(
        name: String,
        arguments: String,
        confidence: Double = 0.94,
        type: String = "call",
    ): String = """
        {"type":"$type","success":true,"function_calls":
        [{"name":"$name","arguments":$arguments}],
        "reasoning":"test","confidence":$confidence}
    """.trimIndent()

    private fun reasonOf(block: () -> Unit): FallbackReason {
        val thrown = assertThrows<NeedleFallbackRequired> { block() }
        return thrown.reason
    }

    @Test
    fun `valid click_text maps with reasoning`() {
        val parsed = NeedleResponseParser.parse(
            callJson("click_text", """{"text":"Search"}"""), 0.6
        )
        assertEquals(DeviceAction.ClickText("Search"), parsed.action)
        assertEquals("test", parsed.reasoning)
    }

    @Test
    fun `open_app maps optional package`() {
        val parsed = NeedleResponseParser.parse(
            callJson("open_app", """{"app_name":"Instagram"}"""), 0.6
        )
        assertEquals(DeviceAction.OpenApp("Instagram", null), parsed.action)
    }

    @Test
    fun `done marks complete`() {
        val parsed = NeedleResponseParser.parse(
            callJson("done", """{"message":"Opened."}"""), 0.6
        )
        assertTrue(parsed.isComplete)
    }

    @Test
    fun `numeric index coerces from number`() {
        val parsed = NeedleResponseParser.parse(
            callJson("click_index", """{"index":3}"""), 0.6
        )
        assertEquals(DeviceAction.ClickIndex(3), parsed.action)
    }

    @Test
    fun `empty call refuses`() {
        assertEquals(FallbackReason.EMPTY, reasonOf { NeedleResponseParser.parse("[]", 0.6) })
    }

    @Test
    fun `respond type never becomes action`() {
        assertEquals(
            FallbackReason.EMPTY,
            reasonOf { NeedleResponseParser.parse("""{"type":"respond","function_calls":[]}""", 0.6) },
        )
    }

    @Test
    fun `unknown tool name falls back`() {
        assertEquals(
            FallbackReason.UNKNOWN_ACTION,
            reasonOf {
                NeedleResponseParser.parse(callJson("click_at", """{"x":1.0,"y":2.0}"""), 0.6)
            },
        )
    }

    @Test
    fun `low confidence falls back`() {
        assertEquals(
            FallbackReason.LOW_CONFIDENCE,
            reasonOf {
                NeedleResponseParser.parse(
                    callJson("click_text", """{"text":"Search"}""", confidence = 0.2), 0.6
                )
            },
        )
    }

    @Test
    fun `bad arguments fall back`() {
        assertEquals(
            FallbackReason.BAD_ARGUMENTS,
            reasonOf {
                NeedleResponseParser.parse(callJson("click_index", """{"index":"abc"}"""), 0.6)
            },
        )
    }

    @Test
    fun `malformed json is engine error`() {
        assertEquals(
            FallbackReason.ENGINE_ERROR,
            reasonOf { NeedleResponseParser.parse("not json", 0.6) },
        )
    }

    @Test
    fun `type_text infers press_enter follow-up`() {
        val followUps = NeedleResponseParser.inferFollowUps(DeviceAction.TypeText("hi"))
        assertEquals(listOf(DeviceAction.PressEnter), followUps)
    }

    @Test
    fun `scroll rejects bad direction`() {
        assertEquals(
            FallbackReason.BAD_ARGUMENTS,
            reasonOf {
                NeedleResponseParser.parse(callJson("scroll", """{"direction":"SIDEWAYS"}"""), 0.6)
            },
        )
    }
}
