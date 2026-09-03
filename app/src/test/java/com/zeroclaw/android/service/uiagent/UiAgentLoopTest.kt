/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.uiagent

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("UiAgentLoop")
class UiAgentLoopTest {
    @Test
    fun `default loop timeout leaves room for model-backed app control`() {
        assertEquals(120_000L, UiAgentLoopConfig().timeoutMs)
    }

    @Test
    fun `executes one planned action per iteration and completes on no-op`() =
        runTest {
            val initialSnapshot = snapshotWithButton(text = "Continue")
            val completedSnapshot =
                initialSnapshot.copy(
                    nodes =
                        initialSnapshot.nodes +
                            UiNode(id = "done", text = "Done"),
                )
            val planner =
                RecordingPlanner(
                    listOf(
                        UiAgentDecision(
                            action = UiAgentAction.TapNode("button"),
                            expectedState = UiExpectedState.TextVisible("Done"),
                            confidence = 2f,
                        ),
                        UiAgentDecision(
                            action = UiAgentAction.NoOp("goal satisfied"),
                        ),
                    ),
                )
            val executor = RecordingExecutor()
            val loop =
                UiAgentLoop(
                    snapshotProvider =
                        SequenceSnapshotProvider(
                            initialSnapshot,
                            completedSnapshot,
                            completedSnapshot,
                            completedSnapshot,
                        ),
                    planner = planner,
                    executor = executor,
                    config = UiAgentLoopConfig(maxSteps = 2),
                )

            val completedSteps = mutableListOf<UiAgentStepRecord>()
            val result =
                loop.run(
                    goal = UiAgentGoal.Generic("tap continue"),
                    onStepCompleted = completedSteps::add,
                )

            assertTrue(result.completed)
            assertEquals(UiAgentLoopStatus.COMPLETED, result.status)
            assertEquals(2, planner.prompts.size)
            assertEquals(2, executor.commands.size)
            assertEquals(2, result.history.size)
            assertEquals(result.history, completedSteps)
            assertEquals(1f, result.history.first().decision.confidence)
        }

    @Test
    fun `fails before planning when snapshot is missing`() =
        runTest {
            val planner = RecordingPlanner(emptyList())
            val executor = RecordingExecutor()
            val loop =
                UiAgentLoop(
                    snapshotProvider = SequenceSnapshotProvider(null),
                    planner = planner,
                    executor = executor,
                )

            val result = loop.run(UiAgentGoal.Generic("do something"))

            assertFalse(result.completed)
            assertEquals(UiAgentLoopFailureReason.MISSING_SNAPSHOT, result.failureReason)
            assertEquals(0, planner.prompts.size)
            assertEquals(0, executor.commands.size)
        }

    @Test
    fun `waits through transient missing snapshot before planning`() =
        runTest {
            val snapshot = snapshotWithButton()
            val planner =
                RecordingPlanner(
                    listOf(UiAgentDecision(action = UiAgentAction.NoOp("done"))),
                )
            val loop =
                UiAgentLoop(
                    snapshotProvider = SequenceSnapshotProvider(null, null, snapshot, snapshot),
                    planner = planner,
                    executor = RecordingExecutor(),
                    pause = {},
                    clock = mutableClock(),
                    config =
                        UiAgentLoopConfig(
                            maxSteps = 1,
                            snapshotAcquisitionTimeoutMs = 1_000L,
                            snapshotPollIntervalMs = 100L,
                        ),
                )

            val result = loop.run(UiAgentGoal.Generic("do something"))

            assertTrue(result.completed)
            assertEquals(1, planner.prompts.size)
        }

