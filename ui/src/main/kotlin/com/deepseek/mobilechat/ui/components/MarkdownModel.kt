package com.deepseek.mobilechat.ui.components

import org.intellij.markdown.MarkdownElementTypes
import org.intellij.markdown.MarkdownTokenTypes
import org.intellij.markdown.ast.ASTNode
import org.intellij.markdown.flavours.gfm.GFMElementTypes
import org.intellij.markdown.flavours.gfm.GFMFlavourDescriptor
import org.intellij.markdown.flavours.gfm.GFMTokenTypes
import org.intellij.markdown.parser.CancellationToken
import org.intellij.markdown.parser.MarkdownParser

// ---------------------------------------------------------------------------
// The block model.
//
// `parseMarkdown` is a *pure* function on purpose: it is the part that has to
// stay testable on the JVM (no Android, no Compose) and it is also the part
// that runs on every streamed token. Rendering is hand-written in
// MarkdownText.kt against this model so the transcript keeps the app's look.
// ---------------------------------------------------------------------------

sealed interface MarkdownBlock {
    /** Plain paragraph; inline markup lives in [inlines]. */
    data class Paragraph(val inlines: List<MarkdownInline>) : MarkdownBlock

    /** ATX (`#`) or setext (`===`) heading, [level] 1..6. */
    data class Heading(val level: Int, val inlines: List<MarkdownInline>) : MarkdownBlock

    /**
     * Fenced (``` / ~~~) or 4-space-indented code. [language] is empty for
     * indented blocks and unterminated fences still produce a block.
     */
    data class Code(val language: String, val code: String) : MarkdownBlock

    /** Ordered/unordered list. Nested lists live inside the item's [MarkdownListItem.blocks]. */
    data class ListBlock(
        val ordered: Boolean,
        /** First item's number for ordered lists (respects "3. …"), always 1 otherwise. */
        val start: Int,
        val items: List<MarkdownListItem>,
    ) : MarkdownBlock

    /** Block quote; may contain paragraphs, lists, code, nested quotes. */
    data class Quote(val blocks: List<MarkdownBlock>) : MarkdownBlock

    /** GFM table with one entry per column in [alignments] (already padded). */
    data class Table(
        val header: List<List<MarkdownInline>>,
        val alignments: List<MarkdownAlignment>,
        val rows: List<List<List<MarkdownInline>>>,
    ) : MarkdownBlock

    /** `---`, `***`, `___`. */
    data object ThematicBreak : MarkdownBlock
}

/** One item of a [MarkdownBlock.ListBlock]. */
data class MarkdownListItem(
    /** 1-based source number for ordered lists, 0 for bullet items. */
    val number: Int,
    val blocks: List<MarkdownBlock>,
)

/** Column alignment declared by a GFM table's separator row. */
enum class MarkdownAlignment { START, CENTER, END }

/**
 * A run of inline text with the markup that applies to it. These flags compose:
 * `***bold italic***` is one run with both [bold] and [italic] (or a bold run
 * inside an italic one, depending on how the tree nests); `~~**x**~~` keeps
 * [strikethrough] and [bold].
 */
data class MarkdownInline(
    val text: String,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strikethrough: Boolean = false,
    /** Target of an inline link; null for plain text. */
    val link: String? = null,
)

/** Concatenated text of a run list, ignoring styling. */
internal fun inlinesText(inlines: List<MarkdownInline>): String = buildString {
    inlines.forEach { append(it.text) }
}

/**
 * Parses [source] into the block model.
 *
 * Streaming contract: the input may stop in the middle of anything. The parser
 * never throws — unknown/unclosed constructs degrade to literal text (a lone
 * `**` is two literal stars, an open fence is a code block with what arrived,
 * a half-written table row is either a short row or plain text) — so the
 * renderer can redraw on every token without special cases.
 */
