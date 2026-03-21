/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.data

/**
 * Recommended free or easy-to-start DroidRun LLM options shown in the UI.
 */
data class DroidRunRecommendation(
    val providerId: String,
    val models: String,
    val limits: String,
    val reason: String,
)

/**
 * Curated DroidRun-capable provider list and recommendations.
 */
object DroidRunProviderCatalog {
    private val supportedProviderIds =
        setOf(
            "openai",
            "anthropic",
            "google-gemini",
            "openrouter",
            "groq",
            "mistral",
            "deepseek",
            "huggingface",
            "custom-openai",
            "ollama",
            "lmstudio",
            "vllm",
            "localai",
        )

    val recommendations: List<DroidRunRecommendation> =
        listOf(
            DroidRunRecommendation(
                providerId = "google-gemini",
                models = "Gemini 1.5 Flash, 2.0 Flash",
                limits = "60 req/min, 1000/day",
                reason = "Large context and fast setup",
            ),
            DroidRunRecommendation(
                providerId = "groq",
                models = "Llama 3 70B, Mixtral 8x7B, Gemma 2 9B",
                limits = "~30 req/min, 14k/day",
                reason = "Very fast responses",
            ),
            DroidRunRecommendation(
                providerId = "huggingface",
                models = "Llama 3.2 3B, Phi-3.5 Mini, Mistral 7B",
                limits = "~30 req/min",
                reason = "Wide model selection",
            ),
            DroidRunRecommendation(
                providerId = "openrouter",
                models = "Llama 3.2 3B, Phi-3 Mini free variants",
                limits = "20 req/day per model",
                reason = "One API for many models",
            ),
            DroidRunRecommendation(
                providerId = "deepseek",
                models = "DeepSeek-Chat",
                limits = "50 req/min, 5k/day",
                reason = "Strong coding and chat quality",
            ),
        )

    /**
     * Returns true when the provider is supported by the DroidRun bridge.
     *
     * @param providerId Provider ID or alias.
     * @return True when the provider can be used for DroidRun execution.
     */
    fun isSupported(providerId: String): Boolean {
        val canonical = ProviderRegistry.findById(providerId)?.id ?: providerId.lowercase()
        return canonical in supportedProviderIds
    }
}
