/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.uiagent

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("DeterministicUiAgentPlanner")
class DeterministicUiAgentPlannerTest {
    private val planner = DeterministicUiAgentPlanner()

    @Test
    fun `send message opens WhatsApp when another package is foregrounded`() {
        val decision =
            decide(
                goal = sendMessageGoal(),
                snapshot =
                    UiSnapshot(
                        capturedAtEpochMs = 1L,
                        foregroundPackageName = "com.android.launcher",
                        rootNodeIds = listOf("root"),
                        nodes = listOf(UiNode(id = "root", packageName = "com.android.launcher")),
                    ),
            )

        assertTrue(decision?.action is UiAgentAction.OpenPackage)
        assertEquals("com.whatsapp", (decision?.action as UiAgentAction.OpenPackage).packageName)
    }

    @Test
    fun `send message returns null for unsupported messaging package so hybrid can fall back`() {
        val decision =
            decide(
                goal =
                    UiAgentGoal.SendMessage(
                        recipient = "Sweetheart",
                        message = "hi there",
                        targetPackageName = "com.example.chat",
                    ),
                snapshot = messagingSnapshot(nodes = emptyList()),
            )

        assertNull(decision)
    }

    @Test
    fun `send message backs out of wrong active conversation before drafting`() {
        val decision =
            decide(
                goal = sendMessageGoal(),
                snapshot =
                    messagingSnapshot(
                        foregroundWindowTitle = "Different Chat",
                        nodes = listOf(draftNode()),
                    ),
            )

        assertTrue(decision?.action is UiAgentAction.PressGlobal)
        assertEquals(
            UiAgentGlobalAction.BACK,
            (decision?.action as UiAgentAction.PressGlobal).action,
        )
    }

    @Test
    fun `send message aborts when back did not leave wrong active conversation`() {
        val decision =
            planner.decideOrNull(
                prompt =
                    UiPrompt(
                        goal = sendMessageGoal(),
                        snapshot =
                            messagingSnapshot(
                                foregroundWindowTitle = "Different Chat",
                                nodes = listOf(draftNode()),
                            ),
                    ),
                context =
                    context(
                        history =
                            listOf(
                                UiAgentStepRecord(
                                    stepIndex = 0,
                                    snapshot = messagingSnapshot(foregroundWindowTitle = "Different Chat", nodes = listOf(draftNode())),
                                    decision =
                                        UiAgentDecision(
                                            action = UiAgentAction.PressGlobal(UiAgentGlobalAction.BACK),
                                        ),
                                    executionResult = UiAgentExecutionResult.Succeeded,
                                    verificationResult = UiVerificationResult(true, UiExpectedState.RootReady),
                                    completedAtEpochMs = 2L,
                                ),
                            ),
                    ),
            )

        assertTrue(decision?.action is UiAgentAction.Abort)
    }

    @Test
    fun `send message drafts only inside verified active conversation`() {
        val decision =
            decide(
                goal = sendMessageGoal(),
                snapshot =
                    messagingSnapshot(
                        foregroundWindowTitle = "Sweetheart",
                        nodes = listOf(draftNode()),
                    ),
            )

        assertTrue(decision?.action is UiAgentAction.SetText)
        val action = decision?.action as UiAgentAction.SetText
        assertEquals("draft", action.nodeId)
        assertEquals("hi there", action.text)
    }

    @Test
    fun `send message taps send after requested draft is visible in active conversation`() {
        val decision =
            decide(
                goal = sendMessageGoal(),
                snapshot =
                    messagingSnapshot(
                        foregroundWindowTitle = "Sweetheart",
                        nodes =
                            listOf(
                                draftNode(text = "hi there"),
                                UiNode(
                                    id = "send",
                                    packageName = "com.whatsapp",
                                    contentDescription = "Send",
                                    enabled = true,
                                    clickable = true,
                                    visibleToUser = true,
                                ),
                            ),
                    ),
            )

        assertTrue(decision?.action is UiAgentAction.TapNode)
        assertEquals("send", (decision?.action as UiAgentAction.TapNode).nodeId)
    }

