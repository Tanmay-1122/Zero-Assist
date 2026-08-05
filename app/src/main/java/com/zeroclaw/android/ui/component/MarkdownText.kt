/*
 * Copyright 2026 Zero-Assist Community
 *
 * Licensed under the MIT License. See LICENSE in the project root.
 */

@file:Suppress("TooManyFunctions")

package com.zeroclaw.android.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zeroclaw.android.ui.theme.JetBrainsMonoFamily
import com.zeroclaw.android.util.MessageUrlDetector
import org.commonmark.ext.gfm.tables.TableBody
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.Image
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.Parser

private const val BLOCK_GAP_DP = 6
private const val CODE_CORNER_DP = 8
private const val CODE_PADDING_DP = 12
private const val QUOTE_BAR_WIDTH_DP = 3
private const val QUOTE_PADDING_DP = 10
private const val LIST_INDENT_DP = 18
private const val TABLE_CELL_HPAD_DP = 8
private const val TABLE_CELL_VPAD_DP = 4
private const val URL_TAG = "url"

private sealed interface RenderableBlock {
    data class Paragraph(val text: AnnotatedString) : RenderableBlock
    data class Heading(val level: Int, val text: AnnotatedString) : RenderableBlock
    data class CodeBlock(val language: String?, val code: String) : RenderableBlock
    data class BlockQuote(val children: List<RenderableBlock>) : RenderableBlock
    data class BulletList(val items: List<List<RenderableBlock>>) : RenderableBlock
    data class OrderedList(val items: List<List<RenderableBlock>>, val start: Int) : RenderableBlock
    data object ThematicBreak : RenderableBlock
    data class Table(
        val headers: List<AnnotatedString>,
        val rows: List<List<AnnotatedString>>,
    ) : RenderableBlock
}

@Composable
fun MarkdownText(
    markdown: String,
    style: TextStyle,
    color: Color,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    if (markdown.isBlank()) return

    val linkColor = MaterialTheme.colorScheme.primary
    val inlineCodeBg = MaterialTheme.colorScheme.surfaceVariant
    val parser = remember {
        Parser.builder().extensions(listOf(TablesExtension.create())).build()
    }

    val blocks = remember(markdown, linkColor, inlineCodeBg) {
        val document = parser.parse(markdown)
        parseBlocks(document, linkColor, inlineCodeBg)
    }

    Column(modifier = modifier) {
        RenderBlocks(
            blocks = blocks,
            style = style,
            color = color,
            linkColor = linkColor,
            onLongClick = onLongClick,
        )
    }
}

@Composable
private fun RenderBlocks(
    blocks: List<RenderableBlock>,
    style: TextStyle,
    color: Color,
    linkColor: Color,
    onLongClick: (() -> Unit)? = null,
) {
    for (block in blocks) {
        when (block) {
            is RenderableBlock.Paragraph -> ParagraphBlock(block.text, style, color, onLongClick)
            is RenderableBlock.Heading -> HeadingBlock(block.level, block.text, color, onLongClick)
            is RenderableBlock.CodeBlock -> CodeBlockCard(block.language, block.code)
            is RenderableBlock.BlockQuote -> {
                BlockQuoteBlock(block.children, style, color, linkColor, onLongClick)
            }
            is RenderableBlock.BulletList -> {
                BulletListBlock(block.items, style, color, linkColor, onLongClick)
            }
            is RenderableBlock.OrderedList -> {
                OrderedListBlock(block.items, block.start, style, color, linkColor, onLongClick)
            }
            is RenderableBlock.ThematicBreak -> ThematicBreakBlock()
            is RenderableBlock.Table -> {
                TableBlock(block.headers, block.rows, style, color, linkColor, onLongClick)
            }
        }
        Spacer(modifier = Modifier.height(BLOCK_GAP_DP.dp))
    }
}

@Composable
private fun ParagraphBlock(
    text: AnnotatedString,
    style: TextStyle,
    color: Color,
    onLongClick: (() -> Unit)? = null,
) {
    val uriHandler = LocalUriHandler.current
    val annotated = text.toLinkified(linkColor = MaterialTheme.colorScheme.primary)

    ClickableText(
        text = annotated,
        style = style,
        color = color,
        onUrlClick = { url -> runCatching { uriHandler.openUri(url) } },
        onLongClick = onLongClick,
    )
}

