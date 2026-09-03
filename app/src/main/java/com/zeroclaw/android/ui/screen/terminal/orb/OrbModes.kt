/*
 * Ported from https://github.com/Jakubantalik/thinking-orbs (MIT License)
 * into the Zero-Assist package.
 */

package com.zeroclaw.android.ui.screen.terminal.orb

import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Draws one frame of a mode: canvas [ctx], logical [size], elapsed seconds [t], [dark] theme, tuned [opts]. */
typealias ModeDraw = (ctx: DrawScope, size: Float, t: Float, dark: Boolean, opts: ModeOpts) -> Unit

private const val TAU = (PI * 2).toFloat()

// ============================================================================================
// Orbits — particles on tilted orbits — the "working" state.
// ============================================================================================

val drawOrbits: ModeDraw = { ctx, size, t, dark, o ->
    val cx = size / 2f
    val cy = size / 2f
    val R = (size / 2f) * 0.82f
    val pt = makeProj(t * 0.12f, 0.3f, cx, cy, 1f)
    val rs = radiusScale(size, o.f("rsPow", 0.6f))

    val dots = mutableListOf<Dot>()
    val orbitN = o.f("orbitN", 12f).toInt()
    val ghostN = o.f("ghostN", 40f).toInt()
    val particles = o.f("particles", 3f).toInt()

    for (orb in 0 until orbitN) {
        val h1 = hashD(orb.toFloat(), 1.7f)
        val h2 = hashD(orb.toFloat(), 5.2f)
        val h3 = hashD(orb.toFloat(), 8.9f)
        val ro = R * (0.45f + 0.52f * h1)
        val th = h1 * TAU
        val phi = acos(2f * h2 - 1f)
        val nx = sin(phi) * cos(th)
        val ny = cos(phi)
        val nz = sin(phi) * sin(th)
        var ux = -ny
        var uy = nx
        val uz = 0f
        val ul = max(1e-6f, sqrt(ux * ux + uy * uy))
        ux /= ul
        uy /= ul
        val vx = ny * uz - nz * uy
        val vy = nz * ux - nx * uz
        val vz = nx * uy - ny * ux
        val speed = (0.25f + 0.55f * h3) * (if (h3 > 0.5f) 1f else -1f)

        // ghost path
        for (k in 0 until ghostN) {
            val a = (k.toFloat() / ghostN) * TAU
            val (px, py, z) = pt(
                (ux * cos(a) + vx * sin(a)) * ro,
                (uy * cos(a) + vy * sin(a)) * ro,
                (uz * cos(a) + vz * sin(a)) * ro
            )
            val depth = (z / ro + 1f) / 2f
            dots.add(
                Dot(
                    x = px, y = py, z = z,
                    r = o.f("ghostR", 0.9f) * rs,
                    white = 0.72f,
                    a = o.f("ghostA", 0.5f) * (0.4f + 0.6f * depth)
                )
            )
        }
        // the particles doing the work
        for (m in 0 until particles) {
            val a = t * speed + (m.toFloat() / particles) * TAU + h2 * 6f
            val (px, py, z) = pt(
                (ux * cos(a) + vx * sin(a)) * ro,
                (uy * cos(a) + vy * sin(a)) * ro,
                (uz * cos(a) + vz * sin(a)) * ro
            )
            val depth = (z / ro + 1f) / 2f
            dots.add(
                Dot(
                    x = px, y = py, z = z,
                    r = (o.f("partR", 1.2f) + o.f("partRDepth", 1.6f) * depth) * rs,
                    white = 0.3f - 0.22f * depth
                )
            )
        }
    }
    paint(ctx, dots, dark, o.f("rMin", 0.3f))
}

// ============================================================================================
// Shared rubik "solver heartbeat"
// ============================================================================================

private data class Move(val axis: Int, val lo: Float, val hi: Float, val ang: Float)

private data class SolveCycle(val amount: FloatArray, val active: Int)

