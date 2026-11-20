package com.deepseek.mobilechat.ui.chat

import com.deepseek.mobilechat.domain.SearchHit

/**
 * Turns web-search hits into the context block that rides along on the
 * ephemeral-injection channel of `ChatService.send`.
 *
 * Pure on purpose: this text is what the model actually reads, so the shape
 * (order, numbering, truncation, the "verify the sources" note) is worth
 * testing without a screen.
 */

internal const val SearchMaxHits = 5
internal const val SearchSnippetLimit = 200

/** Collapses newlines/whitespace runs so a snippet cannot break the layout. */
private fun String.oneLine(): String = replace(Whitespace, " ").trim()

private val Whitespace = Regex("\\s+")

private fun String.limit(limit: Int): String =
    if (length <= limit) this else take(limit) + "…"

internal fun formatSearchInjection(query: String, hits: List<SearchHit>): String = buildString {
    append("【联网搜索结果：").append(query.trim()).append("】")
    if (hits.isEmpty()) {
        append('\n').append("（没有找到相关结果）")
    } else {
        hits.take(SearchMaxHits).forEachIndexed { index, hit ->
            val url = hit.url.trim()
            val title = hit.title.oneLine().ifBlank { url }
            append('\n').append(index + 1).append(". ").append(title)
            if (url.isNotEmpty()) append('\n').append("   ").append(url)
            val snippet = hit.snippet.oneLine()
            if (snippet.isNotEmpty()) {
                append('\n').append("   ").append(snippet.limit(SearchSnippetLimit))
            }
        }
    }
    append('\n').append("以上结果来自网络搜索，请核对来源。")
}

/** One line that explains why the answer has no web context. */
internal fun searchFailureInjection(reason: String): String =
    "联网搜索失败：${reason.trim().ifBlank { "未知错误" }}"

/** Joins the user's manual injection, search context and failure note. */
internal fun mergeInjections(vararg parts: String?): String? =
    parts.mapNotNull { it?.trim() }
        .filter { it.isNotEmpty() }
        .takeIf { it.isNotEmpty() }
        ?.joinToString("\n\n")