    @Test
    fun `send message completes after verified send tap`() {
        val snapshot =
            messagingSnapshot(
                foregroundWindowTitle = "Sweetheart",
                nodes =
                    listOf(
                        draftNode(text = "hi there"),
                        UiNode(
                            id = "send",
                            packageName = "com.whatsapp",
                            contentDescription = "Send",
                            enabled = true,
                            clickable = true,
                            visibleToUser = true,
                        ),
                    ),
            )
        val decision =
            planner.decideOrNull(
                prompt =
                    UiPrompt(
                        goal = sendMessageGoal(),
                        snapshot =
                            messagingSnapshot(
                                foregroundWindowTitle = "Sweetheart",
                                nodes = listOf(draftNode()),
                            ),
                    ),
                context =
                    context(
                        history =
                            listOf(
                                UiAgentStepRecord(
                                    stepIndex = 0,
                                    snapshot = snapshot,
                                    decision =
                                        UiAgentDecision(
                                            action = UiAgentAction.TapNode("send"),
                                            expectedState = UiExpectedState.TextVisible("hi there", "com.whatsapp"),
                                        ),
                                    executionResult = UiAgentExecutionResult.Succeeded,
                                    verificationResult =
                                        UiVerificationResult(
                                            true,
                                            UiExpectedState.TextVisible("hi there", "com.whatsapp"),
                                        ),
                                    completedAtEpochMs = 2L,
                                ),
                            ),
                    ),
            )

        assertTrue(decision?.action is UiAgentAction.NoOp)
    }

    @Test
    fun `send message taps visible unique recipient before drafting`() {
        val decision =
            decide(
                goal = sendMessageGoal(),
                snapshot =
                    messagingSnapshot(
                        foregroundWindowTitle = "WhatsApp",
                        nodes =
                            listOf(
                                UiNode(
                                    id = "row",
                                    packageName = "com.whatsapp",
                                    viewIdResourceName = "com.whatsapp:id/contact_row_container",
                                    enabled = true,
                                    clickable = true,
                                    visibleToUser = true,
                                    childIds = listOf("name"),
                                ),
                                UiNode(
                                    id = "name",
                                    parentId = "row",
                                    packageName = "com.whatsapp",
                                    text = "Sweetheart",
                                    visibleToUser = true,
                                ),
                            ),
                    ),
            )

        assertTrue(decision?.action is UiAgentAction.TapNode)
        assertEquals("name", (decision?.action as UiAgentAction.TapNode).nodeId)
    }

    @Test
    fun `send message uses search field when recipient is not visible`() {
        val decision =
            decide(
                goal = sendMessageGoal(),
                snapshot =
                    messagingSnapshot(
                        foregroundWindowTitle = "WhatsApp",
                        nodes =
                            listOf(
                                UiNode(
                                    id = "search-field",
                                    packageName = "com.whatsapp",
                                    viewIdResourceName = "com.whatsapp:id/search_src_text",
                                    text = "Search",
                                    enabled = true,
                                    editable = true,
                                    visibleToUser = true,
                                    actions = listOf(UiNodeAction.SET_TEXT),
                                ),
                            ),
                    ),
            )

        assertTrue(decision?.action is UiAgentAction.SetText)
        val action = decision?.action as UiAgentAction.SetText
        assertEquals("search-field", action.nodeId)
        assertEquals("Sweetheart", action.text)
    }

    @Test
    fun `send message drafts inside verified Instagram conversation`() {
        val decision =
            decide(
                goal =
                    UiAgentGoal.SendMessage(
                        recipient = "Rohit",
                        message = "hello rohit",
                        targetPackageName = "com.instagram.android",
                    ),
                snapshot =
                    instagramSnapshot(
                        foregroundWindowTitle = "Rohit",
                        nodes = listOf(instagramDraftNode()),
                    ),
            )

        assertTrue(decision?.action is UiAgentAction.SetText)
        val action = decision?.action as UiAgentAction.SetText
        assertEquals("ig-draft", action.nodeId)
        assertEquals("hello rohit", action.text)
    }

    @Test
    fun `send message opens Instagram direct messages before global search`() {
        val decision =
            decide(
                goal =
                    UiAgentGoal.SendMessage(
                        recipient = "Rohit",
                        message = "hello rohit",
                        targetPackageName = "com.instagram.android",
                    ),
                snapshot =
                    instagramSnapshot(
                        foregroundWindowTitle = "Instagram",
                        nodes =
                            listOf(
                                UiNode(
                                    id = "global-search",
                                    packageName = "com.instagram.android",
                                    contentDescription = "Search",
                                    enabled = true,
                                    clickable = true,
                                    visibleToUser = true,
                                ),
                                UiNode(
                                    id = "direct",
                                    packageName = "com.instagram.android",
                                    contentDescription = "Direct messages",
                                    enabled = true,
                                    clickable = true,
                                    visibleToUser = true,
                                ),
                            ),
                    ),
            )

        assertTrue(decision?.action is UiAgentAction.TapNode)
        assertEquals("direct", (decision?.action as UiAgentAction.TapNode).nodeId)
    }

