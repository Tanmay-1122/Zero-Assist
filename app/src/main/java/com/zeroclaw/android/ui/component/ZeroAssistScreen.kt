/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.zeroclaw.android.ui.theme.ZeroAssistSpacing

/**
 * Standard scrolling screen container with consistent app margins and safe insets.
 */
@Composable
fun ZeroAssistScreen(
    edgeMargin: Dp,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues =
        PaddingValues(
            top = ZeroAssistSpacing.Small,
            bottom = ZeroAssistSpacing.XLarge,
        ),
    verticalArrangement: Arrangement.Vertical =
        Arrangement.spacedBy(ZeroAssistSpacing.Medium),
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        modifier =
            modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(horizontal = edgeMargin),
        contentPadding = contentPadding,
        verticalArrangement = verticalArrangement,
        content = content,
    )
}
