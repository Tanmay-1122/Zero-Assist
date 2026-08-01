/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service.hardware

import com.zeroclaw.android.model.HardwareCommandResult
import org.json.JSONObject

/**
 * Parses ZeroClaw hardware command responses into a stable Android result model.
 */
object HardwareCommandResponseParser {
    fun parse(
        transportType: String,
        rawResponse: String,
    ): HardwareCommandResult {
        if (rawResponse.isBlank()) {
            return failed(transportType, "No response from hardware")
        }

        val json = runCatching { JSONObject(rawResponse.trim()) }.getOrNull()
            ?: return failed(transportType, "Invalid hardware response: $rawResponse")
        val ok = json.optBoolean("ok", false)
        val data = responsePayload(json)
        val error = responseError(json)

        return HardwareCommandResult(
            ok = ok,
            transportType = transportType,
            dataJson = data,
            error = error,
            id = responseId(json),
        )
    }

    private fun responseId(json: JSONObject): String? {
        val id = json.opt("id")
            ?.takeUnless { it == JSONObject.NULL }
            ?: return null
        return id.toString().takeIf { it.isNotBlank() }
    }

    private fun responsePayload(json: JSONObject): String? {
        val data = json.opt("data")
            ?.takeUnless { it == JSONObject.NULL }
        if (data != null) {
            return data.toString()
        }

        val result = json.opt("result")
            ?.takeUnless { it == JSONObject.NULL }
        return result?.toString()
    }

    private fun responseError(json: JSONObject): String? {
        val error = json.opt("error")
            ?.takeUnless { it == JSONObject.NULL }
            ?: json.opt("message")
                ?.takeUnless { it == JSONObject.NULL }
            ?: return null

        return when (error) {
            is JSONObject -> error.optString("message").ifBlank { error.toString() }
            else -> error.toString().takeIf { it.isNotBlank() }
        }
    }

    private fun failed(transportType: String, error: String): HardwareCommandResult {
        return HardwareCommandResult(
            ok = false,
            transportType = transportType,
            error = error,
        )
    }
}
