/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.PI as PI_CONST

/**
 * Premium entrance animation - smooth fade in with slight upward translation.
 * Perfect for screens, cards, and list items entering the view.
 */
@Composable
fun Modifier.premiumFadeInUp(
    durationMillis: Int = 400,
    delayMillis: Int = 0,
    enabled: Boolean = true
): Modifier {
    if (!enabled) return this
    val isVisible = remember { mutableStateOf(false) }
    val animAlpha by animateFloatAsState(
        targetValue = if (isVisible.value) 1f else 0f,
        animationSpec = tween(durationMillis, delayMillis, FastOutSlowInEasing),
        label = "FadeInUpAlpha"
    )
    val animOffsetY by animateFloatAsState(
        targetValue = if (isVisible.value) 0f else 16f,
        animationSpec = tween(durationMillis, delayMillis, FastOutSlowInEasing),
        label = "FadeInUpOffset"
    )
    
    LaunchedEffect(Unit) { isVisible.value = true }
    
    return then(
        Modifier.graphicsLayer {
            alpha = animAlpha
            translationY = animOffsetY * density
        }
    )
}

/**
 * Premium exit animation - smooth fade out with slight downward translation.
 */
@Composable
fun Modifier.premiumFadeOutDown(
    durationMillis: Int = 300,
    enabled: Boolean = true
): Modifier {
    if (!enabled) return this
    val shouldExit = remember { mutableStateOf(false) }
    val animAlpha by animateFloatAsState(
        targetValue = if (shouldExit.value) 0f else 1f,
        animationSpec = tween(durationMillis, easing = FastOutSlowInEasing),
        label = "FadeOutDownAlpha"
    )
    val animOffsetY by animateFloatAsState(
        targetValue = if (shouldExit.value) 12f else 0f,
        animationSpec = tween(durationMillis, easing = FastOutSlowInEasing),
        label = "FadeOutDownOffset"
    )
    
    return then(
        Modifier.graphicsLayer {
            alpha = animAlpha
            translationY = animOffsetY * density
        }
    )
}

/**
 * Smooth scale-up entrance with fade - great for modals and cards.
 */
@Composable
fun Modifier.premiumScaleUp(
    durationMillis: Int = 500,
    delayMillis: Int = 0
): Modifier {
    val isVisible = remember { mutableStateOf(false) }
    val animScale by animateFloatAsState(
        targetValue = if (isVisible.value) 1f else 0.95f,
        animationSpec = tween(durationMillis, delayMillis, FastOutSlowInEasing),
        label = "ScaleUpScale"
    )
    val animAlpha by animateFloatAsState(
        targetValue = if (isVisible.value) 1f else 0f,
        animationSpec = tween(durationMillis, delayMillis, FastOutSlowInEasing),
        label = "ScaleUpAlpha"
    )
    
    LaunchedEffect(Unit) { isVisible.value = true }
    
    return then(
        Modifier.graphicsLayer {
            scaleX = animScale
            scaleY = animScale
            alpha = animAlpha
        }
    )
}

/**
 * Smooth bounce entrance - playful and premium feel.
 */
@Composable
fun Modifier.premiumBounceIn(totalDurationMillis: Int = 700): Modifier {
    val isVisible = remember { mutableStateOf(false) }
    val animScale by animateFloatAsState(
        targetValue = if (isVisible.value) 1f else 0f,
        animationSpec = keyframes {
            durationMillis = totalDurationMillis
            0.0f at 0 using FastOutSlowInEasing
            1.1f at (totalDurationMillis * 0.75f).toInt() using FastOutSlowInEasing
            1f at totalDurationMillis
        },
        label = "BounceInScale"
    )
    val animAlpha by animateFloatAsState(
        targetValue = if (isVisible.value) 1f else 0f,
        animationSpec = tween(totalDurationMillis, easing = FastOutSlowInEasing),
        label = "BounceInAlpha"
    )
    
    LaunchedEffect(Unit) { isVisible.value = true }
    
    return then(
        Modifier.graphicsLayer {
            scaleX = animScale
            scaleY = animScale
            alpha = animAlpha
        }
    )
}

/**
 * Smooth rotation entrance - elegant spinning effect.
 */
@Composable
fun Modifier.premiumRotateIn(durationMillis: Int = 600): Modifier {
    val isVisible = remember { mutableStateOf(false) }
    val animRotation by animateFloatAsState(
        targetValue = if (isVisible.value) 0f else -180f,
        animationSpec = tween(durationMillis, easing = FastOutSlowInEasing),
        label = "RotateInRotation"
    )
    val animAlpha by animateFloatAsState(
        targetValue = if (isVisible.value) 1f else 0f,
        animationSpec = tween(durationMillis, easing = FastOutSlowInEasing),
        label = "RotateInAlpha"
    )
    
    LaunchedEffect(Unit) { isVisible.value = true }
    
    return then(
        Modifier.graphicsLayer {
            rotationZ = animRotation
            alpha = animAlpha
        }
    )
}

