package com.zeroclaw.android.service.devicecontrol

import android.view.View
import android.view.WindowManager
import io.mockk.mockk
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("DeviceControlOverlayService")
@OptIn(ExperimentalCoroutinesApi::class)
class DeviceControlOverlayServiceTest {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: View

    @BeforeEach
    fun setUp() {
        windowManager = mockk(relaxed = true)
        overlayView = mockk(relaxed = true) {
            every { tag } returns null
        }
    }

    @AfterEach
    fun tearDown() {
        DeviceControlMonitor.reset()
        unmockkAll()
    }

    private fun serviceWith(scope: CoroutineScope) = DeviceControlOverlayService(
        injectedWindowManager = windowManager,
        permissionCheck = { true },
        overlayViewFactory = { overlayView },
        externalScope = scope,
    )

    @Test
    @DisplayName("attaches the overlay window when control starts")
    fun `attach on control started`() = runTest {
        val service = serviceWith(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        DeviceControlMonitor.onControlStarted("open settings", 10)
        service.onMonitorStateChanged(DeviceControlMonitor.state.value)

        assertTrue(DeviceControlMonitor.state.value.shouldShowOverlay())
        verify(exactly = 1) { windowManager.addView(overlayView, any()) }
    }

    @Test
    @DisplayName("removes the overlay window when control completes")
    fun `detach after control completed`() = runTest {
        val service = serviceWith(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        DeviceControlMonitor.onControlStarted("open settings", 10)
        service.onMonitorStateChanged(DeviceControlMonitor.state.value)

        DeviceControlMonitor.onControlCompleted("opened settings")
        assertFalse(DeviceControlMonitor.state.value.shouldShowOverlay())
        service.onMonitorStateChanged(DeviceControlMonitor.state.value)
        advanceTimeBy(500)

        verify(exactly = 1) { windowManager.removeView(overlayView) }
    }

    @Test
    @DisplayName("terminal statuses hide the overlay")
    fun `terminal statuses hide overlay`() {
        DeviceControlMonitor.onControlStarted("goal", 5)

        DeviceControlMonitor.onControlCompleted("done")
        assertFalse(DeviceControlMonitor.state.value.shouldShowOverlay())

        DeviceControlMonitor.onControlFailed("boom")
        assertFalse(DeviceControlMonitor.state.value.shouldShowOverlay())

        DeviceControlMonitor.requestCancel()
        assertFalse(DeviceControlMonitor.state.value.shouldShowOverlay())
    }

    @Test
    @DisplayName("active executing status keeps the overlay visible")
    fun `executing keeps overlay visible`() {
        DeviceControlMonitor.onControlStarted("goal", 5)
        DeviceControlMonitor.onStepStarted(1, "Clicking text \"Search\"")

        val state = DeviceControlMonitor.state.value
        assertTrue(state.isActive)
        assertTrue(state.status == DeviceControlStatus.EXECUTING || state.status == DeviceControlStatus.INITIALIZING)
        assertTrue(state.shouldShowOverlay())
    }

    @Test
    @DisplayName("re-attach during exit window keeps a single attached view")
    fun `no duplicate attach during exit window`() = runTest {
        val service = serviceWith(CoroutineScope(UnconfinedTestDispatcher(testScheduler)))

        DeviceControlMonitor.onControlStarted("goal a", 5)
        service.onMonitorStateChanged(DeviceControlMonitor.state.value)

        // Terminal → schedule detach (450ms window starts).
        DeviceControlMonitor.onControlCompleted("done a")
        service.onMonitorStateChanged(DeviceControlMonitor.state.value)
        advanceTimeBy(100)

        // New session begins inside the exit window.
        DeviceControlMonitor.onControlStarted("goal b", 5)
        service.onMonitorStateChanged(DeviceControlMonitor.state.value)
        advanceTimeBy(600)

        verify(exactly = 1) { windowManager.addView(overlayView, any()) }
        verify(exactly = 0) { windowManager.removeView(any()) }
    }
}
