/*
 * Copyright 2026 ZeroClaw Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.navigation.TopLevelDestination
import com.zeroclaw.android.ui.theme.GlowGreen
import com.zeroclaw.android.ui.theme.GlassWhite

/**
 * A premium floating pill-style navigation bar with glassmorphism.
 */
@Composable
fun FloatingPillNavigationBar(
    selectedDestination: TopLevelDestination?,
    onNavigate: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 20.dp)
            .height(64.dp)
            .glassmorphic(blur = 20.dp, color = GlassWhite)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.05f)),
        contentAlignment = Alignment.Center
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TopLevelDestination.entries.forEach { destination ->
                val isSelected = selectedDestination == destination
                
                NavPillItem(
                    destination = destination,
                    isSelected = isSelected,
                    onClick = { onNavigate(destination) }
                )
            }
        }
    }
}

@Composable
private fun NavPillItem(
    destination: TopLevelDestination,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val iconSize by animateFloatAsState(
        targetValue = if (isSelected) 28f else 24f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "IconSize"
    )

    val color by animateColorAsState(
        targetValue = if (isSelected) GlowGreen else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
        label = "IconColor"
    )

    Box(
        modifier = Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isSelected) destination.selectedIcon else destination.unselectedIcon,
            contentDescription = destination.label,
            tint = color,
            modifier = Modifier.size(iconSize.dp)
        )
    }
}
