/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.goal.graph

import com.zeroclaw.android.goal.chainGraph
import com.zeroclaw.android.goal.parallelGraph
import com.zeroclaw.android.goal.plan.ReplanningEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.Timeout
import kotlinx.coroutines.runBlocking

class TaskGraphTest {

    @JvmField
    @Rule
    val timeout = Timeout.seconds(60)

    @Test
    fun `graph roots and eligibility respect dependencies`() {
        val graph = chainGraph("g1", listOf("A", "B", "C", "D"))
        assertEquals(4, graph.size())
        assertEquals(listOf("task_0"), graph.roots().map { it.taskId })
        assertEquals(listOf("task_0"), graph.eligibleNodes().map { it.taskId })

        val afterA = graph.apply(GraphUpdate.ReplaceNode(graph.node("task_0")!!.copy(state = TaskState.SUCCEEDED)))
        assertEquals(listOf("task_1"), afterA.eligibleNodes().map { it.taskId })
    }

    @Test
    fun `apply update mutates revision copy-on-write`() {
        val graph = parallelGraph("g2", "X", 3)
        val updated = graph.apply(GraphUpdate.AddNode(TaskNode(taskId = "extra", capabilityId = "Y")))
        assertEquals(1, updated.revision)
        assertEquals(4, updated.size())
        assertEquals(3, graph.size())
        assertEquals(0, graph.revision)
    }

    @Test
    fun `remove node drops edges`() {
        val graph = chainGraph("g3", listOf("A", "B", "C"))
        val updated = graph.apply(GraphUpdate.RemoveNode("task_0"))
        assertEquals(2, updated.size())
        val task1 = updated.node("task_1")!!
        assertFalse(task1.dependencies.contains("task_0"))
    }

    @Test
    fun `replace node preserves dependencies and state`() {
        val graph = chainGraph("g4", listOf("A", "B"))
        val updated = graph.apply(
            GraphUpdate.ReplaceNode(
                TaskNode(taskId = "task_1", capabilityId = "B_ALT", state = TaskState.FAILED),
            ),
        )
        val replaced = updated.node("task_1")!!
        assertEquals(listOf("task_0"), replaced.dependencies)
        assertEquals(TaskState.FAILED, replaced.state)
    }

    @Test
    fun `blocked nodes detect failed dependencies`() {
        val graph = chainGraph("g5", listOf("A", "B"))
        val withFail = graph.apply(GraphUpdate.ReplaceNode(graph.node("task_0")!!.copy(state = TaskState.FAILED)))
        val blocked = withFail.blockedNodes().map { it.taskId }
        assertEquals(listOf("task_1"), blocked)
    }

    @Test
    fun `insert alternative segment rewires downstream (no flights scenario)`() {
        val graph = chainGraph("flights", listOf("FLIGHTS_SEARCH", "BOOKING"))
        val failedTask = graph.node("task_0")!!
        val replanning = ReplanningEngine()

        val updates = runBlocking {
            replanning.buildUpdates(
                graph = graph,
                failedTaskId = failedTask.taskId,
                alternativeSteps = listOf(
                    com.zeroclaw.android.goal.GoalPlanStep(capabilityId = "ALT_AIRPORTS_SEARCH", taskId = "alt_search"),
                    com.zeroclaw.android.goal.GoalPlanStep(capabilityId = "ALT_FLIGHTS", taskId = "alt_flights"),
                ),
            )
        }

        val replanned = runBlocking { replanning.applyUpdates(graph, updates) }
        assertTrue("alt_search", replanned.node("alt_search") != null)
        assertTrue("alt_flights", replanned.node("alt_flights") != null)
        assertNull(replanned.node("task_0"))
        // Booking now depends on the last replacement node.
        val booking = replanned.node("task_1")!!
        assertTrue(booking.dependencies.contains("alt_flights"))
        assertFalse(booking.dependencies.contains("task_0"))
        assertEquals(3, replanned.size())
    }

    @Test
    fun `graph validates invalid updates`() {
        val graph = parallelGraph("g6", "X", 2)
        assertFalse(graph.validateUpdate(GraphUpdate.AddEdge(from = "missing", to = "p_0")))
        assertFalse(graph.validateUpdate(GraphUpdate.RemoveNode("missing")))
        assertTrue(graph.validateUpdate(GraphUpdate.AddEdge(from = "p_0", to = "p_1")))
    }

    @Test
    fun `1000 node chain graph constructs and navigates`() {
        val capabilities = (0 until 1000).map { "CAP_$it" }
        val graph = chainGraph("big", capabilities)
        assertEquals(1000, graph.size())

        var current = graph
        (0 until 1000).forEach { index ->
            val eligible = current.eligibleNodes().map { it.taskId }
            assertEquals(listOf("task_$index"), eligible)
            current = current.apply(
                GraphUpdate.ReplaceNode(current.node("task_$index")!!.copy(state = TaskState.SUCCEEDED)),
            )
        }
        assertEquals(1000, current.nodes.values.count { it.state == TaskState.SUCCEEDED })
    }

    @Test
    fun `task graph serialization round trips`() {
        val graph = chainGraph("g7", listOf("A", "B", "C"))
        val json = TaskGraph.toJson(graph)
        val restored = TaskGraph.fromJson(json)
        assertEquals(graph, restored)
    }
}
