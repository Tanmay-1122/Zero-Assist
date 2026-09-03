@file:Suppress("UndocumentedPublicProperty")

package com.zeroclaw.android.ui.theme

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// ─── Light theme palette ───
val LightBackground = Color(0xFFFAF8F5)
val LightSurface = Color(0xFFFEFEFE)
val LightSurfaceVariant = Color(0xFFECE8E3)
val LightSurfaceContainer = Color(0xFFE3DFD9)
val LightSurfaceContainerHigh = Color(0xFFDAD6D0)
val LightOnSurface = Color(0xFF1C1B1F)
val LightOnSurfaceVariant = Color(0xFF4A4540)
val LightOutline = Color(0xFFD4CEC8)
val LightOutlineVariant = Color(0xFFE4E0DB)
val IndigoPrimary = Color(0xFF4338CA)
val IndigoSecondary = Color(0xFF6366F1)
val TealTertiary = Color(0xFF0F766E)
val IndigoContainer = Color(0xFFE0E7FF)
val TealContainer = Color(0xFFCCFBF1)

// ─── Dark theme palette ───
val PureBlack = Color(0xFF0D0D0D)
val VeryDarkGray = Color(0xFF151518)
val DarkGray = Color(0xFF1E1E24)
val MediumGray = Color(0xFF33333D)
val LightGray = Color(0xFF6B6B7A)
val SilverGray = Color(0xFF9E9EAE)
val LighterGray = Color(0xFFE0E0E8)
val WhiteGray = Color(0xFFF5F5FA)
val AccentBlue = Color(0xFFB7A7FF)
val AccentBlueBright = Color(0xFFD2CAFF)
val DarkTealContainer = Color(0xFF222230)

// ─── Semantic colors ───
val AppError = Color(0xFFC62828)
val AppSuccess = Color(0xFF3DDC97)
val AppWarning = Color(0xFFF0A500)
val InlineTerminalError = Color(0xFFFF6B6B)

// ─── Glass / overlay colors ───
val GlassSurface = LightSurface.copy(alpha = 0.92f)
val GlassBackground = PureBlack.copy(alpha = 0.7f)
val GlassCard = DarkGray.copy(alpha = 0.5f)

val AppLightColorScheme =
    lightColorScheme(
        background = LightBackground,
        onBackground = LightOnSurface,
        surface = LightSurface,
        onSurface = LightOnSurface,
        surfaceVariant = LightSurfaceVariant,
        onSurfaceVariant = LightOnSurfaceVariant,
        surfaceBright = LightSurface,
        surfaceContainerLowest = LightSurface,
        surfaceContainerLow = LightBackground,
        surfaceContainer = LightSurfaceVariant,
        surfaceContainerHigh = LightSurfaceContainer,
        surfaceContainerHighest = LightSurfaceContainerHigh,
        primary = IndigoPrimary,
        onPrimary = Color.White,
        primaryContainer = IndigoContainer,
        onPrimaryContainer = LightOnSurface,
        secondary = IndigoSecondary,
        onSecondary = Color.White,
        secondaryContainer = IndigoContainer,
        onSecondaryContainer = LightOnSurface,
        tertiary = TealTertiary,
        onTertiary = Color.White,
        tertiaryContainer = TealContainer,
        onTertiaryContainer = LightOnSurface,
        outline = LightOutline,
        outlineVariant = LightOutlineVariant,
        error = Color(0xFFDC2626),
        onError = Color.White,
        errorContainer = Color(0xFFFEE2E2),
        onErrorContainer = LightOnSurface,
        inverseSurface = LightOnSurface,
        inverseOnSurface = LightBackground,
        inversePrimary = Color(0xFFA5B4FC),
        scrim = Color(0x80000000),
    )

val AppDarkColorScheme =
    darkColorScheme(
        background = PureBlack,
        onBackground = WhiteGray,
        surface = VeryDarkGray,
        onSurface = LighterGray,
        surfaceVariant = DarkGray,
        onSurfaceVariant = SilverGray,
        surfaceBright = Color(0xFF2A2A35),
        surfaceContainerLowest = PureBlack,
        surfaceContainerLow = VeryDarkGray,
        surfaceContainer = DarkGray,
        surfaceContainerHigh = MediumGray,
        surfaceContainerHighest = Color(0xFF3D3D4A),
        primary = AccentBlue,
        onPrimary = Color.White,
        primaryContainer = DarkTealContainer,
        onPrimaryContainer = LighterGray,
        secondary = Color(0xFF8EDFD4),
        onSecondary = Color(0xFF101316),
        secondaryContainer = Color(0xFF173735),
        onSecondaryContainer = LighterGray,
        tertiary = Color(0xFFE1C778),
        onTertiary = Color(0xFF15130B),
        tertiaryContainer = Color(0xFF44391B),
        onTertiaryContainer = LighterGray,
        outline = Color(0xFF3D3D4A),
        outlineVariant = Color(0xFF2A2A35),
        error = InlineTerminalError,
        onError = Color.White,
        errorContainer = Color(0xFF3D1515),
        onErrorContainer = LighterGray,
        inverseSurface = LighterGray,
        inverseOnSurface = PureBlack,
        inversePrimary = AccentBlue.copy(alpha = 0.7f),
        scrim = Color(0xB30D0D0D),
    )
