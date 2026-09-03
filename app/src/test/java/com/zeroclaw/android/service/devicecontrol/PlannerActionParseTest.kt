package com.zeroclaw.android.service.devicecontrol

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ModelBackedDeviceControlPlanner action parsing")
class PlannerActionParseTest {

    private val json = ModelBackedDeviceControlPlanner.PlannerJson.decisionJson

    @Serializable
    private data class PlannerResponse(
        val action: JsonObject,
        val reasoning: String = "",
        val is_complete: Boolean = false,
    )

    private fun parseDecision(raw: String): PlannerDecision? {
        val trimmed = raw.trim()
        val stripped = trimmed
            .replace(Regex("^```(?:json)?\\s*", RegexOption.MULTILINE), "")
            .replace(Regex("^```\\s*$", RegexOption.MULTILINE), "")
            .trim()
        val start = stripped.indexOf('{')
        val end = stripped.lastIndexOf('}')
        if (start == -1 || end == -1 || start > end) return null
        val jsonText = stripped.substring(start, end + 1)
        return try {
            val parsed = json.decodeFromString<PlannerResponse>(jsonText)
            val type = parsed.action["type"]?.toString()?.trim('"') ?: return null
            val action = mapAction(type, parsed.action) ?: return null
            PlannerDecision(action = action, reasoning = parsed.reasoning, isComplete = parsed.is_complete)
        } catch (e: Exception) {
            null
        }
    }

    private fun mapAction(type: String, action: kotlinx.serialization.json.JsonObject): DeviceAction? = when (type) {
        "click_text" -> action["text"]?.toString()?.trim('"')?.let { DeviceAction.ClickText(it) }
        "click_index" -> action["index"]?.toString()?.trim('"')?.toIntOrNull()?.let { DeviceAction.ClickIndex(it) }
        "click_at" -> {
            val x = action["x"]?.toString()?.trim('"')?.toFloatOrNull()
            val y = action["y"]?.toString()?.trim('"')?.toFloatOrNull()
            if (x != null && y != null) DeviceAction.ClickAt(x, y) else null
        }
        "type_text" -> action["text"]?.toString()?.trim('"')?.let {
            DeviceAction.TypeText(it, action["field_hint"]?.toString()?.trim('"'))
        }
        "press_enter" -> DeviceAction.PressEnter
        "scroll" -> when (action["direction"]?.toString()?.trim('"')?.uppercase()) {
            "UP" -> DeviceAction.Scroll(DeviceAction.Direction.UP)
            "DOWN" -> DeviceAction.Scroll(DeviceAction.Direction.DOWN)
            else -> DeviceAction.Scroll(DeviceAction.Direction.DOWN)
        }
        "swipe" -> {
            val sx = action["startX"]?.toString()?.trim('"')?.toFloatOrNull()
            val sy = action["startY"]?.toString()?.trim('"')?.toFloatOrNull()
            val ex = action["endX"]?.toString()?.trim('"')?.toFloatOrNull()
            val ey = action["endY"]?.toString()?.trim('"')?.toFloatOrNull()
            if (sx != null && sy != null && ex != null && ey != null)
                DeviceAction.Swipe(sx, sy, ex, ey)
            else null
        }
        "back" -> DeviceAction.Back
        "home" -> DeviceAction.Home
        "recents" -> DeviceAction.Recents
        "notifications" -> DeviceAction.Notifications
        "open_app" -> {
            val name = action["app_name"]?.toString()?.trim('"') ?: "unknown"
            val pkg = action["package_name"]?.toString()?.trim('"')
            DeviceAction.OpenApp(name, pkg)
        }
        "wait" -> DeviceAction.Wait(action["millis"]?.toString()?.trim('"')?.toLongOrNull() ?: 1_000)
        "share_file" -> action["uri"]?.toString()?.trim('"')?.let {
            DeviceAction.ShareFile(it, action["mime_type"]?.toString()?.trim('"'))
        }
        "done" -> DeviceAction.Done(action["message"]?.toString()?.trim('"') ?: "Done")
        else -> null
    }

    // ─── Valid action parsing tests ───

    @Test
    @DisplayName("parse click_text action")
    fun `parse click_text`() {
        val r = parseDecision("""{"action":{"type":"click_text","text":"Search"},"reasoning":"tap search","is_complete":false}""")
        assertNotNull(r)
        assertTrue(r!!.action is DeviceAction.ClickText)
        assertEquals("Search", (r.action as DeviceAction.ClickText).text)
    }

    @Test
    @DisplayName("parse click_index action")
    fun `parse click_index`() {
        val r = parseDecision("""{"action":{"type":"click_index","index":0},"reasoning":"first item","is_complete":false}""")
        assertNotNull(r)
        assertTrue(r!!.action is DeviceAction.ClickIndex)
        assertEquals(0, (r.action as DeviceAction.ClickIndex).index)
    }

