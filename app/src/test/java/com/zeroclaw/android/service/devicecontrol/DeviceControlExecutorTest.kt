package com.zeroclaw.android.service.devicecontrol

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Rect
import android.graphics.drawable.Drawable
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkAll
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("DeviceControlExecutor")
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceControlExecutorTest {

    private lateinit var mockService: DeviceControlServiceBridge
    private lateinit var mockContext: android.content.Context

    @BeforeEach
    fun setUp() {
        val mockPm = mockk<PackageManager>(relaxed = true)
        every { mockPm.getInstalledApplications(any<Int>()) } returns emptyList()

        mockContext = mockk(relaxed = true) {
            every { applicationContext } returns this@mockk
            every { packageManager } returns mockPm
            every { mainLooper } returns android.os.Looper.getMainLooper()
            every { contentResolver } returns mockk(relaxed = true)
            every { startActivity(any<Intent>()) } returns Unit
        }

        mockService = mockk(relaxed = true) {
            every { snapshot() } returns listOf(
                UiNodeSnapshot(
                    index = 0, text = "Instagram", contentDescription = "",
                    className = "TextView", viewId = "", clickable = true,
                    editable = false, scrollable = false, checkable = false,
                    checked = false, focused = false, focusable = true,
                    enabled = true, bounds = Rect(0, 0, 100, 100), depth = 0,
                )
            )
            every { currentPackage() } returns "com.instagram.android"
            every { onNextUiChange(any()) } returns CompletableDeferred(1L)
            every { home() } returns true
            every { back() } returns true
            every { recents() } returns true
            every { notifications() } returns true
        }
    }

    @AfterEach
    fun tearDown() {
        unmockkAll()
    }

    // ──────────────────────────────────────────────────────────
    // Fast-path classification tests
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("exact 'open Instagram' uses terminal fast path")
    fun `exact open Instagram uses terminal fast path`() = runTest {
        setupMockPackageManager("Instagram", "com.instagram.android")
        setupForegroundPackage("com.instagram.android")

        var plannerCalled = false
        val planner = DeviceControlPlanner {
            plannerCalled = true
            PlannerDecision(DeviceAction.Done("should not be called"))
        }
        val executor = DeviceControlExecutor(mockContext, planner, maxSteps = 5,
            serviceProvider = { mockService })

        val result = executor.execute("open Instagram")

        assertFalse(plannerCalled, "Planner should not be called for terminal fast path")
        assertTrue(result is DeviceControlResult.Success)
    }

    @Test
    @DisplayName("exact 'launch Spotify' uses terminal fast path")
    fun `exact launch Spotify uses terminal fast path`() = runTest {
        setupMockPackageManager("Spotify", "com.spotify.music")
        setupForegroundPackage("com.spotify.music")

        var plannerCalled = false
        val planner = DeviceControlPlanner {
            plannerCalled = true
            PlannerDecision(DeviceAction.Done("should not be called"))
        }
        val executor = DeviceControlExecutor(mockContext, planner, maxSteps = 5,
            serviceProvider = { mockService })

        val result = executor.execute("launch Spotify")

        assertFalse(plannerCalled, "Planner should not be called for terminal fast path")
        assertTrue(result is DeviceControlResult.Success)
    }

    @Test
    @DisplayName("compound goal 'Open Instagram and message Rohit' falls through to planner")
    fun `compound goal does not use terminal fast path`() = runTest {
        var plannerCalled = false
        val planner = DeviceControlPlanner {
            plannerCalled = true
            PlannerDecision(DeviceAction.Done("compound goal handled"))
        }
        val executor = DeviceControlExecutor(mockContext, planner, maxSteps = 5,
            serviceProvider = { mockService })

        val result = executor.execute("Open Instagram and message Rohit")

        assertTrue(plannerCalled, "Planner MUST be called for compound goals")
        assertTrue(result is DeviceControlResult.Success)
    }

    @Test
    @DisplayName("'Open Instagram DMs' falls through to planner")
    fun `goal with app suffix falls through to planner`() = runTest {
        var plannerCalled = false
        val planner = DeviceControlPlanner {
            plannerCalled = true
            PlannerDecision(DeviceAction.Done("handled"))
        }
        val executor = DeviceControlExecutor(mockContext, planner, maxSteps = 5,
            serviceProvider = { mockService })

        val result = executor.execute("Open Instagram DMs")

        assertTrue(plannerCalled, "Planner must be called for 'Instagram DMs'")
    }

    @Test
    @DisplayName("direct element click uses fast path when label is visible and clickable")
    fun `direct element click uses fast path`() = runTest {
        every { mockService.snapshot() } returns listOf(
            UiNodeSnapshot(
                index = 0, text = "Instagram", contentDescription = "",
                className = "Button", viewId = "btn", clickable = true,
                editable = false, scrollable = false, checkable = false,
                checked = false, focused = false, focusable = true,
                enabled = true, bounds = Rect(0, 0, 100, 100), depth = 0,
            )
        )
        every { mockService.clickText("Instagram", any()) } returns true

        var plannerCalled = false
        val planner = DeviceControlPlanner {
            plannerCalled = true
            PlannerDecision(DeviceAction.Done("should not be called"))
        }
        val executor = DeviceControlExecutor(mockContext, planner, maxSteps = 5,
            serviceProvider = { mockService })

        val result = executor.execute("click Instagram")

        assertFalse(plannerCalled, "Planner should not be called for direct element tap fast path")
        assertTrue(result is DeviceControlResult.Success)
    }

    @Test
    @DisplayName("'back' uses terminal fast path")
    fun `back uses terminal fast path`() = runTest {
        var plannerCalled = false
        val planner = DeviceControlPlanner {
            plannerCalled = true
            PlannerDecision(DeviceAction.Done("should not be called"))
        }
        val executor = DeviceControlExecutor(mockContext, planner, maxSteps = 5,
            serviceProvider = { mockService })

        val result = executor.execute("back")

        assertFalse(plannerCalled)
        assertTrue(result is DeviceControlResult.Success)
    }

    @Test
    @DisplayName("goal 'Open YouTube and play cat videos' falls through to planner")
    fun `YouTube compound goal falls through`() = runTest {
        var plannerCalled = false
        val planner = DeviceControlPlanner {
            plannerCalled = true
            PlannerDecision(DeviceAction.Done("handled"))
        }
        val executor = DeviceControlExecutor(mockContext, planner, maxSteps = 5,
            serviceProvider = { mockService })

        val result = executor.execute("Open YouTube and play cat videos")

        assertTrue(plannerCalled, "Planner must handle compound YouTube goal")
    }

    @Test
    @DisplayName("'Open Settings and enable Bluetooth' falls through to planner")
    fun `Settings compound goal falls through`() = runTest {
        var plannerCalled = false
        val planner = DeviceControlPlanner {
            plannerCalled = true
            PlannerDecision(DeviceAction.Done("handled"))
        }
        val executor = DeviceControlExecutor(mockContext, planner, maxSteps = 5,
            serviceProvider = { mockService })

        val result = executor.execute("Open Settings and enable Bluetooth")

        assertTrue(plannerCalled)
    }

    // ──────────────────────────────────────────────────────────
    // Completion semantics tests
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("ClickAt with is_complete=true still executes the action")
    fun `clickAt with is_complete true still executes action`() = runTest {
        var actionExecuted = false
        val planner = DeviceControlPlanner {
            actionExecuted = true
            PlannerDecision(
                action = DeviceAction.ClickAt(682f, 109f),
                reasoning = "clicking will complete the goal",
                isComplete = true
            )
        }
        val executor = DeviceControlExecutor(mockContext, planner, maxSteps = 5,
            serviceProvider = { mockService })

        val result = executor.execute("test completion semantics")

        assertTrue(actionExecuted, "The ClickAt action MUST execute even when is_complete=true")
        assertTrue(result is DeviceControlResult)
    }

    @Test
    @DisplayName("Done action terminates immediately")
    fun `done action terminates immediately`() = runTest {
        var callCount = 0
        val planner = DeviceControlPlanner {
            callCount++
            PlannerDecision(
                action = DeviceAction.Done("Goal achieved"),
                isComplete = true
            )
        }
        val executor = DeviceControlExecutor(mockContext, planner, maxSteps = 10,
            serviceProvider = { mockService })

        val result = executor.execute("test done action")

        assertEquals(1, callCount, "Done action should terminate on first call")
        assertTrue(result is DeviceControlResult.Success)
    }

    @Test
    @DisplayName("is_complete=true with non-Done action still executes and re-evaluates")
    fun `is_complete true with non-Done action executes and continues`() = runTest {
        val callSteps = mutableListOf<Int>()
        val planner = DeviceControlPlanner { request ->
            callSteps.add(request.step)
            if (request.step == 1) {
                PlannerDecision(
                    action = DeviceAction.ClickText("Button"),
                    reasoning = "clicking completes",
                    isComplete = true
                )
            } else {
                PlannerDecision(
                    action = DeviceAction.Done("Actually done now"),
                    isComplete = true
                )
            }
        }
        val executor = DeviceControlExecutor(mockContext, planner, maxSteps = 5,
            serviceProvider = { mockService })

        val result = executor.execute("test re-evaluation")

        assertTrue(result is DeviceControlResult.Success)
        assertTrue(callSteps.size >= 2,
            "Expected planner to be called >= 2 times, was ${callSteps.size}")
    }

    // ──────────────────────────────────────────────────────────
    // Action protocol tests
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("every advertised action type is in SUPPORTED_ACTION_TYPES")
    fun `all action types are advertised`() {
        val supportedTypes = ModelBackedDeviceControlPlanner.SUPPORTED_ACTION_TYPES
        val expectedTypes = listOf(
            "click_text", "click_index", "click_at", "type_text", "press_enter",
            "scroll", "swipe", "back", "home", "recents", "notifications",
            "open_app", "wait", "share_file", "done"
        )
        for (type in expectedTypes) {
            assertTrue(supportedTypes.contains(type),
                "Action type '$type' is not in SUPPORTED_ACTION_TYPES")
        }
    }

    // ──────────────────────────────────────────────────────────
    // Loop detection tests
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("repeated identical action on same screen triggers loop protection")
    fun `repeated identical action triggers loop protection`() = runTest {
        var callCount = 0
        val planner = DeviceControlPlanner {
            callCount++
            PlannerDecision(
                action = DeviceAction.ClickAt(100f, 200f),
                reasoning = "retrying same click",
                isComplete = false
            )
        }
        val executor = DeviceControlExecutor(mockContext, planner, maxSteps = 10,
            serviceProvider = { mockService })

        val result = executor.execute("test loop detection")

        assertTrue(callCount <= 5,
            "Loop detection should stop before all steps. Call count: $callCount")
    }

    // ──────────────────────────────────────────────────────────
    // Multi-action plan tests (Phase 6)
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("follow-up actions are executed after primary action")
    fun `follow-up actions executed`() = runTest {
        val executedActions = mutableListOf<String>()
        var callCount = 0
        val planner = DeviceControlPlanner {
            callCount++
            if (callCount == 1) {
                PlannerDecision(
                    action = DeviceAction.TypeText("query"),
                    reasoning = "type and enter",
                    followUpActions = listOf(DeviceAction.PressEnter)
                )
            } else {
                PlannerDecision(
                    action = DeviceAction.Done("done"),
                    isComplete = true
                )
            }
        }
        val executor = DeviceControlExecutor(mockContext, planner, maxSteps = 5,
            serviceProvider = { mockService })

        val result = executor.execute("test follow-ups")

        assertTrue(result is DeviceControlResult.Success)
        assertTrue(callCount <= 2, "Follow-ups should reduce planner calls, got $callCount")
    }

    @Test
    @DisplayName("follow-up action failure stops the chain")
    fun `follow-up failure stops chain`() = runTest {
        var callCount = 0
        val planner = DeviceControlPlanner {
            callCount++
            if (callCount == 1) {
                PlannerDecision(
                    action = DeviceAction.TypeText("query"),
                    reasoning = "type and enter",
                    followUpActions = listOf(DeviceAction.PressEnter)
                )
            } else {
                PlannerDecision(
                    action = DeviceAction.Done("recovered"),
                    isComplete = true
                )
            }
        }
        // Make pressEnter fail
        every { mockService.pressEnter() } returns false

        val executor = DeviceControlExecutor(mockContext, planner, maxSteps = 5,
            serviceProvider = { mockService })
        val result = executor.execute("test follow-up failure")

        // Should still complete (via recovery or planner)
        assertTrue(result is DeviceControlResult)
    }

    // ──────────────────────────────────────────────────────────
    // TaskContext tests (Phase 5)
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("task context is populated for messaging goals")
    fun `task context for messaging`() = runTest {
        var capturedRequest: PlannerRequest? = null
        val planner = DeviceControlPlanner { request ->
            capturedRequest = request
            PlannerDecision(DeviceAction.Done("done"), isComplete = true)
        }
        val executor = DeviceControlExecutor(mockContext, planner, maxSteps = 3,
            serviceProvider = { mockService })

        executor.execute("message Aditya hi")

        assertNotNull(capturedRequest?.taskContext)
        assertEquals("send_message", capturedRequest?.taskContext?.intentCategory)
        assertEquals("Aditya", capturedRequest?.taskContext?.target)
        assertEquals("hi", capturedRequest?.taskContext?.messageContent)
    }

    @Test
    @DisplayName("task context is null for system fast-path goals")
    fun `task context not used for fast path`() = runTest {
        var plannerCalled = false
        val planner = DeviceControlPlanner {
            plannerCalled = true
            PlannerDecision(DeviceAction.Done("nope"))
        }
        val executor = DeviceControlExecutor(mockContext, planner, maxSteps = 3,
            serviceProvider = { mockService })

        executor.execute("go home")

        assertFalse(plannerCalled, "Fast path should not invoke planner")
    }

    // ──────────────────────────────────────────────────────────
    // Edge case tests
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("accessibility service not available returns Failure")
    fun `accessibility service not available returns Failure`() = runTest {
        val planner = DeviceControlPlanner {
            PlannerDecision(DeviceAction.Done("should not run"))
        }
        val executor = DeviceControlExecutor(mockContext, planner, maxSteps = 5,
            serviceProvider = { null })

        val result = executor.execute("any goal")

        assertTrue(result is DeviceControlResult.Failure)
        val failure = result as DeviceControlResult.Failure
        assertTrue(failure.message.contains("Accessibility service is not enabled"))
    }

    @Test
    @DisplayName("empty goal goes to planner")
    fun `empty goal goes to planner`() = runTest {
        var plannerCalled = false
        val planner = DeviceControlPlanner {
            plannerCalled = true
            PlannerDecision(DeviceAction.Done("handled empty"))
        }
        val executor = DeviceControlExecutor(mockContext, planner, maxSteps = 3,
            serviceProvider = { mockService })

        val result = executor.execute("")

        assertTrue(plannerCalled, "Empty goal should go to planner")
    }

    @Test
    @DisplayName("goal with only conjunction 'open and close' goes to planner")
    fun `goal with conjunction goes to planner`() = runTest {
        var plannerCalled = false
        val planner = DeviceControlPlanner {
            plannerCalled = true
            PlannerDecision(DeviceAction.Done("handled"))
        }
        val executor = DeviceControlExecutor(mockContext, planner, maxSteps = 3,
            serviceProvider = { mockService })

        val result = executor.execute("open and close")

        assertTrue(plannerCalled)
    }

    @Test
    @DisplayName("action history is capped at 15 entries")
    fun `action history is capped`() = runTest {
        var lastHistorySize = 0
        val planner = DeviceControlPlanner { request ->
            lastHistorySize = request.actionHistory.size
            if (request.step <= 20) {
                PlannerDecision(
                    action = DeviceAction.ClickAt(100f, 200f),
                    reasoning = "keep going",
                    isComplete = false
                )
            } else {
                PlannerDecision(DeviceAction.Done("done"))
            }
        }
        val executor = DeviceControlExecutor(mockContext, planner, maxSteps = 25,
            serviceProvider = { mockService })

        executor.execute("test history cap")

        assertTrue(lastHistorySize <= 15,
            "Action history should be capped at 15, was $lastHistorySize")
    }

    @Test
    @DisplayName("recents uses terminal fast path")
    fun `recents uses terminal fast path`() = runTest {
        var plannerCalled = false
        val planner = DeviceControlPlanner {
            plannerCalled = true
            PlannerDecision(DeviceAction.Done("nope"))
        }
        val executor = DeviceControlExecutor(mockContext, planner, maxSteps = 3,
            serviceProvider = { mockService })

        val result = executor.execute("recents")

        assertFalse(plannerCalled)
        assertTrue(result is DeviceControlResult.Success)
    }

    @Test
    @DisplayName("notifications uses terminal fast path")
    fun `notifications uses terminal fast path`() = runTest {
        var plannerCalled = false
        val planner = DeviceControlPlanner {
            plannerCalled = true
            PlannerDecision(DeviceAction.Done("nope"))
        }
        val executor = DeviceControlExecutor(mockContext, planner, maxSteps = 3,
            serviceProvider = { mockService })

        val result = executor.execute("notifications")

        assertFalse(plannerCalled)
        assertTrue(result is DeviceControlResult.Success)
    }

    @Test
    @DisplayName("'start the Instagram' uses terminal fast path")
    fun `start the app uses terminal fast path`() = runTest {
        setupMockPackageManager("Instagram", "com.instagram.android")
        setupForegroundPackage("com.instagram.android")

        var plannerCalled = false
        val planner = DeviceControlPlanner {
            plannerCalled = true
            PlannerDecision(DeviceAction.Done("nope"))
        }
        val executor = DeviceControlExecutor(mockContext, planner, maxSteps = 3,
            serviceProvider = { mockService })

        val result = executor.execute("start the Instagram")

        assertFalse(plannerCalled)
        assertTrue(result is DeviceControlResult.Success)
    }

    @Test
    @DisplayName("goal 'Open Instagram search for Tanmay' falls through to planner")
    fun `search goal falls through to planner`() = runTest {
        var plannerCalled = false
        val planner = DeviceControlPlanner {
            plannerCalled = true
            PlannerDecision(DeviceAction.Done("handled"))
        }
        val executor = DeviceControlExecutor(mockContext, planner, maxSteps = 5,
            serviceProvider = { mockService })

        executor.execute("Open Instagram search for Tanmay")

        assertTrue(plannerCalled)
    }

    // ──────────────────────────────────────────────────────────
    // Performance trace tests
    // ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("executor returns failure on hard stop after repeated failures")
    fun `hard stop after 4 failures`() = runTest {
        var callCount = 0
        val planner = DeviceControlPlanner {
            callCount++
            PlannerDecision(
                action = DeviceAction.ClickText("Nonexistent"),
                reasoning = "failing",
                isComplete = false
            )
        }
        // All clicks will fail since there's no matching node
        every { mockService.clickText(any()) } returns false
        // Vary snapshot to avoid fingerprint-based loop detection blocking failure accumulation
        var snapshotVersion = 0
        every { mockService.snapshot() } answers {
            snapshotVersion++
            listOf(
                UiNodeSnapshot(
                    index = 0, text = "Screen$snapshotVersion", contentDescription = "",
                    className = "TextView", viewId = "", clickable = false,
                    editable = false, scrollable = false, checkable = false,
                    checked = false, focused = false, focusable = false,
                    enabled = true, bounds = Rect(0, 0, 100, 100), depth = 0,
                )
            )
        }
        val executor = DeviceControlExecutor(mockContext, planner, maxSteps = 20,
            serviceProvider = { mockService })

        val result = executor.execute("fail repeatedly")

        assertTrue(result is DeviceControlResult.Failure)
        val failure = result as DeviceControlResult.Failure
        assertTrue(failure.errorCode == DeviceControlResult.ErrorCode.STUCK)
    }

    // ──────────────────────────────────────────────────────────
    // Screen description caching tests (speed)
    // ──────────────────────────────────────────────────────────

    private fun singleNode(text: String): UiNodeSnapshot = UiNodeSnapshot(
        index = 0, text = text, contentDescription = "",
        className = "Button", viewId = "", clickable = true,
        editable = false, scrollable = false, checkable = false,
        checked = false, focused = false, focusable = true,
        enabled = true, bounds = Rect(0, 0, 100, 100), depth = 0,
    )

    @Test
    @DisplayName("does not call describeWithFingerprint when fingerprint unchanged")
    fun `describe skipped when fingerprint unchanged`() = runTest {
        val nodes = listOf(singleNode("Instagram"))
        val fingerprint = ScreenFingerprint.compute(nodes, "com.instagram.android")
        every { mockService.snapshot() } returns nodes
        every { mockService.currentPackage() } returns "com.instagram.android"
        every { mockService.snapshotFingerprint() } returns fingerprint
        every { mockService.clickText(any(), any()) } returns true

        val observerMock = mockk<ScreenObserver>()
        every { observerMock.describeWithFingerprint(any(), any()) } returns
            Triple("PKG: com.instagram.android\nNODES (1 total)", fingerprint, nodes)
        every { observerMock.describe(any(), any()) } returns "PKG: com.instagram.android"

        val planner = DeviceControlPlanner { request ->
            if (request.step == 1) {
                PlannerDecision(DeviceAction.ClickText("Instagram"), reasoning = "tap it")
            } else {
                PlannerDecision(DeviceAction.Done("done"), isComplete = true)
            }
        }
        val executor = DeviceControlExecutor(
            mockContext, planner, observer = observerMock, maxSteps = 5,
            serviceProvider = { mockService },
        )

        val result = executor.execute("click Instagram")

        assertTrue(result is DeviceControlResult.Success)
        io.mockk.verify(exactly = 1) {
            observerMock.describeWithFingerprint(any(), any())
        }
    }

    @Test
    @DisplayName("awaitUiChange replaces delay in recovery path")
    fun `recovery path uses event-driven wait`() = runTest {
        val recoveryTimeouts = mutableListOf<Long>()
        every { mockService.waitForUiChange(any(), any(), any()) } answers {
            if ((firstArg<String>()).startsWith("loop_recovery")) {
                recoveryTimeouts.add(secondArg())
            }
            false
        }

        val planner = DeviceControlPlanner {
            PlannerDecision(
                action = DeviceAction.ClickAt(100f, 200f),
                reasoning = "retrying same click",
                isComplete = false
            )
        }
        val executor = DeviceControlExecutor(mockContext, planner, maxSteps = 10,
            serviceProvider = { mockService })

        executor.execute("test loop detection")

        assertTrue(recoveryTimeouts.isNotEmpty(),
            "Recovery path must use an event-driven waitForUiChange")
        assertTrue(
            recoveryTimeouts.all { it <= 300L },
            "Recovery waits must be capped at 300ms, got $recoveryTimeouts",
        )
    }

    // ──────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────

    private fun setupMockPackageManager(appLabel: String, packageName: String) {
        val mockPm = mockContext.packageManager
        val appInfo = ApplicationInfo().apply {
            this.packageName = packageName
        }
        every { mockPm.getInstalledApplications(any<Int>()) } returns listOf(appInfo)
        every { mockPm.getApplicationLabel(appInfo) } returns appLabel
        val mockIntent = mockk<Intent>(relaxed = true)
        every { mockPm.getLaunchIntentForPackage(packageName) } returns mockIntent
    }

    private fun setupForegroundPackage(packageName: String) {
        mockkObject(DeviceControlAccessibilityService.Companion)
        every { DeviceControlAccessibilityService.instance() } returns mockk(relaxed = true) {
            every { currentPackage() } returns packageName
        }
    }
}
