/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.uiagent

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("UiAgent JSON models")
class UiAgentModelsJsonTest {
    private val json =
        Json {
            encodeDefaults = true
            classDiscriminator = "type"
        }

    @Test
    fun `prompt serializes sealed goals and decisions with stable type labels`() {
        val prompt =
            UiPrompt(
                goal =
                    UiAgentGoal.SendMessage(
                        recipient = "Alex",
                        message = "On my way",
                        targetPackageName = "com.chat.app",
                    ),
                snapshot =
                    UiSnapshot(
                        capturedAtEpochMs = 42L,
                        foregroundPackageName = "com.chat.app",
                        rootNodeIds = listOf("node-1"),
                        nodes =
                            listOf(
                                UiNode(
                                    id = "node-1",
                                    text = "Message",
                                    actions = listOf(UiNodeAction.SET_TEXT),
                                    editable = true,
                                ),
                            ),
                    ),
                previousDecisions =
                    listOf(
                        UiAgentDecision(
                            action = UiAgentAction.PressGlobal(UiAgentGlobalAction.BACK),
                            expectedState = UiExpectedState.RootReady,
                            rationale = "Leave wrong conversation",
                            confidence = 0.9f,
                        ),
                    ),
                expectedState = UiExpectedState.TextVisible("On my way", "com.chat.app"),
            )

        val encoded = json.encodeToString(prompt)

        assertTrue(encoded.contains(""""type":"send_message""""))
        assertTrue(encoded.contains(""""type":"press_global""""))
        assertTrue(encoded.contains(""""action":"back""""))
        assertTrue(encoded.contains(""""type":"root_ready""""))
        assertTrue(encoded.contains(""""type":"text_visible""""))
        assertTrue(encoded.contains("com.chat.app"))
    }

    @Test
    fun `snapshot JSON contains redacted values instead of raw sensitive text`() {
        val snapshot =
            UiSnapshotMapper.toSnapshot(
                RawUiSnapshot(
                    roots =
                        listOf(
                            RawUiNode(
                                text = "person@example.com",
                                children =
                                    listOf(
                                        RawUiNode(
                                            viewIdResourceName = "pin_input",
                                            text = "123456",
                                        ),
                                    ),
                            ),
                        ),
                    capturedAtEpochMs = 1L,
                ),
            )

        val encoded = json.encodeToString(snapshot)

        assertTrue(encoded.contains(UiTextSanitizer.REDACTED_EMAIL))
        assertTrue(encoded.contains(UiTextSanitizer.REDACTED_VALUE))
        assertFalse(encoded.contains("person@example.com"))
        assertFalse(encoded.contains("123456"))
    }

    @Test
    fun `decision confidence normalization keeps JSON safe range`() {
        val decision =
            UiAgentDecision(
                action = UiAgentAction.NoOp("already there"),
                rationale = "  visible state already satisfies goal  ",
                confidence = 1.7f,
            ).normalized()

        assertEquals(1f, decision.confidence)
        assertEquals("visible state already satisfies goal", decision.rationale)
        assertTrue(json.encodeToString(decision).contains(""""type":"noop""""))
    }
}
