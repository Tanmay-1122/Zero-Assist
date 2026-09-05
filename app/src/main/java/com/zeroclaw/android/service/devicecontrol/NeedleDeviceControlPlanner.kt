package com.zeroclaw.android.service.devicecontrol

import android.util.Log
import com.zeroclaw.android.service.needle.NeedleEngine
import com.zeroclaw.android.service.needle.NeedlePromptCompressor
import com.zeroclaw.android.service.needle.NeedleToolSchema
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Reason a Needle step must fall back to the cloud planner. Thrown by
 * [NeedleDeviceControlPlanner] and caught only by [NeedleFirstPlanner].
 *
 * Field provenance (cactus-android AAR 1.4.3-beta inspection found NO
 * confidence field, but the native Needle 2 engine JSON carries one, so
 * LOW_CONFIDENCE is a live branch on this JNI path):
 * `CactusCompletionResult(success, response, timeToFirstTokenMs,
 * totalTimeMs, tokensPerSecond, prefillTokens, decodeTokens, totalTokens,
 * toolCalls)` — no confidence. Native `complete()` JSON —
 * `{type, function_calls[{name, arguments}], reasoning, confidence}`.
 */
enum class FallbackReason {
    TIMEOUT,
    EMPTY,
    LOW_CONFIDENCE,
    UNKNOWN_ACTION,
    BAD_ARGUMENTS,
    ENGINE_ERROR,
    WEAK_ACTION,
}

class NeedleFallbackRequired(val reason: FallbackReason) : Exception("Needle fallback: $reason")

/**
 * Pure-Needle implementation of [DeviceControlPlanner]: compressed prompt in,
 * typed tool call out. Never calls cloud itself; every Needle-side problem
 * throws [NeedleFallbackRequired] for [NeedleFirstPlanner] to route.
 *
 * Engine quirks honored: one process-global conversation (serialized in
 * [NeedleEngine]), `reset()` at each goal start (`request.step == 1`) while
 * tools stay pinned, single `complete()` per planner step (multi-step goals
 * are unrolled by [DeviceControlExecutor], not by feeding results back).
 */
class NeedleDeviceControlPlanner(
    private val engine: NeedleEngine,
    private val compressor: NeedlePromptCompressor = NeedlePromptCompressor(),
    private val toolsJson: String = NeedleToolSchema.toolsJson,
    private val minConfidence: Double = DEFAULT_MIN_CONFIDENCE,
    private val needleTimeoutMs: Long = NEEDLE_TIMEOUT_MS,
) : DeviceControlPlanner {

    override suspend fun nextAction(request: PlannerRequest): PlannerDecision {
        if (!engine.isReady()) throw NeedleFallbackRequired(FallbackReason.ENGINE_ERROR)
        return try {
            withTimeout(needleTimeoutMs) {
                if (request.step == 1) engine.reset()
                val input = compressor.compress(request)
                val raw = engine.complete(input.toUserText())
                    ?: throw NeedleFallbackRequired(FallbackReason.ENGINE_ERROR)
                parseResponse(raw)
            }
        } catch (e: NeedleFallbackRequired) {
            throw e
        } catch (e: TimeoutCancellationException) {
            throw NeedleFallbackRequired(FallbackReason.TIMEOUT)
        } catch (e: Exception) {
            // Coroutine cancellation (executor shutdown) must propagate, never
            // convert to fallback: isCancellationRequested drives the loop exit.
            if (e is kotlinx.coroutines.CancellationException) throw e
            throw NeedleFallbackRequired(FallbackReason.ENGINE_ERROR)
        }
    }

    private fun parseResponse(raw: String): PlannerDecision {
        val parsed = NeedleResponseParser.parse(raw, minConfidence)
        return PlannerDecision(
            action = parsed.action,
            reasoning = parsed.reasoning,
            isComplete = parsed.isComplete,
            followUpActions = NeedleResponseParser.inferFollowUps(parsed.action),
        )
    }

    companion object {
        /**
         * Confidence gate. The model card contract: act at/above threshold,
         * escalate below. 0.6 is the starting point; retune from the
         * LOW_CONFIDENCE fallback rate in logcat before changing.
         */
        const val DEFAULT_MIN_CONFIDENCE = 0.6

        /** Hard cap per step covering mutex queueing plus inference. */
        const val NEEDLE_TIMEOUT_MS = 4_000L
    }
}
