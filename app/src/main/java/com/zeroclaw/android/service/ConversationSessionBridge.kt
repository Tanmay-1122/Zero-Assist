/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import com.zeroclaw.ffi.FfiSessionListener
import com.zeroclaw.ffi.SessionMessage
import com.zeroclaw.ffi.evalRepl
import com.zeroclaw.ffi.getVersion
import com.zeroclaw.ffi.reloadDaemonConfig
import com.zeroclaw.ffi.sessionCancel
import com.zeroclaw.ffi.sessionClear
import com.zeroclaw.ffi.sessionDestroy
import com.zeroclaw.ffi.sessionHistory
import com.zeroclaw.ffi.sessionSeed
import com.zeroclaw.ffi.sessionSend
import com.zeroclaw.ffi.sessionStart
import com.zeroclaw.ffi.sessionStartCustom

/**
 * App-owned bridge over generated session, REPL, version, and delegation FFI.
 *
 * ViewModels use this contract instead of importing generated `com.zeroclaw.ffi`
 * classes directly, keeping upstream binding churn inside the service layer.
 */
open class ConversationSessionBridge {
    private var sessionActive = false

    open fun isSessionActive(): Boolean = sessionActive

    open fun startSession(configToml: String? = null) {
        if (configToml != null) {
            reloadDaemonConfig(configToml)
        }
        sessionStart()
        sessionActive = true
    }

    open fun startCustomSession(
        providerName: String,
        model: String,
        apiKey: String?,
        baseUrl: String?,
        temperature: Double?,
        thinkingLevel: String?,
        systemPrompt: String,
    ) {
        sessionStartCustom(
            providerName = providerName,
            model = model,
            apiKey = apiKey,
            baseUrl = baseUrl,
            temperature = temperature,
            thinkingLevel = thinkingLevel,
            systemPrompt = systemPrompt,
        )
        sessionActive = true
    }

    open fun seed(messages: List<ConversationSeedMessage>) {
        sessionSeed(messages.map { it.toFfi() })
    }

    open fun send(
        message: String,
        imageData: List<String>,
        mimeTypes: List<String>,
        listener: ConversationSessionListener,
    ) {
        sessionSend(
            message = message,
            imageData = imageData,
            mimeTypes = mimeTypes,
            listener = listener.toFfi(),
        )
    }

    open fun cancel() {
        sessionCancel()
        sessionActive = false
    }

    open fun clear() {
        sessionClear()
        sessionActive = false
    }

    open fun history(): List<ConversationSeedMessage> =
        sessionHistory().map { message ->
            ConversationSeedMessage(
                role = message.role,
                content = message.content,
            )
        }

    open fun destroy() {
        sessionDestroy()
        sessionActive = false
    }

    open fun evalReplExpression(expression: String): String = evalRepl(expression)

    open fun version(): String = getVersion()

    open fun autoClassifyAndMatchAgent(
        message: String,
        agentsJson: String,
    ): String = com.zeroclaw.ffi.autoClassifyAndMatchAgent(message, agentsJson)
}

data class ConversationSeedMessage(
    val role: String,
    val content: String,
) {
    internal fun toFfi(): SessionMessage =
        SessionMessage(
            role = role,
            content = content,
        )
}

interface ConversationSessionListener {
    fun onThinking(text: String) = Unit

    fun onResponseChunk(text: String) = Unit

    fun onToolStart(
        name: String,
        argumentsHint: String,
    ) = Unit

    fun onToolResult(
        name: String,
        success: Boolean,
        durationSecs: ULong,
    ) = Unit

    fun onToolOutput(
        name: String,
        output: String,
    ) = Unit

    fun onProgress(message: String) = Unit

    fun onCompaction(summary: String) = Unit

    fun onComplete(fullResponse: String) = Unit

    fun onError(error: String) = Unit

    fun onCancelled() = Unit
}

private fun ConversationSessionListener.toFfi(): FfiSessionListener =
    object : FfiSessionListener {
        override fun onThinking(text: String) {
            this@toFfi.onThinking(text)
        }

        override fun onResponseChunk(text: String) {
            this@toFfi.onResponseChunk(text)
        }

        override fun onToolStart(
            name: String,
            argumentsHint: String,
        ) {
            this@toFfi.onToolStart(name, argumentsHint)
        }

        override fun onToolResult(
            name: String,
            success: Boolean,
            durationSecs: ULong,
        ) {
            this@toFfi.onToolResult(name, success, durationSecs)
        }

        override fun onToolOutput(
            name: String,
            output: String,
        ) {
            this@toFfi.onToolOutput(name, output)
        }

        override fun onProgress(message: String) {
            this@toFfi.onProgress(message)
        }

        override fun onCompaction(summary: String) {
            this@toFfi.onCompaction(summary)
        }

        override fun onComplete(fullResponse: String) {
            this@toFfi.onComplete(fullResponse)
        }

        override fun onError(error: String) {
            this@toFfi.onError(error)
        }

        override fun onCancelled() {
            this@toFfi.onCancelled()
        }
    }
