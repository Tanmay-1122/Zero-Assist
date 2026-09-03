/*
 * Ported from https://github.com/Jakubantalik/thinking-orbs (MIT License)
 * into the Zero-Assist package.
 */

package com.zeroclaw.android.ui.screen.terminal.orb

import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Which draw mode a given [OrbState] uses. */
enum class ModeKey { ORBITS, GLOBE, RUBIK, WAVE, RIBBON, MORPH }

private val COUNT_PAIRS: List<Pair<String, String>> = listOf(
    "latRings" to "lonDensity",
    "rings" to "lonDensity",
    "lanes" to "segs"
)
private val COUNT_KEYS = listOf("orbitN", "ghostN")
private val ICON_DENSITY_KEYS = listOf("iconD")
private val RADIUS_KEYS = listOf("rBase", "rDepth", "rActive", "rDot", "ghostR", "partR", "partRDepth")

/** Scales dot counts by [scale], preserving lattice pairing. */
fun scaleCounts(opts: ModeOpts, scale: Float): ModeOpts {
    val out = opts.toMutableMap()
    val done = mutableSetOf<String>()
    val rt = sqrt(scale)
    for ((a, b) in COUNT_PAIRS) {
        val va = out[a]
        val vb = out[b]
        if (va != null && vb != null && a !in done && b !in done) {
            out[a] = max(2f, (va * rt).roundToInt().toFloat())
            out[b] = max(2f, (vb * rt).roundToInt().toFloat())
            done += a
            done += b
        }
    }
    for (k in COUNT_KEYS) {
        val v = out[k]
        if (v != null && k !in done) out[k] = max(1f, (v * scale).roundToInt().toFloat())
    }
    for (k in ICON_DENSITY_KEYS) {
        val v = out[k]
        if (v != null) out[k] = max(0.02f, v * scale)
    }
    return out
}

/** Scales every radius-controlling key by [scale]. */
fun scaleRadii(opts: ModeOpts, scale: Float): ModeOpts {
    val out = opts.toMutableMap()
    for (k in RADIUS_KEYS) {
        val v = out[k]
        if (v != null) out[k] = v * scale
    }
    out["rSizeMul"] = (out["rSizeMul"] ?: 1f) * scale
    return out
}

/** Base ("fine") profiles per mode, before preset multipliers are applied. */
val BASE_PROFILES: Map<ModeKey, ModeOpts> = mapOf(
    ModeKey.GLOBE to mapOf(
        "latRings" to 17f,
        "lonDensity" to 44f,
        "rBase" to 0.6f,
        "rDepth" to 1.7f,
        "rBoost" to 1.0f,
        "inkFar" to 0.62f,
        "inkSpan" to 0.54f,
        "rsPow" to 0.6f,
        "rMin" to 0.3f
    ),
    ModeKey.ORBITS to mapOf(
        "orbitN" to 12f,
        "ghostN" to 40f,
        "ghostR" to 0.9f,
        "ghostA" to 0.5f,
        "particles" to 3f,
        "partR" to 1.2f,
        "partRDepth" to 1.6f,
        "rsPow" to 0.6f,
        "rMin" to 0.3f
    ),
    ModeKey.RUBIK to mapOf(
        "latRings" to 15f,
        "lonDensity" to 40f,
        "moveCount" to 14f,
        "rBase" to 0.6f,
        "rDepth" to 1.7f,
        "rActive" to 0.3f,
        "inkFar" to 0.62f,
        "inkSpan" to 0.54f,
        "rsPow" to 0.6f,
        "rMin" to 0.3f
    ),
    ModeKey.WAVE to mapOf(
        "rings" to 15f,
        "lonDensity" to 40f,
        "rBase" to 0.6f,
        "rDepth" to 1.7f,
        "rsPow" to 0.6f,
        "rMin" to 0.3f
    ),
    ModeKey.RIBBON to mapOf(
        "lanes" to 5f,
        "segs" to 88f,
        "ghostN" to 150f,
        "rBase" to 1.1f,
        "rDepth" to 1.7f,
        "rsPow" to 0.6f,
        "rMin" to 0.3f
    ),
    ModeKey.MORPH to mapOf(
        "rDot" to 0.021f,
        "iconD" to 1f,
        "rMin" to 0.25f
    )
)
