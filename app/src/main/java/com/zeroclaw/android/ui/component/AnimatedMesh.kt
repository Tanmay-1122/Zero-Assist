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
            animation = tween(10000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "Phase"
    )

    val color1 by infiniteTransition.animateColor(
        initialValue = DeepIndigo,
        targetValue = Obsidian,
        animationSpec = infiniteRepeatable(
            animation = tween(5000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "Color1"
    )

    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height

        // Background base
        drawRect(color = Obsidian)

        // Moving Globs
        val glob1Pos = Offset(
            x = width * (0.5f + 0.3f * cos(phase)),
            y = height * (0.5f + 0.3f * sin(phase))
        )
        
        val glob2Pos = Offset(
            x = width * (0.5f + 0.3f * sin(phase * 0.7f)),
            y = height * (0.5f + 0.2f * cos(phase * 1.2f))
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(GlowGreen.copy(alpha = 0.15f), Color.Transparent),
                center = glob1Pos,
                radius = width * 0.8f
            ),
            center = glob1Pos,
            radius = width * 0.8f
        )

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(NeonBlue.copy(alpha = 0.12f), Color.Transparent),
                center = glob2Pos,
                radius = width * 0.7f
            ),
            center = glob2Pos,
            radius = width * 0.7f
        )
        
        // Subtle Vignette
        drawRect(
            brush = Brush.radialGradient(
                colors = listOf(Color.Transparent, Obsidian.copy(alpha = 0.6f)),
                center = center,
                radius = width
            )
        )
    }
}
