/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.model.PluginCategory

/**
 * Compact chip displaying a [PluginCategory] label.
 *
 * @param category The plugin category to display.
 * @param modifier Modifier applied to the chip.
 */
@Composable
fun CategoryBadge(
    category: PluginCategory,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(6.dp),
        border =
            BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
            ),
        modifier = modifier,
    ) {
        Text(
            text = categoryLabel(category),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/**
 * Compact chip displaying a plain text label.
 *
 * Used for tags, sources, and categories that are represented as strings
 * rather than the [PluginCategory] enum.
 *
 * @param category The label text to display.
 * @param modifier Modifier applied to the chip.
 */
@Composable
fun CategoryBadge(
    category: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(6.dp),
        border =
            BorderStroke(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outline.copy(alpha = 0.7f),
            ),
        modifier = modifier,
    ) {
        Text(
            text = category,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

private fun categoryLabel(category: PluginCategory): String =
    when (category) {
        PluginCategory.CHANNEL -> "Channel"
        PluginCategory.MEMORY -> "Memory"
        PluginCategory.TOOL -> "Tool"
        PluginCategory.OBSERVER -> "Observer"
        PluginCategory.SECURITY -> "Security"
        PluginCategory.OTHER -> "Other"
    }
