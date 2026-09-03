/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.goal

import com.zeroclaw.android.goal.agents.ExecutionOutcome
import com.zeroclaw.android.goal.integration.GoalRuntimeBridge
import com.zeroclaw.android.goal.schedule.GoalScheduler
import com.zeroclaw.android.goal.schedule.ResourceLimits
import com.zeroclaw.android.goal.verify.VerificationEngine
import com.zeroclaw.android.model.content.ContentBlock
import com.zeroclaw.android.runtime.BlockRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class GoalRuntimeBridgeTest {

    @JvmField
    @Rule
    val timeout = Timeout.seconds(60)

    @Test
    fun `bridge streams tasks into the block runtime`() = runTest {
        val executor = ScriptedExecutor()
        executor.on("A") { _ -> ExecutionOutcome.Output("p", "{\"a\":1}") }
        executor.on("B") { _ -> ExecutionOutcome.Output("p", "{\"b\":2}") }
        val scheduler = GoalScheduler(
            executor = executor,
            verificationEngine = VerificationEngine(),
            recoveryEngine = com.zeroclaw.android.goal.recover.RecoveryEngine(),
            scope = CoroutineScope(coroutineContext + SupervisorJob()),
            resourceLimits = ResourceLimits(maxConcurrentTasks = 2),
            identityGrant = GoalSecurityEnforcer.READ_ONLY,
        )
        val runtime = BlockRuntime(conversationId = "conv", messageId = "msg")
        val bridge = GoalRuntimeBridge(
            scheduler = scheduler,
            conversationId = "conv",
            messageId = "msg",
            runtime = runtime,
        )
        bridge.start(CoroutineScope(coroutineContext + SupervisorJob()))

        val graph = chainGraph("bridge", listOf("A", "B"))
        val deferred = async { scheduler.execute(testGoal("bridge"), graph) }
        advanceUntilIdle()
        deferred.await()

        val blockIds = runtime.blocksState.value.map { it.blockId }.toSet()
        assertTrue("task card missing", blockIds.contains("goaee_task_0"))
        assertTrue("task card missing", blockIds.contains("goaee_task_1"))
        assertTrue("final callout missing", blockIds.contains("goaee_final"))

        val finalCallout = runtime.blocksState.value.firstOrNull { it.blockId == "goaee_final" }
        assertTrue(finalCallout is ContentBlock.Callout)
        val taskCard = runtime.blocksState.value.firstOrNull { it.blockId == "goaee_task_0" }
        assertTrue(taskCard is ContentBlock.ToolCard)
        assertEquals("A", (taskCard as ContentBlock.ToolCard).toolName)

        bridge.stop()
        runtime.destroy()
    }

    @Test
    fun `bridge renders a task tree container on progress`() = runTest {
        val executor = ScriptedExecutor()
        executor.on("A") { _ -> ExecutionOutcome.Output("p", "{\"ok\":true}") }
        executor.setDelay(1)
        val scheduler = GoalScheduler(
            executor = executor,
            verificationEngine = VerificationEngine(),
            recoveryEngine = com.zeroclaw.android.goal.recover.RecoveryEngine(),
            scope = CoroutineScope(coroutineContext + SupervisorJob()),
            resourceLimits = ResourceLimits(maxConcurrentTasks = 2),
            identityGrant = GoalSecurityEnforcer.READ_ONLY,
        )
        val runtime = BlockRuntime(conversationId = "conv", messageId = "msg")
        val bridge = GoalRuntimeBridge(scheduler, "conv", "msg", runtime)
        bridge.start(CoroutineScope(coroutineContext + SupervisorJob()))

        val graph = parallelGraph("tree", "A", 5)
        val deferred = async { scheduler.execute(testGoal("tree"), graph) }
        advanceUntilIdle()
        deferred.await()

        val blocks = runtime.blocksState.value
        val tree = blocks.firstOrNull { it.blockId == "goaee_tree" }
        assertTrue("task tree container missing", tree is ContentBlock.Container)
        val header = (tree as ContentBlock.Container).children.firstOrNull { it.blockId == "goaee_header" }
        assertTrue("header missing", header is ContentBlock.Callout)
        assertTrue((header as ContentBlock.Callout).title!!.contains("100%"))

        bridge.stop()
        runtime.destroy()
    }
}
