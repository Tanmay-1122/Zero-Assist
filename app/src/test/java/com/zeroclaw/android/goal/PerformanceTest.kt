/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.goal

import com.zeroclaw.android.goal.agents.ExecutionOutcome
import com.zeroclaw.android.goal.graph.TaskGraph
import com.zeroclaw.android.goal.graph.TaskNode
import com.zeroclaw.android.goal.graph.TaskState
import com.zeroclaw.android.goal.graph.isTerminal
import com.zeroclaw.android.goal.schedule.GoalScheduler
import com.zeroclaw.android.goal.schedule.ResourceLimits
import com.zeroclaw.android.goal.verify.VerificationEngine
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

class PerformanceTest {

    @JvmField
    @Rule
    val timeout = Timeout.seconds(60)

    private fun buildScheduler(scope: CoroutineScope, executor: ScriptedExecutor, concurrency: Int): GoalScheduler =
        GoalScheduler(
            executor = executor,
            verificationEngine = VerificationEngine(),
            recoveryEngine = com.zeroclaw.android.goal.recover.RecoveryEngine(),
            scope = scope,
            resourceLimits = ResourceLimits(maxConcurrentTasks = concurrency),
            identityGrant = GoalSecurityEnforcer.READ_ONLY,
        )

    @Test
    fun `executes a 1000 node chain within budget`() = runTest {
        val executor = ScriptedExecutor()
        executor.on("*") { _ -> ExecutionOutcome.Output("p", "{\"ok\":true}") }
        val scheduler = buildScheduler(CoroutineScope(coroutineContext + SupervisorJob()), executor, 1)
        val graph = chainGraph("perf_chain", List(1000) { "C_$it" })

        val start = System.currentTimeMillis()
        val deferred = async { scheduler.execute(testGoal("perf_chain"), graph) }
        advanceUntilIdle()
        val result = deferred.await()
        val elapsed = System.currentTimeMillis() - start

        assertEquals(GoalStatus.COMPLETED, result.status)
        assertEquals(1000, result.graph.nodes.values.count { it.state.isTerminal })
        assertTrue("took too long: ${elapsed}ms", elapsed < 20_000)
    }

    @Test
    fun `executes 1000 parallel tasks capped at concurrency limit`() = runTest {
        val executor = ScriptedExecutor()
        executor.on("P") { _ -> ExecutionOutcome.Output("p", "{\"ok\":true}") }
        executor.setDelay(1)
        val scheduler = buildScheduler(CoroutineScope(coroutineContext + SupervisorJob()), executor, 64)
        val graph = parallelGraph("perf_par", "P", 1000)

        val deferred = async { scheduler.execute(testGoal("perf_par"), graph) }
        advanceUntilIdle()
        val result = deferred.await()

        assertEquals(GoalStatus.COMPLETED, result.status)
        assertEquals(1000, result.graph.nodes.values.count { it.state == TaskState.SUCCEEDED })
        assertEquals(64, executor.concurrentMax.get())
    }

    @Test
    fun `broad fan-in merge handles 500 parallel producers`() = runTest {
        val executor = ScriptedExecutor()
        executor.on("PRODUCER") { _ -> ExecutionOutcome.Output("p", "{\"v\":1}") }
        executor.on("MERGE") { _ -> ExecutionOutcome.Output("p", "{\"merged\":true}") }
        val scheduler = buildScheduler(CoroutineScope(coroutineContext + SupervisorJob()), executor, 32)
        val producers = (0 until 500).map { TaskNode(taskId = "pro_$it", capabilityId = "PRODUCER") }
        val merger = TaskNode(
            taskId = "merger",
            capabilityId = "MERGE",
            dependencies = producers.map { it.taskId },
        )
        val graph = com.zeroclaw.android.goal.graph.TaskGraph.from("fanin", producers + merger)

        val deferred = async { scheduler.execute(testGoal("fanin"), graph) }
        advanceUntilIdle()
        val result = deferred.await()

        assertEquals(GoalStatus.COMPLETED, result.status)
        assertEquals(501, result.graph.nodes.values.count { it.state == TaskState.SUCCEEDED })
    }
}
