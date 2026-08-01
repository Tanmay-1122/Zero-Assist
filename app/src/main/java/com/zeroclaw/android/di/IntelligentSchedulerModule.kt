/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.di

import com.zeroclaw.android.service.ChannelDetector
import com.zeroclaw.android.service.cron.CronBridge
import com.zeroclaw.android.service.cron.CronBridgeImpl
import com.zeroclaw.android.service.cron.CronIntentParser
import com.zeroclaw.android.service.cron.CronTranslator
import com.zeroclaw.android.service.cron.IntelligentTaskScheduler
import com.zeroclaw.android.data.repository.ChannelConfigRepository

/**
 * Factory methods for intelligent task scheduler components.
 *
 * Called directly from [ZeroClawApplication] during manual DI setup.
 * Hilt annotations removed — the app uses manual DI, not Hilt.
 */
object IntelligentSchedulerModule {

    fun provideCronIntentParser(): CronIntentParser = CronIntentParser()

    fun provideCronTranslator(): CronTranslator = CronTranslator()

    fun provideChannelDetector(
        channelConfigRepository: ChannelConfigRepository
    ): ChannelDetector = ChannelDetector(channelConfigRepository)

    fun provideCronBridge(): CronBridge = CronBridgeImpl()

    fun provideIntelligentTaskScheduler(
        intentParser: CronIntentParser,
        cronTranslator: CronTranslator,
        channelDetector: ChannelDetector,
        cronBridge: CronBridge
    ): IntelligentTaskScheduler = IntelligentTaskScheduler(
        intentParser,
        cronTranslator,
        channelDetector,
        cronBridge
    )
}