private fun solveCycle(time: Float, count: Int, slotDur: Float, rest: Float): SolveCycle {
    val cyc = 2 * count * slotDur + rest
    val tc = time % cyc
    val amount = FloatArray(count)
    var active = -1
    if (tc < 2 * count * slotDur) {
        val slot = (tc / slotDur).toInt()
        val p = (tc - slot * slotDur) / slotDur
        val cl = min(1f, p / 0.7f)
        val ep = 1f - (1f - cl).pow(3)
        if (slot < count) {
            for (i in 0 until slot) amount[i] = 1f
            amount[slot] = ep
            active = slot
        } else {
            val u = 2 * count - 1 - slot
            for (i in 0 until u) amount[i] = 1f
            amount[u] = 1f - ep
            active = u
        }
    }
    return SolveCycle(amount, active)
}

private data class MoveResult(val x: Float, val y: Float, val z: Float, val inActive: Boolean)

private fun applyMoves(px: Float, py: Float, pz: Float, moves: List<Move>, sc: SolveCycle): MoveResult {
    var x = px
    var y = py
    var z = pz
    var inActive = false
    for (i in moves.indices) {
        if (sc.amount[i] <= 0f) continue
        val mv = moves[i]
        val coord = when (mv.axis) { 0 -> x; 1 -> y; else -> z }
        if (coord < mv.lo || coord >= mv.hi) continue
        if (i == sc.active) inActive = true
        val a = mv.ang * sc.amount[i]
        val ca = cos(a)
        val sa = sin(a)
        when (mv.axis) {
            0 -> {
                val y2 = y * ca - z * sa
                z = y * sa + z * ca
                y = y2
            }
            1 -> {
                val x2 = x * ca + z * sa
                z = -x * sa + z * ca
                x = x2
            }
            else -> {
                val x2 = x * ca - y * sa
                y = x * sa + y * ca
                x = x2
            }
        }
    }
    return MoveResult(x, y, z, inActive)
}

private fun makeMoves(count: Int): List<Move> {
    val moves = mutableListOf<Move>()
    for (i in 0 until count) {
        val axis = min(2, (hashD(i.toFloat(), 2.3f) * 3f).toInt())
        val lo = -1.0f + 0.5f * min(3, (hashD(i.toFloat(), 5.9f) * 4f).toInt())
        val dir = if (hashD(i.toFloat(), 7.7f) < 0.5f) 1f else -1f
        moves.add(Move(axis, lo, lo + 0.5f, dir * PI.toFloat() / 2f))
    }
    return moves
}

// ============================================================================================
// Globe — lat/long field, a scan meridian sweeps — the "searching" state.
// ============================================================================================

val drawGlobe: ModeDraw = { ctx, size, t, dark, o ->
    val spin = 0.5f
    val cx = size / 2f
    val cy = size / 2f
    val radius = (size / 2f) * 0.82f
    val tilt = 0.4f + 0.06f * sin(t * 0.35f)
    val pt = makeProj(t * spin, tilt, cx, cy, radius)
    val scan = t * (spin + (1.7f - spin) * o.f("scanMul", 1f))
    val rs = radiusScale(size, o.f("rsPow", 0.6f))
    val dimBase = o.f("dimBase", 1f)

    val dots = mutableListOf<Dot>()
    val latRings = o.f("latRings", 17f).toInt()
    val lonDensity = o.f("lonDensity", 44f)
    for (li in 0..latRings) {
        val lat = -PI.toFloat() / 2f + (li.toFloat() / latRings) * PI.toFloat()
        val cosLat = cos(lat)
        val sinLat = sin(lat)
        val lonCount = max(1, (abs(cosLat) * lonDensity).roundToInt())
        for (lj in 0 until lonCount) {
            val lon = (lj.toFloat() / lonCount) * TAU
            val (px, py, z) = pt(cosLat * cos(lon), sinLat, cosLat * sin(lon))
            val depth = (z + 1f) / 2f
            val d = angleDelta(lon + t * spin, scan)
            val boost = exp(-(d * d) / 0.18f) * max(0f, z)
            dots.add(
                Dot(
                    x = px, y = py, z = z,
                    r = (o.f("rBase", 0.6f) + o.f("rDepth", 1.7f) * depth + o.f("rBoost", 1f) * boost) * rs,
                    white = o.f("inkFar", 0.62f) - o.f("inkSpan", 0.54f) * depth,
                    a = dimBase + (1f - dimBase) * min(1f, boost)
                )
            )
        }
    }
    paint(ctx, dots, dark, o.f("rMin", 0.3f))
}

