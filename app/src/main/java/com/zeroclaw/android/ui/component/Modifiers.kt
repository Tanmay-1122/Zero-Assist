/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.component

import android.graphics.BlurMaskFilter
import android.os.Build
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Applies a glassmorphic blur effect to the background of the composable.
 * 
 * Note: Requires API 31+ for full Blur Effect support.
 */
fun Modifier.glassmorphic(
    blur: Dp = 16.dp,
    color: Color = Color.White.copy(alpha = 0.1f)
): Modifier = this.then(
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        Modifier
            .graphicsLayer {
                renderEffect = android.graphics.RenderEffect.createBlurEffect(
                    blur.toPx(),
                    blur.toPx(),
                    android.graphics.Shader.TileMode.CLAMP
                ).asComposeRenderEffect()
                clip = true
            }
            .drawBehind {
                drawRect(color)
            }
    } else {
        // Fallback for older devices: premium multi-layer translucency to simulate depth
        Modifier.drawBehind {
            // Base layer: slightly more opaque color
            drawRect(color.copy(alpha = (color.alpha + 0.15f).coerceAtMost(1f)))
            // Noise/texture layer (simulated with a very light white overlay)
            drawRect(Color.White.copy(alpha = 0.03f))
            // Subtle top highlight
            drawLine(
                color = Color.White.copy(alpha = 0.15f),
                start = androidx.compose.ui.geometry.Offset(0f, 0f),
                end = androidx.compose.ui.geometry.Offset(size.width, 0f),
                strokeWidth = 1.dp.toPx()
            )
        }
    }
)

/**
 * Applies a premium neumorphic shadow effect.
 */
fun Modifier.neumorphic(
    elevation: Dp = 8.dp,
    color: Color = Color.Black.copy(alpha = 0.1f),
    cornerRadius: Dp = 16.dp
): Modifier = this.drawBehind {
    val shadowColor = color.toArgb()
    val transparentColor = color.copy(alpha = 0f).toArgb()

    drawIntoCanvas { canvas ->
        val paint = Paint()
        val frameworkPaint = paint.asFrameworkPaint()
        frameworkPaint.color = transparentColor
        
        // Soft outer shadow
        frameworkPaint.setShadowLayer(
            elevation.toPx(),
            elevation.toPx() / 2,
            elevation.toPx() / 2,
            shadowColor
        )
        
        canvas.drawRoundRect(
            0f,
            0f,
            size.width,
            size.height,
            cornerRadius.toPx(),
            cornerRadius.toPx(),
            paint
        )
    }
}
