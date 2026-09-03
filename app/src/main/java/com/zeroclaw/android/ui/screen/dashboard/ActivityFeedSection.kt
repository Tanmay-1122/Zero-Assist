/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.model.ActivityEvent
import com.zeroclaw.android.model.ActivityType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Activity feed section for the dashboard showing recent events.
 *
 * @param events List of recent activity events to display.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
fun ActivityFeedSection(
    events: List<ActivityEvent>,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        if (events.isEmpty()) {
            Text(
                text = "No recent activity",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        } else {
            val visibleEvents = events.take(MAX_VISIBLE_EVENTS)
            visibleEvents.forEachIndexed { index, event ->
                TimelineEventRow(
                    event = event,
                    isLast = index == visibleEvents.lastIndex,
                )
            }
        }
    }
}

/**
 * Single activity event row in open timeline style.
 *
 * @param event The event to display.
 */
@Composable
private fun TimelineEventRow(
    event: ActivityEvent,
    isLast: Boolean,
) {
    val dotColor =
        when (event.type) {
            ActivityType.DAEMON_ERROR -> Color(0xFFFF5C5C)
            ActivityType.DAEMON_STARTED,
            ActivityType.DAEMON_STOPPED,
            ActivityType.NETWORK_CHANGE,
            -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
        }

    Row(modifier = Modifier.padding(bottom = 8.dp)) {
        Box(
            modifier =
                Modifier
                    .width(20.dp)
                    .padding(top = 2.dp),
        ) {
            if (!isLast) {
                Box(
                    modifier =
                        Modifier
                            .padding(start = 9.dp)
                            .width(2.dp)
                            .height(44.dp)
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)),
                )
            }
            Box(
                modifier =
                    Modifier
                        .padding(start = 6.dp)
                        .width(8.dp)
                        .height(8.dp)
                        .background(dotColor, CircleShape),
            )
        }

        Spacer(modifier = Modifier.width(8.dp))

        Column {
            Text(
                text = event.message,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = formatTime(event.timestamp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private val activityTimeFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/**
 * Formats a Unix timestamp in milliseconds to a time string.
 *
 * @param epochMs Milliseconds since epoch.
 * @return Formatted time string (HH:mm).
 */
private fun formatTime(epochMs: Long): String = activityTimeFormat.format(Instant.ofEpochMilli(epochMs))

private const val MAX_VISIBLE_EVENTS = 5
