/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.terminal

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * A floating pill badge shown over the terminal when the background process card is hidden,
 * but processes are actively running or have run recently.
 * Allows the user to re-open the background process card.
 */
@Composable
fun ActivityPill(
    processCount: Int,
    activeCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val hasActive = activeCount > 0
    val text = if (hasActive) {
        "⚡ $activeCount active task${if (activeCount > 1) "s" else ""}"
    } else {
        "✓ $processCount task${if (processCount > 1) "s" else ""} done"
    }

    val primaryColor = MaterialTheme.colorScheme.primary
    val containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.95f)
    
    // Pulse animation if there are active tasks
    val borderAlpha by animateFloatAsState(
        targetValue = if (hasActive) 0.2f else 0.2f,
        animationSpec = if (hasActive) {
            infiniteRepeatable(
                animation = tween(1000),
                repeatMode = RepeatMode.Reverse,
            )
        } else {
            tween<Float>(1)
        },
        label = "borderAlpha"
    )
    
    val borderColor = if (hasActive) {
        primaryColor.copy(alpha = borderAlpha)
    } else {
        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    }

    AnimatedVisibility(
        visible = processCount > 0,
        enter = fadeIn() + slideInVertically(initialOffsetY = { it / 2 }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { it / 2 }),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .padding(bottom = 16.dp, end = 16.dp)
                .clip(RoundedCornerShape(24.dp))
                .clickable(onClick = onClick)
                .border(
                    width = if (hasActive) 1.5.dp else 1.dp,
                    color = borderColor,
                    shape = RoundedCornerShape(24.dp)
                ),
            color = containerColor,
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hasActive) {
                    val dotAlpha by animateFloatAsState(
                        targetValue = 1.0f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(800),
                            repeatMode = RepeatMode.Reverse,
                        ),
                        label = "dotAlpha"
                    )
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .alpha(dotAlpha)
                            .background(Color(0xFF4CAF50), CircleShape)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = if (hasActive) FontWeight.SemiBold else FontWeight.Medium
                    ),
                    color = if (hasActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
