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
import com.zeroclaw.android.goal.memory.GoalMemoryService
import com.zeroclaw.android.goal.memory.InMemoryGoalStore
import com.zeroclaw.android.goal.memory.JsonGoalStore
import com.zeroclaw.android.goal.memory.StoredGoal
import com.zeroclaw.android.goal.schedule.ExecutionEvent
import com.zeroclaw.android.goal.schedule.GoalScheduler
import com.zeroclaw.android.goal.schedule.ResourceLimits
import com.zeroclaw.android.goal.verify.VerificationEngine
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout

class PersistenceTest {

    @JvmField
    @Rule
    val timeout = Timeout.seconds(60)

    private fun tempDir(): File =
        Files.createTempDirectory("goaee_persistence_test").toFile()

    @Test
    fun `in memory store round trips goals`() = runTest {
        val store = InMemoryGoalStore()
        val stored = StoredGoal(goal = testGoal("g1"), graph = chainGraph("g1", listOf("A", "B")))

        store.save(stored)
        assertEquals(1, store.size())
        val loaded = store.load("g1")
        assertNotNull(loaded)
        assertEquals("g1", loaded!!.goal.id)
        assertEquals(2, loaded.graph.size())
        assertEquals(1, loaded.snapshotCount)

        store.save(stored)
        assertEquals(1, store.size())
        assertEquals(2, store.load("g1")!!.snapshotCount)
        assertEquals(listOf("g1"), store.listGoalIds())

        store.delete("g1")
        assertNull(store.load("g1"))
        assertTrue(store.listGoalIds().isEmpty())
    }

    @Test
    fun `json store survives a fresh instance`() = runTest {
        val dir = tempDir()
        try {
            val graph = TaskGraph.from(
                "crash",
                listOf(
                    TaskNode(taskId = "done", capabilityId = "A", state = TaskState.SUCCEEDED),
                    TaskNode(taskId = "next", capabilityId = "B"),
                ),
            )
            val stored = StoredGoal(
                goal = testGoal("crash"),
                graph = graph,
                savedAtEpochMs = 1234L,
            )

            JsonGoalStore(dir).save(stored)

            // Simulates a process restart: brand new store instance over the same files.
            val fresh = JsonGoalStore(dir)
            val loaded = fresh.load("crash")
            assertNotNull(loaded)
            assertEquals("crash", loaded!!.goal.id)
            assertEquals(2, loaded.graph.size())
            assertEquals(TaskState.SUCCEEDED, loaded.graph.node("done")!!.state)
            assertEquals(TaskState.PENDING, loaded.graph.node("next")!!.state)
            assertEquals(1234L, loaded.savedAtEpochMs)
            assertEquals(listOf("crash"), fresh.listGoalIds())

            fresh.delete("crash")
            assertNull(fresh.load("crash"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `schedule stores goals for later execution`() = runTest {
        val memory = GoalMemoryService(InMemoryGoalStore())
        memory.schedule(testGoal("later"), atEpochMs = 0L)

        val due = memory.dueGoals(nowEpochMs = 100L)
        assertEquals(1, due.size)
        assertEquals(GoalType.SCHEDULED, due.first().goal.type)
        assertEquals(0L, due.first().goal.runtime.scheduledForEpochMs)

        val notDue = memory.dueGoals(nowEpochMs = -1L)
        assertTrue(notDue.isEmpty())
    }

    @Test
    fun `resume restarts an interrupted execution`() = runTest {
        val executor = ScriptedExecutor()
        executor.on("B") { _ -> ExecutionOutcome.Output("p", "{\"ok\":true}") }
        val scheduler = GoalScheduler(
            executor = executor,
            verificationEngine = VerificationEngine(),
            recoveryEngine = com.zeroclaw.android.goal.recover.RecoveryEngine(),
            scope = CoroutineScope(coroutineContext + SupervisorJob()),
            resourceLimits = ResourceLimits(maxConcurrentTasks = 2),
            identityGrant = GoalSecurityEnforcer.READ_ONLY,
        )
        val memory = GoalMemoryService(InMemoryGoalStore())

        // A goal interrupted after the first task already finished.
        val partial = TaskGraph.from(
            "resume_goal",
            listOf(
                TaskNode(taskId = "task_0", capabilityId = "A", state = TaskState.SUCCEEDED),
                TaskNode(taskId = "task_1", capabilityId = "B"),
            ),
        )
        memory.snapshot(testGoal("resume_goal").copy(runtime = GoalRuntimeMetadata(attempt = 1)), partial)

        val completed = mutableListOf<String>()
        val collector = CoroutineScope(coroutineContext + SupervisorJob()).launch {
            scheduler.events.collect { event ->
                if (event is ExecutionEvent.GoalCompleted) completed.add(event.goal.id)
            }
        }

        val resumed = memory.resume("resume_goal", scheduler)
        assertNotNull(resumed)
        assertEquals(2, resumed!!.runtime.attempt)
        advanceUntilIdle()

        assertTrue(completed.contains("resume_goal"))
        assertTrue(executor.callLog.contains("task_1"))
        collector.cancel()
    }
}
