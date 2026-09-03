/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.component

import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI as PI_CONST

/**
 * Premium rotating spinner with glowing effect.
 */
@Composable
fun PremiumSpinner(
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    strokeWidth: Dp = 3.dp,
    durationMillis: Int = 800
) {
    val infiniteTransition = rememberInfiniteTransition(label = "PremiumSpinner")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis, easing = LinearEasing)
        ),
        label = "SpinnerRotation"
    )
    
    Canvas(
        modifier = modifier
            .size(size)
            .premiumGlow(color = color, glowRadius = 4.dp)
    ) {
        rotate(rotation) {
            // Draw rotating arc
            drawArc(
                color = color,
                startAngle = 0f,
                sweepAngle = 240f,
                useCenter = false,
                topLeft = androidx.compose.ui.geometry.Offset(
                    strokeWidth.toPx() / 2,
                    strokeWidth.toPx() / 2
                ),
                size = androidx.compose.ui.geometry.Size(
                    size.toPx() - strokeWidth.toPx(),
                    size.toPx() - strokeWidth.toPx()
                )
            )
        }
    }
}

/**
 * Dots animation - bouncing dots that create a wave effect.
 */
@Composable
fun BouncingDotsLoader(
    modifier: Modifier = Modifier,
    dotCount: Int = 3,
    dotSize: Dp = 8.dp,
    spaceBetween: Dp = 6.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    durationMillis: Int = 600
) {
    val infiniteTransition = rememberInfiniteTransition(label = "BouncingDots")
    
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(dotCount) { index ->
            val animatedOffsetY by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = -12f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis,
                        delayMillis = index * (durationMillis / dotCount)
                    )
                ),
                label = "DotOffset$index"
            )
            
            Box(
                modifier = Modifier
                    .size(dotSize)
                    .graphicsLayer { translationY = animatedOffsetY * density }
                    .background(
                        color = color,
                        shape = androidx.compose.foundation.shape.CircleShape
                    )
            )
            
            if (index < dotCount - 1) {
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(spaceBetween))
            }
        }
    }
}

/**
 * Orbit spinner - multiple dots orbiting around a center point.
 */
@Composable
fun OrbitSpinner(
    modifier: Modifier = Modifier,
    size: Dp = 50.dp,
    orbitCount: Int = 3,
    color: Color = MaterialTheme.colorScheme.primary,
    durationMillis: Int = 1000
) {
    val infiniteTransition = rememberInfiniteTransition(label = "OrbitSpinner")
    val rotation by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis, easing = LinearEasing)
        ),
        label = "OrbitRotation"
    )
    
    Canvas(modifier = modifier.size(size)) {
        val orbitRadius = size.toPx() / 3
        
        repeat(orbitCount) { index ->
            val angle = (360f / orbitCount) * index + rotation
            val radians = Math.toRadians(angle.toDouble())
            
            val x = center.x + orbitRadius * cos(radians).toFloat()
            val y = center.y + orbitRadius * sin(radians).toFloat()
            
            drawCircle(
                color = color,
                radius = 4.dp.toPx(),
                center = androidx.compose.ui.geometry.Offset(x, y)
            )
        }
        
        // Draw center circle
        drawCircle(
            color = color.copy(alpha = 0.5f),
            radius = 2.dp.toPx(),
            center = center
        )
    }
}

/**
 * Wave loader - animated horizontal lines like sound waves.
 */
@Composable
fun WaveLoader(
    modifier: Modifier = Modifier,
    barCount: Int = 5,
    color: Color = MaterialTheme.colorScheme.primary,
    durationMillis: Int = 800
) {
    val infiniteTransition = rememberInfiniteTransition(label = "WaveLoader")
    
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        repeat(barCount) { index ->
            val scaleY by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis,
                        delayMillis = index * (durationMillis / barCount)
                    )
                ),
                label = "WaveScale$index"
            )
            
            Box(
                modifier = Modifier
                    .size(4.dp, 24.dp)
                    .background(
                        color = color,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(2.dp)
                    )
                    .graphicsLayer(scaleY = scaleY)
            )
        }
    }
}

/**
 * Pulse ring loader - expanding and contracting circles.
 */
@Composable
fun PulseRingLoader(
    modifier: Modifier = Modifier,
    size: Dp = 50.dp,
    ringCount: Int = 3,
    color: Color = MaterialTheme.colorScheme.primary,
    durationMillis: Int = 1000
) {
    val infiniteTransition = rememberInfiniteTransition(label = "PulseRingLoader")
    
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        repeat(ringCount) { index ->
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis,
                        delayMillis = index * (durationMillis / ringCount)
                    )
                ),
                label = "PulseScale$index"
            )
            
            val alpha by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(
                        durationMillis,
                        delayMillis = index * (durationMillis / ringCount)
                    )
                ),
                label = "PulseAlpha$index"
            )
            
            Box(
                modifier = Modifier
                    .size(size * scale)
                    .align(Alignment.Center)
                    .background(
                        color = color.copy(alpha = alpha * 0.5f),
                        shape = androidx.compose.foundation.shape.CircleShape
                    )
            )
        }
    }
}

/**
 * Shimmer skeleton loader - animated gradient sweep over placeholder.
 */
@Composable
fun ShimmerSkeleton(
    modifier: Modifier = Modifier,
    width: Dp = 200.dp,
    height: Dp = 16.dp,
    shape: androidx.compose.ui.graphics.Shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
    backgroundColor: Color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
) {
    Box(
        modifier = modifier
            .size(width = width, height = height)
            .background(backgroundColor, shape)
            .premiumShimmer()
    )
}
