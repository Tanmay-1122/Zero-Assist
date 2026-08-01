/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.terminal

import android.util.Log
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
 * Smart message router for terminal input.
 *
 * Analyzes user messages and routes them to either:
 * - IntelligentTaskScheduler (if message suggests task scheduling)
 * - Regular agent/LLM chat (if regular message)
 */
class TerminalMessageRouter(
    private val intentParser: CronIntentParser,
    private val schedulerViewModel: IntelligentSchedulerViewModel
) {
    companion object {
        private const val TAG = "TerminalMessageRouter"
    }

    /**
     * Route message to appropriate handler.
     *
     * @return true if handling as scheduling request, false if should handle as regular chat
     */
    fun routeMessage(message: String): Boolean {
        val isScheduling = intentParser.isSchedulingRequest(message)
        Log.d(TAG, "Message '$message' -> isScheduling=$isScheduling")

        if (isScheduling) {
            schedulerViewModel.scheduleTask(message)
        }

        return isScheduling
    }
}

/**
 * Composable integration for intelligent scheduling in terminal.
 *
 * Shows scheduling result dialogs and status indicators.
 */
@Composable
fun TerminalSchedulingIntegration(
    schedulerViewModel: IntelligentSchedulerViewModel,
    onMessageRouted: (isScheduling: Boolean) -> Unit = {}
) {
    val schedulingState by schedulerViewModel.schedulingState.collectAsState()

    // Show result dialog if needed
    when (schedulingState) {
        is ScheduleTState.Success,
        is ScheduleTState.NeedsClarification,
        is ScheduleTState.Error -> {
            SchedulingResultDialog(
                state = schedulingState,
                onDismiss = {
                    schedulerViewModel.reset()
                    onMessageRouted(false)
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
 * Integration point for inserting scheduling support into TerminalScreen.
 *
 * Add this to the terminal UI where messages are sent:
 *
 * ```kotlin
 * val router = TerminalMessageRouter(intentParser, schedulerViewModel)
 * val isScheduling = router.routeMessage(userMessage)
 *
 * if (!isScheduling) {
 *     // Handle as regular chat/agent message
 *     viewModel.submitInput(userMessage)
 * }
 *
 * // Show scheduling UI
 * TerminalSchedulingIntegration(schedulerViewModel)
 * ```
 */
