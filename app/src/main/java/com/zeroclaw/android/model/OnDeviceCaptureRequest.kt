package com.zeroclaw.android.model

/**
 * One-shot UI request to open camera or screen capture flows.
 */
data class OnDeviceCaptureRequest(
    val prompt: String = "",
    val requestId: Long = System.currentTimeMillis(),
)
