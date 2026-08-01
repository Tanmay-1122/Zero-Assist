/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.agent

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.service.cron.CronIntentParser
import com.zeroclaw.android.ui.component.SchedulingResultDialog
import com.zeroclaw.android.ui.component.SchedulingStatusIndicator
import com.zeroclaw.android.viewmodel.IntelligentSchedulerViewModel
import com.zeroclaw.android.viewmodel.ScheduleTState

/**
 * Integration for intelligent scheduling in agent conversations.
 *
 * Allows agents to schedule tasks by understanding natural language from group chat.
 *
 * Usage in GroupChatScreen:
 * ```kotlin
 * val schedulerViewModel = remember { IntelligentSchedulerViewModel(...) }
 *
 * // When agent responds with scheduling request:
 * if (intentParser.isSchedulingRequest(message)) {
 *     schedulerViewModel.scheduleTask(message)
 * }
 *
 * // Show UI
 * AgentSchedulingIntegration(schedulerViewModel)
 * ```
 */
@Composable
fun AgentSchedulingIntegration(
    schedulerViewModel: IntelligentSchedulerViewModel,
    onTaskScheduled: (jobId: String) -> Unit = {}
) {
    val schedulingState by schedulerViewModel.schedulingState.collectAsState()

    // Show result dialog if needed
    when (val state = schedulingState) {
        is ScheduleTState.Success -> {
            SchedulingResultDialog(
                state = state,
                onDismiss = {
                    onTaskScheduled(state.jobId)
                    schedulerViewModel.reset()
                },
                onClarificationSubmit = { answers ->
                    schedulerViewModel.respondToClarification(answers)
                }
            )
        }

        is ScheduleTState.NeedsClarification,
        is ScheduleTState.Error -> {
            SchedulingResultDialog(
                state = state,
                onDismiss = {
                    schedulerViewModel.reset()
                },
                onClarificationSubmit = { answers ->
                    schedulerViewModel.respondToClarification(answers)
                }
            )
        }

        else -> {}
    }

    // Show status indicator
    if (schedulingState is ScheduleTState.Processing) {
        SchedulingStatusIndicator(
            state = schedulingState,
            modifier = Modifier.padding(8.dp)
        )
    }
}

/**
 * This allows agents in group chat to:
 *
 * 1. Understand user intent for task scheduling
 * 2. Ask clarifying questions if needed
 * 3. Create cron jobs from natural language
 * 4. Deliver results to configured channels
 *
 * Example agent flow:
 * User: "Check CPU every 5 minutes and send to Telegram"
 * Agent: "I'll set that up for you!"
 * System: Creates cron job, delivers via Telegram
 * Agent: "Done! Job ID: <id>"
 *
 * Example with clarification:
 * User: "Send me a daily report"
 * Agent: "I can do that! What time would you prefer?"
 * User: "9 AM"
 * System: Creates cron job for 9 AM delivery
 * Agent: "Perfect! I'll send it every day at 9 AM"
 */