    @Test
    fun `waits for target package snapshot before planning target app goal`() =
        runTest {
            val systemSnapshot =
                UiSnapshot(
                    capturedAtEpochMs = 1L,
                    foregroundPackageName = "com.android.systemui",
                    rootNodeIds = listOf("system-root"),
                    nodes = listOf(UiNode(id = "system-root", packageName = "com.android.systemui")),
                )
            val instagramSnapshot =
                UiSnapshot(
                    capturedAtEpochMs = 2L,
                    foregroundPackageName = "com.instagram.android",
                    rootNodeIds = listOf("ig-root"),
                    nodes = listOf(UiNode(id = "ig-root", packageName = "com.instagram.android")),
                )
            val planner =
                RecordingPlanner(
                    listOf(UiAgentDecision(action = UiAgentAction.NoOp("ready"))),
                )
            val loop =
                UiAgentLoop(
                    snapshotProvider = SequenceSnapshotProvider(systemSnapshot, instagramSnapshot, instagramSnapshot),
                    planner = planner,
                    executor = RecordingExecutor(),
                    pause = {},
                    clock = mutableClock(),
                    config =
                        UiAgentLoopConfig(
                            maxSteps = 1,
                            snapshotAcquisitionTimeoutMs = 1_000L,
                            snapshotPollIntervalMs = 100L,
                        ),
                )

            val result =
                loop.run(
                    UiAgentGoal.SendMessage(
                        recipient = "Rohit",
                        message = "hello",
                        targetPackageName = "com.instagram.android",
                    ),
                )

            assertTrue(result.completed)
            assertEquals("com.instagram.android", planner.prompts.single().snapshot.foregroundPackageName)
        }

    @Test
    fun `skips launcher snapshot with stale target node but no target root`() =
        runTest {
            val launcherSnapshot =
                UiSnapshot(
                    capturedAtEpochMs = 1L,
                    foregroundPackageName = "com.android.launcher",
                    rootNodeIds = listOf("launcher-root"),
                    nodes =
                        listOf(
                            UiNode(
                                id = "launcher-root",
                                packageName = "com.android.launcher",
                            ),
                            UiNode(
                                id = "stale-youtube-search",
                                packageName = "com.google.android.youtube",
                                text = "Search",
                                enabled = true,
                                clickable = true,
                                visibleToUser = true,
                            ),
                        ),
                )
            val youtubeSnapshot =
                UiSnapshot(
                    capturedAtEpochMs = 2L,
                    foregroundPackageName = "com.google.android.youtube",
                    rootNodeIds = listOf("youtube-root"),
                    nodes =
                        listOf(
                            UiNode(
                                id = "youtube-root",
                                packageName = "com.google.android.youtube",
                            ),
                        ),
                )
            val planner =
                RecordingPlanner(
                    listOf(UiAgentDecision(action = UiAgentAction.NoOp("ready"))),
                )
            val loop =
                UiAgentLoop(
                    snapshotProvider = SequenceSnapshotProvider(launcherSnapshot, youtubeSnapshot, youtubeSnapshot),
                    planner = planner,
                    executor = RecordingExecutor(),
                    pause = {},
                    clock = mutableClock(),
                    config =
                        UiAgentLoopConfig(
                            maxSteps = 1,
                            snapshotAcquisitionTimeoutMs = 1_000L,
                            snapshotPollIntervalMs = 100L,
                        ),
                )

            val result =
                loop.run(
                    UiAgentGoal.Generic(
                        instruction = "tap Search",
                        targetPackageName = "com.google.android.youtube",
                    ),
                )

            assertTrue(result.completed)
            assertEquals("com.google.android.youtube", planner.prompts.single().snapshot.foregroundPackageName)
        }

    @Test
    fun `recovery policy separates retryable state failures from terminal safety failures`() {
        val policy = UiAgentRecoveryPolicy()
        val snapshot = snapshotWithButton()

        val missingNodeRecovery =
            policy.planRecovery(
                failure =
                    UiAgentRecoverableFailure.Validation(
                        reason = "Target node button was not found.",
                        failureReason = UiAgentLoopFailureReason.INVALID_ACTION,
                    ),
                snapshot = snapshot,
                decision = UiAgentDecision(UiAgentAction.TapNode("button")),
                goal = UiAgentGoal.Generic("tap"),
            )
        val wrongConversationRecovery =
            policy.planRecovery(
                failure =
                    UiAgentRecoverableFailure.Execution(
                        reason = "Wrong conversation detected before drafting.",
                        retryable = true,
                    ),
                snapshot = snapshot,
                decision = UiAgentDecision(UiAgentAction.PressGlobal(UiAgentGlobalAction.BACK)),
                goal = UiAgentGoal.Generic("back"),
            )
        val ambiguousRecipientRecovery =
            policy.planRecovery(
                failure =
                    UiAgentRecoverableFailure.Execution(
                        reason = "Ambiguous exact recipient rows are visible.",
                        retryable = false,
                    ),
                snapshot = snapshot,
                decision = UiAgentDecision(UiAgentAction.TapNode("button")),
                goal =
                    UiAgentGoal.SendMessage(
                        recipient = "Sweetheart",
                        message = "hi",
                        targetPackageName = "com.whatsapp",
                    ),
            )

        assertTrue((missingNodeRecovery as UiAgentRecoveryDecision.Retry).action is UiAgentRecoveryAction.RefreshSnapshot)
        assertTrue((wrongConversationRecovery as UiAgentRecoveryDecision.Retry).action is UiAgentRecoveryAction.PressBack)
        assertTrue(ambiguousRecipientRecovery is UiAgentRecoveryDecision.Terminal)
    }

