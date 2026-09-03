/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.plugins

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.service.termux.TermuxAutoConnector

@Composable
fun TermuxConnectionStatus(
    state: TermuxAutoConnector.ConnectionState,
    modifier: Modifier = Modifier,
) {
    val (color, label) =
        when (state) {
            is TermuxAutoConnector.ConnectionState.Checking ->
                MaterialTheme.colorScheme.secondary to "Checking\u2026"
            is TermuxAutoConnector.ConnectionState.NotInstalled ->
                MaterialTheme.colorScheme.error to "Termux not installed"
            is TermuxAutoConnector.ConnectionState.PermissionNeeded ->
                MaterialTheme.colorScheme.error to "Permission needed"
            is TermuxAutoConnector.ConnectionState.StartingBridge ->
                MaterialTheme.colorScheme.tertiary to "Starting bridge\u2026"
            is TermuxAutoConnector.ConnectionState.TestingConnection ->
                MaterialTheme.colorScheme.tertiary to "Testing connection\u2026"
            is TermuxAutoConnector.ConnectionState.Connected ->
                MaterialTheme.colorScheme.primary to "Connected"
            is TermuxAutoConnector.ConnectionState.Failed ->
                MaterialTheme.colorScheme.error to "Disconnected"
        }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier,
    ) {
        Box(
            modifier =
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(color),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
