package com.zeroclaw.android.ui.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zeroclaw.android.navigation.TopLevelDestination

@Composable
fun ZeroAssistBottomBar(
    selectedDestination: TopLevelDestination?,
    onNavigate: (TopLevelDestination) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 8.dp,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp)
                    .height(60.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TopLevelDestination.entries.forEach { destination ->
                val isSelected = selectedDestination == destination
                PillNavItem(
                    destination = destination,
                    isSelected = isSelected,
                    onClick = { onNavigate(destination) },
                )
            }
        }
    }
}

@Composable
private fun PillNavItem(
    destination: TopLevelDestination,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val containerColor by animateColorAsState(
        targetValue =
            if (isSelected) {
                MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
            } else {
                MaterialTheme.colorScheme.surface
            },
        label = "NavContainerColor",
    )

    val contentColor by animateColorAsState(
        targetValue =
            if (isSelected) {
                MaterialTheme.colorScheme.primary
            } else {
                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            },
        label = "NavContentColor",
    )

    val pillWidth by animateDpAsState(
        targetValue = if (isSelected) 68.dp else 48.dp,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "PillWidth",
    )

    val iconScale by animateFloatAsState(
        targetValue = if (isSelected) 1.1f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "IconScale",
    )

    Box(
        modifier =
            Modifier
                .width(pillWidth)
                .height(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(containerColor)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick,
                ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = if (isSelected) destination.selectedIcon else destination.unselectedIcon,
                contentDescription = destination.label,
                tint = contentColor,
                modifier = Modifier.size(20.dp).scale(iconScale),
            )
            if (isSelected) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = destination.label,
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = contentColor,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
    }
}
