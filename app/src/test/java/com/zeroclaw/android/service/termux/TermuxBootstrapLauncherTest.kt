/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.termux

import android.content.ComponentName
import android.content.Context
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("AndroidTermuxBootstrapLauncher")
class TermuxBootstrapLauncherTest {
    @Test
    fun `launches already-built run command intent through starter`() {
        val starter = RecordingStarter()
        val launcher =
            AndroidTermuxBootstrapLauncher(
                context = mockk<Context>(relaxed = true),
                serviceStarter = starter,
            )
        val spec =
            TermuxIntentBoundary().runCommandIntent(
                TermuxCommandIntentRequest(
                    commandPath = "/data/data/com.termux/files/usr/bin/python",
                    arguments = listOf("-m", "zero_assist.bridge"),
                    workingDirectory = "/data/data/com.termux/files/home",
                    commandLabel = "Zero-Assist Termux bridge",
                ),
            )

        val result = launcher.launchRunCommandIntent(spec)

        assertEquals(TermuxBootstrapLaunchStatus.STARTED, result.status)
        assertNotNull(starter.startedSpec)
        assertEquals(TermuxRuntimeContract.RUN_COMMAND_ACTION, starter.startedSpec?.action)
        assertEquals(TermuxRuntimeContract.TERMUX_PACKAGE_NAME, starter.startedSpec?.packageName)
        assertEquals(TermuxRuntimeContract.RUN_COMMAND_SERVICE_CLASS_NAME, starter.startedSpec?.className)
        assertEquals(
            "/data/data/com.termux/files/usr/bin/python",
            (starter.startedSpec?.extras?.get(TermuxRuntimeContract.EXTRA_COMMAND_PATH) as? TermuxIntentExtra.Text)
                ?.value,
        )
        assertEquals(
            arrayOf("-m", "zero_assist.bridge").toList(),
            (starter.startedSpec?.extras?.get(TermuxRuntimeContract.EXTRA_ARGUMENTS) as? TermuxIntentExtra.TextArray)
                ?.value
                ?.toList(),
        )
    }

    @Test
    fun `rejects non run command intent before starting service`() {
        val starter = RecordingStarter()
        val launcher =
            AndroidTermuxBootstrapLauncher(
                context = mockk<Context>(relaxed = true),
                serviceStarter = starter,
            )

        val result = launcher.launchRunCommandIntent(TermuxIntentBoundary().bootstrapIntent())

        assertEquals(TermuxBootstrapLaunchStatus.FAILED, result.status)
        assertEquals("Only prebuilt Termux RUN_COMMAND intents can be launched.", result.reason)
        assertEquals(null, starter.startedSpec)
    }

    private class RecordingStarter : TermuxRunCommandServiceStarter {
        var startedSpec: TermuxIntentSpec? = null
            private set

        override fun start(
            context: Context,
            intentSpec: TermuxIntentSpec,
        ): ComponentName? {
            startedSpec = intentSpec
            return ComponentName(intentSpec.packageName, intentSpec.className.orEmpty())
        }
    }
}