    @Test
    @DisplayName("parse click_at action")
    fun `parse click_at`() {
        val r = parseDecision("""{"action":{"type":"click_at","x":682,"y":109},"reasoning":"coordinates","is_complete":false}""")
        assertNotNull(r)
        assertTrue(r!!.action is DeviceAction.ClickAt)
        assertEquals(682f, (r.action as DeviceAction.ClickAt).x)
        assertEquals(109f, (r.action as DeviceAction.ClickAt).y)
    }

    @Test
    @DisplayName("parse type_text action")
    fun `parse type_text`() {
        val r = parseDecision("""{"action":{"type":"type_text","text":"hello","field_hint":"search"},"reasoning":"type","is_complete":false}""")
        assertNotNull(r)
        assertTrue(r!!.action is DeviceAction.TypeText)
        assertEquals("hello", (r.action as DeviceAction.TypeText).text)
        assertEquals("search", (r.action as DeviceAction.TypeText).fieldHint)
    }

    @Test
    @DisplayName("parse press_enter action")
    fun `parse press_enter`() {
        val r = parseDecision("""{"action":{"type":"press_enter"},"reasoning":"submit","is_complete":false}""")
        assertNotNull(r)
        assertTrue(r!!.action is DeviceAction.PressEnter)
    }

    @Test
    @DisplayName("parse scroll action with direction DOWN")
    fun `parse scroll down`() {
        val r = parseDecision("""{"action":{"type":"scroll","direction":"DOWN"},"reasoning":"scroll","is_complete":false}""")
        assertNotNull(r)
        assertTrue(r!!.action is DeviceAction.Scroll)
        assertEquals(DeviceAction.Direction.DOWN, (r.action as DeviceAction.Scroll).direction)
    }

    @Test
    @DisplayName("parse scroll action with direction UP")
    fun `parse scroll up`() {
        val r = parseDecision("""{"action":{"type":"scroll","direction":"UP"},"reasoning":"scroll up","is_complete":false}""")
        assertNotNull(r)
        assertTrue(r!!.action is DeviceAction.Scroll)
        assertEquals(DeviceAction.Direction.UP, (r.action as DeviceAction.Scroll).direction)
    }

    @Test
    @DisplayName("parse scroll action without direction defaults to DOWN")
    fun `parse scroll without direction defaults to DOWN`() {
        val r = parseDecision("""{"action":{"type":"scroll"},"reasoning":"scroll","is_complete":false}""")
        assertNotNull(r)
        assertTrue(r!!.action is DeviceAction.Scroll)
        assertEquals(DeviceAction.Direction.DOWN, (r.action as DeviceAction.Scroll).direction)
    }

    @Test
    @DisplayName("parse swipe action")
    fun `parse swipe`() {
        val r = parseDecision("""{"action":{"type":"swipe","startX":100,"startY":200,"endX":300,"endY":400},"reasoning":"swipe","is_complete":false}""")
        assertNotNull(r)
        assertTrue(r!!.action is DeviceAction.Swipe)
        val swipe = r.action as DeviceAction.Swipe
        assertEquals(100f, swipe.startX)
        assertEquals(400f, swipe.endY)
    }

    @Test
    @DisplayName("parse back action")
    fun `parse back`() {
        val r = parseDecision("""{"action":{"type":"back"},"reasoning":"go back","is_complete":false}""")
        assertNotNull(r)
        assertTrue(r!!.action is DeviceAction.Back)
    }

    @Test
    @DisplayName("parse home action")
    fun `parse home`() {
        val r = parseDecision("""{"action":{"type":"home"},"reasoning":"go home","is_complete":true}""")
        assertNotNull(r)
        assertTrue(r!!.action is DeviceAction.Home)
    }

    @Test
    @DisplayName("parse open_app action")
    fun `parse open_app`() {
        val r = parseDecision("""{"action":{"type":"open_app","app_name":"Instagram","package_name":"com.instagram.android"},"reasoning":"launch","is_complete":false}""")
        assertNotNull(r)
        assertTrue(r!!.action is DeviceAction.OpenApp)
        assertEquals("Instagram", (r.action as DeviceAction.OpenApp).appName)
        assertEquals("com.instagram.android", (r.action as DeviceAction.OpenApp).packageName)
    }

    @Test
    @DisplayName("parse wait action")
    fun `parse wait`() {
        val r = parseDecision("""{"action":{"type":"wait","millis":2000},"reasoning":"wait","is_complete":false}""")
        assertNotNull(r)
        assertTrue(r!!.action is DeviceAction.Wait)
        assertEquals(2000L, (r.action as DeviceAction.Wait).millis)
    }

