package com.zeroclaw.android.service.devicecontrol

import com.zeroclaw.android.model.ServiceState
import com.zeroclaw.android.service.DaemonServiceBridge
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ModelBackedDeviceControlPlanner")
class ModelBackedDeviceControlPlannerTest {

    private lateinit var bridge: DaemonServiceBridge

    @BeforeEach
    fun setUp() {
        bridge = mockk(relaxed = true)
        every { bridge.serviceState } returns MutableStateFlow(ServiceState.RUNNING)
    }

    private fun request() = PlannerRequest(
        requestId = "test",
        goal = "open settings",
        step = 1,
        maxSteps = 10,
        currentPackage = "com.test",
        screen = "PKG: com.test\nNODES (0 total)",
        previousAction = null,
        previousResult = null,
        failureCount = 0,
    )

    @Test
    @DisplayName("hung LLM call times out and returns Planner-failed Done within 15s")
    fun `hung call times out within 15s`() = runTest {
        coEvery { bridge.sendPlannerCompletion(any()) } coAnswers {
            kotlinx.coroutines.awaitCancellation()
        }
        val planner = ModelBackedDeviceControlPlanner(bridge, maxRetries = 1)

        val startVirtualMs = testScheduler.currentTime
        val decision = planner.nextAction(request())
        val elapsedVirtualMs = testScheduler.currentTime - startVirtualMs

        assertTrue(decision.action is DeviceAction.Done)
        assertTrue(
            (decision.action as DeviceAction.Done).message.startsWith("Planner failed"),
            "Unexpected message: ${(decision.action as DeviceAction.Done).message}",
        )
        assertTrue(
            elapsedVirtualMs < 15_000,
            "First-attempt timeout must stay under 15s, was ${elapsedVirtualMs}ms",
        )
    }

    @Test
    @DisplayName("timeout retries are bounded: full retry chain completes without parse backoff")
    fun `full retry chain is bounded`() = runTest {
        coEvery { bridge.sendPlannerCompletion(any()) } coAnswers {
            kotlinx.coroutines.awaitCancellation()
        }
        val planner = ModelBackedDeviceControlPlanner(bridge)

        val decision = planner.nextAction(request())
        val elapsedVirtualMs = testScheduler.currentTime

        assertTrue(decision.action is DeviceAction.Done)
        // 12s + 10s + 10s timeouts, zero parse backoff on pure timeouts.
        assertEquals(32_000L, elapsedVirtualMs)
    }

    @Test
    @DisplayName("invalid JSON retries with corrective context, then parses valid response")
    fun `parse retry recovers with corrective context`() = runTest {
        val validJson =
            """{"action":{"type":"home"},"reasoning":"go home","is_complete":false}"""
        var callIndex = 0
        val prompts = mutableListOf<String>()
        coEvery { bridge.sendPlannerCompletion(any()) } coAnswers {
            prompts.add(firstArg())
            if (callIndex++ == 0) {
                "this is definitely not json"
            } else {
                validJson
            }
        }

        val planner = ModelBackedDeviceControlPlanner(bridge)
        val decision = planner.nextAction(request())

        assertTrue(decision.action is DeviceAction.Home)
        assertEquals(2, prompts.size, "Expected exactly one corrective retry")
        assertTrue(
            prompts[1].contains("PREVIOUS RESPONSE WAS INVALID"),
            "Retry prompt must carry corrective context",
        )
        coVerify(exactly = 2) { bridge.sendPlannerCompletion(any()) }
    }

    @Test
    @DisplayName("parse failures apply exponential backoff before retrying")
    fun `parse failure applies backoff`() = runTest {
        var callIndex = 0
        coEvery { bridge.sendPlannerCompletion(any()) } coAnswers {
            if (callIndex++ == 0) {
                "not json"
            } else {
                """{"action":{"type":"back"},"reasoning":"b","is_complete":false}"""
            }
        }

        val planner = ModelBackedDeviceControlPlanner(bridge)
        val startVirtualMs = testScheduler.currentTime
        planner.nextAction(request())

        // Attempt 1 → 200ms backoff before the retry.
        assertEquals(200L, testScheduler.currentTime - startVirtualMs)
    }

    @Test
    @DisplayName("non-running daemon short-circuits to a failed Done")
    fun `daemon not running aborts`() = runTest {
        every { bridge.serviceState } returns MutableStateFlow(ServiceState.STOPPED)

        val decision = ModelBackedDeviceControlPlanner(bridge).nextAction(request())

        assertTrue(decision.action is DeviceAction.Done)
        assertTrue((decision.action as DeviceAction.Done).message.contains("stopped"))
    }
}