/**
 * Premium floating animation - gentle up-down bobbing motion.
 */
@Composable
fun Modifier.premiumFloating(
    durationMillis: Int = 1800,
    offset: Dp = 8.dp
): Modifier {
    val infiniteTransition = rememberInfiniteTransition(label = "Floating")
    val translateY by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = offset.value,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis / 2, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "FloatingY"
    )
    
    return this.graphicsLayer { translationY = translateY * density }
}

/**
 * Premium shimmer/loading effect - animated gradient sweep.
 */
@Composable
fun Modifier.premiumShimmer(
    enabled: Boolean = true,
    durationMillis: Int = 1000
): Modifier = if (enabled) {
    val infiniteTransition = rememberInfiniteTransition(label = "Shimmer")
    val shimmerProgress by infiniteTransition.animateFloat(
        initialValue = -1f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "ShimmerProgress"
    )
    
    this.drawWithCache {
        val shimmerBrush = androidx.compose.ui.graphics.Brush.linearGradient(
            colors = listOf(
                Color.Transparent,
                Color.White.copy(alpha = 0.3f),
                Color.Transparent
            ),
            start = Offset(shimmerProgress * size.width - size.width, 0f),
            end = Offset(shimmerProgress * size.width, size.height)
        )
        onDrawWithContent {
            drawContent()
            drawRect(shimmerBrush)
        }
    }
} else this

/**
 * Premium pulse animation - gentle opacity pulse for attention.
 */
@Composable
fun Modifier.premiumPulse(
    durationMillis: Int = 1000,
    minAlpha: Float = 0.6f,
    maxAlpha: Float = 1f
): Modifier {
    val infiniteTransition = rememberInfiniteTransition(label = "Pulse")
    val animAlpha by infiniteTransition.animateFloat(
        initialValue = maxAlpha,
        targetValue = minAlpha,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis / 2, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "PulseAlpha"
    )
    
    return then(Modifier.graphicsLayer { alpha = animAlpha })
}

/**
 * Premium glow effect - subtle glowing pulse around elements.
 */
@Composable
fun Modifier.premiumGlow(
    color: Color = MaterialTheme.colorScheme.primary,
    durationMillis: Int = 1200,
    glowRadius: Dp = 8.dp
): Modifier {
    val infiniteTransition = rememberInfiniteTransition(label = "Glow")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis / 2, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "GlowAlpha"
    )
    
    return this.drawWithCache {
        onDrawBehind {
            val glowColor = color.copy(alpha = glowAlpha * 0.5f)
            repeat(3) { index ->
                val radius = glowRadius.toPx() * (1f + index * 0.5f)
                drawCircle(
                    color = glowColor.copy(alpha = glowAlpha / (index + 1)),
                    radius = radius,
                    center = center
                )
            }
        }
    }
}

/**
 * Premium wave effect - flowing motion like waves.
 */
@Composable
fun Modifier.premiumWave(
    durationMillis: Int = 1200,
    amplitude: Float = 4f,
    frequency: Float = 0.5f
): Modifier {
    val infiniteTransition = rememberInfiniteTransition(label = "Wave")
    val wavePhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * PI_CONST.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "WavePhase"
    )
    
    return this.graphicsLayer {
        translationY = (sin(wavePhase * frequency) * amplitude).toFloat()
    }
}

/**
 * Premium slide-in from left animation.
 */
@Composable
fun Modifier.premiumSlideInLeft(
    durationMillis: Int = 400,
    delayMillis: Int = 0
): Modifier {
    val isVisible = remember { mutableStateOf(false) }
    val animOffsetX by animateFloatAsState(
        targetValue = if (isVisible.value) 0f else (-32f),
        animationSpec = tween(durationMillis, delayMillis, FastOutSlowInEasing),
        label = "SlideInLeftX"
    )
    val animAlpha by animateFloatAsState(
        targetValue = if (isVisible.value) 1f else 0f,
        animationSpec = tween(durationMillis, delayMillis, FastOutSlowInEasing),
        label = "SlideInLeftAlpha"
    )
    
    LaunchedEffect(Unit) { isVisible.value = true }
    
    return then(Modifier.graphicsLayer {
        translationX = animOffsetX * density
        alpha = animAlpha
    })
}

/**
 * Premium slide-in from right animation.
 */