fun parseMarkdown(source: String): List<MarkdownBlock> {
    if (source.isEmpty()) return emptyList()
    val tree = runCatching { markdownParser.buildMarkdownTreeFromString(source as CharSequence) }
        .getOrElse { return literalFallback(source) }
    return runCatching { collectBlocks(tree, source) }.getOrElse { literalFallback(source) }
}

/**
 * The parser keeps no per-call state, but it is not documented as thread-safe;
 * the whole app parses on the main thread (UI streaming) and in JVM tests.
 */
private val markdownFlavour: GFMFlavourDescriptor by lazy { GFMFlavourDescriptor() }
private val markdownParser: MarkdownParser by lazy {
    MarkdownParser(markdownFlavour, false, CancellationToken.NonCancellable)
}

private fun literalFallback(source: String): List<MarkdownBlock> {
    if (source.isBlank()) return emptyList()
    return listOf(MarkdownBlock.Paragraph(listOf(MarkdownInline(source.trimEnd('\n')))))
}

private fun nodeText(node: ASTNode, source: String): String =
    source.substring(node.startOffset.coerceIn(0, source.length), node.endOffset.coerceIn(0, source.length))

// ---------------------------------------------------------------------------
// Block level
// ---------------------------------------------------------------------------

private fun collectBlocks(container: ASTNode, source: String): List<MarkdownBlock> {
    val blocks = ArrayList<MarkdownBlock>()
    for (child in container.children) {
        when (child.type) {
            MarkdownElementTypes.PARAGRAPH ->
                blocks += MarkdownBlock.Paragraph(inlineRuns(child, source))

            MarkdownElementTypes.ATX_1 -> blocks += heading(1, child, source)
            MarkdownElementTypes.ATX_2 -> blocks += heading(2, child, source)
            MarkdownElementTypes.ATX_3 -> blocks += heading(3, child, source)
            MarkdownElementTypes.ATX_4 -> blocks += heading(4, child, source)
            MarkdownElementTypes.ATX_5 -> blocks += heading(5, child, source)
            MarkdownElementTypes.ATX_6 -> blocks += heading(6, child, source)
            MarkdownElementTypes.SETEXT_1 -> blocks += heading(1, child, source)
            MarkdownElementTypes.SETEXT_2 -> blocks += heading(2, child, source)

            MarkdownElementTypes.UNORDERED_LIST ->
                blocks += listBlock(child, source, ordered = false)
            MarkdownElementTypes.ORDERED_LIST ->
                blocks += listBlock(child, source, ordered = true)

            MarkdownElementTypes.CODE_FENCE -> blocks += fencedCode(child, source)
            MarkdownElementTypes.CODE_BLOCK -> blocks += indentedCode(child, source)

            MarkdownElementTypes.BLOCK_QUOTE ->
                blocks += MarkdownBlock.Quote(collectBlocks(child, source))

            MarkdownTokenTypes.HORIZONTAL_RULE -> blocks += MarkdownBlock.ThematicBreak

            MarkdownElementTypes.HTML_BLOCK -> {
                val raw = nodeText(child, source).trim()
                if (raw.isNotEmpty()) {
                    blocks += MarkdownBlock.Paragraph(listOf(MarkdownInline(raw)))
                }
            }

            // Link reference definitions are metadata, not visible content.
            MarkdownElementTypes.LINK_DEFINITION -> Unit

            GFMElementTypes.TABLE -> blocks += table(child, source)

            else -> {
                // Unknown container: descend so a plugin flavour cannot make
                // text disappear. Leaf tokens that reach here are part of an
                // enclosing block and are ignored.
                if (child.children.isNotEmpty()) {
                    blocks += collectBlocks(child, source)
                }
            }
        }
    }
    return blocks
}

private fun heading(level: Int, node: ASTNode, source: String): MarkdownBlock.Heading {
    val content = node.children.firstOrNull {
        it.type == MarkdownTokenTypes.ATX_CONTENT || it.type == MarkdownTokenTypes.SETEXT_CONTENT
    }
    val inlines = content?.let { inlineRuns(it, source) }.orEmpty()
    return MarkdownBlock.Heading(level.coerceIn(1, 6), inlines)
}