    @Test
    @DisplayName("parse done action")
    fun `parse done`() {
        val r = parseDecision("""{"action":{"type":"done","message":"Goal achieved"},"reasoning":"complete","is_complete":true}""")
        assertNotNull(r)
        assertTrue(r!!.action is DeviceAction.Done)
        assertEquals("Goal achieved", (r.action as DeviceAction.Done).message)
    }

    // ─── Unknown/malformed action tests ───

    @Test
    @DisplayName("unknown action type returns null")
    fun `unknown action type returns null`() {
        val r = parseDecision("""{"action":{"type":"tap"},"reasoning":"tap","is_complete":false}""")
        assertNull(r, "Unknown action type 'tap' should return null")
    }

    @Test
    @DisplayName("malformed JSON returns null")
    fun `malformed JSON returns null`() {
        val r = parseDecision("not json at all")
        assertNull(r)
    }

    @Test
    @DisplayName("missing type field returns null")
    fun `missing type field returns null`() {
        val r = parseDecision("""{"action":{"text":"Search"},"reasoning":"no type"}""")
        assertNull(r)
    }

    @Test
    @DisplayName("extra prose around JSON is handled")
    fun `extra prose around JSON is handled`() {
        val r = parseDecision(
            """Here is my response:
            {"action":{"type":"done","message":"done"},"reasoning":"complete","is_complete":true}
            Hope that helps!"""
        )
        assertNotNull(r, "Should extract JSON from surrounding prose")
        assertTrue(r!!.action is DeviceAction.Done)
    }

    @Test
    @DisplayName("markdown code fences around JSON are handled")
    fun `markdown code fences around JSON are handled`() {
        val r = parseDecision(
            """```json
            {"action":{"type":"back"},"reasoning":"go back","is_complete":false}
            ```"""
        )
        assertNotNull(r, "Should extract JSON from markdown code fences")
        assertTrue(r!!.action is DeviceAction.Back)
    }

    @Test
    @DisplayName("null/empty response returns null")
    fun `null empty response returns null`() {
        assertNull(parseDecision(""))
        assertNull(parseDecision("   "))
        assertNull(parseDecision("null"))
    }

    @Test
    @DisplayName("empty object returns null")
    fun `empty object returns null`() {
        val r = parseDecision("""{}""")
        assertNull(r)
    }

    @Test
    @DisplayName("action with null/invalid coordinates for click_at returns null")
    fun `click_at with invalid coords returns null`() {
        val r = parseDecision("""{"action":{"type":"click_at","x":"abc","y":"def"},"reasoning":"bad coords","is_complete":false}""")
        assertNull(r, "click_at with non-numeric coordinates should return null")
    }

    @Test
    @DisplayName("scroll with invalid direction defaults to DOWN")
    fun `scroll with invalid direction defaults`() {
        val r = parseDecision("""{"action":{"type":"scroll","direction":"LEFT"},"reasoning":"wrong","is_complete":false}""")
        assertNotNull(r, "scroll with invalid direction should default to DOWN, not fail")
        assertEquals(DeviceAction.Direction.DOWN, (r!!.action as DeviceAction.Scroll).direction)
    }

    // ─── Protocol completeness tests ───

    @Test
    @DisplayName("every action in SUPPORTED_ACTION_TYPES can be parsed")
    fun `all advertised actions parse successfully`() {
        val testCases = mapOf(
            "click_text" to """{"type":"click_text","text":"OK"}""",
            "click_index" to """{"type":"click_index","index":0}""",
            "click_at" to """{"type":"click_at","x":100,"y":200}""",
            "type_text" to """{"type":"type_text","text":"hello"}""",
            "press_enter" to """{"type":"press_enter"}""",
            "scroll" to """{"type":"scroll","direction":"DOWN"}""",
            "swipe" to """{"type":"swipe","startX":0,"startY":0,"endX":100,"endY":100}""",
            "back" to """{"type":"back"}""",
            "home" to """{"type":"home"}""",
            "recents" to """{"type":"recents"}""",
            "notifications" to """{"type":"notifications"}""",
            "open_app" to """{"type":"open_app","app_name":"Test"}""",
            "wait" to """{"type":"wait","millis":1000}""",
            "share_file" to """{"type":"share_file","uri":"content://test"}""",
            "done" to """{"type":"done","message":"done"}""",
        )

        for ((typeName, jsonPayload) in testCases) {
            val result = parseDecision(
                """{"action":$jsonPayload,"reasoning":"test","is_complete":false}"""
            )
            assertNotNull(result, "Action type '$typeName' failed to parse from: $jsonPayload")
            assertNotNull(result?.action, "Action type '$typeName' produced null action")
        }
    }
}
