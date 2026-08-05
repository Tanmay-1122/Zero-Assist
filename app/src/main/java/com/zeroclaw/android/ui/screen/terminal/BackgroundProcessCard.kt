/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.terminal

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zeroclaw.android.model.BackgroundProcessEntry
import com.zeroclaw.android.model.BackgroundProcessState
import com.zeroclaw.android.model.ProcessStatus
import com.zeroclaw.android.model.ProcessType
import com.zeroclaw.android.model.label

/**
 * Premium glass-morphic card displaying live background processes in real-time.
 *
 * Features:
 * - Clean, sleek design with pulsing indicators
 * - Emoji-based icons for different process types
 * - Animated row insertions
 * - Auto-scrolling to latest entries
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun BackgroundProcessCard(
    state: BackgroundProcessState,
    onClose: () -> Unit,
    onToggleExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!state.isVisible) return

    val listState = rememberLazyListState()

    // Auto-scroll on new entry if expanded
    LaunchedEffect(state.processes.size, state.isExpanded) {
        if (state.isExpanded && state.processes.isNotEmpty()) {
            listState.scrollToItem(state.processes.lastIndex)
        }
    }

    // Determine header dynamic state
    val activeCount = state.processes.count { it.status == ProcessStatus.ACTIVE }
    val hasActive = activeCount > 0
    val headerText = if (hasActive) {
        "● $activeCount process${if (activeCount > 1) "es" else ""} running"
    } else if (state.processes.isNotEmpty()) {
        "✓ All processes completed"
    } else {
        "Waiting for operations..."
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .border(
                1.dp,
                MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                RoundedCornerShape(12.dp)
            ),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f),
        shadowElevation = 2.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand)
                    .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val primaryColor = MaterialTheme.colorScheme.primary
                    
                    val pulseAlpha by animateFloatAsState(
                        targetValue = if (hasActive) 1.0f else 1.0f,
                        animationSpec = if (hasActive) {
                            infiniteRepeatable(
                                animation = tween(900),
                                repeatMode = RepeatMode.Reverse,
                            )
                        } else {
            tween<Float>(1)
                        },
                        label = "headerPulseAlpha"
                    )

                    Text(
                        text = headerText,
                        fontSize = 13.sp,
                        fontWeight = if (hasActive) FontWeight.SemiBold else FontWeight.Medium,
                        color = if (hasActive) primaryColor.copy(alpha = pulseAlpha) else MaterialTheme.colorScheme.onSurface,
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(
                        onClick = onToggleExpand,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = if (state.isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = "Toggle Expand",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }

                    Spacer(modifier = Modifier.width(4.dp))

                    IconButton(
                        onClick = onClose,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
            }

            HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

            AnimatedVisibility(
                visible = state.isExpanded,
                enter = expandVertically(expandFrom = Alignment.Top) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + fadeOut()
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp)
                ) {
                    itemsIndexed(
                        items = state.processes,
                        key = { index, process -> backgroundProcessRenderKey(process, index) },
                        contentType = { _, _ -> "process" },
                    ) { _, process ->
                        Box(modifier = Modifier.animateItem(tween(250))) {
                            BackgroundProcessRow(process)
                        }
                        HorizontalDivider(thickness = 0.5.dp, color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.2f))
                    }
                }
            }
            
            // Collapsed summary preview
            AnimatedVisibility(
                visible = !state.isExpanded && state.processes.isNotEmpty(),
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut()
            ) {
                val latest = state.processes.lastOrNull()
                if (latest != null) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onToggleExpand)
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TypeIcon(type = latest.type)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = latest.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BackgroundProcessRow(process: BackgroundProcessEntry) {
    val isActive = process.status == ProcessStatus.ACTIVE
    val isFailed = process.status == ProcessStatus.FAILED

    val alpha by animateFloatAsState(
        targetValue = if (isActive) 1.0f else 1.0f,
        animationSpec = if (isActive) {
            infiniteRepeatable(
                animation = tween(800),
                repeatMode = RepeatMode.Reverse,
            )
        } else {
            tween<Float>(1)
        },
        label = "rowAlpha"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isActive) MaterialTheme.colorScheme.surface.copy(alpha = 0.3f) else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top
    ) {
        TypeIcon(process.type)

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = process.description,
                fontSize = 13.sp,
                fontWeight = if (isActive) FontWeight.Medium else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = alpha),
                lineHeight = 18.sp
            )
            if (process.details.isNotEmpty()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = process.details,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }
            if (isFailed) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "Failed",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        // Status Indicator
        Box(
            modifier = Modifier.padding(top = 4.dp),
            contentAlignment = Alignment.Center
        ) {
            val statusColor = when (process.status) {
                ProcessStatus.ACTIVE -> Color(0xFF4CAF50)
                ProcessStatus.COMPLETED -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                ProcessStatus.FAILED -> MaterialTheme.colorScheme.error
                ProcessStatus.CANCELLED -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor)
                    .alpha(if (isActive) alpha else 1.0f)
            )
        }
    }
}

@Composable
private fun TypeIcon(type: ProcessType) {
    val (emoji, bgColor) = when (type) {
        ProcessType.SEARCH -> "🔍" to Color(0xFFE3F2FD)
        ProcessType.FILE_READ -> "📄" to Color(0xFFF5F5F5)
        ProcessType.FILE_WRITE -> "💾" to Color(0xFFE8F5E9)
        ProcessType.API_CALL -> "📡" to Color(0xFFF3E5F5)
        ProcessType.MEMORY_OP -> "🧠" to Color(0xFFFFF3E0)
        ProcessType.ANALYSIS -> "🔬" to Color(0xFFE0F7FA)
        ProcessType.TOOL_EXEC -> "🔧" to Color(0xFFFFF8E1)
        ProcessType.DECISION -> "✅" to Color(0xFFFCE4EC)
        ProcessType.OTHER -> "⚙️" to Color(0xFFECEFF1)
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(26.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(bgColor)
    ) {
        Text(text = emoji, fontSize = 12.sp)
    }
}