private fun listBlock(node: ASTNode, source: String, ordered: Boolean): MarkdownBlock.ListBlock {
    val items = node.children
        .filter { it.type == MarkdownElementTypes.LIST_ITEM }
        .map { item ->
            MarkdownListItem(
                number = if (ordered) listItemNumber(item, source) else 0,
                blocks = collectBlocks(item, source),
            )
        }
    val start = if (ordered) items.firstOrNull()?.number?.takeIf { it > 0 } ?: 1 else 1
    return MarkdownBlock.ListBlock(ordered = ordered, start = start, items = items)
}

private fun listItemNumber(item: ASTNode, source: String): Int {
    val marker = item.children.firstOrNull { it.type == MarkdownTokenTypes.LIST_NUMBER } ?: return 1
    return nodeText(marker, source).trim().trimEnd('.').toIntOrNull() ?: 1
}

private fun fencedCode(node: ASTNode, source: String): MarkdownBlock.Code {
    var language = ""
    val body = StringBuilder()
    for (child in node.children) {
        when (child.type) {
            MarkdownTokenTypes.FENCE_LANG -> language = nodeText(child, source).trim()
            MarkdownTokenTypes.CODE_FENCE_CONTENT -> body.append(nodeText(child, source))
            MarkdownTokenTypes.EOL -> body.append('\n')
            else -> Unit
        }
    }
    // The EOL right after the opening fence is structural, not the first code
    // line; the EOL before the closing fence (and trailing blank lines) are
    // layout, not code. A deliberately blank first code line survives because
    // only one leading newline is removed.
    return MarkdownBlock.Code(
        language = language,
        code = body.toString().removePrefix("\n").trimEnd('\n'),
    )
}

private fun indentedCode(node: ASTNode, source: String): MarkdownBlock.Code {
    val raw = nodeText(node, source)
    val body = raw
        .split('\n')
        .joinToString("\n") { line ->
            when {
                line.startsWith("\t") -> line.substring(1)
                line.startsWith("    ") -> line.substring(4)
                else -> line
            }
        }
        .trimEnd('\n')
    return MarkdownBlock.Code(language = "", code = body)
}

private fun table(node: ASTNode, source: String): MarkdownBlock.Table {
    val header = node.children
        .firstOrNull { it.type == GFMElementTypes.HEADER }
        ?.tableCells(source)
        .orEmpty()
    val rows = node.children
        .filter { it.type == GFMElementTypes.ROW }
        .map { it.tableCells(source) }
    val alignments = tableAlignments(node, source)

    val columns = maxOf(header.size, alignments.size, rows.maxOfOrNull { it.size } ?: 0)
    val paddedHeader = List(columns) { header.getOrNull(it).orEmpty() }
    val paddedRows = rows.map { row -> List(columns) { row.getOrNull(it).orEmpty() } }
    val paddedAlignments = List(columns) { alignments.getOrNull(it) ?: MarkdownAlignment.START }
    return MarkdownBlock.Table(paddedHeader, paddedAlignments, paddedRows)
}

private fun ASTNode.tableCells(source: String): List<List<MarkdownInline>> = children
    .filter { it.type == GFMTokenTypes.CELL }
    .map { inlineRuns(it, source) }

/**
 * GFM puts the whole `| :-- | --: |` line in one TABLE_SEPARATOR token (the
 * row's own separators are separate `|` tokens), so alignment is read from the
 * token text rather than from the tree shape.
 */
