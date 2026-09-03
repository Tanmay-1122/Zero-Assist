/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.component

import android.graphics.BlurMaskFilter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zeroclaw.android.service.devicecontrol.DeviceControlMonitor
import com.zeroclaw.android.service.devicecontrol.DeviceControlState
import com.zeroclaw.android.service.devicecontrol.DeviceControlStatus
import com.zeroclaw.android.service.devicecontrol.shouldShowOverlay

/** Semi-transparent dark scrim covering the screen while control is active. */
private val OverlayScrim = Color(0xAA000000)

/** Cyan/purple accents for the sonar rings and the glowing agent eye. */
private val RingCyan = Color(0xFF00BCD4)
private val RingPurple = Color(0xFF9C27B0)

/**
 * Full-screen device-control overlay.
 *
 * Shows what Zero-Assist is doing while it drives the UI through the
 * accessibility service: a pulsing sonar ring with a glowing agent eye,
 * the current goal, the latest action, step progress, and a status pill.
 * Touch input passes through the window; only the cancel button captures taps.
 *
 * @param state Current [DeviceControlMonitor.state] snapshot.
 * @param onCancel Invoked when the user taps the cancel button.
 */
@Composable
fun DeviceControlOverlay(state: DeviceControlState, onCancel: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OverlayScrim),
        contentAlignment = Alignment.Center,
    ) {
        GoalBanner(
            goal = state.goal,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PulseRingCluster()

            Spacer(modifier = Modifier.height(28.dp))

            AnimatedContent(
                targetState = state.currentAction,
                transitionSpec = {
                    (fadeIn(animationSpec = tween(180)) togetherWith
                        fadeOut(animationSpec = tween(180)))
                },
                label = "overlayActionText",
            ) { action ->
                Text(
                    text = action,
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 40.dp),
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Step ${state.currentStep} of ${state.maxSteps}",
                color = Color(0xFF9E9EAE),
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
            )

            Spacer(modifier = Modifier.height(18.dp))

            StatusPill(status = state.status)
        }

        CancelButton(
            onCancel = onCancel,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 56.dp),
        )
    }
}

/**
 * Entry/exit-animated host for [DeviceControlOverlay]. Enters with
 * fadeIn + scaleIn (0.95x) and exits with the mirrored fadeOut + scaleOut.
 */
@Composable
fun DeviceControlOverlayHost(
    visible: Boolean,
    state: DeviceControlState,
    onCancel: () -> Unit,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(220)) +
            scaleIn(initialScale = 0.95f, animationSpec = tween(220)),
        exit = fadeOut(animationSpec = tween(320)) +
            scaleOut(targetScale = 0.95f, animationSpec = tween(320)),
    ) {
        DeviceControlOverlay(state = state, onCancel = onCancel)
    }
}

@Composable
private fun GoalBanner(goal: String, modifier: Modifier = Modifier) {
    Text(
        text = goal,
        color = Color.White,
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .padding(top = 64.dp, start = 32.dp, end = 32.dp),
    )
}

/**
 * Three concentric sonar rings pulsing outward on staggered offsets,
 * wrapped around the glowing agent-eye canvas at the center.
 */
@Composable
private fun PulseRingCluster() {
    val transition = rememberInfiniteTransition(label = "deviceControlPulse")
    val ringScales = (0 until RING_COUNT).map { index ->
        transition.animateFloat(
            initialValue = PULSE_MIN_SCALE,
            targetValue = PULSE_MAX_SCALE,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = PULSE_DURATION_MS, easing = LinearEasing),
                repeatMode = RepeatMode.Restart,
                initialStartOffset = StartOffset(index * (PULSE_DURATION_MS / RING_COUNT)),
            ),
            label = "ringScale$index",
        )
    }

    Box(contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.size(RingDiameter)) {
            val maxRadius = size.minDimension / 2f
            ringScales.forEachIndexed { index, scale ->
                val value = scale.value
                val progress = (value - PULSE_MIN_SCALE) / (PULSE_MAX_SCALE - PULSE_MIN_SCALE)
                val tint = if (index % 2 == 0) RingCyan else RingPurple
                drawCircle(
                    color = tint.copy(alpha = (1f - progress) * 0.45f),
                    radius = maxRadius * 0.35f * value,
                )
            }
        }
        GlowingAgentEye()
    }
}

/** Stylized AI "eye": soft blurred halo plus a bright solid core. */
@Composable
private fun GlowingAgentEye() {
    Canvas(modifier = Modifier.size(EyeDiameter)) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val haloPaint = Paint().asFrameworkPaint().apply {
            isAntiAlias = true
            color = android.graphics.Color.parseColor("#00BCD4")
            maskFilter = BlurMaskFilter(HaloBlurDp.toPx(), BlurMaskFilter.Blur.NORMAL)
        }
        drawIntoCanvas { canvas ->
            canvas.nativeCanvas.drawCircle(
                center.x,
                center.y,
                EyeCoreRadius.toPx() * 2f,
                haloPaint,
            )
        }
        drawCircle(
            color = Color.White.copy(alpha = 0.92f),
            radius = EyeCoreRadius.toPx(),
            center = center,
        )
        drawCircle(
            color = RingPurple.copy(alpha = 0.85f),
            radius = EyeCoreRadius.toPx() * 0.55f,
            center = center,
        )
    }
}

@Composable
private fun StatusPill(status: DeviceControlStatus) {
    Surface(
        shape = RoundedCornerShape(50),
        color = statusColor(status).copy(alpha = 0.22f),
        contentColor = statusColor(status),
        border = BorderStroke(1.dp, statusColor(status).copy(alpha = 0.6f)),
    ) {
        Text(
            text = status.name,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.5.sp,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

private fun statusColor(status: DeviceControlStatus): Color = when (status) {
    DeviceControlStatus.PLANNING,
    DeviceControlStatus.INITIALIZING,
    -> Color(0xFF00BCD4)

    DeviceControlStatus.EXECUTING -> Color(0xFF9C27B0)
    DeviceControlStatus.COMPLETED -> Color(0xFF4CAF50)
    DeviceControlStatus.FAILED -> Color(0xFFF44336)
    else -> Color(0xFF9E9EAE)
}

@Composable
private fun CancelButton(onCancel: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .size(44.dp)
            .background(Color.White.copy(alpha = 0.12f), CircleShape)
            .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
            .clickable(onClick = onCancel),
    ) {
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = "Cancel device control",
            tint = Color.White,
            modifier = Modifier.size(22.dp),
        )
    }
}

private val RingDiameter = 240.dp
private val EyeDiameter = 96.dp
private val EyeCoreRadius = 14.dp
private val HaloBlurDp = 18.dp

private const val RING_COUNT = 3
private const val PULSE_MIN_SCALE = 0.8f
private const val PULSE_MAX_SCALE = 1.0f
private const val PULSE_DURATION_MS = 1500
