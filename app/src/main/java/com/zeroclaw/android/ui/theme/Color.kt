/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.theme

import androidx.compose.ui.graphics.Color

// Premium Glow Palettes
/** Primary glow green color used for emphasis and highlights. */
val GlowGreen = Color(0xFF00FF9D)
/** Variant of glow green with reduced brightness. */
val GlowGreenVariant = Color(0xFF00E68E)
/** Neon blue accent color for secondary highlights. */
val NeonBlue = Color(0xFF00D1FF)
/** Deep indigo color used in the dark theme palette. */
val DeepIndigo = Color(0xFF12122b)
/** Obsidian black base color for dark backgrounds. */
val Obsidian = Color(0xFF09090F)

// Glass Variants (Translucent)
/** Translucent white for glass morphism effects. */
val GlassWhite = Color(0x33FFFFFF)
/** Translucent obsidian for glass morphism backgrounds. */
val GlassObsidian = Color(0xB309090F)
/** Translucent indigo for glass morphism overlays. */
val GlassIndigo = Color(0x8012122B)

// Functional Glows
/** Success state indicator color (green glow). */
val SuccessGlow = Color(0xFF00FF9D)
/** Warning state indicator color (golden glow). */
val WarningGlow = Color(0xFFFFB800)
/** Error state indicator color (red glow). */
val ErrorGlow = Color(0xFFFF4D4D)

/** Dark theme primary color. */
val DarkPrimary = GlowGreen
/** Dark theme on-primary text/foreground color. */
val DarkOnPrimary = Color(0xFF003920)
/** Dark theme primary container background. */
val DarkPrimaryContainer = Color(0xFF005230)
/** Dark theme text on primary container. */
val DarkOnPrimaryContainer = Color(0xFF86FFC3)

/** Dark theme secondary color. */
val DarkSecondary = NeonBlue
/** Dark theme on-secondary text/foreground color. */
val DarkOnSecondary = Color(0xFF003643)
/** Dark theme secondary container background. */
val DarkSecondaryContainer = Color(0xFF004E60)
/** Dark theme text on secondary container. */
val DarkOnSecondaryContainer = Color(0xFFB6EAFF)

/** Dark theme tertiary color. */
val DarkTertiary = Color(0xFFBFBFFF)
/** Dark theme on-tertiary text/foreground color. */
val DarkOnTertiary = Color(0xFF262677)
/** Dark theme tertiary container background. */
val DarkTertiaryContainer = Color(0xFF3D3D8F)
/** Dark theme text on tertiary container. */
val DarkOnTertiaryContainer = Color(0xFFE1E0FF)

/** Dark theme error state color. */
val DarkError = ErrorGlow
/** Dark theme on-error text/foreground color. */
val DarkOnError = Color(0xFF690005)
/** Dark theme error container background. */
val DarkErrorContainer = Color(0xFF93000A)
/** Dark theme text on error container. */
val DarkOnErrorContainer = Color(0xFFFFDAD6)

/** Dark theme background color. */
val DarkBackground = Obsidian
/** Dark theme on-background text/foreground color. */
val DarkOnBackground = Color(0xFFE3E2E6)
/** Dark theme surface color. */
val DarkSurface = Obsidian
/** Dark theme on-surface text/foreground color. */
val DarkOnSurface = Color(0xFFE3E2E6)
/** Dark theme surface variant for secondary surfaces. */
val DarkSurfaceVariant = Color(0xFF44474E)
/** Dark theme on-surface variant text/foreground color. */
val DarkOnSurfaceVariant = Color(0xFFC4C6D0)
/** Dark theme outline color for borders. */
val DarkOutline = Color(0xFF8E9199)

// Light Theme (Optional, but let's provide a consistent fallback)
/** Light theme primary color. */
val LightPrimary = Color(0xFF006D42)
/** Light theme on-primary text/foreground color. */
val LightOnPrimary = Color(0xFFFFFFFF)
/** Light theme primary container background. */
val LightPrimaryContainer = Color(0xFF86FFC3)
/** Light theme text on primary container. */
val LightOnPrimaryContainer = Color(0xFF002111)

/** Light theme secondary color. */
val LightSecondary = Color(0xFF00687B)
/** Light theme on-secondary text/foreground color. */
val LightOnSecondary = Color(0xFFFFFFFF)
/** Light theme secondary container background. */
val LightSecondaryContainer = Color(0xFFB6EAFF)
/** Light theme text on secondary container. */
val LightOnSecondaryContainer = Color(0xFF001F26)
