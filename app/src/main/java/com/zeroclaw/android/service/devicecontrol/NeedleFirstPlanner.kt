package com.zeroclaw.android.service.devicecontrol

import android.util.Log

/**
 * Needle-first orchestrator: owns the pure-Needle planner and the unchanged
 * cloud planner, implements [DeviceControlPlanner], and holds ALL fallback
 * policy in [nextAction]. [NeedleDeviceControlPlanner] never calls cloud;
 * [ModelBackedDeviceControlPlanner] is never modified for Needle.
 *
 * Per-step flow: weak-action pre-route → Needle attempt → cloud fallback
 * with the original unmodified [PlannerRequest]. Fallback is one-way per
 * step (cloud failures keep cloud retry semantics; never loops back).
 */
class NeedleFirstPlanner(
    private val needle: NeedleDeviceControlPlanner,
    private val cloud: ModelBackedDeviceControlPlanner,
) : DeviceControlPlanner {

    override suspend fun nextAction(request: PlannerRequest): PlannerDecision {
        // Zero-inference-cost guard: the previous cloud step already proved
        // float-coordinate precision is needed here; Needle cannot emit
        // ClickAt/Swipe (excluded from its schema), so skip the 4s burn.
        // Names match `lastAction::class.simpleName` as set by the executor.
        if (request.previousAction in WEAK_PREVIOUS_ACTIONS) {
            Log.d(TAG, "[${request.requestId}] pre-route to cloud (${request.previousAction})")
            return cloud.nextAction(request)
        }
        return try {
            needle.nextAction(request)
        } catch (e: NeedleFallbackRequired) {
            Log.d(TAG, "[${request.requestId}] Needle fallback (${e.reason}), routing to cloud")
            cloud.nextAction(request)
        }
    }

    companion object {
        private const val TAG = "NeedleFirst"

        private val WEAK_PREVIOUS_ACTIONS = setOf("ClickAt", "Swipe")
    }
}