private fun tableAlignments(node: ASTNode, source: String): List<MarkdownAlignment> {
    val separator = node.children.firstOrNull {
        it.type == GFMTokenTypes.TABLE_SEPARATOR && nodeText(it, source).contains('-')
    } ?: return emptyList()
    return nodeText(separator, source)
        .trim()
        .trim('|')
        .split('|')
        .map { cell ->
            val value = cell.trim()
            when {
                value.startsWith(":") && value.endsWith(":") -> MarkdownAlignment.CENTER
                value.endsWith(":") -> MarkdownAlignment.END
                else -> MarkdownAlignment.START
            }
        }
}

// ---------------------------------------------------------------------------
// Inline level
// ---------------------------------------------------------------------------

private data class InlineContext(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val code: Boolean = false,
    val strikethrough: Boolean = false,
    val link: String? = null,
)

private fun inlineRuns(node: ASTNode, source: String): List<MarkdownInline> {
    val runs = ArrayList<MarkdownInline>()
    appendInline(node, source, InlineContext(), runs)
    return normalizeInlines(runs)
}

private fun isEmphasisMarker(type: org.intellij.markdown.IElementType): Boolean =
    type == MarkdownTokenTypes.EMPH || type == GFMTokenTypes.TILDE

private fun appendInline(
    node: ASTNode,
    source: String,
    context: InlineContext,
    out: MutableList<MarkdownInline>,
) {
    for (child in node.children) {
        appendNode(child, source, context, out)
    }
}

private fun appendNode(
    child: ASTNode,
    source: String,
    context: InlineContext,
    out: MutableList<MarkdownInline>,
) {
    when (child.type) {
        MarkdownElementTypes.STRONG ->
            appendStyled(child, source, context.copy(bold = true), out)

        MarkdownElementTypes.EMPH ->
            if (child.children.isEmpty()) {
                // Unmatched marker: render it literally instead of guessing.
                out += run(nodeText(child, source), context)
            } else {
                appendStyled(child, source, context.copy(italic = true), out)
            }

        GFMElementTypes.STRIKETHROUGH ->
            appendStyled(child, source, context.copy(strikethrough = true), out)

        MarkdownElementTypes.CODE_SPAN -> {
            val code = StringBuilder()
            child.children.forEach { inner ->
                // Backticks delimit the span; everything else is literal
                // text even when it looks like markup.
                if (inner.type != MarkdownTokenTypes.BACKTICK) {
                    code.append(nodeText(inner, source))
                }
            }
            out += run(code.toString(), context.copy(code = true))
        }

        MarkdownElementTypes.INLINE_LINK -> {
            val url = linkDestination(child, source)
            val label = child.children.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
            if (url != null && label != null) {
                appendInline(label, source, context.copy(link = url), out)
            } else {
                out += run(nodeText(child, source), context)
            }
        }

        MarkdownElementTypes.AUTOLINK -> {
            val raw = nodeText(child, source).trim().removeSurrounding("<", ">")
            if (raw.isNotEmpty()) out += run(raw, context.copy(link = raw))
        }

        MarkdownElementTypes.IMAGE -> {
            // No bitmap loader for remote markdown images (and no image
            // block in the model yet): surface the alt text so the answer
            // does not turn into a blank line.
            val link = child.children.firstOrNull { it.type == MarkdownElementTypes.INLINE_LINK }
            val label = link?.children?.firstOrNull { it.type == MarkdownElementTypes.LINK_TEXT }
            val alt = label?.let { inlinesText(inlineRuns(it, source)).trim() }.orEmpty()
            if (alt.isNotEmpty()) out += run(alt, context)
        }

        MarkdownElementTypes.FULL_REFERENCE_LINK,
        MarkdownElementTypes.SHORT_REFERENCE_LINK,
        MarkdownElementTypes.LINK_DEFINITION,
        MarkdownElementTypes.LINK_LABEL,
        MarkdownElementTypes.LINK_DESTINATION,
        MarkdownElementTypes.LINK_TITLE ->
            // Reference-style links need the link map; keep the source
            // visible rather than silently dropping the text.
            out += run(nodeText(child, source), context)

        MarkdownTokenTypes.HTML_TAG -> out += run(nodeText(child, source), context)

        MarkdownTokenTypes.EOL,
        MarkdownTokenTypes.HARD_LINE_BREAK -> out += run("\n", context)

        // Structural inline punctuation. Keeping any of it would print
        // markdown syntax on screen; the matching content is emitted by the
        // composite's own handler.
        MarkdownTokenTypes.BACKTICK,
        MarkdownTokenTypes.LBRACKET,
        MarkdownTokenTypes.RBRACKET,
        MarkdownTokenTypes.LPAREN,
        MarkdownTokenTypes.RPAREN,
        MarkdownTokenTypes.EXCLAMATION_MARK,
        MarkdownTokenTypes.COLON,
        MarkdownTokenTypes.SINGLE_QUOTE,
        MarkdownTokenTypes.DOUBLE_QUOTE,
        MarkdownTokenTypes.LT,
        MarkdownTokenTypes.GT,
        GFMTokenTypes.TILDE -> Unit

        else -> {
            if (child.children.isEmpty()) {
                out += run(nodeText(child, source), context)
            } else {
                appendInline(child, source, context, out)
            }
        }
    }
}

