/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.agents

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.zeroclaw.android.model.content.ContentBlock
import com.zeroclaw.android.ui.component.LinkifiedText

import com.zeroclaw.android.ui.renderer.BlockInteraction
import com.zeroclaw.android.ui.renderer.ContentBlockRendererRegistry
import com.zeroclaw.android.ui.renderer.RenderContext

/**
 * Type-safe composable dispatcher for rendering [ContentBlock] variants.
 * Delegates to [ContentBlockRendererRegistry] for extensible block rendering.
 */
@Composable
fun RenderContentBlock(
    block: ContentBlock,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    isStreaming: Boolean = false,
    onInteraction: ((BlockInteraction) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    ContentBlockRendererRegistry.Render(
        block = block,
        context = RenderContext(
            textColor = textColor,
            isStreaming = isStreaming,
            onInteraction = onInteraction,
        ),
        modifier = modifier,
    )
}