    @Test
    fun `missing node refreshes snapshot and replans within step budget`() =
        runTest {
            val staleSnapshot =
                UiSnapshot(
                    capturedAtEpochMs = 1L,
                    rootNodeIds = listOf("root"),
                    nodes = listOf(UiNode(id = "root")),
                )
            val freshSnapshot = snapshotWithButton()
            val planner =
                RecordingPlanner(
                    listOf(
                        UiAgentDecision(action = UiAgentAction.TapNode("button")),
                        UiAgentDecision(action = UiAgentAction.TapNode("button")),
                        UiAgentDecision(action = UiAgentAction.NoOp("done")),
                    ),
                )
            val executor = RecordingExecutor()
            val loop =
                UiAgentLoop(
                    snapshotProvider =
                        SequenceSnapshotProvider(
                            staleSnapshot,
                            freshSnapshot,
                            freshSnapshot,
                            freshSnapshot,
                            freshSnapshot,
                        ),
                    planner = planner,
                    executor = executor,
                    config = UiAgentLoopConfig(maxSteps = 3, maxRecoveryAttempts = 1),
                )

            val result = loop.run(UiAgentGoal.Generic("tap continue"))

            assertTrue(result.completed)
            assertEquals(3, planner.prompts.size)
            assertEquals(2, executor.commands.size)
            assertTrue(result.history.first().recoveryAction is UiAgentRecoveryAction.RefreshSnapshot)
            assertEquals(1, result.history.first().recoveryAttempt)
        }

    @Test
    fun `wrong conversation retry presses back then replans against fresh state`() =
        runTest {
            val snapshot =
                snapshotWithButton()
                    .copy(
                        foregroundPackageName = "com.whatsapp",
                        nodes =
                            snapshotWithButton().nodes.map { node ->
                                node.copy(packageName = "com.whatsapp")
                            },
                    )
            val planner =
                RecordingPlanner(
                    listOf(
                        UiAgentDecision(action = UiAgentAction.TapNode("button")),
                        UiAgentDecision(action = UiAgentAction.NoOp("backed out")),
                    ),
                )
            val executor =
                RecordingExecutor(
                    listOf(
                        UiAgentExecutionResult.Failed(
                            reason = "Wrong conversation detected before drafting.",
                            retryable = true,
                        ),
                    ),
                )
            val loop =
                UiAgentLoop(
                    snapshotProvider = SequenceSnapshotProvider(snapshot, snapshot, snapshot, snapshot),
                    planner = planner,
                    executor = executor,
                    config = UiAgentLoopConfig(maxSteps = 2, maxRecoveryAttempts = 1),
                )

            val result =
                loop.run(
                    UiAgentGoal.SendMessage(
                        recipient = "Sweetheart",
                        message = "hi",
                        targetPackageName = "com.whatsapp",
                    ),
            )

            assertTrue(result.completed)
            assertEquals(3, executor.commands.size)
            assertTrue(executor.commands[1].action is UiAgentAction.PressGlobal)
            assertTrue(result.history.first().recoveryAction is UiAgentRecoveryAction.PressBack)
        }

    @Test
    fun `aborts on failed execution`() =
        runTest {
            val planner =
                RecordingPlanner(
                    listOf(
                        UiAgentDecision(
                            action = UiAgentAction.TapNode("button"),
                            expectedState = UiExpectedState.TextVisible("Done"),
                        ),
                    ),
                )
            val executor =
                RecordingExecutor(
                    listOf(UiAgentExecutionResult.Failed("bridge offline")),
                )
            val loop =
                UiAgentLoop(
                    snapshotProvider = SequenceSnapshotProvider(snapshotWithButton()),
                    planner = planner,
                    executor = executor,
                )

            val result = loop.run(UiAgentGoal.Generic("tap continue"))

            assertFalse(result.completed)
            assertEquals(UiAgentLoopFailureReason.EXECUTION_FAILED, result.failureReason)
            assertEquals("bridge offline", result.failureMessage)
            assertEquals(1, executor.commands.size)
            assertEquals(1, result.history.size)
        }

