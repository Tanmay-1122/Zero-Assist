package com.zeroclaw.android.service.devicecontrol

import android.graphics.Rect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("ScreenFingerprint")
class ScreenFingerprintTest {

    private fun node(
        text: String = "",
        clickable: Boolean = false,
        editable: Boolean = false,
        scrollable: Boolean = false,
        className: String = "View",
        top: Int = 0,
        left: Int = 0,
    ) = UiNodeSnapshot(
        index = 0, text = text, contentDescription = text,
        className = className, viewId = "", clickable = clickable,
        editable = editable, scrollable = scrollable, checkable = false,
        checked = false, focused = false, focusable = true,
        enabled = true, bounds = Rect(left, top, left + 100, top + 100), depth = 0,
    )

    @Test
    @DisplayName("identical screens produce same fingerprint")
    fun `identical screens match`() {
        val nodes = listOf(node("Hello", clickable = true))
        val fp1 = ScreenFingerprint.compute(nodes, "com.test")
        val fp2 = ScreenFingerprint.compute(nodes, "com.test")

        assertTrue(fp1.isSameScreen(fp2))
        assertFalse(fp1.hasChanged(fp2))
    }

    @Test
    @DisplayName("different labels produce different fingerprint")
    fun `different labels differ`() {
        val fp1 = ScreenFingerprint.compute(listOf(node("Hello")), "com.test")
        val fp2 = ScreenFingerprint.compute(listOf(node("World")), "com.test")

        assertTrue(fp1.hasChanged(fp2))
    }

    @Test
    @DisplayName("different packages produce different fingerprint")
    fun `different packages differ`() {
        val nodes = listOf(node("Hello"))
        val fp1 = ScreenFingerprint.compute(nodes, "com.app1")
        val fp2 = ScreenFingerprint.compute(nodes, "com.app2")

        assertTrue(fp1.hasChanged(fp2))
    }

    @Test
    @DisplayName("actionable node count is tracked")
    fun `actionable nodes counted`() {
        val nodes = listOf(
            node("A", clickable = true),
            node("B", editable = true),
            node("C"),
        )
        val fp = ScreenFingerprint.compute(nodes, "com.test")

        assertEquals(2, fp.actionableNodeCount)
        assertTrue(fp.hasEditableField)
        assertEquals(3, fp.uniqueLabelCount)
    }

    @Test
    @DisplayName("empty nodes produce valid fingerprint")
    fun `empty nodes valid`() {
        val fp = ScreenFingerprint.compute(emptyList(), null)

        assertEquals(0, fp.actionableNodeCount)
        assertFalse(fp.hasEditableField)
        assertEquals(0, fp.uniqueLabelCount)
        assertNull(fp.packageName)
    }

    @Test
    @DisplayName("toLogString is compact and safe")
    fun `log string compact`() {
        val fp = ScreenFingerprint.compute(listOf(node("Test")), "com.test")
        val log = fp.toLogString()

        assertTrue(log.contains("fp("))
        assertTrue(log.contains("com.test"))
        assertFalse(log.contains("Test")) // no sensitive content in log
    }
}
