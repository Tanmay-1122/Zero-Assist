/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

package com.zeroclaw.android.ui.component

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.text.BasicText
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import com.zeroclaw.android.util.MessageUrlDetector

/**
 * Renders text with detected HTTP(S) links tappable through the platform URL handler.
 */
@Composable
fun LinkifiedText(
    text: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    onOpenUrl: ((String) -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
) {
    val links = remember(text) { MessageUrlDetector.detect(text) }
    val linkColor = MaterialTheme.colorScheme.primary
    val annotatedText =
        remember(text, links, linkColor) {
            buildAnnotatedString {
                append(text)
                for (link in links) {
                    addStyle(
                        style =
                            SpanStyle(
                                color = linkColor,
                                textDecoration = TextDecoration.Underline,
                            ),
                        start = link.start,
                        end = link.end,
                    )
                    addStringAnnotation(
                        tag = URL_TAG,
                        annotation = link.openUrl,
                        start = link.start,
                        end = link.end,
                    )
                }
            }
        }
    val uriHandler = LocalUriHandler.current
    var textLayoutResult by remember { mutableStateOf<TextLayoutResult?>(null) }
    val gestureModifier =
        if (links.isEmpty() && onLongClick == null) {
            modifier
        } else {
            modifier.pointerInput(annotatedText, onOpenUrl, onLongClick) {
                detectTapGestures(
                    onTap = { position ->
                        val offset =
                            textLayoutResult
                                ?.getOffsetForPosition(position)
                                ?: return@detectTapGestures
                        val url =
                            annotatedText
                                .getStringAnnotations(URL_TAG, offset, offset)
                                .firstOrNull()
                                ?.item
                                ?: return@detectTapGestures
                        if (onOpenUrl != null) {
                            onOpenUrl(url)
                        } else {
                            runCatching { uriHandler.openUri(url) }
                        }
                    },
                    onLongPress = {
                        onLongClick?.invoke()
                    },
                )
            }
        }

    BasicText(
        text = annotatedText,
        modifier = gestureModifier,
        style = style.copy(color = color),
        onTextLayout = { textLayoutResult = it },
        maxLines = maxLines,
        overflow = overflow,
        softWrap = softWrap,
    )
}

private const val URL_TAG = "url"
