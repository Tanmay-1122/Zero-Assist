package com.zeroclaw.android.service.devicecontrol

import android.graphics.Rect
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ScreenObserver")
class ScreenObserverTest {

    private lateinit var mockService: DeviceControlServiceBridge

    @BeforeEach
    fun setUp() {
        mockService = mockk(relaxed = true) {
            every { onNextUiChange(any()) } returns CompletableDeferred(1L)
        }
    }

    @Test
    @DisplayName("empty snapshot produces valid output with no nodes")
    fun `empty snapshot produces valid output`() {
        every { mockService.snapshot() } returns emptyList()
        every { mockService.currentPackage() } returns "com.test.app"

        val observer = ScreenObserver()
        val (desc, fp, nodes) = observer.describeWithFingerprint(mockService, "test goal")

        assertTrue(desc.contains("PKG: com.test.app"))
        assertTrue(desc.contains("NODES (0 total"))
        assertNotNull(fp)
        assertTrue(nodes.isEmpty())
    }

    @Test
    @DisplayName("nodes matching goal keywords are highlighted with * marker")
    fun `matching nodes are highlighted`() {
        every { mockService.snapshot() } returns listOf(
            UiNodeSnapshot(
                index = 0, text = "Instagram", contentDescription = "",
                className = "TextView", viewId = "", clickable = true,
                editable = false, scrollable = false, checkable = false,
                checked = false, focused = false, focusable = true,
                enabled = true, bounds = Rect(0, 0, 100, 100), depth = 0,
            ),
            UiNodeSnapshot(
                index = 1, text = "Settings", contentDescription = "",
                className = "TextView", viewId = "", clickable = true,
                editable = false, scrollable = false, checkable = false,
                checked = false, focused = false, focusable = true,
                enabled = true, bounds = Rect(0, 100, 100, 200), depth = 0,
            )
        )
        every { mockService.currentPackage() } returns "com.test.app"

        val observer = ScreenObserver()
        val (desc, _, _) = observer.describeWithFingerprint(mockService, "open Instagram")

        assertTrue(desc.contains("[0]*"), "Instagram node should be highlighted")
        assertFalse(desc.contains("[1]*"), "Settings node should NOT be highlighted")
    }

    @Test
    @DisplayName("long labels are truncated")
    fun `long labels are truncated`() {
        val longLabel = "A".repeat(200)
        every { mockService.snapshot() } returns listOf(
            UiNodeSnapshot(
                index = 0, text = longLabel, contentDescription = "",
                className = "TextView", viewId = "", clickable = false,
                editable = false, scrollable = false, checkable = false,
                checked = false, focused = false, focusable = false,
                enabled = true, bounds = Rect(0, 0, 100, 100), depth = 0,
            )
        )
        every { mockService.currentPackage() } returns "com.test"

        val observer = ScreenObserver(maxLabelLength = 50)
        val (desc, _, _) = observer.describeWithFingerprint(mockService, "test")

        assertFalse(desc.contains(longLabel), "Long label should be truncated")
    }

    @Test
    @DisplayName("clickable tags are included in output")
    fun `clickable tags included`() {
        every { mockService.snapshot() } returns listOf(
            UiNodeSnapshot(
                index = 0, text = "Button", contentDescription = "",
                className = "Button", viewId = "com.test:id/btn", clickable = true,
                editable = false, scrollable = false, checkable = false,
                checked = false, focused = false, focusable = true,
                enabled = true, bounds = Rect(10, 20, 100, 100), depth = 0,
            )
        )
        every { mockService.currentPackage() } returns "com.test"

        val observer = ScreenObserver()
        val (desc, _, _) = observer.describeWithFingerprint(mockService, "test")

        assertTrue(desc.contains("[click]"), "Should contain [click] tag")
        assertTrue(desc.contains("id:btn"), "Should contain resource ID")
    }

    @Test
    @DisplayName("maxNodes limits output")
    fun `maxNodes limits output`() {
        val nodes = (0..50).map { i ->
            UiNodeSnapshot(
                index = i, text = "Node $i", contentDescription = "",
                className = "View", viewId = "", clickable = false,
                editable = false, scrollable = false, checkable = false,
                checked = false, focused = false, focusable = false,
                enabled = true, bounds = Rect(0, i * 10, 100, i * 10 + 10), depth = 0,
            )
        }
        every { mockService.snapshot() } returns nodes
        every { mockService.currentPackage() } returns "com.test"

        val observer = ScreenObserver(maxNodes = 10)
        val (desc, _, _) = observer.describeWithFingerprint(mockService, "test")

        assertTrue(desc.contains("NODES (51 total, showing 10"))
        assertFalse(desc.contains("[50]"), "Node 50 should not appear with maxNodes=10")
    }

    @Test
    @DisplayName("legacy describe method works")
    fun `legacy describe works`() {
        every { mockService.snapshot() } returns listOf(
            UiNodeSnapshot(
                index = 0, text = "Hello", contentDescription = "",
                className = "TextView", viewId = "", clickable = true,
                editable = false, scrollable = false, checkable = false,
                checked = false, focused = false, focusable = true,
                enabled = true, bounds = Rect(0, 0, 100, 100), depth = 0,
            )
        )
        every { mockService.currentPackage() } returns "com.test"

        val observer = ScreenObserver()
        val desc = observer.describe(mockService, "test")

        assertTrue(desc.contains("PKG: com.test"))
    }

    @Test
    @DisplayName("actionable nodes ranked first")
    fun `actionable nodes ranked first`() {
        every { mockService.snapshot() } returns listOf(
            UiNodeSnapshot(
                index = 0, text = "Label", contentDescription = "",
                className = "TextView", viewId = "", clickable = false,
                editable = false, scrollable = false, checkable = false,
                checked = false, focused = false, focusable = false,
                enabled = true, bounds = Rect(0, 0, 100, 100), depth = 0,
            ),
            UiNodeSnapshot(
                index = 1, text = "Button", contentDescription = "",
                className = "Button", viewId = "", clickable = true,
                editable = false, scrollable = false, checkable = false,
                checked = false, focused = false, focusable = true,
                enabled = true, bounds = Rect(0, 100, 100, 200), depth = 0,
            ),
        )
        every { mockService.currentPackage() } returns "com.test"

        val observer = ScreenObserver()
        val (desc, _, _) = observer.describeWithFingerprint(mockService, "test")

        val buttonPos = desc.indexOf("Button")
        val labelPos = desc.indexOf("Label")
        // Button (clickable) should appear before Label (non-clickable) in ranked output
        assertTrue(buttonPos < labelPos, "Actionable nodes should be ranked first")
    }
}