// ============================================================================================
// Rubik — bands twist in quarter turns — the "solving" state.
// ============================================================================================

val drawRubik: ModeDraw = { ctx, size, t, dark, o ->
    val cx = size / 2f
    val cy = size / 2f
    val R = (size / 2f) * 0.82f
    val pt = makeProj(t * 0.55f, 0.35f + 0.1f * sin(t * 0.9f), cx, cy, R)
    val rs = radiusScale(size, o.f("rsPow", 0.6f))
    val moveCount = o.f("moveCount", 14f).toInt()
    val moves = makeMoves(moveCount)
    val sc = solveCycle(t, moveCount, 0.42f, 1.2f)

    val dots = mutableListOf<Dot>()
    val latRings = o.f("latRings", 15f).toInt()
    val lonDensity = o.f("lonDensity", 40f)
    for (li in 0..latRings) {
        val lat = -PI.toFloat() / 2f + (li.toFloat() / latRings) * PI.toFloat()
        val cosLat = cos(lat)
        val sinLat = sin(lat)
        val lonCount = max(1, (abs(cosLat) * lonDensity).roundToInt())
        for (lj in 0 until lonCount) {
            val lon = (lj.toFloat() / lonCount) * TAU
            val res = applyMoves(cosLat * cos(lon), sinLat, cosLat * sin(lon), moves, sc)
            val (px, py, zr) = pt(res.x, res.y, res.z)
            val depth = (zr + 1f) / 2f
            dots.add(
                Dot(
                    x = px, y = py, z = zr,
                    r = (o.f("rBase", 0.6f) + o.f("rDepth", 1.7f) * depth +
                        if (res.inActive) o.f("rActive", 0.3f) else 0f) * rs,
                    white = o.f("inkFar", 0.62f) - o.f("inkSpan", 0.54f) * depth -
                        if (res.inActive) 0.14f else 0f
                )
            )
        }
    }
    paint(ctx, dots, dark, o.f("rMin", 0.3f))
}

// ============================================================================================
// Wave — a waveform rolls through the rings — the "listening" state.
// ============================================================================================

val drawWave: ModeDraw = { ctx, size, t, dark, o ->
    val cx = size / 2f
    val cy = size / 2f
    val R = (size / 2f) * 0.874f
    val pt = makeProj(t * 0.18f, 0.38f, cx, cy, 1f)
    val rs = radiusScale(size, o.f("rsPow", 0.6f))

    val dots = mutableListOf<Dot>()
    val rings = o.f("rings", 15f).toInt()
    val lonDensity = o.f("lonDensity", 40f)
    for (ri in 0..rings) {
        val lat = -PI.toFloat() / 2f + (ri.toFloat() / rings) * PI.toFloat()
        val cosLat = cos(lat)
        val sinLat = sin(lat)
        val w = 0.62f * sin(t * 2.1f - ri * 0.52f) + 0.38f * sin(t * 1.27f + ri * 0.83f)
        val rr = R * (0.88f + 0.105f * w)
        val lonCount = max(1, (abs(cosLat) * lonDensity).roundToInt())
        for (lj in 0 until lonCount) {
            val lon = (lj.toFloat() / lonCount) * TAU
            val (px, py, z) = pt(cosLat * cos(lon) * rr, sinLat * rr, cosLat * sin(lon) * rr)
            val depth = (z / R + 1f) / 2f
            val crest = max(0f, w)
            dots.add(
                Dot(
                    x = px, y = py, z = z,
                    r = (o.f("rBase", 0.6f) + o.f("rDepth", 1.7f) * depth) * (1f + 0.4f * crest) * rs,
                    white = 0.66f - 0.56f * depth - 0.1f * crest
                )
            )
        }
    }
    paint(ctx, dots, dark, o.f("rMin", 0.3f))
}

