/*
 * Ported from https://github.com/Jakubantalik/thinking-orbs (MIT License)
 * into the Zero-Assist package.
 */

package com.zeroclaw.android.ui.screen.terminal.orb

/** Which animated state the orb reflects; each maps to one draw mode + tuned preset. */
enum class OrbState(val label: String) {
    WORKING("Working"),
    SEARCHING("Searching"),
    SOLVING("Solving"),
    LISTENING("Listening"),
    COMPOSING("Composing"),
    SHAPING("Shaping")
}

/** The two hand-tuned sizes. */
enum class OrbSize(val px: Int) {
    /** 64dp – an avatar-scale orb. */
    LARGE(64),
    /** 20dp – an inline / adjacent-to-text orb. */
    SMALL(20)
}

/** Theme override. [AUTO] follows the system light/dark setting live. */
enum class OrbTheme { AUTO, DARK, LIGHT }

/** Which draw mode each [OrbState] uses. */
val STATE_TO_MODE: Map<OrbState, ModeKey> = mapOf(
    OrbState.WORKING   to ModeKey.ORBITS,
    OrbState.SEARCHING to ModeKey.GLOBE,
    OrbState.SOLVING   to ModeKey.RUBIK,
    OrbState.LISTENING to ModeKey.WAVE,
    OrbState.COMPOSING to ModeKey.RIBBON,
    OrbState.SHAPING   to ModeKey.MORPH
)

/** Per-mode size/count/speed multiplier pair. */
data class Preset(
    val speed: Float,
    val count: Float,
    val size: Float,
    val extra: ModeOpts? = null
)

private val PRESETS: Map<ModeKey, Map<OrbSize, Preset>> = mapOf(
    ModeKey.ORBITS to mapOf(
        OrbSize.LARGE to Preset(speed = 1.885f, count = 1f, size = 1f),
        OrbSize.SMALL to Preset(speed = 3.9f,   count = 0.238f, size = 2.4f)
    ),
    ModeKey.GLOBE to mapOf(
        OrbSize.LARGE to Preset(speed = 2.015f, count = 0.42f,  size = 1.15f,
            extra = mapOf("scanMul" to 4.08f, "dimBase" to 0.45f)),
        OrbSize.SMALL to Preset(speed = 2.665f, count = 0.105f, size = 1.75f,
            extra = mapOf("scanMul" to 4.335f, "dimBase" to 0.45f))
    ),
    ModeKey.RUBIK to mapOf(
        OrbSize.LARGE to Preset(speed = 1.82f, count = 0.35f,  size = 1.05f),
        OrbSize.SMALL to Preset(speed = 1.95f, count = 0.088f, size = 1.9f)
    ),
    ModeKey.WAVE to mapOf(
        OrbSize.LARGE to Preset(speed = 4.388f, count = 0.341f, size = 1f),
        OrbSize.SMALL to Preset(speed = 3.998f, count = 0.105f, size = 1.6f)
    ),
    ModeKey.RIBBON to mapOf(
        OrbSize.LARGE to Preset(speed = 2.34f,  count = 0.25f,  size = 0.85f,
            extra = mapOf("spin" to 0f, "bandMul" to 3.9f, "wobMul" to 1f)),
        OrbSize.SMALL to Preset(speed = 3.12f,  count = 0.051f, size = 1.073f,
            extra = mapOf("spin" to 0f, "bandMul" to 4.94f, "wobMul" to 1f))
    ),
    ModeKey.MORPH to mapOf(
        OrbSize.LARGE to Preset(speed = 2.405f, count = 0.54f,  size = 0.395f,
            extra = mapOf("spread" to 1.45f)),
        OrbSize.SMALL to Preset(speed = 2.08f,  count = 0.53f,  size = 1.011f,
            extra = mapOf("spread" to 1.45f))
    )
)

/** A resolved (state, size) pair: which mode to draw, at what clock speed, with which options. */
data class Resolved(val mode: ModeKey, val speed: Float, val opts: ModeOpts)

private val resolveCache = mutableMapOf<String, Resolved>()

/** Resolves a (state, size) pair to its mode + fully-scaled draw options. Cached after first call. */
fun resolvePreset(state: OrbState, size: OrbSize): Resolved {
    val key = "$state-$size"
    resolveCache[key]?.let { return it }

    val mode = STATE_TO_MODE.getValue(state)
    val preset = PRESETS.getValue(mode).getValue(size)
    var opts: ModeOpts = BASE_PROFILES.getValue(mode)
    if (preset.count != 1f) opts = scaleCounts(opts, preset.count)
    if (preset.size != 1f) opts = scaleRadii(opts, preset.size)
    if (preset.extra != null) opts = opts + preset.extra

    val resolved = Resolved(mode, preset.speed, opts)
    resolveCache[key] = resolved
    return resolved
}
