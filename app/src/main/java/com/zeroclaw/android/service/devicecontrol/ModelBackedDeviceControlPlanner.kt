package com.zeroclaw.android.service.devicecontrol

import android.util.Log
import com.zeroclaw.android.model.ServiceState
import com.zeroclaw.android.service.DaemonServiceBridge
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/** Planner that calls the daemon's LLM for each device-control step. */
class ModelBackedDeviceControlPlanner(
    private val daemonBridge: DaemonServiceBridge,
    private val json: Json = PlannerJson.decisionJson,
    private val maxRetries: Int = 3,
) : DeviceControlPlanner {

    override suspend fun nextAction(request: PlannerRequest): PlannerDecision {
        val state = daemonBridge.serviceState.value
        if (state != ServiceState.RUNNING) {
            return abort(
                "Zero-Assist daemon is ${state.name.lowercase(Locale.US)}; device-control planning is unavailable.",
            )
        }

        val modelPrompt = buildPlannerPrompt(request)
        Log.d(TAG, "[${request.requestId}] step=${request.step} prompt_len=${modelPrompt.length}")

        var lastError: Exception? = null
        var correctiveContext: String? = null
        var lastAttemptWasParseFailure = false

        repeat(maxRetries) { attempt ->
            // Exponential backoff only on parse failure (the model is still
            // generating; a short pause gives it a fresh chance). Timeout
            // failures retry immediately — the call already burned its slot.
            if (attempt > 0 && lastAttemptWasParseFailure) {
                delay(RETRY_BACKOFF_BASE_MS shl (attempt - 1))
            }

            val promptToSend = if (correctiveContext != null) {
                modelPrompt + "\n\nPREVIOUS RESPONSE WAS INVALID:\n$correctiveContext\n\n" +
                    "You MUST respond with ONLY a valid JSON object using one of these action types:\n" +
                    "${SUPPORTED_ACTIONS_DOCUMENTATION}\n\n" +
                    "Respond with ONLY the JSON object. No markdown fences, no explanation."
            } else {
                modelPrompt
            }

            val timeoutMs = if (attempt == 0) FIRST_ATTEMPT_TIMEOUT_MS else RETRY_TIMEOUT_MS
            val rawResponse =
                try {
                    withTimeoutOrNull(timeoutMs) {
                        daemonBridge.sendPlannerCompletion(promptToSend)
                    } ?: run {
                        lastError = Exception("LLM planner call timed out after ${timeoutMs / 1_000}s")
                        lastAttemptWasParseFailure = false
                        Log.w(TAG, "[${request.requestId}] LLM call timed out after ${timeoutMs / 1_000}s (attempt ${attempt + 1}/$maxRetries)")
                        return@repeat
                    }
                } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    lastError = e
                    lastAttemptWasParseFailure = false
                    Log.w(TAG, "[${request.requestId}] LLM call failed (attempt ${attempt + 1}/$maxRetries): ${e.message}")
                    return@repeat
                }

            Log.d(TAG, "[${request.requestId}] raw response: ${rawResponse.take(500)}")

            val parsed = parseDecision(rawResponse)
            if (parsed != null) {
                Log.d(TAG, "[${request.requestId}] parsed action=${parsed.action::class.simpleName} complete=${parsed.isComplete} followUps=${parsed.followUpActions.size}")
                return parsed
            }

            lastError = Exception("Failed to parse LLM response: ${rawResponse.take(120)}")
            lastAttemptWasParseFailure = true
            correctiveContext = "Invalid raw response:\n${rawResponse.take(220)}"
            Log.w(TAG, "[${request.requestId}] Failed to parse LLM response (attempt ${attempt + 1}/$maxRetries)")
        }

        return PlannerDecision(
            action = DeviceAction.Done("Planner failed after $maxRetries attempts: ${lastError?.message}"),
            isComplete = true,
        )
    }

    private fun buildPlannerPrompt(request: PlannerRequest): String = buildString {
        appendLine(DeviceControlPrompt.SYSTEM_PROMPT)
        appendLine()

        // Compact goal + task context
        appendLine("GOAL: ${request.goal}")
        if (request.taskContext != null) {
            append(request.taskContext.toPromptContext())
        }
        appendLine("STEP: ${request.step} of ${request.maxSteps}")

        // Action history — compact
        if (request.actionHistory.isNotEmpty()) {
            appendLine("HISTORY:")
            request.actionHistory.takeLast(6).forEachIndexed { i, entry ->
                appendLine("  ${i + 1}. $entry")
            }
        }

        if (request.failureCount > 0) {
            appendLine("FAILURES: ${request.failureCount}")
        }

        appendLine()
        append(request.screen)
        appendLine()
        appendLine("CRITICAL: Respond with ONLY a valid JSON object. No markdown fences.")
    }

    @Serializable
    data class PlannerResponse(
        val action: JsonObject,
        val reasoning: String = "",
        val is_complete: Boolean = false,
        val follow_up_actions: List<JsonObject>? = null,
    )

    private fun parseDecision(raw: String): PlannerDecision? {
        val jsonText = extractJsonObject(raw) ?: run {
            Log.w(TAG, "No JSON object found in LLM response")
            return null
        }
        return try {
            val parsed = json.decodeFromString<PlannerResponse>(jsonText)

            val type = parsed.action["type"]?.toString()?.trim('"') ?: run {
                Log.w(TAG, "Action object missing 'type' field: ${parsed.action}")
                return null
            }

            val action = mapAction(type, parsed.action)
                ?: return null.also { Log.w(TAG, "Unknown action type: $type") }

            val followUps = parsed.follow_up_actions?.mapNotNull { fuJson ->
                val fuType = fuJson["type"]?.toString()?.trim('"') ?: return@mapNotNull null
                mapAction(fuType, fuJson)
            } ?: emptyList()

            PlannerDecision(
                action = action,
                reasoning = parsed.reasoning,
                isComplete = parsed.is_complete || action is DeviceAction.Done,
                followUpActions = followUps,
            )
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse planner JSON: ${e.message}")
            null
        }
    }

    private fun extractJsonObject(raw: String): String? {
        var trimmed = raw.trim()

        // Strip markdown code fences if present
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.substringAfter('\n').substringBeforeLast("```").trim()
        }

        val start = trimmed.indexOf('{')
        if (start == -1) return null

        var depth = 0
        var inString = false
        var isEscaped = false
        var end = -1

        for (i in start until trimmed.length) {
            val char = trimmed[i]
            if (isEscaped) {
                isEscaped = false
                continue
            }
            if (char == '\\') {
                isEscaped = true
                continue
            }
            if (char == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (char == '{') {
                    depth++
                } else if (char == '}') {
                    depth--
                    if (depth == 0) {
                        end = i
                        break
                    }
                }
            }
        }

        if (end != -1) {
            return trimmed.substring(start, end + 1)
        }

        val lastEnd = trimmed.lastIndexOf('}')
        if (start < lastEnd) {
            return trimmed.substring(start, lastEnd + 1)
        }
        return null
    }

    private fun mapAction(type: String, action: JsonObject): DeviceAction? = when (type) {
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
        "scroll" -> when (action["direction"]?.toString()?.trim('"')?.uppercase(Locale.US)) {
            "UP" -> DeviceAction.Scroll(DeviceAction.Direction.UP)
            "DOWN" -> DeviceAction.Scroll(DeviceAction.Direction.DOWN)
            else -> {
                Log.w(TAG, "scroll action missing/invalid direction, defaulting to DOWN")
                DeviceAction.Scroll(DeviceAction.Direction.DOWN)
            }
        }
        "swipe" -> {
            val sx = action["startX"]?.toString()?.trim('"')?.toFloatOrNull()
            val sy = action["startY"]?.toString()?.trim('"')?.toFloatOrNull()
            val ex = action["endX"]?.toString()?.trim('"')?.toFloatOrNull()
            val ey = action["endY"]?.toString()?.trim('"')?.toFloatOrNull()
            if (sx != null && sy != null && ex != null && ey != null)
                DeviceAction.Swipe(sx, sy, ex, ey, action["durationMs"]?.toString()?.trim('"')?.toLongOrNull() ?: 350)
            else null
        }
        "back", "press_back" -> DeviceAction.Back
        "home", "press_home" -> DeviceAction.Home
        "recents" -> DeviceAction.Recents
        "notifications", "open_notifications" -> DeviceAction.Notifications
        "open_app" -> {
            val name = action["app_name"]?.toString()?.trim('"') ?: "unknown"
            val pkg = action["package_name"]?.toString()?.trim('"')
            DeviceAction.OpenApp(name, pkg)
        }
        "wait" -> DeviceAction.Wait(action["millis"]?.toString()?.trim('"')?.toLongOrNull() ?: 1_000)
        "share_file" -> action["uri"]?.toString()?.trim('"')?.let {
            DeviceAction.ShareFile(
                it,
                action["mime_type"]?.toString()?.trim('"'),
                action["target_package"]?.toString()?.trim('"'),
            )
        }
        "done" -> DeviceAction.Done(action["message"]?.toString()?.trim('"') ?: "Done")
        else -> null
    }

    private fun abort(reason: String): PlannerDecision =
        PlannerDecision(action = DeviceAction.Done(reason), isComplete = true)

    companion object {
        private const val TAG = "ModelDevicePlanner"

        /**
         * First-attempt LLM timeout. Reduced from 25s — a healthy planner call
         * completes in well under 5s; hanging longer starves the action loop.
         */
        private const val FIRST_ATTEMPT_TIMEOUT_MS = 12_000L

        /** Retry timeout. Retries are corrective re-asks, not cold starts. */
        private const val RETRY_TIMEOUT_MS = 10_000L

        /** Base delay for parse-failure backoff; doubled per retry attempt. */
        private const val RETRY_BACKOFF_BASE_MS = 200L

        const val SUPPORTED_ACTION_TYPES =
            "click_text, click_index, click_at, type_text, press_enter, " +
            "scroll, swipe, back, home, recents, notifications, open_app, wait, share_file, done"

        const val SUPPORTED_ACTIONS_DOCUMENTATION = """
  click_text: {"type":"click_text","text":"<visible label>"}
  click_index: {"type":"click_index","index":<0-based integer>}
  click_at: {"type":"click_at","x":<float>,"y":<float>}
  type_text: {"type":"type_text","text":"<text>","field_hint":"<optional>"}
  press_enter: {"type":"press_enter"}
  scroll: {"type":"scroll","direction":"UP"|"DOWN"}
  swipe: {"type":"swipe","startX":<f>,"startY":<f>,"endX":<f>,"endY":<f>}
  back: {"type":"back"}
  home: {"type":"home"}
  recents: {"type":"recents"}
  notifications: {"type":"notifications"}
  open_app: {"type":"open_app","app_name":"<name>","package_name":"<optional>"}
  wait: {"type":"wait","millis":<long>}
  share_file: {"type":"share_file","uri":"content://...","mime_type":"<optional>"}
  done: {"type":"done","message":"<what was achieved>"}
"""
    }

    object PlannerJson {
        val decisionJson = Json {
            ignoreUnknownKeys = true
            coerceInputValues = true
            isLenient = true
        }
    }
}
