/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.android.data.remote.SkillsMarketplaceClient
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Downloads a marketplace skill and installs it into the active ZeroClaw workspace.
 */
class SkillsMarketplaceInstaller(
    private val marketplaceClient: SkillsMarketplaceClient,
    private val skillsBridge: SkillsBridge,
    private val cacheRoot: File,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun install(skillName: String) =
        withContext(ioDispatcher) {
            cacheRoot.mkdirs()
            val sessionDir = cacheRoot.resolve("skill-download-$skillName-${System.currentTimeMillis()}")
            val skillDir = sessionDir.resolve(skillName)

            try {
                marketplaceClient.downloadSkill(skillName, skillDir)
                skillsBridge.installSkill(skillDir.absolutePath)
            } finally {
                sessionDir.deleteRecursively()
            }
        }
}
