/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.agents

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.model.AgentStatus
import com.zeroclaw.android.ui.theme.AppSuccess
import kotlinx.coroutines.delay

@Composable
fun AgentStatusDot(
    status: AgentStatus,
    modifier: Modifier = Modifier,
) {
    var doneHasFaded by remember { mutableStateOf(false) }

    LaunchedEffect(status) {
        doneHasFaded = false
        if (status == AgentStatus.DONE) {
            delay(3_000)
            doneHasFaded = true
        }
    }

    val baseColor =
        when (status) {
            AgentStatus.DONE -> if (doneHasFaded) agentStatusColor(AgentStatus.IDLE) else agentStatusColor(status)
            else -> agentStatusColor(status)
        }
    val animatedColor by animateColorAsState(
        targetValue = baseColor,
        animationSpec = tween(durationMillis = 500),
        label = "agent-status-color",
    )

    when (status) {
        AgentStatus.EXECUTING -> ExecutingStatusIndicator(modifier = modifier)
        else -> AnimatedStatusDot(
            status = status,
            color = animatedColor,
            modifier = modifier,
        )
    }
}

@Composable
private fun AnimatedStatusDot(
    status: AgentStatus,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val isPulsing = status == AgentStatus.THINKING || status == AgentStatus.DELEGATING
    val isBlinking = status == AgentStatus.WAITING
    val isShaking = status == AgentStatus.ERROR

    val pulseScale by animateFloatAsState(
        targetValue = if (isPulsing) 1.16f else 1f,
        animationSpec = if (isPulsing) {
            infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            )
        } else {
            tween<Float>(1)
        },
        label = "pulse-scale",
    )
    val pulseAlpha by animateFloatAsState(
        targetValue = if (isPulsing) 1f else 1f,
        animationSpec = if (isPulsing) {
            infiniteRepeatable(
                animation = tween(durationMillis = 900, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            )
        } else {
            tween<Float>(1)
        },
        label = "pulse-alpha",
    )
    val blinkAlpha by animateFloatAsState(
        targetValue = if (isBlinking) 1f else 1f,
        animationSpec = if (isBlinking) {
            infiniteRepeatable(
                animation = tween(durationMillis = 700, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse,
            )
        } else {
            tween<Float>(1)
        },
        label = "blink-alpha",
    )
    val shakeTransition = rememberInfiniteTransition(label = "agent-status-shake")
    val shakeOffset by shakeTransition.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = if (isShaking) {
            infiniteRepeatable(
                animation = keyframes {
                    durationMillis = 420
                    0f at 0
                    -3f at 70
                    3f at 140
                    -2f at 210
                    2f at 280
                    0f at 420
                },
                repeatMode = RepeatMode.Restart,
            )
        } else {
            infiniteRepeatable(
                animation = tween<Float>(1),
                repeatMode = RepeatMode.Restart,
            )
        },
        label = "shake-offset",
    )

    val dotModifier =
        modifier
            .size(10.dp)
            .graphicsLayer {
                scaleX = pulseScale
                scaleY = pulseScale
                alpha =
                    when (status) {
                        AgentStatus.THINKING, AgentStatus.DELEGATING -> pulseAlpha
                        AgentStatus.WAITING -> blinkAlpha
                        else -> 1f
                    }
                translationX = if (isShaking) shakeOffset else 0f
            }
            .clip(CircleShape)
            .background(color)

    Box(modifier = dotModifier)
}

@Composable
private fun ExecutingStatusIndicator(
    modifier: Modifier = Modifier,
) {
    val rotationTransition = rememberInfiniteTransition(label = "executing-rotation")
    val rotation by rotationTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "executing-rotation-value",
    )
    val color = agentStatusColor(AgentStatus.EXECUTING)

    Canvas(
        modifier =
            modifier
                .size(11.dp)
                .graphicsLayer {
                    rotationZ = rotation
                },
    ) {
        val strokeWidth = size.minDimension * 0.18f
        drawArc(
            color = color.copy(alpha = 0.22f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            style = Stroke(width = strokeWidth),
        )
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 230f,
            useCenter = false,
            style = Stroke(width = strokeWidth, cap = StrokeCap.Round),
        )
    }
}

@Composable
internal fun agentStatusColor(status: AgentStatus): Color =
    when (status) {
        AgentStatus.IDLE -> MaterialTheme.colorScheme.outline
        AgentStatus.THINKING -> MaterialTheme.colorScheme.secondary
        AgentStatus.EXECUTING -> MaterialTheme.colorScheme.tertiary
        AgentStatus.DELEGATING -> MaterialTheme.colorScheme.primary
        AgentStatus.WAITING -> MaterialTheme.colorScheme.primaryContainer
        AgentStatus.ERROR -> MaterialTheme.colorScheme.error
        AgentStatus.DONE -> AppSuccess
    }

internal fun agentStatusLabel(status: AgentStatus): String =
    when (status) {
        AgentStatus.IDLE -> "Idle"
        AgentStatus.THINKING -> "Thinking"
        AgentStatus.EXECUTING -> "Executing"
        AgentStatus.DELEGATING -> "Delegating"
        AgentStatus.WAITING -> "Waiting"
        AgentStatus.ERROR -> "Error"
        AgentStatus.DONE -> "Done"
    }
