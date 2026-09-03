/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.di

import com.zeroclaw.android.data.local.dao.GreetingHistoryDao
import com.zeroclaw.android.data.repository.GreetingHistoryRepository
import com.zeroclaw.android.ui.screen.dashboard.AiGreetingGenerator
import com.zeroclaw.android.ui.screen.dashboard.GreetingGeneratorFactory
import com.zeroclaw.android.ui.screen.dashboard.LocalGreetingGenerator
import android.content.Context

/**
 * Factory methods for dashboard greeting components.
 *
 * Called directly from [com.zeroclaw.android.ZeroClawApplication] during manual DI setup.
 * Hilt annotations removed — the app uses manual DI, not Hilt.
 */
object GreetingModule {

    fun provideGreetingHistoryDao(
        dao: GreetingHistoryDao,
    ): GreetingHistoryDao = dao

    fun provideGreetingHistoryRepository(
        dao: GreetingHistoryDao,
    ): GreetingHistoryRepository = GreetingHistoryRepository(dao)

    fun provideLocalGreetingGenerator(): LocalGreetingGenerator = LocalGreetingGenerator()

    fun provideAiGreetingGenerator(
        context: Context,
        localGenerator: LocalGreetingGenerator,
        historyRepository: GreetingHistoryRepository,
    ): AiGreetingGenerator = AiGreetingGenerator(context, localGenerator, historyRepository)

    fun provideGreetingGeneratorFactory(
        aiGenerator: AiGreetingGenerator,
        localGenerator: LocalGreetingGenerator,
    ): GreetingGeneratorFactory = GreetingGeneratorFactory(aiGenerator, localGenerator)
}