    @Test
    fun `send message aborts instead of falling back when no safe recipient action exists`() {
        val decision =
            decide(
                goal = sendMessageGoal(),
                snapshot =
                    messagingSnapshot(
                        foregroundWindowTitle = "WhatsApp",
                        nodes =
                            listOf(
                                UiNode(
                                    id = "row",
                                    packageName = "com.whatsapp",
                                    viewIdResourceName = "com.whatsapp:id/contact_row_container",
                                    enabled = true,
                                    clickable = true,
                                    visibleToUser = true,
                                ),
                            ),
                    ),
            )

        assertTrue(decision?.action is UiAgentAction.Abort)
    }

    @Test
    fun `generic tap command taps matching visible control`() {
        val decision =
            decide(
                goal = UiAgentGoal.Generic("tap Search", targetPackageName = "com.whatsapp"),
                snapshot =
                    messagingSnapshot(
                        nodes =
                            listOf(
                                UiNode(
                                    id = "search",
                                    packageName = "com.whatsapp",
                                    contentDescription = "Search",
                                    enabled = true,
                                    clickable = true,
                                    visibleToUser = true,
                                ),
                            ),
                    ),
            )

        assertTrue(decision?.action is UiAgentAction.TapNode)
        assertEquals("search", (decision?.action as UiAgentAction.TapNode).nodeId)
    }

    @Test
    fun `generic scroll command scrolls largest matching scrollable node`() {
        val decision =
            decide(
                goal = UiAgentGoal.Generic("scroll down", targetPackageName = "com.whatsapp"),
                snapshot =
                    messagingSnapshot(
                        nodes =
                            listOf(
                                UiNode(
                                    id = "small-list",
                                    packageName = "com.whatsapp",
                                    boundsInScreen = UiBounds(left = 0, top = 100, right = 100, bottom = 240),
                                    enabled = true,
                                    visibleToUser = true,
                                    actions = listOf(UiNodeAction.SCROLL_FORWARD),
                                ),
                                UiNode(
                                    id = "large-list",
                                    packageName = "com.whatsapp",
                                    boundsInScreen = UiBounds(left = 0, top = 100, right = 100, bottom = 600),
                                    enabled = true,
                                    visibleToUser = true,
                                    actions = listOf(UiNodeAction.SCROLL_FORWARD),
                                ),
                            ),
                    ),
            )

        assertTrue(decision?.action is UiAgentAction.ScrollNode)
        val action = decision?.action as UiAgentAction.ScrollNode
        assertEquals("large-list", action.nodeId)
        assertEquals(UiAgentScrollDirection.FORWARD, action.direction)
    }

    @Test
    fun `returns null when no deterministic action is safe`() {
        val decision =
            decide(
                goal = UiAgentGoal.Generic("answer the weather"),
                snapshot = messagingSnapshot(nodes = emptyList()),
            )

        assertNull(decision)
    }

    @Test
    fun `hybrid planner falls back when deterministic planner has no action`() =
        runTest {
            val fallback =
                object : UiAgentPlanner {
                    override suspend fun decide(
                        prompt: UiPrompt,
                        context: UiAgentSessionContext,
                    ): UiAgentDecision =
                        UiAgentDecision(UiAgentAction.NoOp("fallback"))
                }
            val hybrid = HybridUiAgentPlanner(fallbackPlanner = fallback)

            val decision =
                hybrid.decide(
                    prompt =
                        UiPrompt(
                            goal = UiAgentGoal.Generic("answer the weather"),
                            snapshot = messagingSnapshot(nodes = emptyList()),
                        ),
                    context = context(),
                )

            assertTrue(decision.action is UiAgentAction.NoOp)
        }

