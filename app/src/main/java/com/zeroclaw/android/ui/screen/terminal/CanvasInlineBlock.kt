/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.screen.terminal

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.zeroclaw.android.data.model.CanvasFrame
import com.zeroclaw.android.ui.theme.JetBrainsMonoFamily
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

/**
 * Compact inline renderer for a [CanvasFrame] displayed inside the chat.
 *
 * Constrain WebView height with [heightIn] so it doesn't dominate the
 * conversation. No JavaScript interfaces added (security).
 */
@Composable
fun CanvasInlineBlock(
    canvasFrame: CanvasFrame,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(
            1.dp,
            MaterialTheme.colorScheme.outline.copy(alpha = 0.3f),
        ),
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = canvasFrame.frameId.take(8),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = canvasFrame.contentType,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            CanvasFrameContent(
                contentType = canvasFrame.contentType,
                content = canvasFrame.content,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 150.dp, max = 300.dp),
            )
        }
    }
}

@Composable
private fun CanvasFrameContent(
    contentType: String,
    content: String,
    modifier: Modifier = Modifier,
) {
    when (contentType.lowercase()) {
        "html", "svg" -> InlineHtmlRenderer(content = content, modifier = modifier)
        "markdown" -> InlineMarkdownRenderer(content = content, modifier = modifier)
        "text" -> InlineTextRenderer(content = content, modifier = modifier)
        else -> Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text("Unsupported: $contentType", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun InlineHtmlRenderer(content: String, modifier: Modifier = Modifier) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                webViewClient = WebViewClient()
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
            }
        },
        update = { it.loadData(content, "text/html; charset=utf-8", "utf-8") },
        modifier = modifier,
    )
}

@Composable
private fun InlineMarkdownRenderer(content: String, modifier: Modifier = Modifier) {
    val parser = Parser.builder().build()
    val renderer = HtmlRenderer.builder().build()
    val html = renderer.render(parser.parse(content))
    InlineHtmlRenderer(content = html, modifier = modifier)
}

@Composable
private fun InlineTextRenderer(content: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
    ) {
        Text(
            text = content,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = JetBrainsMonoFamily,
            ),
        )
    }
}
