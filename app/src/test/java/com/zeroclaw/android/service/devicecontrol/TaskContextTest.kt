package com.zeroclaw.android.service.devicecontrol

import android.graphics.Rect
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("TaskContext")
class TaskContextTest {

    @Test
    @DisplayName("messaging goal parses intent, target, and content")
    fun `messaging goal parsed correctly`() {
        val ctx = TaskContext("message Aditya hi")
        ctx.inferFromGoal()

        assertEquals("send_message", ctx.intentCategory)
        assertEquals("Aditya", ctx.target)
        assertEquals("hi", ctx.messageContent)
    }

    @Test
    @DisplayName("text goal parses intent and target")
    fun `text goal parsed`() {
        val ctx = TaskContext("text Mom meeting at 5")
        ctx.inferFromGoal()

        assertEquals("send_message", ctx.intentCategory)
        assertEquals("Mom", ctx.target)
        assertEquals("meeting at 5", ctx.messageContent)
    }

    @Test
    @DisplayName("open app goal parses correctly")
    fun `open app goal parsed`() {
        val ctx = TaskContext("Open Settings")
        ctx.inferFromGoal()

        assertEquals("open_app", ctx.intentCategory)
        assertEquals("Settings", ctx.resolvedAppName)
    }

    @Test
    @DisplayName("system home goal parses correctly")
    fun `system home goal parsed`() {
        val ctx = TaskContext("go home")
        ctx.inferFromGoal()

        assertEquals("system_home", ctx.intentCategory)
    }

    @Test
    @DisplayName("simple back goal parses correctly")
    fun `simple back goal parsed`() {
        val ctx = TaskContext("back")
        ctx.inferFromGoal()

        assertEquals("system_back", ctx.intentCategory)
    }

    @Test
    @DisplayName("unknown goal has null intent category")
    fun `unknown goal null category`() {
        val ctx = TaskContext("do something weird")
        ctx.inferFromGoal()

        assertNull(ctx.intentCategory)
    }

    @Test
    @DisplayName("toPromptContext includes task context when populated")
    fun `prompt context includes task info`() {
        val ctx = TaskContext("message Aditya hi")
        ctx.inferFromGoal()
        ctx.resolvedPackage = "com.whatsapp"
        ctx.resolvedAppName = "WhatsApp"

        val prompt = ctx.toPromptContext()

        assertTrue(prompt.contains("intent: send_message"))
        assertTrue(prompt.contains("target: Aditya"))
        assertTrue(prompt.contains("message: \"hi\""))
        assertTrue(prompt.contains("app: WhatsApp"))
    }

    @Test
    @DisplayName("toPromptContext includes failed actions")
    fun `prompt context includes failures`() {
        val ctx = TaskContext("test")
        ctx.failedActions.add("ClickText('missing')")

        val prompt = ctx.toPromptContext()

        assertTrue(prompt.contains("FAILED_ACTIONS"))
        assertTrue(prompt.contains("ClickText"))
    }

    @Test
    @DisplayName("toPromptContext warns about screen stall")
    fun `prompt context warns on stall`() {
        val ctx = TaskContext("test")
        ctx.stepsSinceScreenChange = 4

        val prompt = ctx.toPromptContext()

        assertTrue(prompt.contains("WARNING"))
        assertTrue(prompt.contains("4"))
    }
}
