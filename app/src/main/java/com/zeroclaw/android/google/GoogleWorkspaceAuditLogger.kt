/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.google

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Audit logger for Google Workspace tool executions.
 * Records all gws CLI calls with timestamps, parameters, and results.
 */
object GoogleWorkspaceAuditLogger {

    private const val TAG = "GwsAudit"

    /**
     * Logs a Google Workspace tool execution.
     */
    fun logExecution(
        service: String,
        resource: String,
        method: String,
        subResource: String? = null,
        success: Boolean,
        durationMs: Long,
        error: String? = null,
    ) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())
        val opPath = if (subResource != null) {
            "$service/$resource/$subResource/$method"
        } else {
            "$service/$resource/$method"
        }

        val status = if (success) "OK" else "FAILED"
        val detail = if (error != null) " error=$error" else ""

        Log.i(TAG, "[$timestamp] $opPath $status ${durationMs}ms$detail")
    }

    /**
     * Logs a credential export event.
     */
    fun logCredentialExport(accountEmail: String, success: Boolean) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())
        Log.i(TAG, "[$timestamp] CREDENTIAL_EXPORT account=$accountEmail success=$success")
    }

    /**
     * Logs a sign-in event.
     */
    fun logSignIn(accountEmail: String, success: Boolean) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(Date())
        Log.i(TAG, "[$timestamp] SIGN_IN account=$accountEmail success=$success")
    }
}
