/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.component

import androidx.compose.animation.animateColor
import androidx.compose.animation.core.InfiniteRepeatableSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.zeroclaw.android.ui.theme.DeepIndigo
import com.zeroclaw.android.ui.theme.GlowGreen
import com.zeroclaw.android.ui.theme.NeonBlue
import com.zeroclaw.android.ui.theme.Obsidian
import kotlin.math.cos
import kotlin.math.sin

/**
 * A performant animated mesh gradient background.
 * 
 * Uses Compose Canvas to draw moving radial gradients that create a fluid,
 * premium "lava lamp" or "mesh" effect.
 */
@Composable
fun AnimatedMeshBackground() {
    val infiniteTransition = rememberInfiniteTransition(label = "MeshTransition")
    
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 2f * Math.PI.toFloat(),
        animationSpec = infiniteRepeatable(
            animation = tween(TRANSITION_DURATION_MS, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Phase"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        // Background base
        drawRect(color = Obsidian)

        // Moving Globs
        val glob1Pos = Offset(
            x = width * (CENTER_OFFSET + GLOB_MOVE_RADIUS * cos(phase)),
            y = height * (CENTER_OFFSET + GLOB_MOVE_RADIUS * sin(phase))
        )
        
        val glob2Pos = Offset(
            x = width * (CENTER_OFFSET + GLOB_MOVE_RADIUS * sin(phase * GLOB2_PHASE_X)),
            y = height * (CENTER_OFFSET + GLOB2_MOVE_RADIUS_Y * cos(phase * GLOB2_PHASE_Y))
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(GlowGreen.copy(alpha = GLOB1_ALPHA), Color.Transparent),
                center = glob1Pos,
                radius = width * GLOB_RADIUS_LARGE
            ),
            center = glob1Pos,
            radius = width * GLOB_RADIUS_LARGE
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(NeonBlue.copy(alpha = GLOB2_ALPHA), Color.Transparent),
                center = glob2Pos,
                radius = width * GLOB_RADIUS_MEDIUM
            ),
            center = glob2Pos,
            radius = width * GLOB_RADIUS_MEDIUM
        )
        
        // Subtle Vignette
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, Obsidian.copy(alpha = VIGNETTE_ALPHA)),
                center = center,
                radius = width
            )
        )
    }
}

private const val TRANSITION_DURATION_MS = 10000
private const val CENTER_OFFSET = 0.5f
private const val GLOB_MOVE_RADIUS = 0.3f
private const val GLOB2_MOVE_RADIUS_Y = 0.2f
private const val GLOB2_PHASE_X = 0.7f
private const val GLOB2_PHASE_Y = 1.2f
private const val GLOB1_ALPHA = 0.15f
private const val GLOB2_ALPHA = 0.12f
private const val GLOB_RADIUS_LARGE = 0.8f
private const val GLOB_RADIUS_MEDIUM = 0.7f
private const val VIGNETTE_ALPHA = 0.6f
