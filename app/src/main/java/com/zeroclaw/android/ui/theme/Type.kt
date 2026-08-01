package com.zeroclaw.android.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.zeroclaw.android.R

val InterFamily = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
)

val JetBrainsMonoFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold, FontWeight.Bold),
)

/** General UI typography using platform sans-serif for clean, modern readability. */
val AppTypography =
    Typography(
        displayLarge =
            TextStyle(
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 32.sp,
                lineHeight = 40.sp,
            ),
        displayMedium =
            TextStyle(
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 28.sp,
                lineHeight = 36.sp,
            ),
        displaySmall =
            TextStyle(
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 24.sp,
                lineHeight = 32.sp,
            ),
        headlineLarge =
            TextStyle(
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 22.sp,
                lineHeight = 28.sp,
            ),
        headlineMedium =
            TextStyle(
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                lineHeight = 26.sp,
            ),
        headlineSmall =
            TextStyle(
                fontFamily = InterFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                lineHeight = 22.sp,
            ),
        titleLarge =
            TextStyle(
                fontFamily = InterFamily,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                lineHeight = 26.sp,
            ),
        titleMedium =
            TextStyle(
                fontFamily = InterFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 16.sp,
                lineHeight = 22.sp,
            ),
        titleSmall =
            TextStyle(
                fontFamily = InterFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
        bodyLarge =
            TextStyle(
                fontFamily = InterFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 15.sp,
                lineHeight = 24.sp,
            ),
        bodyMedium =
            TextStyle(
                fontFamily = InterFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 14.sp,
                lineHeight = 20.sp,
            ),
        bodySmall =
            TextStyle(
                fontFamily = InterFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                lineHeight = 18.sp,
            ),
        labelLarge =
            TextStyle(
                fontFamily = InterFamily,
                fontWeight = FontWeight.SemiBold,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                letterSpacing = 0.sp,
            ),
        labelMedium =
            TextStyle(
                fontFamily = InterFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                lineHeight = 16.sp,
                letterSpacing = 0.sp,
            ),
        labelSmall =
            TextStyle(
                fontFamily = InterFamily,
                fontWeight = FontWeight.Normal,
                fontSize = 11.sp,
                lineHeight = 14.sp,
                letterSpacing = 0.2.sp,
            ),
    )

/** General UI typography using platform sans-serif. */
val ZeroAssistTypography = AppTypography

/** Terminal / code typography using JetBrains Mono for monospace contexts. */
val TerminalTypography =
    Typography(
        displayLarge = AppTypography.displayLarge.copy(fontFamily = JetBrainsMonoFamily),
        displayMedium = AppTypography.displayMedium.copy(fontFamily = JetBrainsMonoFamily),
        displaySmall = AppTypography.displaySmall.copy(fontFamily = JetBrainsMonoFamily),
        headlineLarge = AppTypography.headlineLarge.copy(fontFamily = JetBrainsMonoFamily),
        headlineMedium = AppTypography.headlineMedium.copy(fontFamily = JetBrainsMonoFamily),
        headlineSmall = AppTypography.headlineSmall.copy(fontFamily = JetBrainsMonoFamily),
        titleLarge = AppTypography.titleLarge.copy(fontFamily = JetBrainsMonoFamily),
        titleMedium = AppTypography.titleMedium.copy(fontFamily = JetBrainsMonoFamily),
        titleSmall = AppTypography.titleSmall.copy(fontFamily = JetBrainsMonoFamily),
        bodyLarge = AppTypography.bodyLarge.copy(fontFamily = JetBrainsMonoFamily),
        bodyMedium = AppTypography.bodyMedium.copy(fontFamily = JetBrainsMonoFamily),
        bodySmall = AppTypography.bodySmall.copy(fontFamily = JetBrainsMonoFamily),
        labelLarge = AppTypography.labelLarge.copy(fontFamily = JetBrainsMonoFamily),
        labelMedium = AppTypography.labelMedium.copy(fontFamily = JetBrainsMonoFamily),
        labelSmall = AppTypography.labelSmall.copy(fontFamily = JetBrainsMonoFamily),
    )
