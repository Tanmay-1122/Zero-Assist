/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.model

import kotlinx.serialization.Serializable

/**
 * Remote skill metadata published in the official ZeroClaw skills marketplace.
 *
 * Mirrors the registry consumed by https://www.zeroclawlabs.ai/skills.
 */
@Serializable
data class MarketplaceSkill(
    val name: String,
    val version: String,
    val author: String,
    val description: String,
    val category: String,
    val tags: List<String> = emptyList(),
    val downloads: Int = 0,
    val stars: Int = 0,
) {
    val isOfficial: Boolean
        get() = tags.any { it.equals("Official", ignoreCase = true) }

    val isFeatured: Boolean
        get() = tags.any { it.equals("Featured", ignoreCase = true) }
}

/**
 * Top-level JSON document returned by the official skills registry.
 */
@Serializable
data class MarketplaceSkillRegistry(
    val version: Int,
    val skills: List<MarketplaceSkill>,
)
