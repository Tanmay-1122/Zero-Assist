package com.zeroclaw.android.service.devicecontrol

import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("DeviceControlQuickIntents")
class DeviceControlQuickIntentsTest {
    private val quickIntents = DeviceControlQuickIntents(mockk(relaxed = true))

    @Test
    @DisplayName("extracts YouTube play query without app/conjunction noise")
    fun `extracts YouTube play query`() {
        val query = quickIntents.extractYouTubeQuery("Open YouTube and play cat videos")

        assertEquals("cat videos", query)
        assertTrue(quickIntents.requiresPlannerAfterYouTubeSearch("Open YouTube and play cat videos"))
    }

    @Test
    @DisplayName("extracts YouTube search query")
    fun `extracts YouTube search query`() {
        val query = quickIntents.extractYouTubeQuery("Search for lofi coding music on YouTube")

        assertEquals("lofi coding music", query)
        assertFalse(quickIntents.requiresPlannerAfterYouTubeSearch("Search for lofi coding music on YouTube"))
    }

    @Test
    @DisplayName("ignores non-YouTube goals")
    fun `ignores non YouTube goals`() {
        assertNull(quickIntents.extractYouTubeQuery("Open Instagram and message Rohit"))
    }

    @Test
    @DisplayName("extracts URL and detects remaining form work")
    fun `extracts URL and continuation need`() {
        val goal = "Go to https://gmail.com in the Chrome browser, sign in with tanmay@example.com"

        assertEquals("https://gmail.com", quickIntents.extractUrl(goal))
        assertTrue(quickIntents.requiresPlannerAfterUrlOpen(goal))
        assertFalse(quickIntents.requiresPlannerAfterUrlOpen("Open https://example.com"))
    }
}
