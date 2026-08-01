/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.termux

import android.content.Intent

data class TermuxIntentSpec(
    val action: String,
    val packageName: String,
    val className: String? = null,
    val extras: Map<String, TermuxIntentExtra> = emptyMap(),
)

sealed interface TermuxIntentExtra {
    data class Text(val value: String) : TermuxIntentExtra

    data class TextArray(val value: Array<String>) : TermuxIntentExtra {
        override fun equals(other: Any?): Boolean =
            this === other || other is TextArray && value.contentEquals(other.value)

        override fun hashCode(): Int = value.contentHashCode()
    }

    data class Flag(val value: Boolean) : TermuxIntentExtra
}

data class TermuxCommandIntentRequest(
    val commandPath: String,
    val arguments: List<String> = emptyList(),
    val workingDirectory: String? = null,
    val background: Boolean = true,
    val sessionAction: String? = null,
    val commandLabel: String? = null,
)

class TermuxIntentBoundary {
    fun bootstrapIntent(): TermuxIntentSpec =
        TermuxIntentSpec(
            action = Intent.ACTION_MAIN,
            packageName = TermuxRuntimeContract.TERMUX_PACKAGE_NAME,
        )

    fun runCommandIntent(request: TermuxCommandIntentRequest): TermuxIntentSpec {
        require(request.commandPath.isNotBlank()) {
            "Termux command path must be provided before building a RUN_COMMAND intent."
        }

        val extras =
            buildMap {
                put(
                    TermuxRuntimeContract.EXTRA_COMMAND_PATH,
                    TermuxIntentExtra.Text(request.commandPath),
                )
                if (request.arguments.isNotEmpty()) {
                    put(
                        TermuxRuntimeContract.EXTRA_ARGUMENTS,
                        TermuxIntentExtra.TextArray(request.arguments.toTypedArray()),
                    )
                }
                request.workingDirectory?.takeIf { it.isNotBlank() }?.let {
                    put(TermuxRuntimeContract.EXTRA_WORKDIR, TermuxIntentExtra.Text(it))
                }
                put(TermuxRuntimeContract.EXTRA_BACKGROUND, TermuxIntentExtra.Flag(request.background))
                request.sessionAction?.takeIf { it.isNotBlank() }?.let {
                    put(TermuxRuntimeContract.EXTRA_SESSION_ACTION, TermuxIntentExtra.Text(it))
                }
                request.commandLabel?.takeIf { it.isNotBlank() }?.let {
                    put(TermuxRuntimeContract.EXTRA_COMMAND_LABEL, TermuxIntentExtra.Text(it))
                }
            }

        return TermuxIntentSpec(
            action = TermuxRuntimeContract.RUN_COMMAND_ACTION,
            packageName = TermuxRuntimeContract.TERMUX_PACKAGE_NAME,
            className = TermuxRuntimeContract.RUN_COMMAND_SERVICE_CLASS_NAME,
            extras = extras,
        )
    }
}

fun TermuxIntentSpec.toAndroidIntent(): Intent =
    Intent(action).also { intent ->
        intent.setPackage(packageName)
        className?.let { intent.setClassName(packageName, it) }
        extras.forEach { (key, value) ->
            when (value) {
                is TermuxIntentExtra.Text -> intent.putExtra(key, value.value)
                is TermuxIntentExtra.TextArray -> intent.putExtra(key, value.value)
                is TermuxIntentExtra.Flag -> intent.putExtra(key, value.value)
            }
        }
    }
