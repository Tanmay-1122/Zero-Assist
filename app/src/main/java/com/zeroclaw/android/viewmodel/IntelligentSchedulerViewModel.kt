/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.viewmodel

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.zeroclaw.android.service.ChannelDetector
import com.zeroclaw.android.model.CronJobIntent
import com.zeroclaw.android.data.repository.SettingsRepository
import com.zeroclaw.android.service.cron.CronBridge
import com.zeroclaw.android.service.cron.CronIntentParser
import com.zeroclaw.android.service.cron.CronTranslator
import com.zeroclaw.android.service.cron.IntelligentTaskScheduler
import com.zeroclaw.android.service.cron.ScheduleResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for intelligent task scheduling from natural language.
 *
 * Manages the conversation flow:
 * 1. User inputs natural language task
 * 2. System parses intent
 * 3. If clarification needed, presents questions
 * 4. Once clear, creates cron job
 * 5. Confirms result to user
 */
class IntelligentSchedulerViewModel(
    private val intentParser: CronIntentParser,
    private val cronTranslator: CronTranslator,
    private val channelDetector: ChannelDetector,
    private val cronBridge: CronBridge,
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    companion object {
        private const val TAG = "IntelligentScheduler"
    }

    private val scheduler = IntelligentTaskScheduler(
        intentParser,
        cronTranslator,
        channelDetector,
        cronBridge
    )

    // Current scheduling state
    private val _schedulingState = MutableStateFlow<ScheduleTState>(ScheduleTState.Idle)
    val schedulingState: StateFlow<ScheduleTState> = _schedulingState.asStateFlow()

    // Current intent (for reference during clarification)
    private val _currentIntent = MutableStateFlow<CronJobIntent?>(null)
    val currentIntent: StateFlow<CronJobIntent?> = _currentIntent.asStateFlow()

    /**
     * Process natural language task request.
     */
    fun scheduleTask(userMessage: String) {
        Log.d(TAG, "Scheduling task: $userMessage")
        _schedulingState.value = ScheduleTState.Processing

        viewModelScope.launch {
            try {
                val result = scheduler.scheduleFromNaturalLanguage(userMessage)
                handleScheduleResult(result)
            } catch (e: Exception) {
                Log.e(TAG, "Error scheduling task", e)
                _schedulingState.value = ScheduleTState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Process user's clarification responses.
     */
    fun respondToClarification(answers: Map<String, String>) {
        Log.d(TAG, "User answering clarifications: $answers")
        _schedulingState.value = ScheduleTState.Processing

        val intent = _currentIntent.value ?: return

        viewModelScope.launch {
            try {
                val result = scheduler.scheduleWithClarifications(intent, answers)
                handleScheduleResult(result)
            } catch (e: Exception) {
                Log.e(TAG, "Error processing clarifications", e)
                _schedulingState.value = ScheduleTState.Error(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * Handle scheduling result and update UI state.
     */
    private fun handleScheduleResult(result: ScheduleResult) {
        Log.d(TAG, "Schedule result: $result")

        _schedulingState.value = when (result) {
            is ScheduleResult.Success -> {
                ScheduleTState.Success(
                    jobId = result.jobId,
                    message = "Task scheduled successfully!\n\n" +
                            "Task: ${result.task}\n" +
                            "Schedule: ${result.schedule}\n" +
                            "Delivery: ${result.deliveryChannels.joinToString(", ")}"
                )
            }

            is ScheduleResult.NeedsClarification -> {
                _currentIntent.value = result.intent
                ScheduleTState.NeedsClarification(
                    intent = result.intent,
                    questions = result.questions
                )
            }

            is ScheduleResult.ParseError -> {
                ScheduleTState.Error("Couldn't understand your request. ${result.message}")
            }

            is ScheduleResult.NoChannelsFound -> {
                ScheduleTState.Error(
                    "No configured channel found for: ${result.requestedChannel}\n" +
                    "Please configure ${result.requestedChannel} in settings first."
                )
            }

            is ScheduleResult.CreationError -> {
                ScheduleTState.Error("Failed to create task: ${result.message}")
            }

            ScheduleResult.NotSchedulingRequest -> {
                ScheduleTState.NotScheduling
            }
        }
    }

    /**
     * Reset to idle state.
     */
    fun reset() {
        _schedulingState.value = ScheduleTState.Idle
        _currentIntent.value = null
    }
}

/**
 * State of the intelligent scheduling process.
 */
sealed class ScheduleTState {
    object Idle : ScheduleTState()
    object Processing : ScheduleTState()
    object NotScheduling : ScheduleTState()

    data class Success(
        val jobId: String,
        val message: String
    ) : ScheduleTState()

    data class NeedsClarification(
        val intent: CronJobIntent,
        val questions: List<String>
    ) : ScheduleTState()

    data class Error(val message: String) : ScheduleTState()
}
