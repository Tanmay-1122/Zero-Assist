/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.dashboard

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.WavingHand
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zeroclaw.android.ui.theme.ZeroClawTheme
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Dashboard welcome message shown on every app launch.
 *
 * Displays a greeting (e.g. "Good morning, Tanmay!") in a theme-aware
 * surface card with a date subtitle for context. The card tint comes from
 * the active [MaterialTheme] color scheme so text contrast stays correct in
 * both light and dark mode.
 */
@Composable
internal fun DashboardWelcomeMessage(
    visible: Boolean,
    greetingText: String,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(600)) + slideInVertically(tween(600)) { it / 3 },
        modifier = modifier.fillMaxWidth(),
    ) {
        val accent = MaterialTheme.colorScheme.primary
        val tint = MaterialTheme.colorScheme.primaryContainer
        val cardEnd = MaterialTheme.colorScheme.surfaceContainerHigh
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            tint.copy(alpha = 0.55f),
                            cardEnd.copy(alpha = 0.9f),
                        ),
                    ),
                ),
        ) {
            // Solid left accent bar
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .matchParentSize()
                    .background(accent),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Waving hand icon in a subtle badge
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(tint.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        imageVector = Icons.Rounded.WavingHand,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        text = greetingText,
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                            letterSpacing = (-0.3).sp,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = buildDateSubtitle(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.65f),
                    )
                }
            }
        }
    }
}

private fun buildDateSubtitle(): String {
    val today = LocalDate.now()
    val formatter = DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.getDefault())
    return today.format(formatter)
}

@Preview(
    name = "Dashboard Welcome - Dark",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES,
)
@Composable
private fun DashboardWelcomeMessagePreview() {
    ZeroClawTheme {
        DashboardWelcomeMessage(
            visible = true,
            greetingText = "Good evening, Tanmay!",
            modifier = Modifier.padding(16.dp),
        )
    }
}

@Preview(
    name = "Dashboard Welcome - Light",
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_NO,
)
@Composable
private fun DashboardWelcomeMessageLightPreview() {
    ZeroClawTheme(darkTheme = false) {
        DashboardWelcomeMessage(
            visible = true,
            greetingText = "Good morning, Tanmay!",
            modifier = Modifier.padding(16.dp),
        )
    }
}
