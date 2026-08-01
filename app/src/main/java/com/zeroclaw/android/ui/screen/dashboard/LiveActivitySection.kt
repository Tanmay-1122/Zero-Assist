/*
 * Copyright 2026 Zero-Assist Community, MIT License
 */

package com.zeroclaw.android.ui.screen.dashboard

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zeroclaw.android.R
import com.zeroclaw.android.model.ActivityStatus
import com.zeroclaw.android.model.LiveActivityItem
import com.zeroclaw.android.model.ProcessingStep
import com.zeroclaw.android.model.StepKind
import com.zeroclaw.android.ui.component.premiumPulse
import com.zeroclaw.android.ui.theme.AppSuccess
import com.zeroclaw.android.ui.theme.JetBrainsMonoFamily
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Live activity feed section showing up to 3 recent request lifecycles.
 *
 * Each item displays channel icon, sender, truncated message preview,
 * a status indicator (spinner → green tick → error dot), and
 * expandable processing steps.
 *
 * @param items Current live activity items from the grouper, newest first.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
fun LiveActivitySection(
    items: List<LiveActivityItem>,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return

    val cardShape = RoundedCornerShape(12.dp)
    val cardBorder = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.18f))

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = cardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = cardBorder,
    ) {
        Column {
            // Section title row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = "LIVE ACTIVITY",
                    style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 1.2.sp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    fontWeight = FontWeight.Bold,
                )
                // Active count badge
                val activeCount = items.count { it.status == ActivityStatus.ACTIVE }
                if (activeCount > 0) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "$activeCount active",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            val visibleItems = items.take(MAX_VISIBLE)
            visibleItems.forEachIndexed { index, item ->
                LiveActivityRow(item = item)
                if (index != visibleItems.lastIndex) {
                    HorizontalDivider(
                        color = dividerCol(),
                        thickness = 0.5.dp,
                        modifier = Modifier.padding(horizontal = 14.dp),
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

@Composable
private fun LiveActivityRow(item: LiveActivityItem) {
    var expanded by remember { mutableStateOf(false) }
    val hasSteps = item.steps.isNotEmpty()

    val statusColor = when (item.status) {
        ActivityStatus.ACTIVE -> MaterialTheme.colorScheme.primary
        ActivityStatus.COMPLETED -> AppSuccess
        ActivityStatus.ERROR -> MaterialTheme.colorScheme.error
    }

    // Animate scale for completion pop
    val iconScale by animateFloatAsState(
        targetValue = if (item.status == ActivityStatus.COMPLETED) 1f else 0.85f,
        animationSpec = tween(300),
        label = "statusIconScale",
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (hasSteps) Modifier.clickable { expanded = !expanded } else Modifier)
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Channel icon with colored ring for status
            val iconRes = channelIconRes(item.channel)
            Box(contentAlignment = Alignment.BottomEnd) {
                if (iconRes != 0) {
                    Icon(
                        painter = painterResource(id = iconRes),
                        contentDescription = item.channel,
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape),
                        tint = Color.Unspecified,
                    )
                } else {
                    // Fallback: first-letter badge
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(statusColor.copy(alpha = 0.18f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = item.channel.firstOrNull()?.uppercase() ?: "?",
                            style = MaterialTheme.typography.labelSmall,
                            color = statusColor,
                            fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }

            Spacer(Modifier.width(12.dp))

            // Sender + preview
            Column(modifier = Modifier.weight(1f)) {
                val senderText = item.sender ?: item.channel
                Text(
                    text = senderText,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (item.contentPreview != null) {
                    Spacer(Modifier.height(1.dp))
                    Text(
                        text = item.contentPreview.take(MAX_PREVIEW_CHARS),
                        style = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = JetBrainsMonoFamily,
                        ),
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.85f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Spacer(Modifier.width(10.dp))

            // Status indicator — animated transition between states
            AnimatedContent(
                targetState = item.status,
                transitionSpec = {
                    (fadeIn(tween(250)) togetherWith fadeOut(tween(150)))
                },
                label = "statusIcon",
            ) { status ->
                when (status) {
                    ActivityStatus.ACTIVE -> {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp).premiumPulse(),
                            strokeWidth = 2.dp,
                            color = statusColor,
                        )
                    }
                    ActivityStatus.COMPLETED -> {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .scale(iconScale)
                                .clip(CircleShape)
                                .background(AppSuccess.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.Check,
                                contentDescription = "Completed",
                                tint = AppSuccess,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                    ActivityStatus.ERROR -> {
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.error.copy(alpha = 0.13f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                imageVector = Icons.Rounded.ErrorOutline,
                                contentDescription = "Error",
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(14.dp),
                            )
                        }
                    }
                }
            }
        }

        // Status label pill under the row
        val statusLabel = when (item.status) {
            ActivityStatus.ACTIVE -> "processing…"
            ActivityStatus.COMPLETED -> "completed"
            ActivityStatus.ERROR -> "failed"
        }
        Spacer(Modifier.height(5.dp))
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(statusColor.copy(alpha = 0.10f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                text = statusLabel,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                color = statusColor,
                fontWeight = FontWeight.Medium,
            )
        }

        // Expandable steps
        if (hasSteps) {
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(tween(200)),
                exit = shrinkVertically() + fadeOut(tween(150)),
            ) {
                Column(Modifier.padding(top = 10.dp)) {
                    HorizontalDivider(
                        color = dividerCol(),
                        thickness = 0.5.dp,
                    )
                    Spacer(Modifier.height(8.dp))
                    item.steps.forEach { step ->
                        StepRow(step = step)
                    }
                }
            }
        }
    }
}

@Composable
private fun StepRow(step: ProcessingStep) {
    val kindColor = when (step.kind) {
        StepKind.THINKING -> MaterialTheme.colorScheme.primary
        StepKind.TOOL_CALL -> MaterialTheme.colorScheme.tertiary
        StepKind.TOOL_RESULT ->
            if (step.success == false) MaterialTheme.colorScheme.error else AppSuccess
        StepKind.RESPONSE ->
            if (step.success == false) MaterialTheme.colorScheme.error else AppSuccess
    }
    val kindIcon = when (step.kind) {
        StepKind.THINKING -> "\u25B6"
        StepKind.TOOL_CALL -> "\u2699"
        StepKind.TOOL_RESULT -> "\u2713"
        StepKind.RESPONSE -> "\u2192"
    }
    val durationSuffix = step.durationMs?.let {
        val ms = if (it < 1000) "${it}ms" else "${"%.1f".format(it / 1000.0)}s"
        " ($ms)"
    } ?: ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = kindIcon,
            style = MaterialTheme.typography.labelSmall,
            color = kindColor,
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "${step.kind.name.lowercase().replace('_', ' ')}: ${step.detail}$durationSuffix",
            style = MaterialTheme.typography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@DrawableRes
private fun channelIconRes(channel: String): Int = when (channel.lowercase()) {
    "telegram" -> R.drawable.ic_channel_telegram
    "discord" -> R.drawable.ic_channel_discord
    "slack" -> R.drawable.ic_channel_slack
    "whatsapp" -> R.drawable.ic_channel_whatsapp
    "email", "mail", "imap", "smtp" -> R.drawable.ic_channel_email
    "signal" -> R.drawable.ic_channel_signal
    "matrix" -> R.drawable.ic_channel_matrix
    else -> 0
}

private const val MAX_VISIBLE = 3
private const val MAX_PREVIEW_CHARS = 80

/** Muted divider color shared with DashboardScreen. */
@Composable
private fun dividerCol(): Color =
    MaterialTheme.colorScheme.outline.copy(alpha = 0.13f)

private val stepTimeFormat: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault())
