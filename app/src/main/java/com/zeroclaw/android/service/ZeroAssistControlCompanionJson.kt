/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.service

import org.json.JSONArray
import org.json.JSONObject

/** JSON contract exposed through ADB content queries. */
object ZeroAssistControlCompanionJson {
    const val VERSION = "zero-assist-internal-1"

    fun version(): String = success(VERSION)

    fun authTokenUnavailable(): String =
        JSONObject()
            .put("status", "success")
            .put("result", JSONObject.NULL)
            .toString()

    fun packages(packages: List<ZeroAssistPackageInfo>): String =
        success(
            JSONArray().apply {
                packages.forEach { app ->
                    put(
                        JSONObject()
                            .put("packageName", app.packageName)
                            .put("label", app.label)
                            .put("isSystemApp", app.isSystemApp),
                    )
                }
            },
        )

    fun error(message: String): String =
        JSONObject()
            .put("status", "error")
            .put("message", message)
            .toString()

    private fun success(result: Any?): String =
        JSONObject()
            .put("status", "success")
            .put("result", result)
            .toString()
}

data class ZeroAssistPackageInfo(
    val packageName: String,
    val label: String,
    val isSystemApp: Boolean,
)
