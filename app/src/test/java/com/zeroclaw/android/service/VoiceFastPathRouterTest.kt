/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.model.VoiceAssistantDeviceAction
import com.zeroclaw.android.service.uiagent.UiAgentGoal
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("VoiceFastPathRouter")
class VoiceFastPathRouterTest {
    @Test
    fun `routes common help prompt to local spoken response`() {
        val result = VoiceFastPathRouter.route("what can you do")

        assertTrue(result is VoiceFastPathResult.SpokenResponse)
        assertEquals(
            "I can open apps, call contacts, set timers, control the phone, and answer short questions.",
            (result as VoiceFastPathResult.SpokenResponse).message,
        )
    }

    @Test
    fun `routes phone number calls directly to dial command`() {
        val result = VoiceFastPathRouter.route("call +1 555 123 4567")

        assertTrue(result is VoiceFastPathResult.Command)
        val command = (result as VoiceFastPathResult.Command).command
        assertEquals("call +1 555 123 4567", command.text)
        assertEquals("tel:+15551234567", command.phoneDialUri)
        assertEquals("Opening phone dialer.", result.statusMessage)
    }

    @Test
    fun `routes contact calls to contact lookup`() {
        val result = VoiceFastPathRouter.route("call Priya")

        assertTrue(result is VoiceFastPathResult.ContactCall)
        assertEquals("call Priya", (result as VoiceFastPathResult.ContactCall).transcript)
        assertEquals("Priya", result.contactName)
    }

    @Test
    fun `routes timers to direct device action command`() {
        val result = VoiceFastPathRouter.route("set a timer for 5 minutes")

        assertTrue(result is VoiceFastPathResult.Command)
        val command = (result as VoiceFastPathResult.Command).command
        val action = command.deviceAction as? VoiceAssistantDeviceAction.SetTimer
        assertEquals(300, action?.lengthSeconds)
        assertEquals("Opening timer.", result.statusMessage)
    }

    @Test
    fun `routes app opening to local device control command`() {
        val result = VoiceFastPathRouter.route("open Spotify")

        assertTrue(result is VoiceFastPathResult.Command)
        val command = (result as VoiceFastPathResult.Command).command
        val action = command.localDeviceAction as? DeviceAction.OpenPackage
        assertEquals("com.spotify.music", action?.packageName)
        assertEquals("Opening app.", result.statusMessage)
    }

    @Test
    fun `routes direct ui commands to ui agent instead of legacy tap action`() {
        val result = VoiceFastPathRouter.route("tap Search on Gmail app")

        assertTrue(result is VoiceFastPathResult.Command)
        val command = (result as VoiceFastPathResult.Command).command
        val goal = command.uiAgentGoal as? UiAgentGoal.Generic
        assertEquals("tap Search", goal?.instruction)
        assertEquals("Gmail", goal?.targetAppQuery)
        assertEquals("Starting phone task.", result.statusMessage)
    }

    @Test
    fun `routes phone workflow to ui agent command`() {
        val result = VoiceFastPathRouter.route("message Alex saying hi on whatsapp")

        assertTrue(result is VoiceFastPathResult.Command)
        val command = (result as VoiceFastPathResult.Command).command
        val goal = command.uiAgentGoal as? UiAgentGoal.SendMessage
        assertEquals("Alex", goal?.recipient)
        assertEquals("hi", goal?.message)
        assertEquals("com.whatsapp", goal?.targetPackageName)
        assertEquals("Starting phone task.", result.statusMessage)
    }

    @Test
    fun `routes instagram message workflow with article to ui agent command`() {
        val result = VoiceFastPathRouter.route("message Alex saying hi using the Instagram app")

        assertTrue(result is VoiceFastPathResult.Command)
        val command = (result as VoiceFastPathResult.Command).command
        val goal = command.uiAgentGoal as? UiAgentGoal.SendMessage
        assertEquals("Alex", goal?.recipient)
        assertEquals("hi", goal?.message)
        assertEquals("com.instagram.android", goal?.targetPackageName)
        assertEquals("Starting phone task.", result.statusMessage)
    }
}