@Composable
fun Modifier.premiumSlideInRight(
    durationMillis: Int = 400,
    delayMillis: Int = 0
): Modifier {
    val isVisible = remember { mutableStateOf(false) }
    val animOffsetX by animateFloatAsState(
        targetValue = if (isVisible.value) 0f else 32f,
        animationSpec = tween(durationMillis, delayMillis, FastOutSlowInEasing),
        label = "SlideInRightX"
    )
    val animAlpha by animateFloatAsState(
        targetValue = if (isVisible.value) 1f else 0f,
        animationSpec = tween(durationMillis, delayMillis, FastOutSlowInEasing),
        label = "SlideInRightAlpha"
    )
    
    LaunchedEffect(Unit) { isVisible.value = true }
    
    return then(Modifier.graphicsLayer {
        translationX = animOffsetX * density
        alpha = animAlpha
    })
}

/**
 * Premium expanding ripple effect - elegant splash animation.
 */
@Composable
fun Modifier.premiumRipple(
    isPressed: Boolean = false,
    color: Color = MaterialTheme.colorScheme.primary
): Modifier {
    val animatable = remember { Animatable(0f) }
    
    LaunchedEffect(isPressed) {
        if (isPressed) {
            animatable.animateTo(
                targetValue = 1f,
                animationSpec = tween(600, easing = FastOutSlowInEasing)
            )
            animatable.snapTo(0f)
        }
    }
    
    return this.drawWithCache {
        onDrawWithContent {
            drawContent()
            val rippleRadius = animatable.value * size.width
            drawCircle(
                color = color.copy(alpha = (1f - animatable.value) * 0.5f),
                radius = rippleRadius,
                center = center
            )
        }
    }
}

/**
 * Premium color transition - smooth color change animation.
 */
@Composable
fun Modifier.premiumColorTransition(
    targetColor: Color,
    durationMillis: Int = 400
): Modifier {
    val animatedColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis, easing = FastOutSlowInEasing),
        label = "ColorTransition"
    )
    
    return this.background(animatedColor)
}

/**
 * Premium spring animation - bouncy, playful feel.
 */
@Composable
fun Modifier.premiumSpringScale(
    targetScale: Float = 1f,
    stiffness: Float = Spring.StiffnessHigh
): Modifier {
    val scale = remember { Animatable(1f) }
    
    LaunchedEffect(targetScale) {
        scale.animateTo(
            targetValue = targetScale,
            animationSpec = spring(stiffness = stiffness)
        )
    }
    
    return this.graphicsLayer(scaleX = scale.value, scaleY = scale.value)
}

/**
 * Animated gradient text background - premium text effect.
 */
@Composable
fun PremiumGradientText(
    text: String,
    modifier: Modifier = Modifier,
    colors: List<Color> = listOf(
        MaterialTheme.colorScheme.primary,
        MaterialTheme.colorScheme.secondary
    ),
    style: TextStyle = MaterialTheme.typography.headlineSmall,
    durationMillis: Int = 2400
) {
    val infiniteTransition = rememberInfiniteTransition(label = "GradientText")
    val gradientPhase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "GradientPhase"
    )
    
    Box(modifier = modifier) {
        Text(
            text = text,
            style = style,
            modifier = Modifier.drawWithCache {
                val brush = androidx.compose.ui.graphics.Brush.linearGradient(
                    colors = colors.let { list ->
                        list + list.reversed()
                    },
                    start = Offset(-size.width + gradientPhase * size.width * 2, 0f),
                    end = Offset(gradientPhase * size.width * 2, size.height)
                )
                onDrawWithContent {
                    drawContent()
                }
            }
        )
    }
}

/**
 * Animated particle effect - creates floating particles.
 * Useful for celebration moments or special UI effects.
 */
@Composable
fun PremiumParticleEffect(
    modifier: Modifier = Modifier,
    particleCount: Int = 8,
    durationMillis: Int = 2000,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Box(modifier = modifier) {
        repeat(particleCount) { index ->
            val infiniteTransition = rememberInfiniteTransition(label = "Particle$index")
            val offsetY by infiniteTransition.animateFloat(
                initialValue = 0f,
                targetValue = -200f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "ParticleY$index"
            )
            val alpha by infiniteTransition.animateFloat(
                initialValue = 1f,
                targetValue = 0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis, easing = LinearEasing),
                    repeatMode = RepeatMode.Restart
                ),
                label = "ParticleAlpha$index"
            )
            
            val angle = (360f / particleCount) * index
            val offsetX = 80f * cos(Math.toRadians(angle.toDouble())).toFloat()
            
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .offset(x = offsetX.dp, y = offsetY.dp)
                    .background(
                        color = color.copy(alpha = alpha * 0.7f),
                        shape = RoundedCornerShape(2.dp)
                    )
            ) {
                // Empty box - represents particle
            }
        }
    }
}
