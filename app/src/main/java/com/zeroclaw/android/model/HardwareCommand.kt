/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.model

import org.json.JSONObject
import java.util.concurrent.atomic.AtomicLong

/**
 * ZeroClaw hardware command using the newline-delimited JSON firmware protocol.
 *
 * Commands include both the canonical firmware envelope (`id`/`args`) and the
 * Android HTTP-compatible `params` field so old and new transports can share the
 * same command path during protocol migration.
 */
data class HardwareCommand(
    val commandName: String,
    val params: JSONObject = JSONObject(),
    val id: String = nextCommandId(),
) {
    fun toJsonLine(): String {
        val args = params.deepCopy()
        return JSONObject()
            .put("id", id)
            .put("cmd", commandName)
            .put("args", args)
            .put("params", args.deepCopy())
            .toString() + "\n"
    }

    companion object {
        private val sequence = AtomicLong()

        private fun nextCommandId(): String =
            "a-${java.lang.Long.toString(sequence.incrementAndGet(), Character.MAX_RADIX)}"
    }
}

/**
 * Result returned by a hardware transport after command execution.
 */
data class HardwareCommandResult(
    val ok: Boolean,
    val transportType: String,
    val dataJson: String? = null,
    val error: String? = null,
    val id: String? = null,
) {
    fun toResultText(): String {
        return if (ok) {
            dataJson ?: "Hardware command completed"
        } else {
            error ?: "Hardware command failed"
        }
    }
}

private fun JSONObject.deepCopy(): JSONObject = JSONObject(toString())
