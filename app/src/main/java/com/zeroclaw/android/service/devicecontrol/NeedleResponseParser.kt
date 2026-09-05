package com.zeroclaw.android.service.devicecontrol

import android.util.Log
import com.zeroclaw.android.service.needle.NeedleToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Parsed form of one native Needle `complete()` response.
 *
 * Separated from [NeedleDeviceControlPlanner] so response mapping stays
 * JVM-unit-testable without loading the native engine.
 */
internal data class ParsedNeedleCall(
    val action: DeviceAction,
    val reasoning: String,
    val isComplete: Boolean,
)

internal object NeedleResponseParser {

    private const val TAG = "NeedleParser"
    private val NATIVE_JSON = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * Maps raw engine JSON to [ParsedNeedleCall].
     *
     * Accepted shape: `{"type":"call","function_calls":[{"name","arguments"}],
     * "reasoning","confidence"}`. The empty call `[]` (engine refusal) and
     * `respond` (plain-text answer) both throw [FallbackReason.EMPTY] — text
     * is never converted into actions.
     *
     * @throws NeedleFallbackRequired with EMPTY, LOW_CONFIDENCE,
     *   UNKNOWN_ACTION, BAD_ARGUMENTS, or ENGINE_ERROR.
     */
    fun parse(raw: String, minConfidence: Double): ParsedNeedleCall {
        val element = try {
            NATIVE_JSON.parseToJsonElement(raw)
        } catch (e: Exception) {
            throw NeedleFallbackRequired(FallbackReason.ENGINE_ERROR)
        }
        if (element is JsonArray) throw NeedleFallbackRequired(FallbackReason.EMPTY)
        val obj = element as? JsonObject
            ?: throw NeedleFallbackRequired(FallbackReason.ENGINE_ERROR)
        if (obj["type"]?.jsonPrimitive?.content != "call") {
            throw NeedleFallbackRequired(FallbackReason.EMPTY)
        }
        val call = obj["function_calls"]?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw NeedleFallbackRequired(FallbackReason.EMPTY)
        val name = call["name"]?.jsonPrimitive?.content
            ?: throw NeedleFallbackRequired(FallbackReason.BAD_ARGUMENTS)
        if (name !in NeedleToolSchema.toolNames) {
            Log.w(TAG, "Unknown Needle action: $name")
            throw NeedleFallbackRequired(FallbackReason.UNKNOWN_ACTION)
        }
        obj["confidence"]?.jsonPrimitive?.doubleOrNull?.let { confidence ->
            if (confidence < minConfidence) throw NeedleFallbackRequired(FallbackReason.LOW_CONFIDENCE)
        }
        val args = call["arguments"]?.jsonObject ?: JsonObject(emptyMap())
        val action = mapToolCall(name, args)
            ?: throw NeedleFallbackRequired(FallbackReason.BAD_ARGUMENTS)
        return ParsedNeedleCall(
            action = action,
            reasoning = obj["reasoning"]?.jsonPrimitive?.content.orEmpty(),
            isComplete = action is DeviceAction.Done,
        )
    }

    fun mapToolCall(name: String, args: JsonObject): DeviceAction? = when (name) {
        "click_text" -> stringArg(args, "text")?.let { DeviceAction.ClickText(it) }
        "click_index" -> stringArg(args, "index")?.toIntOrNull()?.let { DeviceAction.ClickIndex(it) }
        "type_text" -> stringArg(args, "text")?.let {
            DeviceAction.TypeText(it, stringArg(args, "field_hint"))
        }
        "press_enter" -> DeviceAction.PressEnter
        "scroll" -> when (stringArg(args, "direction")?.uppercase()) {
            "UP" -> DeviceAction.Scroll(DeviceAction.Direction.UP)
            "DOWN" -> DeviceAction.Scroll(DeviceAction.Direction.DOWN)
            else -> null
        }
        "open_app" -> stringArg(args, "app_name")?.let {
            DeviceAction.OpenApp(it, stringArg(args, "package_name"))
        }
        "wait" -> DeviceAction.Wait(stringArg(args, "millis")?.toLongOrNull() ?: 1_000L)
        "done" -> DeviceAction.Done(stringArg(args, "message") ?: "Done")
        else -> null
    }

    fun inferFollowUps(action: DeviceAction): List<DeviceAction> = when (action) {
        // Mirrors the cloud prompt's batching rule; everything else resolves
        // on the next planner step against fresh screen state.
        is DeviceAction.TypeText -> listOf(DeviceAction.PressEnter)
        else -> emptyList()
    }

    private fun stringArg(args: JsonObject, key: String): String? {
        val primitive = args[key] as? JsonPrimitive ?: return null
        if (primitive.isString) return primitive.content.takeIf { it.isNotBlank() }
        return primitive.toString()
    }
}