/**
 * Emits the content of a styled composite (`**…**`, `*…*`, `~~…~~`) while
 * dropping the delimiter tokens that the parser keeps as edge children.
 * Literal marker characters *inside* the content are preserved.
 */
private fun appendStyled(
    node: ASTNode,
    source: String,
    context: InlineContext,
    out: MutableList<MarkdownInline>,
) {
    val children = node.children
    val first = children.indexOfFirst { !isEmphasisMarker(it.type) }
    if (first < 0) return
    val last = children.indexOfLast { !isEmphasisMarker(it.type) }
    for (index in first..last) {
        appendNode(children[index], source, context, out)
    }
}

/**
 * Merges adjacent runs with identical styling, drops empty ones and trims the
 * paragraph edges (the parser keeps block markers' whitespace as children).
 */
private fun normalizeInlines(raw: List<MarkdownInline>): List<MarkdownInline> {
    val merged = ArrayList<MarkdownInline>(raw.size)
    raw.forEach { run ->
        if (run.text.isEmpty()) return@forEach
        val previous = merged.lastOrNull()
        if (previous != null && previous.sameStyleAs(run)) {
            merged[merged.lastIndex] = previous.copy(text = previous.text + run.text)
        } else {
            merged += run
        }
    }
    while (merged.isNotEmpty()) {
        val first = merged.first()
        val trimmed = first.text.trimStart()
        if (trimmed.isEmpty()) {
            merged.removeAt(0)
        } else {
            merged[0] = first.copy(text = trimmed)
            break
        }
    }
    while (merged.isNotEmpty()) {
        val last = merged.last()
        val trimmed = last.text.trimEnd()
        if (trimmed.isEmpty()) {
            merged.removeAt(merged.lastIndex)
        } else {
            merged[merged.lastIndex] = last.copy(text = trimmed)
            break
        }
    }
    return merged
}

private fun MarkdownInline.sameStyleAs(other: MarkdownInline): Boolean =
    bold == other.bold &&
        italic == other.italic &&
        code == other.code &&
        strikethrough == other.strikethrough &&
        link == other.link

private fun run(text: String, context: InlineContext): MarkdownInline = MarkdownInline(
    text = text,
    bold = context.bold,
    italic = context.italic,
    code = context.code,
    strikethrough = context.strikethrough,
    link = context.link,
)

private fun linkDestination(link: ASTNode, source: String): String? {
    val destination = link.children.firstOrNull { it.type == MarkdownElementTypes.LINK_DESTINATION }
        ?: return null
    // The destination node's raw text is the URL, parentheses included; only
    // angle-bracket form needs unwrapping.
    val raw = nodeText(destination, source).trim().removeSurrounding("<", ">")
    return raw.ifBlank { null }
}