// ============================================================================================
// Ribbon — undulating strands ride a great circle — the "composing" state.
// ============================================================================================

val drawRibbon: ModeDraw = { ctx, size, t, dark, o ->
    val cx = size / 2f
    val cy = size / 2f
    val R = (size / 2f) * 0.78f
    val spin = o.f("spin", 1f)
    val pt = makeProj(t * 0.1f * spin, 0.3f, cx, cy, 1f)
    val rs = radiusScale(size, o.f("rsPow", 0.6f))

    val dots = mutableListOf<Dot>()
    val ghostN = o.f("ghostN", 150f).toInt()
    for (i in 0 until ghostN) {
        val d = fibDir(i, ghostN)
        val (px, py, z) = pt(d.first * R, d.second * R, d.third * R)
        val depth = (z / R + 1f) / 2f
        dots.add(Dot(x = px, y = py, z = z, r = 0.8f * rs, white = 0.78f, a = 0.1f + 0.22f * depth))
    }

    val ya = t * 0.24f * spin
    val ta = 0.55f + 0.3f * sin(t * 0.18f) * spin
    val ux = cos(ya)
    val uy = 0f
    val uz = sin(ya)
    val vx = -uz * sin(ta)
    val vy = cos(ta)
    val vz = ux * sin(ta)
    val nx = uy * vz - uz * vy
    val ny = uz * vx - ux * vz
    val nz = ux * vy - uy * vx

    val baseLanes = o.f("lanes", 5f)
    val segs = o.f("segs", 88f).toInt()
    val lanes = max(1, (baseLanes * o.f("bandMul", 1f)).roundToInt())
    for (w in 0 until lanes) {
        val laneOff = (w - (lanes - 1) / 2f) * 0.075f
        val edge = abs(w - (lanes - 1) / 2f) / max(1f, (lanes - 1) / 2f)
        for (k in 0 until segs) {
            val a = (k.toFloat() / segs) * TAU
            val wob = (0.16f * sin(a * 3f - t * 1.7f + w * 0.22f) + 0.07f * sin(a * 5f + t * 1.1f)) *
                o.f("wobMul", 1f)
            val off = laneOff + wob
            val x = ux * cos(a) + vx * sin(a) + nx * off
            val y = uy * cos(a) + vy * sin(a) + ny * off
            val z = uz * cos(a) + vz * sin(a) + nz * off
            val l = sqrt(x * x + y * y + z * z)
            val (px, py, zr) = pt((x / l) * R, (y / l) * R, (z / l) * R)
            val depth = (zr / R + 1f) / 2f
            dots.add(
                Dot(
                    x = px, y = py, z = zr,
                    r = (o.f("rBase", 1.1f) + o.f("rDepth", 1.7f) * depth) * (1f - 0.25f * edge) * rs,
                    white = 0.52f - 0.44f * depth + 0.18f * edge,
                    a = 0.4f + 0.6f * depth
                )
            )
        }
    }
    paint(ctx, dots, dark, o.f("rMin", 0.3f))
}

// ============================================================================================
// Morph — dotted outline cycles circle -> triangle -> square — the "shaping" state.
// ============================================================================================

private typealias OrbPath = (Float) -> Pair<Float, Float>

private fun smoothE(x: Float): Float = x * x * (3f - 2f * x)

