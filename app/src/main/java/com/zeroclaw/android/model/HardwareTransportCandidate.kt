/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.model

import org.json.JSONObject

/**
 * A hardware endpoint discovered from Android transport APIs.
 */
data class HardwareTransportCandidate(
    val id: String,
    val displayName: String,
    val transportType: String,
    val address: String,
    val suggestedDeviceType: String,
    val isPermissionGranted: Boolean,
    val metadata: Map<String, String> = emptyMap(),
) {
    fun toConfigJson(): String = JSONObject().apply {
        put("transportType", transportType)
        put("address", address)
        put("isPermissionGranted", isPermissionGranted)
        put("metadata", JSONObject(metadata))
    }.toString()
}
