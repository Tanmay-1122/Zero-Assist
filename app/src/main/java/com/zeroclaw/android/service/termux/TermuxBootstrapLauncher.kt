/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.termux

import android.content.ComponentName
import android.content.Context

enum class TermuxBootstrapLaunchStatus {
    STARTED,
    FAILED,
}

data class TermuxBootstrapLaunchResult(
    val status: TermuxBootstrapLaunchStatus,
    val reason: String,
    val intentSpec: TermuxIntentSpec,
)

interface TermuxRunCommandServiceStarter {
    fun start(
        context: Context,
        intentSpec: TermuxIntentSpec,
    ): ComponentName?
}

class AndroidTermuxRunCommandServiceStarter : TermuxRunCommandServiceStarter {
    override fun start(
        context: Context,
        intentSpec: TermuxIntentSpec,
    ): ComponentName? = context.startService(intentSpec.toAndroidIntent())
}

interface TermuxBootstrapLauncher {
    fun launchRunCommandIntent(intentSpec: TermuxIntentSpec): TermuxBootstrapLaunchResult
}

class AndroidTermuxBootstrapLauncher(
    private val context: Context,
    private val serviceStarter: TermuxRunCommandServiceStarter = AndroidTermuxRunCommandServiceStarter(),
) : TermuxBootstrapLauncher {
    override fun launchRunCommandIntent(intentSpec: TermuxIntentSpec): TermuxBootstrapLaunchResult {
        if (intentSpec.action != TermuxRuntimeContract.RUN_COMMAND_ACTION) {
            return TermuxBootstrapLaunchResult(
                status = TermuxBootstrapLaunchStatus.FAILED,
                reason = "Only prebuilt Termux RUN_COMMAND intents can be launched.",
                intentSpec = intentSpec,
            )
        }
        if (intentSpec.packageName != TermuxRuntimeContract.TERMUX_PACKAGE_NAME) {
            return TermuxBootstrapLaunchResult(
                status = TermuxBootstrapLaunchStatus.FAILED,
                reason = "Termux RUN_COMMAND intent must target the Termux package.",
                intentSpec = intentSpec,
            )
        }
        if (intentSpec.className != TermuxRuntimeContract.RUN_COMMAND_SERVICE_CLASS_NAME) {
            return TermuxBootstrapLaunchResult(
                status = TermuxBootstrapLaunchStatus.FAILED,
                reason = "Termux RUN_COMMAND intent must target the public RunCommandService.",
                intentSpec = intentSpec,
            )
        }

        return try {
            serviceStarter.start(context, intentSpec)
            TermuxBootstrapLaunchResult(
                status = TermuxBootstrapLaunchStatus.STARTED,
                reason = "Termux RUN_COMMAND service start requested.",
                intentSpec = intentSpec,
            )
        } catch (e: RuntimeException) {
            TermuxBootstrapLaunchResult(
                status = TermuxBootstrapLaunchStatus.FAILED,
                reason = "Failed to start Termux RUN_COMMAND service: ${e.message ?: e::class.java.simpleName}.",
                intentSpec = intentSpec,
            )
        }
    }
}
