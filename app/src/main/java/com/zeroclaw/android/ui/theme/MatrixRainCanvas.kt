package com.zeroclaw.android.ui.theme

import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.delay

private const val COLUMN_WIDTH = 20f
private const val FONT_SIZE = 14f
private const val TAIL_LENGTH = 8
private const val ALPHA_DIVISOR = 3
private const val ZERO_Y = 0
private const val DELTA_Y = 20
private const val ANIMATION_DELAY = 42L
private const val ALPHA_TRANSPARENCY = 0.03f

@Composable
fun MatrixRainCanvas(modifier: Modifier = Modifier) {
    val isDark = isSystemInDarkTheme()

    if (isDark) {
        DarkBgImage(modifier = modifier)
    } else {
        // Keep original matrix rain effect for light theme
        val chars = "01ABCXZ+-=[]<>{}".toList()
        val columns = remember { mutableStateListOf<Pair<Float, Int>>() }
        var frame by remember { mutableIntStateOf(0) }
        val primary = MaterialTheme.colorScheme.primary
        val onBg = MaterialTheme.colorScheme.onBackground

        LaunchedEffect(Unit) {
            while (true) {
                delay(ANIMATION_DELAY)
                frame++
            }
        }

        val paint = remember { Paint().apply { textSize = FONT_SIZE; typeface = Typeface.MONOSPACE } }

        Canvas(modifier = modifier) {
            if (columns.isEmpty()) {
                val colCount = (size.width / COLUMN_WIDTH).toInt()
                repeat(colCount) { i ->
                    columns.add(i * COLUMN_WIDTH to (Math.random() * size.height).toInt())
                }
            }
            paint.textSize = FONT_SIZE * density
            columns.forEachIndexed { i, (x, y) ->
                val tailLength = TAIL_LENGTH
                repeat(tailLength) { t ->
                    val alpha = (tailLength - t).toFloat() / tailLength
                    val yPos = y - (t * DELTA_Y)
                    if (yPos < 0 || yPos > size.height) return@repeat
                    paint.color = onBg.copy(alpha = alpha * ALPHA_TRANSPARENCY).toArgb()
                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.drawText(
                            chars[(frame + i + t) % chars.size].toString(),
                            x,
                            yPos.toFloat(),
                            paint,
                        )
                    }
                }
                if (frame % (ALPHA_DIVISOR + i % ALPHA_DIVISOR) == 0) {
                    columns[i] = x to if (y > size.height) ZERO_Y else y + DELTA_Y
                }
            }
        }
    }
}

@Composable
private fun DarkBgImage(modifier: Modifier = Modifier) {
    Box(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
    )
}
