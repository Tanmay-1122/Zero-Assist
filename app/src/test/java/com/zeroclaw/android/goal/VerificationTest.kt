/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.goal

import com.zeroclaw.android.goal.agents.ExecutionOutcome
import com.zeroclaw.android.goal.graph.TaskNode
import com.zeroclaw.android.goal.graph.TaskState
import com.zeroclaw.android.goal.graph.VerificationSpec
import com.zeroclaw.android.goal.schedule.GoalScheduler
import com.zeroclaw.android.goal.schedule.ResourceLimits
import com.zeroclaw.android.goal.verify.OutputSchemaVerifier
import com.zeroclaw.android.goal.verify.VerificationEngine
import com.zeroclaw.android.goal.verify.VerificationResult
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

class VerificationTest {

    @JvmField
    @Rule
    val timeout = Timeout.seconds(60)

    private fun buildScheduler(
        scope: CoroutineScope,
        executor: ScriptedExecutor,
        verification: VerificationEngine,
    ): GoalScheduler = GoalScheduler(
        executor = executor,
        verificationEngine = verification,
        recoveryEngine = com.zeroclaw.android.goal.recover.RecoveryEngine(),
        scope = scope,
        resourceLimits = ResourceLimits(maxConcurrentTasks = 4),
        identityGrant = GoalSecurityEnforcer.READ_ONLY,
    )

    @Test
    fun `schema verifier requires declared output keys`() = runTest {
        val engine = VerificationEngine()
        engine.register(OutputSchemaVerifier())

        val good = ExecutionOutcome.Output("p", "{\"ok\":true,\"id\":\"x\"}")
        val bad = ExecutionOutcome.Output("p", "{\"ok\":true}")

        val task = TaskNode(
            taskId = "t",
            capabilityId = "X",
            verification = VerificationSpec(
                verifierId = "OUTPUT_SCHEMA",
                requiredOutputKeys = listOf("ok", "id"),
            ),
        )

        assertTrue(engine.verify(task, good) is VerificationResult.Verified)
        assertTrue(engine.verify(task, bad) is VerificationResult.NotVerified)
    }

    @Test
    fun `none mode skips verification`() = runTest {
        val engine = VerificationEngine()
        val task = TaskNode(
            taskId = "t",
            capabilityId = "X",
            verification = VerificationSpec(mode = VerificationMode.NONE),
        )
        val outcome = ExecutionOutcome.Output("p", "{}")
        assertTrue(engine.verify(task, outcome) is VerificationResult.Verified)
    }

    @Test
    fun `task succeeds only when verification passes`() = runTest {
        val executor = ScriptedExecutor()
        executor.on("GOOD") { _ -> ExecutionOutcome.Output("p", "{\"ok\":true,\"id\":\"a\"}") }
        executor.on("BAD") { _ -> ExecutionOutcome.Output("p", "{\"ok\":true}") }

        val engine = VerificationEngine()
        engine.register(OutputSchemaVerifier())
        val scheduler = buildScheduler(CoroutineScope(coroutineContext + SupervisorJob()), executor, engine)

        val graph = com.zeroclaw.android.goal.graph.TaskGraph.from(
            "verify",
            listOf(
                TaskNode(
                    taskId = "good_task",
                    capabilityId = "GOOD",
                    verification = VerificationSpec(
                        verifierId = "OUTPUT_SCHEMA",
                        requiredOutputKeys = listOf("id"),
                    ),
                ),
                TaskNode(
                    taskId = "bad_task",
                    capabilityId = "BAD",
                    verification = VerificationSpec(
                        verifierId = "OUTPUT_SCHEMA",
                        requiredOutputKeys = listOf("id"),
                    ),
                ),
            ),
        )

        val goal = testGoal("verify").copy(
            failurePolicy = FailurePolicy(actions = listOf(FailurePolicyAction.ABORT)),
        )
        val deferred = async { scheduler.execute(goal, graph) }
        advanceUntilIdle()
        val result = deferred.await()

        assertEquals(TaskState.SUCCEEDED, result.graph.node("good_task")!!.state)
        assertEquals(TaskState.FAILED, result.graph.node("bad_task")!!.state)
        assertEquals(GoalStatus.FAILED, result.status)
    }

    @Test
    fun `verification attempts retry within policy`() = runTest {
        val executor = ScriptedExecutor()
        executor.on("FLAPPY") { _ -> ExecutionOutcome.Output("p", "{\"ok\":true}") }
        val engine = VerificationEngine()

        // Verifier that fails the first two attempts, passes on the third.
        var attempts = 0
        val flakyVerifier = object : com.zeroclaw.android.goal.verify.TaskVerifier {
            override val verifierId: String = "FLAKY"
            override suspend fun verify(task: TaskNode, outcome: ExecutionOutcome): VerificationResult {
                attempts += 1
                return if (attempts >= 3) {
                    VerificationResult.Verified(evidence = "flaky passed")
                } else {
                    VerificationResult.NotVerified("not yet")
                }
            }
        }
        engine.register(flakyVerifier)

        val scheduler = buildScheduler(CoroutineScope(coroutineContext + SupervisorJob()), executor, engine)
        val graph = com.zeroclaw.android.goal.graph.TaskGraph.from(
            "flaky",
            listOf(
                TaskNode(
                    taskId = "t",
                    capabilityId = "FLAPPY",
                    verification = VerificationSpec(verifierId = "FLAKY", maxAttempts = 3),
                ),
            ),
        )

        val deferred = async { scheduler.execute(testGoal("flaky"), graph) }
        advanceUntilIdle()
        val result = deferred.await()

        assertEquals(GoalStatus.COMPLETED, result.status)
        assertEquals(TaskState.SUCCEEDED, result.graph.node("t")!!.state)
        assertEquals(3, attempts)
    }
}
