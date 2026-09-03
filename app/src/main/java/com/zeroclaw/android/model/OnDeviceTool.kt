package com.zeroclaw.android.model

/**
 * Local-first ML/vision tools exposed in the current UI.
 */
enum class OnDeviceTool(
    val label: String,
    val requiresText: Boolean = false,
    val requiresImage: Boolean = false,
) {
    INFER(label = "Infer", requiresText = true),
    SUMMARIZE(label = "Summarize", requiresText = true),
    PROOFREAD(label = "Proofread", requiresText = true),
    REWRITE(label = "Rewrite", requiresText = true),
    DESCRIBE_IMAGE(label = "Describe", requiresImage = true),
    CAMERA_CAPTURE(label = "Camera"),
    SCREEN_CAPTURE(label = "Screen"),
}