    @Test
    fun `enriches node-available expected state so verification survives node id changes`() =
        runTest {
            val initialSnapshot =
                UiSnapshot(
                    capturedAtEpochMs = 1L,
                    foregroundPackageName = "com.whatsapp",
                    rootNodeIds = listOf("root"),
                    nodes =
                        listOf(
                            UiNode(id = "root", childIds = listOf("row")),
                            UiNode(
                                id = "node-25",
                                packageName = "com.whatsapp",
                                viewIdResourceName = "com.whatsapp:id/contact_name",
                                text = "Sweetheart",
                                visibleToUser = true,
                            ),
                        ),
                )
            val postActionSnapshot =
                initialSnapshot.copy(
                    nodes =
                        listOf(
                            UiNode(id = "root", childIds = listOf("row")),
                            UiNode(
                                id = "node-40",
                                packageName = "com.whatsapp",
                                viewIdResourceName = "com.whatsapp:id/contact_name",
                                text = "Sweetheart",
                                visibleToUser = true,
                            ),
                        ),
                )
            val planner =
                RecordingPlanner(
                    listOf(
                        UiAgentDecision(
                            action = UiAgentAction.NoOp("already selected"),
                            expectedState = UiExpectedState.NodeAvailable("node-25"),
                        ),
                    ),
                )
            val loop =
                UiAgentLoop(
                    snapshotProvider =
                        SequenceSnapshotProvider(
                            initialSnapshot,
                            postActionSnapshot,
                        ),
                    planner = planner,
                    executor = RecordingExecutor(),
                    config = UiAgentLoopConfig(maxSteps = 1),
                )

            val result = loop.run(UiAgentGoal.Generic("select recipient"))

            assertTrue(result.completed)
        }

    @Test
    fun `allows send message tap on visible recipient label with clickable row ancestor`() =
        runTest {
            val snapshot =
                UiSnapshot(
                    capturedAtEpochMs = 1L,
                    foregroundPackageName = "com.whatsapp",
                    rootNodeIds = listOf("root"),
                    nodes =
                        listOf(
                            UiNode(id = "root", childIds = listOf("row")),
                            UiNode(
                                id = "row",
                                packageName = "com.whatsapp",
                                viewIdResourceName = "com.whatsapp:id/contact_row_container",
                                childIds = listOf("name"),
                                enabled = true,
                                clickable = true,
                                visibleToUser = true,
                            ),
                            UiNode(
                                id = "name",
                                parentId = "row",
                                packageName = "com.whatsapp",
                                viewIdResourceName = "com.whatsapp:id/contact_name",
                                text = "Sweetheart",
                                visibleToUser = true,
                            ),
                        ),
                )
            val executor = RecordingExecutor()
            val loop =
                UiAgentLoop(
                    snapshotProvider = SequenceSnapshotProvider(snapshot, snapshot),
                    planner =
                        RecordingPlanner(
                            listOf(
                                UiAgentDecision(action = UiAgentAction.TapNode("name")),
                                UiAgentDecision(action = UiAgentAction.NoOp("recipient selected")),
                            ),
                        ),
                    executor = executor,
                    config = UiAgentLoopConfig(maxSteps = 2),
                )

            val result =
                loop.run(
                    UiAgentGoal.SendMessage(
                        recipient = "Sweetheart",
                        message = "hi there",
                        targetPackageName = "com.whatsapp",
                    ),
                )

            assertTrue(result.completed)
            assertEquals("name", (executor.commands.first().action as UiAgentAction.TapNode).nodeId)
        }

