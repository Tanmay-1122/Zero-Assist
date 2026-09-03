package com.zeroclaw.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

val FullShape = RoundedCornerShape(50.dp)

val AppShapes =
    Shapes(
        extraSmall = RoundedCornerShape(6.dp),
        small = RoundedCornerShape(8.dp),
        medium = RoundedCornerShape(12.dp),
        large = RoundedCornerShape(16.dp),
        extraLarge = RoundedCornerShape(20.dp),
    )

val ZeroAssistShapes = AppShapes
val ZeroAssistFullShape = FullShape
