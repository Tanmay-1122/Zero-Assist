/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

@file:Suppress("TooManyFunctions")

package com.zeroclaw.android.ui.screen.terminal

import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GetApp
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.zeroclaw.android.data.model.CanvasFrame
import com.zeroclaw.android.ui.theme.JetBrainsMonoFamily
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

/**
 * Canvas visualization screen.
 *
 * Displays real-time visualizations streamed from the agent,
 * supporting HTML, SVG, Markdown, and plain text rendering.
 *
 * @param canvasId The Canvas ID to display.
 * @param canvasViewModel The Canvas ViewModel for state management.
 * @param onDismiss Callback when user closes the Canvas view.
 * @param modifier Modifier applied to the root layout.
 */
@Composable
fun CanvasScreen(
    canvasId: String,
    canvasViewModel: CanvasViewModel = viewModel(),
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val displayFrame by canvasViewModel.displayFrame.collectAsStateWithLifecycle()
    val frames by canvasViewModel.currentFrames.collectAsStateWithLifecycle()
    val frameIndex by canvasViewModel.frameIndex.collectAsStateWithLifecycle()
    val isConnected by canvasViewModel.isConnected.collectAsStateWithLifecycle()
    val error by canvasViewModel.error.collectAsStateWithLifecycle()

    LaunchedEffect(canvasId) {
        canvasViewModel.subscribeToCanvas(canvasId)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Header with canvas info and controls
        CanvasHeader(
            canvasId = canvasId,
            isConnected = isConnected,
            frameCount = frames.size,
            currentFrameIndex = frameIndex,
            onDismiss = {
                canvasViewModel.unsubscribeFromCanvas()
                onDismiss()
            },
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Main content area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp)
        ) {
            if (displayFrame != null) {
                CanvasFrameRenderer(
                    frame = displayFrame!!,
                    modifier = Modifier.fillMaxSize(),
                )
            } else if (error != null) {
                ErrorState(error = error!!)
            } else if (isConnected) {
                LoadingState()
            } else {
                DisconnectedState()
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Frame navigation controls
        if (frames.isNotEmpty()) {
            CanvasFrameNavigation(
                frameIndex = frameIndex,
                frameCount = frames.size,
                onPrevious = { canvasViewModel.previousFrame() },
                onNext = { canvasViewModel.nextFrame() },
                onFirst = { canvasViewModel.navigateToFrame(0) },
                onLast = { canvasViewModel.goToLatest() },
            )
        }
    }
}

/**
 * Header with Canvas ID, connection status, and controls.
 */
@Composable
private fun CanvasHeader(
    canvasId: String,
    isConnected: Boolean,
    frameCount: Int,
    currentFrameIndex: Int,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
            ) {
                Text(
                    text = "Canvas: $canvasId",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 4.dp),
                ) {
                    val statusColor = if (isConnected) Color.Green else Color.Gray
                    Box(
                        modifier = Modifier
                            .width(8.dp)
                            .height(8.dp)
                            .background(statusColor, RoundedCornerShape(50))
                    )
                    Text(
                        text = if (isConnected) "Connected" else "Disconnected",
                        style = MaterialTheme.typography.labelSmall,
                        color = statusColor,
                        modifier = Modifier.padding(start = 6.dp),
                    )
                    if (frameCount > 0) {
                        Text(
                            text = " • Frame ${currentFrameIndex + 1}/${frameCount}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
            }

            IconButton(onClick = onDismiss, modifier = Modifier.padding(start = 8.dp)) {
                Icon(Icons.Default.Close, contentDescription = "Close Canvas")
            }
        }
    }
}

/**
 * Renders a single Canvas frame based on content type.
 */
@Composable
private fun CanvasFrameRenderer(
    frame: CanvasFrame,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        when (frame.contentType.lowercase()) {
            "html", "svg" -> {
                HtmlCanvasRenderer(content = frame.content, modifier = Modifier.fillMaxSize())
            }
            "markdown" -> {
                MarkdownCanvasRenderer(content = frame.content, modifier = Modifier.fillMaxSize())
            }
            "text" -> {
                TextCanvasRenderer(content = frame.content, modifier = Modifier.fillMaxSize())
            }
            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Unsupported content type: ${frame.contentType}")
                }
            }
        }
    }
}

/**
 * Renders HTML/SVG content using WebView.
 */
@Composable
private fun HtmlCanvasRenderer(
    content: String,
    modifier: Modifier = Modifier,
) {
    AndroidView(
        factory = { context ->
            WebView(context).apply {
                webViewClient = WebViewClient()
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                }
            }
        },
        update = { webView ->
            webView.loadData(content, "text/html; charset=utf-8", "utf-8")
        },
        modifier = modifier,
    )
}

/**
 * Renders Markdown content as HTML.
 */
@Composable
private fun MarkdownCanvasRenderer(
    content: String,
    modifier: Modifier = Modifier,
) {
    val parser = Parser.builder().build()
    val renderer = HtmlRenderer.builder().build()

    val document = parser.parse(content)
    val html = renderer.render(document)

    HtmlCanvasRenderer(content = html, modifier = modifier)
}

/**
 * Renders plain text content.
 */
@Composable
private fun TextCanvasRenderer(
    content: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(
            text = content,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = JetBrainsMonoFamily,
            ),
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Frame navigation controls (previous, next, first, last).
 */
@Composable
private fun CanvasFrameNavigation(
    frameIndex: Int,
    frameCount: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onFirst: () -> Unit,
    onLast: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 8.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onFirst,
                enabled = frameIndex > 0,
                modifier = Modifier.padding(4.dp),
            ) {
                Icon(Icons.Default.SkipPrevious, contentDescription = "First frame")
            }

            IconButton(
                onClick = onPrevious,
                enabled = frameIndex > 0,
                modifier = Modifier.padding(4.dp),
            ) {
                Icon(Icons.Default.ChevronLeft, contentDescription = "Previous frame")
            }

            Text(
                text = "${frameIndex + 1} / $frameCount",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            IconButton(
                onClick = onNext,
                enabled = frameIndex < frameCount - 1,
                modifier = Modifier.padding(4.dp),
            ) {
                Icon(Icons.Default.ChevronRight, contentDescription = "Next frame")
            }

            IconButton(
                onClick = onLast,
                enabled = frameIndex < frameCount - 1,
                modifier = Modifier.padding(4.dp),
            ) {
                Icon(Icons.Default.SkipNext, contentDescription = "Last frame")
            }
        }
    }
}

/**
 * Loading state indicator.
 */
@Composable
private fun LoadingState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Text("Waiting for frames...")
    }
}

/**
 * Disconnected state indicator.
 */
@Composable
private fun DisconnectedState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text("Canvas disconnected")
            Text(
                "Attempting to reconnect...",
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

/**
 * Error state indicator.
 */
@Composable
private fun ErrorState(
    error: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.errorContainer),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text = "Canvas Error",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
