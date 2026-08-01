/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Rounded provider avatar that stays on-brand with the app theme instead of
 * switching to per-provider brand colors.
 *
 * @param provider Provider ID or name (e.g. "anthropic", "OpenAI").
 * @param modifier Modifier applied to the root layout.
 */
@Composable
fun ProviderIcon(
    provider: String,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    shape: Shape = MaterialTheme.shapes.medium,
) {
    val initial = provider.firstOrNull()?.uppercase() ?: "?"

    Box(
        modifier =
            modifier
                .size(size)
                .clip(shape)
                .background(MaterialTheme.colorScheme.primary),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = initial,
            color = MaterialTheme.colorScheme.onPrimary,
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
