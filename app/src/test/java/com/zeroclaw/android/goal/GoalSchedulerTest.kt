/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.goal

import com.zeroclaw.android.goal.agents.ExecutionOutcome
import com.zeroclaw.android.goal.graph.GraphUpdate
import com.zeroclaw.android.goal.graph.TaskGraph
import com.zeroclaw.android.goal.graph.TaskNode
import com.zeroclaw.android.goal.graph.TaskState
import com.zeroclaw.android.goal.recover.HumanClarificationAnswer
import com.zeroclaw.android.goal.recover.RecoveryEngine
import com.zeroclaw.android.goal.schedule.ExecutionEvent
import com.zeroclaw.android.goal.schedule.GoalScheduler
import com.zeroclaw.android.goal.schedule.ResourceLimits
import com.zeroclaw.android.goal.verify.VerificationEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class GoalSchedulerTest {

    @JvmField
    @Rule
    val timeout = Timeout.seconds(60)

    private fun buildScheduler(
        scope: CoroutineScope,
        executor: ScriptedExecutor,
        verification: VerificationEngine = VerificationEngine(),
        recovery: RecoveryEngine = RecoveryEngine(),
        limits: ResourceLimits = ResourceLimits(maxConcurrentTasks = 4),
        identityGrant: SecurityContext = GoalSecurityEnforcer.READ_ONLY,
        replanProvider: com.zeroclaw.android.goal.plan.AlternativePlanProvider? = null,
    ): GoalScheduler = GoalScheduler(
        executor = executor,
        verificationEngine = verification,
        recoveryEngine = recovery,
        replanProvider = replanProvider,
        scope = scope,
        resourceLimits = limits,
        identityGrant = identityGrant,
    )

    @Test
    fun `executes sequential chain in dependency order`() = runTest {
        val executor = ScriptedExecutor()
        executor.on("A") { _ -> ExecutionOutcome.Output("pA", "{\"a\":1}") }
        executor.on("B") { _ -> ExecutionOutcome.Output("pB", "{\"b\":2}") }
        executor.on("C") { _ -> ExecutionOutcome.Output("pC", "{\"c\":3}") }
        val scheduler = buildScheduler(CoroutineScope(coroutineContext + SupervisorJob()), executor)
        val graph = chainGraph("seq", listOf("A", "B", "C"))

        val deferred = async { scheduler.execute(testGoal("seq"), graph) }
        advanceUntilIdle()
        val result = deferred.await()

        assertEquals(GoalStatus.COMPLETED, result.status)
        assertEquals(1f, result.progress)
        assertEquals(listOf("task_0", "task_1", "task_2"), executor.callLog.toList().sorted())
        val order = executor.callLog.toList()
        assertTrue(order.indexOf("task_0") < order.indexOf("task_1"))
        assertTrue(order.indexOf("task_1") < order.indexOf("task_2"))
    }

    @Test
    fun `executes parallel roots concurrently`() = runTest {
        val executor = ScriptedExecutor()
        executor.on("X") { _ -> ExecutionOutcome.Output("p", "{\"ok\":true}") }
        executor.setDelay(20)
        val scheduler = buildScheduler(
            CoroutineScope(coroutineContext + SupervisorJob()),
            executor,
            limits = ResourceLimits(maxConcurrentTasks = 4),
        )
        val graph = parallelGraph("par", "X", 4)

        val deferred = async { scheduler.execute(testGoal("par"), graph) }
        advanceUntilIdle()
        val result = deferred.await()

        assertEquals(GoalStatus.COMPLETED, result.status)
        assertEquals(4, executor.concurrentMax.get())
    }

    @Test
    fun `respects per-capability backpressure`() = runTest {
        val executor = ScriptedExecutor()
        executor.on("SLOW") { _ -> ExecutionOutcome.Output("p", "{\"ok\":true}") }
        executor.setDelay(10)
        val scheduler = buildScheduler(
            CoroutineScope(coroutineContext + SupervisorJob()),
            executor,
            limits = ResourceLimits(maxConcurrentTasks = 8, maxPerCapability = mapOf("SLOW" to 1)),
        )
        val graph = parallelGraph("bp", "SLOW", 3)

        val deferred = async { scheduler.execute(testGoal("bp"), graph) }
        advanceUntilIdle()
        val result = deferred.await()

        assertEquals(GoalStatus.COMPLETED, result.status)
        assertEquals(1, executor.concurrentMax.get())
    }

    @Test
    fun `priority scheduling runs critical before low`() = runTest {
        val executor = ScriptedExecutor()
        executor.setDelay(5)
        val order = mutableListOf<String>()
        executor.on("LOW") { _ -> order.add("low"); ExecutionOutcome.Output("p", "{\"ok\":true}") }
        executor.on("CRIT") { _ -> order.add("crit"); ExecutionOutcome.Output("p", "{\"ok\":true}") }

        val scheduler = buildScheduler(
            CoroutineScope(coroutineContext + SupervisorJob()),
            executor,
            limits = ResourceLimits(maxConcurrentTasks = 1),
        )
        val graph = TaskGraph.from(
            "prio",
            listOf(
                TaskNode(taskId = "low_1", capabilityId = "LOW", priority = GoalPriority.LOW),
                TaskNode(taskId = "crit_1", capabilityId = "CRIT", priority = GoalPriority.CRITICAL),
                TaskNode(taskId = "low_2", capabilityId = "LOW", priority = GoalPriority.LOW),
            ),
        )

        val deferred = async { scheduler.execute(testGoal("prio"), graph) }
        advanceUntilIdle()
        deferred.await()

        assertEquals("crit", order.first())
        assertEquals(3, order.size)
    }

    @Test
    fun `cancellation cancels running and pending tasks`() = runTest {
        val executor = ScriptedExecutor()
        executor.on("X") { _ -> ExecutionOutcome.Output("p", "{\"ok\":true}") }
        executor.setDelay(5000)
        val scheduler = buildScheduler(CoroutineScope(coroutineContext + SupervisorJob()), executor)
        val graph = parallelGraph("cancel", "X", 3)

        val deferred = async { scheduler.execute(testGoal("cancel"), graph) }
        runCurrent() // leaves the tasks in-flight on their delay
        assertTrue(scheduler.isRunning())
        scheduler.cancel("test cancel")
        advanceUntilIdle()
        val result = deferred.await()

        assertEquals(GoalStatus.CANCELLED, result.status)
        assertTrue(result.skippedTasks.all { it.state == TaskState.CANCELLED })
    }

    @Test
    fun `task timeout marks task failed`() = runTest {
        val executor = ScriptedExecutor()
        executor.alwaysBlock()
        val scheduler = buildScheduler(CoroutineScope(coroutineContext + SupervisorJob()), executor)
        val graph = TaskGraph.from(
            "timeout",
            listOf(TaskNode(taskId = "slow", capabilityId = "X", timeoutMs = 100)),
        )

        val deferred = async { scheduler.execute(testGoal("timeout"), graph) }
        advanceUntilIdle()
        val result = deferred.await()

        assertEquals(GoalStatus.FAILED, result.status)
        val task = result.graph.node("slow")!!
        assertEquals(TaskState.FAILED, task.state)
        assertTrue(task.result!!.error!!.contains("timeout"))
    }

    @Test
    fun `deadline exceeded cancels goal`() = runTest {
        val executor = ScriptedExecutor()
        executor.setDelay(5000)
        val scheduler = buildScheduler(CoroutineScope(coroutineContext + SupervisorJob()), executor)
        val graph = parallelGraph("dl", "X", 2)
        val goal = testGoal("dl").copy(deadlineEpochMs = System.currentTimeMillis() - 1000)

        val deferred = async { scheduler.execute(goal, graph) }
        advanceUntilIdle()
        val result = deferred.await()

        assertEquals(GoalStatus.FAILED, result.status)
        assertTrue(result.skippedTasks.all { it.state == TaskState.CANCELLED })
    }

    @Test
    fun `dynamic graph update while running is picked up`() = runTest {
        val executor = ScriptedExecutor()
        executor.on("A") { _ -> ExecutionOutcome.Output("p", "{\"a\":1}") }
        executor.on("B") { _ -> ExecutionOutcome.Output("p", "{\"b\":2}") }
        executor.on("ADD") { _ -> ExecutionOutcome.Output("p", "{\"added\":true}") }
        executor.setDelay(50)
        val scheduler = buildScheduler(CoroutineScope(coroutineContext + SupervisorJob()), executor)
        val graph = chainGraph("dyn", listOf("A", "B"))

        val deferred = async { scheduler.execute(testGoal("dyn"), graph) }
        runCurrent() // t0 is now in-flight on its delay

        // Insert a new branch while t0 is still in flight.
        scheduler.submitUpdate(
            GraphUpdate.AddNodes(
                listOf(
                    TaskNode(taskId = "extra", capabilityId = "ADD", dependencies = listOf("task_0")),
                ),
            ),
        )
        advanceUntilIdle()
        val result = deferred.await()

        assertEquals(GoalStatus.COMPLETED, result.status)
        val extra = result.graph.node("extra")!!
        assertEquals(TaskState.SUCCEEDED, extra.state)
        assertTrue(executor.callLog.contains("extra"))
    }

    @Test
    fun `fails all tasks when root capability unknown`() = runTest {
        val executor = ScriptedExecutor()
        val scheduler = buildScheduler(CoroutineScope(coroutineContext + SupervisorJob()), executor)
        val graph = TaskGraph.from(
            "unknown",
            listOf(TaskNode(taskId = "u", capabilityId = "NO_SUCH_CAPABILITY")),
        )

        val deferred = async { scheduler.execute(testGoal("unknown"), graph) }
        advanceUntilIdle()
        val result = deferred.await()

        assertEquals(GoalStatus.FAILED, result.status)
        assertEquals(TaskState.FAILED, result.graph.node("u")!!.state)
    }

    @Test
    fun `emits progress events throughout execution`() = runTest {
        val executor = ScriptedExecutor()
        executor.on("A") { _ -> ExecutionOutcome.Output("p", "{\"a\":1}") }
        executor.on("B") { _ -> ExecutionOutcome.Output("p", "{\"b\":2}") }
        executor.setDelay(10)
        val scheduler = buildScheduler(CoroutineScope(coroutineContext + SupervisorJob()), executor)
        val graph = chainGraph("events", listOf("A", "B"))

        val collected = mutableListOf<ExecutionEvent>()
        val collector = CoroutineScope(coroutineContext + SupervisorJob()).launch {
            scheduler.events.toList(collected)
        }

        val deferred = async { scheduler.execute(testGoal("events"), graph) }
        advanceUntilIdle()
        deferred.await()
        collector.cancel()

        assertTrue(collected.any { it is ExecutionEvent.GoalStarted })
        assertTrue(collected.any { it is ExecutionEvent.GoalCompleted })
        val progressEvents = collected.filterIsInstance<ExecutionEvent.GoalProgress>()
        assertTrue(progressEvents.isNotEmpty())
        assertEquals(1f, progressEvents.last().progress)
    }

    @Test
    fun `1000 independent tasks complete under real concurrency`() = runTest {
        val executor = ScriptedExecutor()
        executor.on("X") { _ -> ExecutionOutcome.Output("p", "{\"ok\":true}") }
        executor.setDelay(0)
        val scheduler = buildScheduler(
            CoroutineScope(coroutineContext + SupervisorJob()),
            executor,
            limits = ResourceLimits(maxConcurrentTasks = 16),
        )
        val graph = parallelGraph("big_run", "X", 1000)

        val deferred = async { scheduler.execute(testGoal("big_run"), graph) }
        advanceUntilIdle()
        val result = deferred.await()

        assertEquals(GoalStatus.COMPLETED, result.status)
        assertEquals(1000, result.completedTasks.size)
    }
}
