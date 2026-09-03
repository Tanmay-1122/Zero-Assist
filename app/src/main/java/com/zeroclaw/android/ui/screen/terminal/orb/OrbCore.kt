/*
 * Ported from https://github.com/Jakubantalik/thinking-orbs (MIT License)
 * into the Zero-Assist package.
 */

package com.zeroclaw.android.ui.screen.terminal.orb

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A single point in the orb's point-cloud, in screen (already-projected) space.
 *
 * @param x screen x
 * @param y screen y
 * @param z depth after projection (used for painter's-algorithm sort + shading), not a screen coord
 * @param r radius in the same logical unit as the canvas size
 * @param white 0..1 "how light" this dot is before dark/light inversion
 * @param a alpha, 0..1
 */
data class Dot(
    val x: Float,
    val y: Float,
    val z: Float,
    val r: Float,
    val white: Float,
    val a: Float = 1f
)

/** A 3D -> 2D projector: (x, y, z) in orb-local space -> (screenX, screenY, depth). */
typealias Projector = (Float, Float, Float) -> Triple<Float, Float, Float>

/** Cheap deterministic 2D hash -> [0, 1). Mirrors the JS `sin`-based hash used for jitter/seeding. */
fun hashD(a: Float, b: Float): Float {
    val h = sin(a * 12.9898f + b * 78.233f) * 43758.5453f
    return h - floor(h)
}

/** Point `i` of `n` on a unit sphere via the Fibonacci lattice. */
fun fibDir(i: Int, n: Int): Triple<Float, Float, Float> {
    val golden = Math.PI.toFloat() * (3f - kotlin.math.sqrt(5f))
    val y = 1f - (2f * (i + 0.5f)) / n
    val rad = kotlin.math.sqrt(max(0f, 1f - y * y))
    val a = i * golden
    return Triple(rad * cos(a), y, rad * sin(a))
}

/** Signed shortest angular distance from `b` to `a`, in (-PI, PI]. */
fun angleDelta(a: Float, b: Float): Float = atan2(sin(a - b), cos(a - b))

/**
 * Builds a yaw/tilt orthographic-ish projector centered at ([cx], [cy]) with the given [scale].
 * Mirrors the original's `makeProj`: rotate around Y (yaw) then X (tilt), then flip Y for screen space.
 */
fun makeProj(yaw: Float, tilt: Float, cx: Float, cy: Float, scale: Float): Projector {
    val st = sin(tilt)
    val ct = cos(tilt)
    val sy = sin(yaw)
    val cyw = cos(yaw)
    return proj@{ x, y, z ->
        val x1 = x * cyw + z * sy
        val z1 = -x * sy + z * cyw
        val y1 = y * ct - z1 * st
        val z2 = y * st + z1 * ct
        Triple(cx + x1 * scale, cy - y1 * scale, z2)
    }
}

/** Size-responsive radius multiplier, tuned around a 300-unit baseline. */
fun radiusScale(size: Float, pow: Float): Float = (size / 300f).pow(pow)

/**
 * Paints [dots] onto [ctx] back-to-front (painter's algorithm on `z`), inverting brightness for
 * [dark] mode. Dots with alpha below the visibility threshold are skipped entirely.
 */
fun paint(ctx: DrawScope, dots: MutableList<Dot>, dark: Boolean, rMin: Float = 0.3f) {
    dots.sortBy { it.z }
    for (d in dots) {
        val alpha = d.a
        if (alpha < 0.02f) continue
        val w = d.white.coerceIn(0f, 1f)
        val g = (((if (dark) 1f - w else w) * 255f).roundToInt()).coerceIn(0, 255)
        val a255 = ((alpha.coerceIn(0f, 1f)) * 255f).roundToInt().coerceIn(0, 255)
        ctx.drawCircle(
            color = Color(g, g, g, a255),
            radius = max(rMin, d.r),
            center = Offset(d.x, d.y)
        )
    }
}

/** Reads a numeric option out of a [ModeOpts] map, falling back to [default]. */
fun ModeOpts.f(key: String, default: Float): Float = this[key] ?: default

/** Options bag for a draw mode: option name -> numeric value. */
typealias ModeOpts = Map<String, Float>
