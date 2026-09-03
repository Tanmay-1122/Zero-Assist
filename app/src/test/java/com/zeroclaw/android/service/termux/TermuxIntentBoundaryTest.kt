/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.termux

import android.content.Intent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

@DisplayName("TermuxIntentBoundary")
class TermuxIntentBoundaryTest {
    private val boundary = TermuxIntentBoundary()

    @Test
    fun `bootstrap intent targets Termux without command extras`() {
        val spec = boundary.bootstrapIntent()

        assertEquals(Intent.ACTION_MAIN, spec.action)
        assertEquals(TermuxRuntimeContract.TERMUX_PACKAGE_NAME, spec.packageName)
        assertEquals(null, spec.className)
        assertEquals(emptyMap<String, TermuxIntentExtra>(), spec.extras)
    }

    @Test
    fun `run command intent spec contains public Termux contract values without executing`() {
        val spec =
            boundary.runCommandIntent(
                TermuxCommandIntentRequest(
                    commandPath = "/data/data/com.termux/files/usr/bin/python",
                    arguments = listOf("-m", "zero_assist.health"),
                    workingDirectory = "/data/data/com.termux/files/home",
                    background = true,
                    sessionAction = "0",
                    commandLabel = "Zero-Assist health probe",
                ),
            )

        assertEquals(TermuxRuntimeContract.RUN_COMMAND_ACTION, spec.action)
        assertEquals(TermuxRuntimeContract.TERMUX_PACKAGE_NAME, spec.packageName)
        assertEquals(TermuxRuntimeContract.RUN_COMMAND_SERVICE_CLASS_NAME, spec.className)
        assertEquals(
            TermuxIntentExtra.Text("/data/data/com.termux/files/usr/bin/python"),
            spec.extras[TermuxRuntimeContract.EXTRA_COMMAND_PATH],
        )
        assertEquals(
            TermuxIntentExtra.TextArray(arrayOf("-m", "zero_assist.health")),
            spec.extras[TermuxRuntimeContract.EXTRA_ARGUMENTS],
        )
        assertEquals(
            TermuxIntentExtra.Text("/data/data/com.termux/files/home"),
            spec.extras[TermuxRuntimeContract.EXTRA_WORKDIR],
        )
        assertEquals(
            TermuxIntentExtra.Flag(true),
            spec.extras[TermuxRuntimeContract.EXTRA_BACKGROUND],
        )
        assertEquals(
            TermuxIntentExtra.Text("Zero-Assist health probe"),
            spec.extras[TermuxRuntimeContract.EXTRA_COMMAND_LABEL],
        )
    }

    @Test
    fun `run command intent rejects blank command path`() {
        assertThrows(IllegalArgumentException::class.java) {
            boundary.runCommandIntent(TermuxCommandIntentRequest(commandPath = " "))
        }
    }
}

