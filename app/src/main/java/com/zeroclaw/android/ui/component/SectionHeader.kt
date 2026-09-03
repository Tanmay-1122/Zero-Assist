/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Section title row with an optional trailing action button or expand/collapse toggle.
 *
 * Follows the 8dp baseline grid with 16dp vertical padding.
 *
 * @param title Section heading text.
 * @param modifier Modifier applied to the root layout.
 * @param actionLabel Optional label for the action button.
 * @param onAction Callback invoked when the action button is tapped.
 * @param isExpandable Whether this section can be expanded/collapsed.
 * @param isExpanded If expandable, whether it is currently expanded.
 * @param onToggleExpand Callback invoked when the section header is clicked to toggle expansion.
 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    isExpandable: Boolean = false,
    isExpanded: Boolean = false,
    onToggleExpand: (() -> Unit)? = null,
) {
    val clickableModifier = if (isExpandable && onToggleExpand != null) {
        Modifier.clickable { onToggleExpand() }
    } else {
        Modifier
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(clickableModifier)
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(modifier = Modifier.weight(1f))
        if (isExpandable) {
            Icon(
                imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (isExpanded) "Collapse" else "Expand",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 4.dp),
            )
        } else if (actionLabel != null && onAction != null) {
            TextButton(onClick = onAction) {
                Text(actionLabel)
            }
        }
    }
}