@Composable
private fun HeadingBlock(
    level: Int,
    text: AnnotatedString,
    color: Color,
    onLongClick: (() -> Unit)? = null,
) {
    val headingStyle = when (level) {
        1 -> TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold)
        2 -> TextStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold)
        3 -> TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        else -> TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
    val uriHandler = LocalUriHandler.current
    val annotated = text.toLinkified(linkColor = MaterialTheme.colorScheme.primary)

    ClickableText(
        text = annotated,
        style = headingStyle,
        color = color,
        onUrlClick = { url -> runCatching { uriHandler.openUri(url) } },
        onLongClick = onLongClick,
    )
}

@Composable
private fun CodeBlockCard(
    language: String?,
    code: String,
) {
    val bg = MaterialTheme.colorScheme.surfaceVariant
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(CODE_CORNER_DP.dp))
            .background(bg)
            .padding(CODE_PADDING_DP.dp),
    ) {
        if (!language.isNullOrBlank()) {
            Text(
                text = language,
                color = textColor.copy(alpha = 0.55f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.5.sp,
            )
            Spacer(modifier = Modifier.height(4.dp))
        }
        Text(
            text = code.trimEnd(),
            fontFamily = JetBrainsMonoFamily,
            fontSize = 13.sp,
            color = textColor,
        )
    }
}

@Composable
private fun BlockQuoteBlock(
    children: List<RenderableBlock>,
    style: TextStyle,
    color: Color,
    linkColor: Color,
    onLongClick: (() -> Unit)? = null,
) {
    val accentColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)

    Row(
        modifier = Modifier.fillMaxWidth(),
    ) {
        Box(
            modifier = Modifier
                .width(QUOTE_BAR_WIDTH_DP.dp)
                .fillMaxSize()
                .background(
                    color = accentColor,
                    shape = RoundedCornerShape(2.dp),
                ),
        )
        Spacer(modifier = Modifier.width(QUOTE_PADDING_DP.dp))
        Column(modifier = Modifier.weight(1f)) {
            RenderBlocks(
                blocks = children,
                style = style,
                color = color.copy(alpha = 0.8f),
                linkColor = linkColor,
                onLongClick = onLongClick,
            )
        }
    }
}

@Composable
private fun BulletListBlock(
    items: List<List<RenderableBlock>>,
    style: TextStyle,
    color: Color,
    linkColor: Color,
    onLongClick: (() -> Unit)? = null,
) {
    Column {
        for (itemBlocks in items) {
            Row(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "\u2022",
                    style = style,
                    color = color,
                    modifier = Modifier.width(LIST_INDENT_DP.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    RenderBlocks(
                        blocks = itemBlocks,
                        style = style,
                        color = color,
                        linkColor = linkColor,
                        onLongClick = onLongClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun OrderedListBlock(
    items: List<List<RenderableBlock>>,
    start: Int,
    style: TextStyle,
    color: Color,
    linkColor: Color,
    onLongClick: (() -> Unit)? = null,
) {
    Column {
        for ((index, itemBlocks) in items.withIndex()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = "${start + index}.",
                    style = style,
                    color = color,
                    modifier = Modifier.width(LIST_INDENT_DP.dp),
                )
                Column(modifier = Modifier.weight(1f)) {
                    RenderBlocks(
                        blocks = itemBlocks,
                        style = style,
                        color = color,
                        linkColor = linkColor,
                        onLongClick = onLongClick,
                    )
                }
            }
        }
    }
}

@Composable
private fun ThematicBreakBlock() {
    HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant,
        thickness = 1.dp,
    )
}

@Composable
private fun TableBlock(
    headers: List<AnnotatedString>,
    rows: List<List<AnnotatedString>>,
    style: TextStyle,
    color: Color,
    linkColor: Color,
    onLongClick: (() -> Unit)? = null,
) {
    val borderColor = MaterialTheme.colorScheme.outlineVariant
    val headerBg = MaterialTheme.colorScheme.surfaceVariant

    val uriHandler = LocalUriHandler.current

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
        if (headers.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(headerBg),
            ) {
                val headerStyles = listOf(
                    SpanStyle(fontWeight = FontWeight.SemiBold),
                )
                for ((colIndex, cell) in headers.withIndex()) {
                    val cellAnnotated = buildAnnotatedString {
                        val styled = cell.toLinkified(linkColor)
                        append(styled)
                        for (s in headerStyles) {
                            addStyle(s, 0, styled.length)
                        }
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(
                                horizontal = TABLE_CELL_HPAD_DP.dp,
                                vertical = TABLE_CELL_VPAD_DP.dp,
                            ),
                    ) {
                        ClickableText(
                            text = cellAnnotated,
                            style = style.copy(fontSize = 13.sp),
                            color = color,
                            onUrlClick = { url ->
                                runCatching { uriHandler.openUri(url) }
                            },
                        )
                    }
                }
            }
            HorizontalDivider(color = borderColor, thickness = 0.5.dp)
        }

        for (row in rows) {
            Row(
                modifier = Modifier.fillMaxWidth(),
            ) {
                for ((colIndex, cell) in row.withIndex()) {
                    val cellAnnotated = cell.toLinkified(linkColor)
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(
                                horizontal = TABLE_CELL_HPAD_DP.dp,
                                vertical = TABLE_CELL_VPAD_DP.dp,
                            ),
                    ) {
                        ClickableText(
                            text = cellAnnotated,
                            style = style.copy(fontSize = 13.sp),
                            color = color,
                            onUrlClick = { url ->
                                runCatching { uriHandler.openUri(url) }
                            },
                        )
                    }
                }
            }
            HorizontalDivider(color = borderColor, thickness = 0.5.dp)
        }
    }
}