private fun polyPath(verts: List<Pair<Float, Float>>): OrbPath {
    val v = verts.size
    val lengths = FloatArray(v)
    var total = 0f
    for (i in 0 until v) {
        val a = verts[i]
        val b = verts[(i + 1) % v]
        val l = hypot(b.first - a.first, b.second - a.second)
        lengths[i] = l
        total += l
    }
    return { f ->
        var target = f * total
        var i = 0
        while (target > lengths[i] && i < v - 1) {
            target -= lengths[i]
            i++
        }
        val a = verts[i]
        val b = verts[(i + 1) % v]
        val ff = if (lengths[i] != 0f) min(1f, target / lengths[i]) else 0f
        Pair(a.first + (b.first - a.first) * ff, a.second + (b.second - a.second) * ff)
    }
}

private val CIRCLE: OrbPath = { f ->
    val a = -PI.toFloat() / 2f + f * TAU
    Pair(cos(a) * 0.24f, sin(a) * 0.24f)
}
private val TRIANGLE: OrbPath = polyPath(
    listOf(Pair(0.0f, -0.26f), Pair(0.24f, 0.16f), Pair(-0.24f, 0.16f))
)
private val SQUARE: OrbPath = polyPath(
    listOf(
        Pair(0f, -0.2f),
        Pair(0.2f, -0.2f),
        Pair(0.2f, 0.2f),
        Pair(-0.2f, 0.2f),
        Pair(-0.2f, -0.2f)
    )
)
private val CYCLE: List<OrbPath> = listOf(CIRCLE, TRIANGLE, SQUARE)

private fun morphN(d: Float): Int = max(6, (34 * d).roundToInt())

private const val HOLD = 1.4f
private const val MORPH_DUR = 0.9f
private const val SEG = HOLD + MORPH_DUR

val drawMorph: ModeDraw = { ctx, size, t, dark, o ->
    val k4 = CYCLE.size
    val tc = t % (SEG * k4)
    val k = (tc / SEG).toInt()
    val local = tc - k * SEG
    val m = if (local > HOLD) smoothE((local - HOLD) / MORPH_DUR) else 0f
    val sprd = o.f("spread", 1f)

    val pA = CYCLE[k]
    val pB = CYCLE[(k + 1) % k4]
    val M = 160
    val pts = Array(M) { i ->
        val f = i.toFloat() / M
        val a = pA(f)
        val b = pB(f)
        Pair((a.first + (b.first - a.first) * m) * sprd, (a.second + (b.second - a.second) * m) * sprd)
    }
    val lengths = FloatArray(M)
    var total = 0f
    for (i in 0 until M) {
        val a = pts[i]
        val b = pts[(i + 1) % M]
        val l = hypot(b.first - a.first, b.second - a.second)
        lengths[i] = l
        total += l
    }

    val n = morphN(o.f("iconD", 1f))
    val re = o.f("rDot", 0.021f) * 1.35f * sprd
    val pulse = 1f + 0.02f * sin(local * 3.1f)

    val dots = mutableListOf<Dot>()
    val c2 = size / 2f
    var seg = 0
    var acc = 0f
    for (k2 in 0 until n) {
        val target = (k2.toFloat() / n) * total
        while (acc + lengths[seg] < target && seg < M - 1) {
            acc += lengths[seg]
            seg++
        }
        val a = pts[seg]
        val b = pts[(seg + 1) % M]
        val f = if (lengths[seg] != 0f) min(1f, (target - acc) / lengths[seg]) else 0f
        val x = (a.first + (b.first - a.first) * f) * pulse
        val y = (a.second + (b.second - a.second) * f) * pulse
        dots.add(
            Dot(
                x = c2 + x * size,
                y = c2 + y * size,
                z = 0f,
                r = max(0.35f, re * size),
                white = 0.1f
            )
        )
    }
    paint(ctx, dots, dark, o.f("rMin", 0.3f))
}

/** Registry: [ModeKey] -> its draw function. */
val MODE_DRAWS: Map<ModeKey, ModeDraw> = mapOf(
    ModeKey.ORBITS to drawOrbits,
    ModeKey.GLOBE to drawGlobe,
    ModeKey.RUBIK to drawRubik,
    ModeKey.WAVE to drawWave,
    ModeKey.RIBBON to drawRibbon,
    ModeKey.MORPH to drawMorph
)