    @Test
    fun `hybrid planner falls back for unsupported send message apps`() =
        runTest {
            val fallback =
                object : UiAgentPlanner {
                    override suspend fun decide(
                        prompt: UiPrompt,
                        context: UiAgentSessionContext,
                    ): UiAgentDecision =
                        UiAgentDecision(UiAgentAction.NoOp("model handled unsupported app"))
                }
            val hybrid = HybridUiAgentPlanner(fallbackPlanner = fallback)

            val decision =
                hybrid.decide(
                    prompt =
                        UiPrompt(
                            goal =
                                UiAgentGoal.SendMessage(
                                    recipient = "Sweetheart",
                                    message = "hi there",
                                    targetPackageName = "com.example.chat",
                                ),
                            snapshot = messagingSnapshot(nodes = emptyList()),
                        ),
                    context = context(),
                )

            assertTrue(decision.action is UiAgentAction.NoOp)
            assertEquals("model handled unsupported app", (decision.action as UiAgentAction.NoOp).reason)
        }

    @Test
    fun `hybrid planner does not call model fallback for Instagram send message without safe controls`() =
        runTest {
            var fallbackCalls = 0
            val fallback =
                object : UiAgentPlanner {
                    override suspend fun decide(
                        prompt: UiPrompt,
                        context: UiAgentSessionContext,
                    ): UiAgentDecision {
                        fallbackCalls += 1
                        return UiAgentDecision(UiAgentAction.NoOp("fallback"))
                    }
                }
            val hybrid = HybridUiAgentPlanner(fallbackPlanner = fallback)

            val decision =
                hybrid.decide(
                    prompt =
                        UiPrompt(
                            goal =
                                UiAgentGoal.SendMessage(
                                    recipient = "Rohit",
                                    message = "hello",
                                    targetPackageName = "com.instagram.android",
                                ),
                            snapshot = instagramSnapshot(nodes = emptyList()),
                        ),
                    context = context(),
                )

            assertTrue(decision.action is UiAgentAction.Abort)
            assertEquals(0, fallbackCalls)
        }

    private fun decide(
        goal: UiAgentGoal,
        snapshot: UiSnapshot,
    ): UiAgentDecision? =
        planner.decideOrNull(
            prompt = UiPrompt(goal = goal, snapshot = snapshot),
            context = context(),
        )

    private fun context(
        history: List<UiAgentStepRecord> = emptyList(),
    ): UiAgentSessionContext =
        UiAgentSessionContext(
            sessionId = "test",
            goal = UiAgentGoal.Generic("test"),
            stepIndex = 0,
            maxSteps = 8,
            startedAtEpochMs = 1L,
            deadlineAtEpochMs = 10_000L,
            history = history,
        )

    private fun sendMessageGoal(): UiAgentGoal.SendMessage =
        UiAgentGoal.SendMessage(
            recipient = "Sweetheart",
            message = "hi there",
            targetPackageName = "com.whatsapp",
        )

    private fun messagingSnapshot(
        foregroundWindowTitle: String? = null,
        nodes: List<UiNode>,
    ): UiSnapshot =
        UiSnapshot(
            capturedAtEpochMs = 1L,
            foregroundPackageName = "com.whatsapp",
            foregroundWindowTitle = foregroundWindowTitle,
            rootNodeIds = listOf("root"),
            nodes = listOf(UiNode(id = "root", packageName = "com.whatsapp")) + nodes,
        )

    private fun draftNode(text: String? = null): UiNode =
        UiNode(
            id = "draft",
            packageName = "com.whatsapp",
            viewIdResourceName = "com.whatsapp:id/entry",
            text = text,
            enabled = true,
            editable = true,
            focused = true,
            visibleToUser = true,
            actions = listOf(UiNodeAction.SET_TEXT),
        )

    private fun instagramSnapshot(
        foregroundWindowTitle: String? = null,
        nodes: List<UiNode>,
    ): UiSnapshot =
        UiSnapshot(
            capturedAtEpochMs = 1L,
            foregroundPackageName = "com.instagram.android",
            foregroundWindowTitle = foregroundWindowTitle,
            rootNodeIds = listOf("root"),
            nodes = listOf(UiNode(id = "root", packageName = "com.instagram.android")) + nodes,
        )

    private fun instagramDraftNode(text: String? = null): UiNode =
        UiNode(
            id = "ig-draft",
            packageName = "com.instagram.android",
            viewIdResourceName = "com.instagram.android:id/row_thread_composer_edittext",
            text = text,
            enabled = true,
            editable = true,
            focused = true,
            visibleToUser = true,
            actions = listOf(UiNodeAction.SET_TEXT),
        )
}