    @Test
    fun `blocks actions on sensitive nodes before execution`() =
        runTest {
            val snapshot =
                UiSnapshot(
                    capturedAtEpochMs = 1L,
                    rootNodeIds = listOf("root"),
                    nodes =
                        listOf(
                            UiNode(id = "root"),
                            UiNode(
                                id = "password",
                                enabled = true,
                                editable = true,
                                actions = listOf(UiNodeAction.SET_TEXT),
                                sensitive = true,
                            ),
                        ),
                )
            val planner =
                RecordingPlanner(
                    listOf(
                        UiAgentDecision(
                            action = UiAgentAction.SetText("password", "secret"),
                            expectedState = UiExpectedState.NodeAvailable("password"),
                        ),
                    ),
                )
            val executor = RecordingExecutor()
            val loop =
                UiAgentLoop(
                    snapshotProvider = SequenceSnapshotProvider(snapshot),
                    planner = planner,
                    executor = executor,
                )

            val result = loop.run(UiAgentGoal.Generic("fill password"))

            assertFalse(result.completed)
            assertEquals(UiAgentLoopFailureReason.UNSAFE_ACTION, result.failureReason)
            assertEquals(0, executor.commands.size)
            assertTrue(result.history.single().safetyResult is UiAgentSafetyResult.Blocked)
        }

    @Test
    fun `allows scroll action on visible scrollable node`() =
        runTest {
            val snapshot =
                UiSnapshot(
                    capturedAtEpochMs = 1L,
                    rootNodeIds = listOf("root"),
                    nodes =
                        listOf(
                            UiNode(id = "root"),
                            UiNode(
                                id = "list",
                                enabled = true,
                                visibleToUser = true,
                                boundsInScreen = UiBounds(left = 0, top = 100, right = 100, bottom = 500),
                                actions = listOf(UiNodeAction.SCROLL_FORWARD),
                            ),
                        ),
                )
            val executor = RecordingExecutor()
            val loop =
                UiAgentLoop(
                    snapshotProvider = SequenceSnapshotProvider(snapshot, snapshot, snapshot),
                    planner =
                        RecordingPlanner(
                            listOf(
                                UiAgentDecision(action = UiAgentAction.ScrollNode("list")),
                                UiAgentDecision(action = UiAgentAction.NoOp("done")),
                            ),
                        ),
                    executor = executor,
                    config = UiAgentLoopConfig(maxSteps = 2),
                )

            val result = loop.run(UiAgentGoal.Generic("scroll down"))

            assertTrue(result.completed)
            assertEquals("list", (executor.commands.first().action as UiAgentAction.ScrollNode).nodeId)
        }

    private fun snapshotWithButton(text: String = "Continue"): UiSnapshot =
        UiSnapshot(
            capturedAtEpochMs = 1L,
            rootNodeIds = listOf("root"),
            nodes =
                listOf(
                    UiNode(id = "root", childIds = listOf("button")),
                    UiNode(
                        id = "button",
                        parentId = "root",
                        text = text,
                        enabled = true,
                        clickable = true,
                        actions = listOf(UiNodeAction.CLICK),
                    ),
                ),
        )

    private class SequenceSnapshotProvider(
        vararg snapshots: UiSnapshot?,
    ) : UiSnapshotProvider {
        private val snapshots = snapshots.toList()
        private var index = 0

        override fun currentSnapshot(): UiSnapshot? {
            val snapshot = snapshots.getOrElse(index) { snapshots.lastOrNull() }
            index += 1
            return snapshot
        }
    }

    private class RecordingPlanner(
        private val decisions: List<UiAgentDecision>,
    ) : UiAgentPlanner {
        val prompts = mutableListOf<UiPrompt>()
        val contexts = mutableListOf<UiAgentSessionContext>()

        override suspend fun decide(
            prompt: UiPrompt,
            context: UiAgentSessionContext,
        ): UiAgentDecision {
            prompts += prompt
            contexts += context
            return decisions.getOrElse(prompts.lastIndex) {
                UiAgentDecision(UiAgentAction.NoOp("no more decisions"))
            }
        }
    }

    private class RecordingExecutor(
        private val results: List<UiAgentExecutionResult> = emptyList(),
    ) : UiAgentActionExecutor {
        val commands = mutableListOf<UiAgentExecutionCommand>()

        override suspend fun execute(command: UiAgentExecutionCommand): UiAgentExecutionResult {
            commands += command
            return results.getOrElse(commands.lastIndex) { UiAgentExecutionResult.Succeeded }
        }
    }

    private fun mutableClock(
        startAt: Long = 0L,
        tickMs: Long = 100L,
    ): () -> Long {
        var value = startAt
        return {
            value.also {
                value += tickMs
            }
        }
    }
}
