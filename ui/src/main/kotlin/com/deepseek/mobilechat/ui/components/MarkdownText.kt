package com.deepseek.mobilechat.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.deepseek.mobilechat.ui.theme.LocalChatColors
import com.deepseek.mobilechat.ui.theme.LocalCodeTextStyle
import com.deepseek.mobilechat.ui.theme.Spacing

/**
 * A deliberately small Markdown subset: fenced code blocks, inline code and
 * `**bold**`. Anything else renders literally — better than a half-working
 * third-party parser that would also drag in a new dependency.
 */
sealed interface MarkdownBlock {
    data class Paragraph(val text: String) : MarkdownBlock

    data class Code(val language: String, val code: String) : MarkdownBlock
}

private const val Fence = "```"
private const val CaretChar = "\u258D"

fun parseMarkdown(source: String): List<MarkdownBlock> {
    if (source.isEmpty()) return emptyList()
    val blocks = ArrayList<MarkdownBlock>()
    val paragraph = StringBuilder()
    val code = StringBuilder()
    var codeLanguage: String? = null

    fun flushParagraph() {
        if (paragraph.isNotEmpty()) {
            blocks += MarkdownBlock.Paragraph(paragraph.toString().trim('\n'))
            paragraph.setLength(0)
        }
    }

    fun flushCode() {
        blocks += MarkdownBlock.Code(codeLanguage.orEmpty(), code.toString().trimEnd('\n'))
        code.setLength(0)
        codeLanguage = null
    }

    source.split('\n').forEach { rawLine ->
        val line = rawLine.trimEnd('\r')
        if (codeLanguage != null) {
            if (line.trimStart().startsWith(Fence)) {
                flushCode()
            } else {
                if (code.isNotEmpty()) code.append('\n')
                code.append(line)
            }
            return@forEach
        }
        val fenceIndex = line.indexOf(Fence)
        if (fenceIndex >= 0) {
            if (fenceIndex > 0) paragraph.append(line, 0, fenceIndex)
            flushParagraph()
            codeLanguage = line.substring(fenceIndex + Fence.length).trim().ifEmpty { "" }
            return@forEach
        }
        if (line.isBlank()) {
            flushParagraph()
        } else {
            if (paragraph.isNotEmpty()) paragraph.append('\n')
            paragraph.append(line)
        }
    }
    // An unterminated fence is normal while streaming: keep what we have.
    if (codeLanguage != null) flushCode()
    flushParagraph()
    return blocks
}

/**
 * Inline markup for one paragraph. Returns the annotated text and leaves
 * unmatched markers as literal characters so half-streamed `**` never flickers
 * into garbage.
 */
private fun buildInlineText(
    text: String,
    codeBackground: Color,
    codeColor: Color,
): AnnotatedString = buildAnnotatedString {
    var index = 0
    while (index < text.length) {
        when {
            text.startsWith("`", index) -> {
                val end = text.indexOf('`', index + 1)
                if (end > index + 1) {
                    withStyle(
                        SpanStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            background = codeBackground,
                            color = codeColor,
                        ),
                    ) {
                        append(text.substring(index + 1, end))
                    }
                    index = end + 1
                } else {
                    append(text[index])
                    index++
                }
            }

            text.startsWith("**", index) -> {
                val end = text.indexOf("**", index + 2)
                if (end > index + 2) {
                    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) {
                        append(text.substring(index + 2, end))
                    }
                    index = end + 2
                } else {
                    append(text[index])
                    index++
                }
            }

            else -> {
                append(text[index])
                index++
            }
        }
    }
}

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

    Column(modifier = modifier) {
        blocks.forEachIndexed { index, block ->
            val isLast = index == blocks.lastIndex
            if (index > 0) {
                Spacer(
                    modifier = Modifier.height(
                        if (block is MarkdownBlock.Code || blocks[index - 1] is MarkdownBlock.Code) {
                            Spacing.sm
                        } else {
                            Spacing.xs
                        },
                    ),
                )
            }
            when (block) {
                is MarkdownBlock.Paragraph -> {
                    // Inline parsing is memoised on the text alone; only the
                    // caret span is rebuilt while the blink animates.
                    val base = remember(block.text, chatColors, color) {
                        buildInlineText(
                            block.text,
                            chatColors.codeBackground,
                            color,
                        )
                    }
                    val annotated = if (streaming && isLast) {
                        remember(base, caretAlpha, chatColors) {
                            buildAnnotatedString {
                                append(base)
                                withStyle(
                                    SpanStyle(
                                        color = chatColors.caret.copy(alpha = caretAlpha),
                                        fontWeight = FontWeight.Light,
                                    ),
                                ) {
                                    append(CaretChar)
                                }
                            }
                        }
                    } else {
                        base
                    }
                    Text(
                        text = annotated,
                        style = style,
                        color = color,
                    )
                }

                is MarkdownBlock.Code -> {
                    val annotated = if (streaming && isLast) {
                        remember(block.code, caretAlpha, chatColors) {
                            buildAnnotatedString {
                                append(block.code)
                                if (block.code.isNotEmpty()) append('\n')
                                withStyle(SpanStyle(color = chatColors.caret.copy(alpha = caretAlpha))) {
                                    append(CaretChar)
                                }
                            }
                        }
                    } else {
                        remember(block.code) { AnnotatedString(block.code) }
                    }
                    CodeBlock(
                        code = annotated,
                        language = block.language,
                        codeStyle = codeStyle,
                    )
                }
            }
        }
    }
}

@Composable
private fun CodeBlock(
    code: AnnotatedString,
    language: String,
    codeStyle: TextStyle,
) {
    val chatColors = LocalChatColors.current
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = chatColors.codeBackground,
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, chatColors.codeBorder),
    ) {
        Column(modifier = Modifier.padding(horizontal = Spacing.md, vertical = Spacing.sm)) {
            if (language.isNotBlank()) {
                Text(
                    text = language,
                    style = MaterialTheme.typography.labelSmall,
                    color = chatColors.mutedText,
                )
                Spacer(modifier = Modifier.height(Spacing.xxs))
            }
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
