/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.settings.spotify

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.ui.component.EmptyState

/**
 * Spotify account-linking settings screen.
 *
 * Keeps the route available while Spotify OAuth storage is wired through the
 * shared connected-channel and auth-profile infrastructure.
 */
@Composable
fun SpotifyAccountScreen(
    edgeMargin: Dp,
    modifier: Modifier = Modifier,
) {
    Scaffold(modifier = modifier) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = edgeMargin),
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            EmptyState(
                icon = Icons.Outlined.MusicNote,
                message = "Spotify account linking is not configured yet.",
            )

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors =
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    ),
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "Use Connected Channels for now",
                        style = MaterialTheme.typography.titleSmall,
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text =
                            "Spotify playback actions are available through device control. " +
                                "Full OAuth account linking will appear here once the Spotify " +
                                "profile writer is connected.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
