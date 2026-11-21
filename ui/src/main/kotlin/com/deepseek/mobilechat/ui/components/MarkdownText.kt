package com.deepseek.mobilechat.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.deepseek.mobilechat.ui.motion.pressable
import com.deepseek.mobilechat.ui.theme.ChatColors
import com.deepseek.mobilechat.ui.theme.LocalChatColors
import com.deepseek.mobilechat.ui.theme.LocalCodeTextStyle
import com.deepseek.mobilechat.ui.theme.Spacing
import kotlinx.coroutines.delay

/**
 * Renders the transcript's markdown.
 *
 * The parsing step is pure and lives in MarkdownModel.kt; everything here is
 * hand-written Compose so headings, lists and tables share the app's type
 * scale, spacing and colours instead of a third-party HTML-ish stylesheet.
 *
 * Streaming note: [streaming]/[caretAlpha] only decide where the blinking
 * caret goes (the last inline block, however deeply nested). Parsing never
 * throws on a half-written construct, so the same composable can redraw on
 * every token.
 */
@Composable
fun MarkdownText(
    text: String,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    color: Color = MaterialTheme.colorScheme.onSurface,
    streaming: Boolean = false,
    caretAlpha: Float = 1f,
) {
    val blocks = remember(text) { parseMarkdown(text) }
    val chatColors = LocalChatColors.current
    val codeStyle = LocalCodeTextStyle.current
    val linkColor = if (color == chatColors.userBubbleText) {
        chatColors.onAccent
    } else {
        MaterialTheme.colorScheme.primary
    }

    Column(modifier = modifier) {
        blocks.forEachIndexed { index, block ->
            if (index > 0) {
                Spacer(modifier = Modifier.height(blockGap(blocks[index - 1], block)))
            }
            MarkdownBlockView(
                block = block,
                style = style,
                color = color,
                linkColor = linkColor,
                codeStyle = codeStyle,
                chatColors = chatColors,
                showCaret = streaming && index == blocks.lastIndex,
                caretAlpha = caretAlpha,
            )
        }
    }
}

private const val CaretChar = "\u258D"

private fun blockGap(previous: MarkdownBlock, block: MarkdownBlock): Dp = when {
    previous is MarkdownBlock.Code || block is MarkdownBlock.Code -> Spacing.sm
    previous is MarkdownBlock.Table || block is MarkdownBlock.Table -> Spacing.sm
    previous is MarkdownBlock.Quote || block is MarkdownBlock.Quote -> Spacing.sm
    block is MarkdownBlock.Heading -> Spacing.md
    previous is MarkdownBlock.Heading -> Spacing.xs
    else -> Spacing.xs
}

// ---------------------------------------------------------------------------
// Block rendering
// ---------------------------------------------------------------------------

@Composable
private fun MarkdownBlockView(
    block: MarkdownBlock,
    style: TextStyle,
    color: Color,
    linkColor: Color,
    codeStyle: TextStyle,
    chatColors: ChatColors,
    showCaret: Boolean,
    caretAlpha: Float,
    depth: Int = 0,
) {
    when (block) {
        is MarkdownBlock.Paragraph -> InlineText(
            inlines = block.inlines,
            style = style,
            color = color,
            linkColor = linkColor,
            codeStyle = codeStyle,
            chatColors = chatColors,
            showCaret = showCaret,
            caretAlpha = caretAlpha,
        )

        is MarkdownBlock.Heading -> InlineText(
            inlines = block.inlines,
            style = headingStyle(style, block.level),
            color = color,
            linkColor = linkColor,
            codeStyle = codeStyle,
            chatColors = chatColors,
            showCaret = showCaret,
            caretAlpha = caretAlpha,
        )

        is MarkdownBlock.Code -> CodeBlock(
            block = block,
            codeStyle = codeStyle,
            chatColors = chatColors,
            showCaret = showCaret,
            caretAlpha = caretAlpha,
        )

        is MarkdownBlock.ListBlock -> MarkdownListView(
            block = block,
            style = style,
            color = color,
            linkColor = linkColor,
            codeStyle = codeStyle,
            chatColors = chatColors,
            showCaret = showCaret,
            caretAlpha = caretAlpha,
            depth = depth,
        )

        is MarkdownBlock.Quote -> QuoteView(
            block = block,
            style = style,
            color = color,
            linkColor = linkColor,
            codeStyle = codeStyle,
            chatColors = chatColors,
            showCaret = showCaret,
            caretAlpha = caretAlpha,
            depth = depth,
        )

        is MarkdownBlock.Table -> TableView(
            block = block,
            style = style,
            color = color,
            linkColor = linkColor,
            codeStyle = codeStyle,
            chatColors = chatColors,
            showCaret = showCaret,
            caretAlpha = caretAlpha,
        )

        MarkdownBlock.ThematicBreak -> Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(color.copy(alpha = 0.18f)),
        )
    }
}

