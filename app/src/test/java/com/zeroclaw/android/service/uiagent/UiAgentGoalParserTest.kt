/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.uiagent

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("UiAgentGoalParser")
class UiAgentGoalParserTest {
    @Test
    fun `parses message goals with recipient and body`() {
        val goal = UiAgentGoalParser.parse("message Alex saying hi there")

        assertTrue(goal is UiAgentGoal.SendMessage)
        goal as UiAgentGoal.SendMessage
        assertEquals("Alex", goal.recipient)
        assertEquals("hi there", goal.message)
    }

    @Test
    fun `parses colon message shorthand`() {
        val goal = UiAgentGoalParser.parse("text Mom: running late")

        assertTrue(goal is UiAgentGoal.SendMessage)
        goal as UiAgentGoal.SendMessage
        assertEquals("Mom", goal.recipient)
        assertEquals("running late", goal.message)
    }

    @Test
    fun `parses message goals with trailing app preference`() {
        val goal = UiAgentGoalParser.parse("message Aditya saying hi there on whatsapp")

        assertEquals(
            UiAgentGoal.SendMessage(
                recipient = "Aditya",
                message = "hi there",
                targetPackageName = "com.whatsapp",
            ),
            goal,
        )
    }

    @Test
    fun `parses bodyless app messaging requests as safe preparation goals`() {
        val goal = UiAgentGoalParser.parse("send message to Rohit using WhatsApp")

        assertEquals(
            UiAgentGoal.Generic(
                instruction =
                    "open the target messaging app and find the chat or contact named Rohit; " +
                        "do not type or send a message because no message text was provided",
                targetPackageName = "com.whatsapp",
            ),
            goal,
        )
    }

    @Test
    fun `parses instagram app as trailing message app preference`() {
        val goal = UiAgentGoalParser.parse("message Aditya saying hi there on Instagram app")

        assertEquals(
            UiAgentGoal.SendMessage(
                recipient = "Aditya",
                message = "hi there",
                targetPackageName = "com.instagram.android",
            ),
            goal,
        )
    }

    @Test
    fun `parses article before trailing app preference`() {
        val goal = UiAgentGoalParser.parse("message Aditya saying hi there using the Instagram app")

        assertEquals(
            UiAgentGoal.SendMessage(
                recipient = "Aditya",
                message = "hi there",
                targetPackageName = "com.instagram.android",
            ),
            goal,
        )
    }

    @Test
    fun `parses explicit generic phone tasks`() {
        val goal = UiAgentGoalParser.parse("use my phone to open settings and turn on bluetooth")

        assertEquals(
            UiAgentGoal.Generic(
                instruction = "open settings and turn on bluetooth",
                targetAppQuery = "settings",
            ),
            goal,
        )
    }

    @Test
    fun `parses direct tap requests as generic ui goals`() {
        val goal = UiAgentGoalParser.parse("tap Search")

        assertEquals(
            UiAgentGoal.Generic("tap Search"),
            goal,
        )
    }

    @Test
    fun `parses direct text entry requests as generic ui goals`() {
        val goal = UiAgentGoalParser.parse("type hello there into Message")

        assertEquals(
            UiAgentGoal.Generic("type hello there into Message"),
            goal,
        )
    }

    @Test
    fun `parses direct app-scoped ui requests`() {
        val goal = UiAgentGoalParser.parse("tap Search on whatsapp")

        assertEquals(
            UiAgentGoal.Generic(
                instruction = "tap Search",
                targetPackageName = "com.whatsapp",
            ),
            goal,
        )
    }

    @Test
    fun `parses direct app-scoped ui requests for installed app labels`() {
        val goal = UiAgentGoalParser.parse("tap Search on Gmail app")

        assertEquals(
            UiAgentGoal.Generic(
                instruction = "tap Search",
                targetAppQuery = "Gmail",
            ),
            goal,
        )
    }

    @Test
    fun `parses app specific generic tasks without explicit phone prefix`() {
        val goal = UiAgentGoalParser.parse("play any song on spotify")

        assertEquals(
            UiAgentGoal.Generic(
                instruction = "play any song",
                targetPackageName = "com.spotify.music",
            ),
            goal,
        )
    }

    @Test
    fun `parses arbitrary app open workflows with app query targets`() {
        assertEquals(
            UiAgentGoal.Generic(
                instruction = "open Gmail and search for flight tickets",
                targetAppQuery = "Gmail",
            ),
            UiAgentGoalParser.parse("open Gmail and search for flight tickets"),
        )
        assertEquals(
            UiAgentGoal.Generic(
                instruction = "open Calculator, tap 7",
                targetAppQuery = "Calculator",
            ),
            UiAgentGoalParser.parse("open Calculator, tap 7"),
        )
    }

    @Test
    fun `parses open app multi-step workflows with target package`() {
        assertEquals(
            UiAgentGoal.Generic(
                instruction = "open YouTube and search for samay raina",
                targetPackageName = "com.google.android.youtube",
            ),
            UiAgentGoalParser.parse("open YouTube and search for samay raina"),
        )
        assertEquals(
            UiAgentGoal.Generic(
                instruction = "open Brave and search Spotify",
                targetPackageName = "com.brave.browser",
            ),
            UiAgentGoalParser.parse("open Brave and search Spotify"),
        )
        assertEquals(
            UiAgentGoal.Generic(
                instruction = "open Spotify and start playing a song",
                targetPackageName = "com.spotify.music",
            ),
            UiAgentGoalParser.parse("open Spotify and start playing a song"),
        )
        assertEquals(
            UiAgentGoal.Generic(
                instruction = "open Instagram then tap messages",
                targetPackageName = "com.instagram.android",
            ),
            UiAgentGoalParser.parse("open Instagram then tap messages"),
        )
        assertEquals(
            UiAgentGoal.Generic(
                instruction = "open YouTube, search for lofi music",
                targetPackageName = "com.google.android.youtube",
            ),
            UiAgentGoalParser.parse("open YouTube, search for lofi music"),
        )
    }

    @Test
    fun `ignores ordinary chat`() {
        assertNull(UiAgentGoalParser.parse("what is the weather like"))
    }
}
