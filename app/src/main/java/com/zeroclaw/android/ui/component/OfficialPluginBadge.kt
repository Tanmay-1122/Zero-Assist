/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.component

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * Badge indicating a plugin is an official Zero-Assist built-in.
 *
 * Uses [MaterialTheme.colorScheme.tertiaryContainer] to distinguish from
 * the category badge. The chip is non-interactive (disabled with custom
 * colours) so TalkBack does not announce it as a button.
 *
 * @param modifier Modifier applied to the chip.
 */
@Composable
fun OfficialPluginBadge(modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.primary,
        shape = RoundedCornerShape(6.dp),
        modifier =
            modifier
                .semantics { contentDescription = "Official Zero-Assist plugin" },
    ) {
        Text(
            text = "Official",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}
