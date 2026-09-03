/*
 * Copyright (c) 2026 @Natfii. All rights reserved.
 */

package com.zeroclaw.android.service

/**
 * Curated catalog of LiteRT LM models available for on-device inference.
 *
 * Each entry mirrors the upstream Kai catalog but is namespaced under the
 * Zero-Assist package. Models are downloaded directly from HuggingFace to
 * the app's private files directory.
 *
 * **Update policy**: When new model quantisations are published by the
 * `litert-community` HuggingFace org, add entries here and bump the
 * `litert-lm` version in `libs.versions.toml` if needed.
 */
val ZERO_ASSIST_MODEL_CATALOG: List<LocalModel> = listOf(
    LocalModel(
        id = "gemma-4-e2b-it",
        displayName = "Gemma 4 E2B IT",
        fileName = "gemma-4-E2B-it.litertlm",
        sizeBytes = 2_580_000_000L,
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm",
        gpuMemoryMb = 676,
        defaultContextTokens = 4_096,
        maxContextTokens = 32_768,
        kvPerTokenBytes = 50_000,
        isRecommended = true,
    ),
    LocalModel(
        id = "gemma-4-e4b-it",
        displayName = "Gemma 4 E4B IT",
        fileName = "gemma-4-E4B-it.litertlm",
        sizeBytes = 3_650_000_000L,
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-E4B-it-litert-lm/resolve/main/gemma-4-E4B-it.litertlm",
        gpuMemoryMb = 710,
        defaultContextTokens = 4_096,
        maxContextTokens = 32_768,
        kvPerTokenBytes = 75_000,
    ),
    LocalModel(
        id = "gemma-4-12b-it",
        displayName = "Gemma 4 12B IT",
        fileName = "gemma-4-12B-it.litertlm",
        sizeBytes = 6_547_589_312L,
        downloadUrl = "https://huggingface.co/litert-community/gemma-4-12B-it-litert-lm/resolve/main/gemma-4-12B-it.litertlm",
        gpuMemoryMb = 4_000,
        defaultContextTokens = 8_192,
        maxContextTokens = 32_768,
        kvPerTokenBytes = 140_000,
    ),
    LocalModel(
        id = "qwen3-0.6b",
        displayName = "Qwen3 0.6B",
        fileName = "Qwen3-0.6B.litertlm",
        sizeBytes = 614_236_160L,
        downloadUrl = "https://huggingface.co/litert-community/Qwen3-0.6B/resolve/main/Qwen3-0.6B.litertlm",
        gpuMemoryMb = 300,
        defaultContextTokens = 4_096,
        maxContextTokens = 32_768,
        kvPerTokenBytes = 35_000,
    ),
)

// ---------------------------------------------------------------------------
// String sanitisation helpers
// ---------------------------------------------------------------------------

private val THINK_BLOCK_REGEX = Regex("(?s)<think>.*?</think>")

/**
 * Strips `<think>…</think>` reasoning blocks emitted by Qwen3's chat template
 * before presenting the text to the user. Safe for Gemma 4, which never emits
 * these tags.
 */
fun stripThinkBlocks(s: String): String = THINK_BLOCK_REGEX.replace(s, "").trim()

/**
 * Removes UTF-16 surrogate halves from [s].
 *
 * The litert-lm JNI layer passes strings to the native runtime as *modified*
 * UTF-8, which encodes supplementary-plane characters (most emoji) as
 * surrogate-pair sequences. Those are invalid standard UTF-8, and the native
 * `nlohmann::json` parser crashes when it encounters them. Filtering surrogates
 * silently drops supplementary characters while leaving BMP characters (all
 * CJK, Latin, accented chars, and BMP-only emoji such as ⚔️ ♻️ ❤️) untouched.
 */
fun sanitizeForLiteRt(s: String?): String? {
    if (s == null) return null
    if (s.none { it.isSurrogate() }) return s
    return s.filter { !it.isSurrogate() }
}