@Composable
private fun InlineText(
    inlines: List<MarkdownInline>,
    style: TextStyle,
    color: Color,
    linkColor: Color,
    codeStyle: TextStyle,
    chatColors: ChatColors,
    showCaret: Boolean,
    caretAlpha: Float,
) {
    if (inlines.isEmpty() && !showCaret) return
    val base = remember(inlines, linkColor, codeStyle, chatColors) {
        buildInlineAnnotated(
            inlines = inlines,
            linkColor = linkColor,
            codeStyle = codeStyle,
            chatColors = chatColors,
        )
    }
    val annotated = if (showCaret) {
        remember(base, caretAlpha, chatColors) { base.withCaret(chatColors, caretAlpha) }
    } else {
        base
    }
    Text(text = annotated, style = style, color = color)
}

@Composable
private fun MarkdownListView(
    block: MarkdownBlock.ListBlock,
    style: TextStyle,
    color: Color,
    linkColor: Color,
    codeStyle: TextStyle,
    chatColors: ChatColors,
    showCaret: Boolean,
    caretAlpha: Float,
    depth: Int,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Spacing.xxs)) {
        block.items.forEachIndexed { itemIndex, item ->
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (block.ordered) "${item.number}." else "•",
                    style = style.copy(fontWeight = FontWeight.Medium),
                    color = color.copy(alpha = 0.72f),
                    textAlign = TextAlign.End,
                    modifier = Modifier
                        .widthIn(min = if (block.ordered) 24.dp else 18.dp)
                        .padding(end = Spacing.xs),
                )
                Column(modifier = Modifier.weight(1f)) {
                    item.blocks.forEachIndexed { childIndex, child ->
                        if (childIndex > 0) {
                            Spacer(modifier = Modifier.height(blockGap(item.blocks[childIndex - 1], child)))
                        }
                        val isLast = itemIndex == block.items.lastIndex &&
                            childIndex == item.blocks.lastIndex
                        MarkdownBlockView(
                            block = child,
                            style = style,
                            color = color,
                            linkColor = linkColor,
                            codeStyle = codeStyle,
                            chatColors = chatColors,
                            showCaret = showCaret && isLast,
                            caretAlpha = caretAlpha,
                            depth = depth + 1,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun QuoteView(
    block: MarkdownBlock.Quote,
    style: TextStyle,
    color: Color,
    linkColor: Color,
    codeStyle: TextStyle,
    chatColors: ChatColors,
    showCaret: Boolean,
    caretAlpha: Float,
    depth: Int,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(color.copy(alpha = 0.3f), RoundedCornerShape(2.dp)),
        )
        Column(
            modifier = Modifier.padding(
                start = Spacing.md,
                top = Spacing.xxs,
                bottom = Spacing.xxs,
            ),
        ) {
            block.blocks.forEachIndexed { index, child ->
                if (index > 0) {
                    Spacer(modifier = Modifier.height(blockGap(block.blocks[index - 1], child)))
                }
                MarkdownBlockView(
                    block = child,
                    style = style,
                    // Quotes read as secondary text without changing the type.
                    color = color.copy(alpha = 0.82f),
                    linkColor = linkColor,
                    codeStyle = codeStyle,
                    chatColors = chatColors,
                    showCaret = showCaret && index == block.blocks.lastIndex,
                    caretAlpha = caretAlpha,
                    depth = depth + 1,
                )
            }
        }
    }
}

@Composable
private fun CodeBlock(
    block: MarkdownBlock.Code,
    codeStyle: TextStyle,
    chatColors: ChatColors,
    showCaret: Boolean,
    caretAlpha: Float,
) {
    val copyToClipboard = rememberCopyToClipboard(label = "代码")
    var copied by remember(block.code) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            delay(1600)
            copied = false
        }
    }
    val code = remember(block.code, showCaret, caretAlpha, chatColors) {
        if (showCaret) {
            buildAnnotatedString {
                append(block.code)
                if (block.code.isNotEmpty()) append('\n')
                withStyle(SpanStyle(color = chatColors.caret.copy(alpha = caretAlpha))) {
                    append(CaretChar)
                }
            }
        } else {
            AnnotatedString(block.code)
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = chatColors.codeBackground,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, chatColors.codeBorder),
    ) {
        Column(modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = block.language.ifBlank { "代码" },
                    style = MaterialTheme.typography.labelSmall,
                    color = chatColors.mutedText,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .widthIn(min = 28.dp)
                        .pressable {
                            copyToClipboard(block.code)
                            copied = true
                        }
                        .padding(Spacing.xxs),
                    contentAlignment = Alignment.Center,
                ) {
                    if (copied) {
                        CheckIcon(
                            modifier = Modifier.iconSize(14.dp),
                            tint = MaterialTheme.colorScheme.primary,
                            contentDescription = "已复制",
                        )
                    } else {
                        CopyIcon(
                            modifier = Modifier.iconSize(14.dp),
                            tint = chatColors.mutedText,
                            contentDescription = "复制代码",
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(Spacing.xxs))
            Text(
                text = code,
                style = codeStyle,
                color = MaterialTheme.colorScheme.onSurface,
                softWrap = false,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
        }
    }
}

@Composable
private fun TableView(
    block: MarkdownBlock.Table,
    style: TextStyle,
    color: Color,
    linkColor: Color,
    codeStyle: TextStyle,
    chatColors: ChatColors,
    showCaret: Boolean,
    caretAlpha: Float,
) {
    if (block.header.isEmpty()) return
    val cellStyle = style.copy(
        fontSize = if (style.fontSize.isSpecified) style.fontSize * 0.92f else style.fontSize,
        lineHeight = if (style.lineHeight.isSpecified) style.lineHeight * 0.96f else style.lineHeight,
    )
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val horizontalPadding = 10.dp
    val verticalPadding = 8.dp
    val maxCellWidth = 260.dp
    val minCellWidth = 64.dp
    val columnCount = block.header.size

    // Real measured widths keep every row's columns aligned while the whole
    // table scrolls horizontally; long cells wrap inside their column.
    val widths = remember(block, cellStyle, linkColor, codeStyle, chatColors, density) {
        val maxPx = with(density) { maxCellWidth.roundToPx() }
        val paddingPx = with(density) { horizontalPadding.roundToPx() } * 2
        (0 until columnCount).map { column ->
            var widest = 0
            (listOf(block.header) + block.rows).forEach { row ->
                val cell = row.getOrNull(column).orEmpty()
                if (cell.isNotEmpty()) {
                    widest = maxOf(
                        widest,
                        measurer.measure(
                            text = buildInlineAnnotated(cell, linkColor, codeStyle, chatColors),
                            style = cellStyle,
                            maxLines = 4,
                            constraints = Constraints(maxWidth = maxPx),
                        ).size.width,
                    )
                }
            }
            with(density) {
                (widest + paddingPx)
                    .coerceAtLeast(minCellWidth.roundToPx())
                    .toDp()
            }
        }
    }
    val tableWidth = widths.fold(0.dp) { acc, width -> acc + width }
    val shape = RoundedCornerShape(10.dp)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(1.dp, color.copy(alpha = 0.18f), shape),
    ) {
        Column(modifier = Modifier.horizontalScroll(rememberScrollState())) {
            TableRow(
                cells = block.header,
                widths = widths,
                alignments = block.alignments,
                header = true,
                tableWidth = tableWidth,
                cellStyle = cellStyle,
                color = color,
                linkColor = linkColor,
                codeStyle = codeStyle,
                chatColors = chatColors,
                showCaret = showCaret && block.rows.isEmpty(),
                caretAlpha = caretAlpha,
                horizontalPadding = horizontalPadding,
                verticalPadding = verticalPadding,
            )
            block.rows.forEachIndexed { rowIndex, row ->
                Box(
                    modifier = Modifier
                        .width(tableWidth)
                        .height(1.dp)
                        .background(color.copy(alpha = 0.12f)),
                )
                TableRow(
                    cells = row,
                    widths = widths,
                    alignments = block.alignments,
                    header = false,
                    tableWidth = tableWidth,
                    cellStyle = cellStyle,
                    color = color,
                    linkColor = linkColor,
                    codeStyle = codeStyle,
                    chatColors = chatColors,
                    showCaret = showCaret && rowIndex == block.rows.lastIndex,
                    caretAlpha = caretAlpha,
                    horizontalPadding = horizontalPadding,
                    verticalPadding = verticalPadding,
                )
            }
        }
    }
}

@Composable
private fun TableRow(
    cells: List<List<MarkdownInline>>,
    widths: List<Dp>,
    alignments: List<MarkdownAlignment>,
    header: Boolean,
    tableWidth: Dp,
    cellStyle: TextStyle,
    color: Color,
    linkColor: Color,
    codeStyle: TextStyle,
    chatColors: ChatColors,
    showCaret: Boolean,
    caretAlpha: Float,
    horizontalPadding: Dp,
    verticalPadding: Dp,
) {
    Row(
        modifier = Modifier
            .width(tableWidth)
            .background(if (header) color.copy(alpha = 0.06f) else Color.Transparent),
    ) {
        cells.forEachIndexed { column, cell ->
            if (column >= widths.size) return@forEachIndexed
            val alignment = alignments.getOrNull(column) ?: MarkdownAlignment.START
            val textAlign = when (alignment) {
                MarkdownAlignment.START -> TextAlign.Start
                MarkdownAlignment.CENTER -> TextAlign.Center
                MarkdownAlignment.END -> TextAlign.End
            }
            val contentAlignment = when (alignment) {
                MarkdownAlignment.START -> Alignment.CenterStart
                MarkdownAlignment.CENTER -> Alignment.Center
                MarkdownAlignment.END -> Alignment.CenterEnd
            }
            Box(
                modifier = Modifier
                    .width(widths[column])
                    .padding(horizontal = horizontalPadding, vertical = verticalPadding),
                contentAlignment = contentAlignment,
            ) {
                val base = remember(cell, header, linkColor, codeStyle, chatColors) {
                    buildInlineAnnotated(
                        inlines = cell,
                        linkColor = linkColor,
                        codeStyle = codeStyle,
                        chatColors = chatColors,
                        forceBold = header,
                    )
                }
                val annotated = if (showCaret) {
                    remember(base, caretAlpha, chatColors) { base.withCaret(chatColors, caretAlpha) }
                } else {
                    base
                }
                Text(text = annotated, style = cellStyle, color = color, textAlign = textAlign)
            }
        }
    }
}

private fun headingStyle(base: TextStyle, level: Int): TextStyle {
    val factor = when (level) {
        1 -> 1.55f
        2 -> 1.38f
        3 -> 1.22f
        4 -> 1.12f
        5 -> 1.06f
        else -> 1f
    }
    return base.copy(
        fontSize = if (base.fontSize.isSpecified) base.fontSize * factor else 16.sp * factor,
        lineHeight = if (base.lineHeight.isSpecified) base.lineHeight * factor else TextUnit.Unspecified,
        fontWeight = if (level <= 3) FontWeight.SemiBold else FontWeight.Medium,
    )
}

// ---------------------------------------------------------------------------
// Inline rendering
// ---------------------------------------------------------------------------

private fun buildInlineAnnotated(
    inlines: List<MarkdownInline>,
    linkColor: Color,
    codeStyle: TextStyle,
    chatColors: ChatColors,
    forceBold: Boolean = false,
): AnnotatedString = buildAnnotatedString {
    inlines.forEach { inline ->
        var span = SpanStyle()
        if (inline.bold || forceBold) span = span.copy(fontWeight = FontWeight.SemiBold)
        if (inline.italic) span = span.copy(fontStyle = FontStyle.Italic)
        if (inline.strikethrough) span = span.copy(textDecoration = TextDecoration.LineThrough)
        if (inline.code) {
            span = span.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = if (codeStyle.fontSize.isSpecified) codeStyle.fontSize else 13.sp,
                background = chatColors.codeBackground,
                color = codeStyle.color.takeIf { it != Color.Unspecified }
                    ?: chatColors.assistantBubbleText,
            )
        }
        val link = inline.link
        if (link != null) {
            val linkStyle = span.copy(
                color = linkColor,
                textDecoration = TextDecoration.Underline,
            )
            withLink(
                LinkAnnotation.Url(
                    url = link,
                    styles = TextLinkStyles(style = linkStyle),
                ),
            ) {
                withStyle(span) { append(inline.text) }
            }
        } else {
            withStyle(span) { append(inline.text) }
        }
    }
}

private fun AnnotatedString.withCaret(chatColors: ChatColors, caretAlpha: Float): AnnotatedString =
    buildAnnotatedString {
        append(this@withCaret)
        withStyle(
            SpanStyle(
                color = chatColors.caret.copy(alpha = caretAlpha),
                fontWeight = FontWeight.Light,
            ),
        ) {
            append(CaretChar)
        }
    }
