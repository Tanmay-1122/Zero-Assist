/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.component

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Corner radius shared by every card using this design language. */
val DashboardCardShape = RoundedCornerShape(10.dp)

/** Hairline border used on every dashboard card in place of elevation shadows. */
@Composable
fun dashboardCardBorder(): BorderStroke =
    BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.15f))

/** Muted divider color used between rows and columns within a card. */
@Composable
fun dividerColor(): Color =
    MaterialTheme.colorScheme.outline.copy(alpha = 0.13f)

/**
 * Base flat card container: rounded corners, hairline border,
 * zero elevation, surfaceVariant fill. Reusable across all screens.
 */
@Composable
fun DashboardCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = DashboardCardShape,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        border = dashboardCardBorder(),
    ) {
        content()
    }
}

/**
 * [DashboardCard] with a colored left accent bar, used for advisory banners.
 * The accent color communicates severity at a glance instead of filling the
 * whole card with a tinted background.
 *
 * @param accentColor Color of the left edge bar.
 */
@Composable
fun AccentEdgeCard(
    accentColor: Color,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    DashboardCard(modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite }) {
        Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .fillMaxHeight()
                    .background(accentColor),
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(16.dp),
                content = content,
            )
        }
    }
}
