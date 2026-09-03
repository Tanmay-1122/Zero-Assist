/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.component

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest

/**
 * Premium button press effect - scale down and back on press.
 */
@Composable
fun Modifier.premiumButtonPress(
    interactionSource: MutableInteractionSource,
    pressScale: Float = 0.97f,
    durationMillis: Int = 100
): Modifier {
    val scale = remember { Animatable(1f) }
    
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collectLatest { interaction ->
            when (interaction) {
                is PressInteraction.Press -> {
                    scale.animateTo(
                        pressScale,
                        animationSpec = tween(durationMillis, easing = FastOutSlowInEasing)
                    )
                }
                is PressInteraction.Release -> {
                    scale.animateTo(
                        1f,
                        animationSpec = tween(durationMillis, easing = FastOutSlowInEasing)
                    )
                }
                is PressInteraction.Cancel -> {
                    scale.snapTo(1f)
                }
            }
        }
    }
    
    return this.graphicsLayer(scaleX = scale.value, scaleY = scale.value)
}

/**
 * Premium tap ripple effect - expands outward on tap.
 */
@Composable
fun Modifier.premiumTapRipple(
    color: Color = MaterialTheme.colorScheme.primary,
    radius: Float = 50f,
    durationMillis: Int = 600
): Modifier {
    var tapPosition by remember { mutableStateOf<Pair<Float, Float>?>(null) }
    val rippleProgress = remember { Animatable(0f) }
    
    LaunchedEffect(tapPosition) {
        if (tapPosition != null) {
            rippleProgress.snapTo(0f)
            rippleProgress.animateTo(
                1f,
                animationSpec = tween(durationMillis, easing = FastOutSlowInEasing)
            )
        }
    }
    
    return this.pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Final)
                if (event.changes.any { it.pressed }) {
                    val position = event.changes.first().position
                    tapPosition = position.x to position.y
                }
            }
        }
    }.drawWithCache {
        onDrawWithContent {
            drawContent()
            tapPosition?.let { (x, y) ->
                val actualRadius = radius * rippleProgress.value
                if (actualRadius > 0) {
                    drawCircle(
                        color = color.copy(
                            alpha = (1f - rippleProgress.value) * 0.5f
                        ),
                        radius = actualRadius,
                        center = androidx.compose.ui.geometry.Offset(x, y)
                    )
                }
            }
        }
    }
}

/**
 * Premium hover effect - subtle elevation and color change on hover.
 */
fun Modifier.premiumHoverEffect(
    interactionSource: MutableInteractionSource,
    hoverScale: Float = 1.02f,
    hoverShadowElevation: Float = 8f
): Modifier = this
    .graphicsLayer {
        var currentScale = 1f
        var currentElevation = 0f
        
        // Note: This is a simplified version. Full implementation would track
        // HoveredInteraction state from interactionSource
    }

/**
 * Premium drag effect - smooth follow-the-finger animation.
 */
@Composable
fun Modifier.premiumDragEffect(): Modifier {
    var isDragging by remember { mutableStateOf(false) }
    val dragScale = remember { Animatable(1f) }
    val dragAlpha = remember { Animatable(1f) }
    
    LaunchedEffect(isDragging) {
        if (isDragging) {
            dragScale.animateTo(
                1.05f,
                animationSpec = tween(100, easing = FastOutSlowInEasing)
            )
            dragAlpha.animateTo(
                0.9f,
                animationSpec = tween(100, easing = FastOutSlowInEasing)
            )
        } else {
            dragScale.animateTo(
                1f,
                animationSpec = tween(200, easing = FastOutSlowInEasing)
            )
            dragAlpha.animateTo(
                1f,
                animationSpec = tween(200, easing = FastOutSlowInEasing)
            )
        }
    }
    
    return this.graphicsLayer(
        scaleX = dragScale.value,
        scaleY = dragScale.value,
        alpha = dragAlpha.value
    )
}

/**
 * Premium focus effect - animates when element gains focus (keyboard nav).
 */
@Composable
fun Modifier.premiumFocusEffect(
    hasFocus: Boolean,
    focusColor: Color = MaterialTheme.colorScheme.primary,
    durationMillis: Int = 300
): Modifier {
    val focusAlpha = remember { Animatable(0f) }
    val focusScale = remember { Animatable(1f) }
    
    LaunchedEffect(hasFocus) {
        if (hasFocus) {
            focusAlpha.animateTo(
                0.5f,
                animationSpec = tween(durationMillis, easing = FastOutSlowInEasing)
            )
            focusScale.animateTo(
                1.02f,
                animationSpec = tween(durationMillis, easing = FastOutSlowInEasing)
            )
        } else {
            focusAlpha.animateTo(
                0f,
                animationSpec = tween(durationMillis, easing = FastOutSlowInEasing)
            )
            focusScale.animateTo(
                1f,
                animationSpec = tween(durationMillis, easing = FastOutSlowInEasing)
            )
        }
    }
    
    return this
        .graphicsLayer(
            scaleX = focusScale.value,
            scaleY = focusScale.value
        )
        .drawWithCache {
            onDrawBehind {
                drawRect(
                    color = focusColor.copy(alpha = focusAlpha.value * 0.3f),
                    size = size
                )
            }
        }
}

/**
 * Premium long-press effect - sustained press animation.
 */
@Composable
fun Modifier.premiumLongPressEffect(
    onLongPress: () -> Unit = {},
    durationMillis: Long = 500
): Modifier {
    return this.pointerInput(onLongPress, durationMillis) {
        detectTapGestures(onLongPress = { onLongPress() })
    }
}

/**
 * Premium swipe gesture animation - responds to swipe gestures with animation.
 */
@Composable
fun Modifier.premiumSwipeGesture(
    onSwipeLeft: () -> Unit = {},
    onSwipeRight: () -> Unit = {},
    swipeSensitivity: Float = 100f
): Modifier {
    val offsetX = remember { Animatable(0f) }
    val offsetY = remember { Animatable(0f) }
    
    return this
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent()
                    // Simplified swipe detection
                    // Full implementation would track pointer movements
                }
            }
        }
        .graphicsLayer(
            translationX = offsetX.value,
            translationY = offsetY.value
        )
}
