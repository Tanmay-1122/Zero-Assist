package com.zeroclaw.android.service.devicecontrol

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import com.zeroclaw.android.ZeroClawApplication
import com.zeroclaw.android.service.DaemonServiceBridge
import com.zeroclaw.android.service.needle.NeedleEngine
import com.zeroclaw.android.service.needle.NeedleFlags
import com.zeroclaw.ffi.DeviceControlHandler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * Implements the UniFFI [DeviceControlHandler] callback interface so the
 * Rust daemon can dispatch device_control tool calls directly into Kotlin
 * without an HTTP bridge.
 *
 * Phase 9: Top-level exception containment ensures no recoverable planner
 * error crosses the FFI boundary as an uncaught exception. All exceptions
 * are converted to structured JSON error results.
 *
 * Phase 10: Structured error codes for the outer agent to inspect and
 * make informed retry/fallback decisions.
 */
class DeviceControlCallbackHandler(
    private val context: android.content.Context,
    private val daemonBridge: DaemonServiceBridge,
) : DeviceControlHandler {

    override fun executeDeviceControl(paramsJson: String): String {
        val json = runCatching { JSONObject(paramsJson) }.getOrElse {
            return failJson("Invalid JSON arguments: ${it.message}", DeviceControlResult.ErrorCode.INTERNAL_ERROR)
        }
        val goal = extractGoal(json).ifEmpty {
            return failJson("Missing required field: goal", DeviceControlResult.ErrorCode.INTERNAL_ERROR)
        }
        val maxSteps = json.optInt("max_steps", 30).coerceIn(1, 60)

        val requestId = java.util.UUID.randomUUID().toString().take(8)
        Log.i(TAG, "[$requestId] executeDeviceControl goal='$goal' maxSteps=$maxSteps thread=${Thread.currentThread().name}")

        return try {
            val planner: DeviceControlPlanner = needlePlannerOrNull()
                ?: ModelBackedDeviceControlPlanner(daemonBridge)
            val executor = DeviceControlExecutor(context, planner, maxSteps = maxSteps)

            val result = runBlocking(Dispatchers.IO) {
                executor.execute(goal)
            }

            Log.i(TAG, "[$requestId] executeDeviceControl result=${result::class.simpleName}")

            when (result) {
                is DeviceControlResult.Success -> JSONObject().apply {
                    put("success", true)
                    put("message", result.message)
                    put("steps", result.steps)
                }.toString()

                is DeviceControlResult.Failure -> JSONObject().apply {
                    put("success", false)
                    put("error", result.message)
                    put("steps", result.steps)
                    if (result.errorCode != null) {
                        put("error_code", result.errorCode.name)
                        put("retryable", result.retryable)
                    }
                    // Include diagnostics if available from app launch failures
                    val diagnostics = extractDiagnostics(result)
                    if (diagnostics != null) {
                        put("diagnostics", diagnostics)
                    }
                }.toString()

                is DeviceControlResult.Cancelled -> JSONObject().apply {
                    put("success", false)
                    put("error", "Cancelled")
                    put("steps", result.steps)
                    put("error_code", DeviceControlResult.ErrorCode.CANCELLED.name)
                }.toString()
            }
        } catch (e: CancellationException) {
            Log.w(TAG, "[$requestId] cancelled", e)
            failJson("Device control cancelled", DeviceControlResult.ErrorCode.CANCELLED)
        } catch (e: Exception) {
            Log.e(TAG, "[$requestId] unhandled exception in device_control callback: " +
                "${e::class.simpleName}: ${e.message}\n" +
                "thread=${Thread.currentThread().name}\n" +
                "stackTrace=${e.stackTraceToString().take(2000)}", e)
            failJson(
                "Device control internal error: ${e::class.simpleName}: ${e.message}",
                DeviceControlResult.ErrorCode.INTERNAL_ERROR,
            )
        }
    }

    private fun extractDiagnostics(result: DeviceControlResult.Failure): JSONObject? {
        // Diagnostics are passed through the message for now; the outer agent
        // can parse the structured error_code field to make decisions.
        return null
    }

        /**
     * Needle-first planner when the feature flag is ON, the device ABI is
     * supported, and the engine finished its daemon-start warm-up. Any miss
     * returns null and the caller falls back to cloud-only behavior.
     */
    private fun needlePlannerOrNull(): DeviceControlPlanner? {
        if (!NeedleFlags.plannerEnabled) return null
        if (!NeedleEngine.isDeviceSupported()) return null
        if (!hasEnoughRam()) return null
        val app = context.applicationContext as? ZeroClawApplication ?: return null
        if (!app.needleEngine.isReady()) return null
        return NeedleFirstPlanner(
            NeedleDeviceControlPlanner(app.needleEngine),
            ModelBackedDeviceControlPlanner(daemonBridge),
        )
    }

    private fun hasEnoughRam(): Boolean {
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val memInfo = ActivityManager.MemoryInfo()
        activityManager.getMemoryInfo(memInfo)
        return memInfo.totalMem >= MIN_TOTAL_RAM_BYTES
    }

    private fun extractGoal(json: JSONObject): String =
        GOAL_KEYS
            .asSequence()
            .map { key -> json.optString(key, "").trim() }
            .firstOrNull { it.isNotEmpty() }
            .orEmpty()

    private fun failJson(error: String, errorCode: DeviceControlResult.ErrorCode? = null): String =
        JSONObject().apply {
            put("success", false)
            put("error", error)
            put("steps", 0)
            if (errorCode != null) {
                put("error_code", errorCode.name)
                put("retryable", errorCode == DeviceControlResult.ErrorCode.ACCESSIBILITY_DISABLED ||
                    errorCode == DeviceControlResult.ErrorCode.NO_ACTIVE_WINDOW ||
                    errorCode == DeviceControlResult.ErrorCode.APP_LAUNCH_FAILED)
            }
        }.toString()

    companion object {
        private const val TAG = "DeviceControlCB"
        private val GOAL_KEYS = listOf("goal", "instruction", "command", "task", "request", "query", "text")

        /** Minimum device RAM for the Needle path; below this, cloud-only. */
        private const val MIN_TOTAL_RAM_BYTES = 3L * 1024 * 1024 * 1024
    }
}