@Composable
private fun ClickableText(
    text: AnnotatedString,
    style: TextStyle,
    color: Color,
    onUrlClick: (String) -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    var textLayoutResult by mutableStateOf<TextLayoutResult?>(null)

    androidx.compose.foundation.text.BasicText(
        text = text,
        modifier = modifier.pointerInput(text, onLongClick) {
            detectTapGestures(
                onTap = { position ->
                    textLayoutResult?.let { layout ->
                        val offset = layout.getOffsetForPosition(position)
                        val annotations = text.getStringAnnotations(URL_TAG, offset, offset)
                        annotations.firstOrNull()?.let { annotation ->
                            onUrlClick(annotation.item)
                        }
                    }
                },
                onLongPress = {
                    onLongClick?.invoke()
                },
            )
        },
        style = style.copy(color = color),
        onTextLayout = { textLayoutResult = it },
    )
}

private fun AnnotatedString.toLinkified(linkColor: Color): AnnotatedString {
    val rawText = text
    val links = MessageUrlDetector.detect(rawText)
    if (links.isEmpty()) return this

    return buildAnnotatedString {
        append(this@toLinkified)
        for (link in links) {
            addStyle(
                style = SpanStyle(
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

private fun childrenOf(node: Node): List<Node> {
    val list = mutableListOf<Node>()
    var child: Node? = node.firstChild
    while (child != null) {
        list.add(child)
        child = child.next
    }
    return list
}

private fun parseBlocks(
    node: Node,
    linkColor: Color,
    inlineCodeBg: Color,
): List<RenderableBlock> {
    val blocks = mutableListOf<RenderableBlock>()

    for (child in childrenOf(node)) {
        when (child) {
            is Paragraph -> {
                val text = buildInlineAnnotatedString(child, linkColor, inlineCodeBg)
                blocks.add(RenderableBlock.Paragraph(text))
            }

            is Heading -> {
                val text = buildInlineAnnotatedString(child, linkColor, inlineCodeBg)
                blocks.add(RenderableBlock.Heading(child.level, text))
            }

            is FencedCodeBlock -> {
                blocks.add(RenderableBlock.CodeBlock(child.info, child.literal))
            }

            is IndentedCodeBlock -> {
                blocks.add(RenderableBlock.CodeBlock(null, child.literal))
            }

            is BlockQuote -> {
                val children = parseBlocks(child, linkColor, inlineCodeBg)
                if (children.isNotEmpty()) {
                    blocks.add(RenderableBlock.BlockQuote(children))
                }
            }

            is BulletList -> {
                val items = mutableListOf<List<RenderableBlock>>()
                for (item in childrenOf(child)) {
                    if (item is ListItem) {
                        items.add(parseBlocks(item, linkColor, inlineCodeBg))
                    }
                }
                if (items.isNotEmpty()) {
                    blocks.add(RenderableBlock.BulletList(items))
                }
            }

            is OrderedList -> {
                val items = mutableListOf<List<RenderableBlock>>()
                for (item in childrenOf(child)) {
                    if (item is ListItem) {
                        items.add(parseBlocks(item, linkColor, inlineCodeBg))
                    }
                }
                if (items.isNotEmpty()) {
                    blocks.add(RenderableBlock.OrderedList(items, child.startNumber))
                }
            }

            is ThematicBreak -> {
                blocks.add(RenderableBlock.ThematicBreak)
            }

            is org.commonmark.ext.gfm.tables.TableBlock -> {
                val result = parseTableBlock(child, linkColor, inlineCodeBg)
                if (result != null) {
                    blocks.add(result)
                }
            }
        }
    }

    return blocks
}

private fun parseTableBlock(
    tableBlock: Node,
    linkColor: Color,
    inlineCodeBg: Color,
): RenderableBlock.Table? {
    var headers: List<AnnotatedString>? = null
    val rows = mutableListOf<List<AnnotatedString>>()

    for (part in childrenOf(tableBlock)) {
        when (part) {
            is TableHead -> {
                for (row in childrenOf(part)) {
                    if (row is TableRow) {
                        headers = childrenOf(row).mapNotNull { cell ->
                            if (cell is TableCell) {
                                buildInlineAnnotatedString(cell, linkColor, inlineCodeBg)
                            } else null
                        }
                    }
                }
            }

            is TableBody -> {
                for (row in childrenOf(part)) {
                    if (row is TableRow) {
                        val cells = childrenOf(row).mapNotNull { cell ->
                            if (cell is TableCell) {
                                buildInlineAnnotatedString(cell, linkColor, inlineCodeBg)
                            } else null
                        }
                        if (cells.isNotEmpty()) {
                            rows.add(cells)
                        }
                    }
                }
            }
        }
    }

    val headerList = headers ?: return null
    if (headerList.isEmpty()) return null

    return RenderableBlock.Table(headerList, rows)
}

private fun buildInlineAnnotatedString(
    node: Node,
    linkColor: Color,
    inlineCodeBg: Color,
    baseStyle: SpanStyle = SpanStyle(),
): AnnotatedString {
    return buildAnnotatedString {
        renderInlineChildren(node, linkColor, inlineCodeBg, baseStyle)
    }
}

private fun AnnotatedString.Builder.renderInlineChildren(
    node: Node,
    linkColor: Color,
    inlineCodeBg: Color,
    baseStyle: SpanStyle,
) {
    for (child in childrenOf(node)) {
        renderInlineNode(child, linkColor, inlineCodeBg, baseStyle)
    }
}

private fun AnnotatedString.Builder.renderInlineNode(
    node: Node,
    linkColor: Color,
    inlineCodeBg: Color,
    baseStyle: SpanStyle,
) {
    when (node) {
        is Text -> {
            withStyle(baseStyle) {
                append(node.literal)
            }
        }

        is Code -> {
            val codeStyle = baseStyle.merge(
                SpanStyle(
                    background = inlineCodeBg,
                    fontSize = 13.sp,
                ),
            )
            withStyle(codeStyle) {
                append(node.literal)
            }
        }

        is Emphasis -> {
            val italicStyle = baseStyle.merge(SpanStyle(fontStyle = FontStyle.Italic))
            renderInlineChildren(node, linkColor, inlineCodeBg, italicStyle)
        }

        is StrongEmphasis -> {
            val boldStyle = baseStyle.merge(SpanStyle(fontWeight = FontWeight.Bold))
            renderInlineChildren(node, linkColor, inlineCodeBg, boldStyle)
        }

        is Link -> {
            val linkStyle = baseStyle.merge(
                SpanStyle(
                    color = linkColor,
                    textDecoration = TextDecoration.Underline,
                ),
            )
            val start = length
            renderInlineChildren(node, linkColor, inlineCodeBg, linkStyle)
            addStringAnnotation(URL_TAG, node.destination, start, length)
        }

        is Image -> {
            val altText = buildAnnotatedString {
                renderInlineChildren(node, linkColor, inlineCodeBg, baseStyle)
            }
            withStyle(baseStyle.copy(color = linkColor.copy(alpha = 0.6f))) {
                append("[image: ${altText.text}]")
            }
        }

        is SoftLineBreak -> {
            append(" ")
        }

        is HardLineBreak -> {
            append("\n")
        }
    }
}

