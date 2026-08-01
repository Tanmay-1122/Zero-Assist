/*
 * Ported from https://github.com/Jakubantalik/thinking-orbs (MIT License)
 * into the Zero-Assist package.
 */

package com.zeroclaw.android.ui.screen.terminal.orb

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive

/**
 * An animated, dotted "thinking" indicator whose motion reflects [state]:
 *  - [OrbState.WORKING]   → orbiting particles
 *  - [OrbState.SEARCHING] → scanning globe
 *  - [OrbState.SOLVING]   → twisting Rubik's-cube lattice
 *  - [OrbState.LISTENING] → rolling waveform
 *  - [OrbState.COMPOSING] → undulating ribbon
 *  - [OrbState.SHAPING]   → morphing dotted outline
 *
 * All modes are drawn as plain z-sorted filled circles — no gradients, no blurs —
 * so they render identically everywhere Compose does.
 *
 * @param state   Which animated mode to show.
 * @param orbSize [OrbSize.LARGE] (64dp) or [OrbSize.SMALL] (20dp); each has its own hand-tuned preset.
 * @param theme   [OrbTheme.AUTO] (default) follows the system theme; [OrbTheme.DARK]/[OrbTheme.LIGHT] pin it.
 * @param speed   Multiplies the animation clock; 1 = tuned default.
 * @param paused  Freezes the animation on its current frame.
 * @param contentDescription Accessibility label; defaults to `state`'s label (e.g. "Working").
 * @param modifier Standard Compose modifier. Do not add your own `.size(...)` — [orbSize] controls it.
 */
@Composable
fun ThinkingOrb(
    state: OrbState = OrbState.WORKING,
    orbSize: OrbSize = OrbSize.LARGE,
    theme: OrbTheme = OrbTheme.AUTO,
    speed: Float = 1f,
    paused: Boolean = false,
    contentDescription: String? = null,
    modifier: Modifier = Modifier,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (theme) {
        OrbTheme.DARK  -> true
        OrbTheme.LIGHT -> false
        OrbTheme.AUTO  -> systemDark
    }

    // Nearest Android equivalent of `prefers-reduced-motion`: the user has turned off system
    // animations under Settings > Accessibility > Remove animations.
    val context = LocalContext.current
    val reducedMotion = remember {
        try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        } catch (e: Exception) {
            false
        }
    }

    val resolved = remember(state, orbSize) { resolvePreset(state, orbSize) }
    val draw = MODE_DRAWS.getValue(resolved.mode)
    val effSpeed = resolved.speed * speed

    var timeSec by remember { mutableStateOf(if (reducedMotion) 0.6f else 0f) }
    // Read via rememberUpdatedState so the long-running frame loop always sees the latest
    // `paused` value without needing to restart.
    val latestPaused = rememberUpdatedState(paused)

    LaunchedEffect(effSpeed, reducedMotion) {
        if (reducedMotion) return@LaunchedEffect
        while (isActive) {
            withFrameNanos { nanos ->
                if (!latestPaused.value) {
                    timeSec = (nanos / 1_000_000_000.0 * effSpeed).toFloat()
                }
            }
        }
    }

    val a11yLabel = contentDescription ?: state.label

    Canvas(
        modifier = modifier
            .size(orbSize.px.dp)
            .semantics { this.contentDescription = a11yLabel },
    ) {
        // The tuned formulas assume a logical unit equal to `orbSize.px` (64 or 20),
        // independent of actual device pixel density. Scale the coordinate system to fill
        // whatever pixel area this Canvas actually occupies.
        val logical = orbSize.px.toFloat()
        val scaleFactor = this.size.width / logical
        scale(scaleFactor, pivot = Offset.Zero) {
            draw(this, logical, timeSec, dark, resolved.opts)
        }
    }
}
