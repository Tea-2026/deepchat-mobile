package com.deepseek.mobilechat.ui.components

import com.deepseek.mobilechat.domain.SearchHit

/**
 * How many sources the collapsed/initial view shows. Kept small: the panel
 * lives inside a bubble and five rows already cover "where did this come
 * from" without pushing the transcript around.
 */
internal const val SearchSourceLimit = 5

/** The slice of [hits] the panel should draw, plus what is left over. */
internal data class SearchSourceView(
    val visible: List<SearchHit>,
    val hidden: Int,
)

/**
 * Pure view-model for the sources list: drops hits without a URL, de-dupes by
 * URL (search providers happily return the same page twice) and cuts the list
 * to [limit] unless [showAll] is set.
 */
internal fun searchSourceView(
    hits: List<SearchHit>,
    showAll: Boolean = false,
    limit: Int = SearchSourceLimit,
): SearchSourceView {
    val usable = hits
        .filter { it.url.isNotBlank() }
        .distinctBy { it.url.trim() }
    if (usable.isEmpty()) return SearchSourceView(emptyList(), 0)
    val visibleCount = if (showAll) usable.size else limit.coerceIn(0, usable.size)
    return SearchSourceView(
        visible = usable.take(visibleCount),
        hidden = usable.size - visibleCount,
    )
}

/**
 * Display hostname for a URL. Deliberately hand-parsed (no android.net.Uri, no
 * java.net.URI) so it stays a pure JVM function and never throws on the
 * half-valid URLs models like to cite.
 */
internal fun hostOf(url: String): String {
    var value = url.trim()
    if (value.isEmpty()) return ""
    val scheme = value.indexOf("://")
    if (scheme >= 0) value = value.substring(scheme + 3)
    value = value
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
        .substringAfter('@') // user:pass@host
        .substringBefore(':') // :port
    return value.removePrefix("www.").trimEnd('.').lowercase()
}

/** Flattens a snippet to one line and truncates it for the compact row. */
internal fun oneLineSnippet(text: String, maxChars: Int = 150): String {
    val flat = text.replace(WhitespaceRun, " ").trim()
    if (flat.length <= maxChars) return flat
    var cut = flat.take(maxChars).trimEnd()
    if (cut.isNotEmpty() && cut.last().isHighSurrogate()) cut = cut.dropLast(1)
    return cut + "…"
}

/** What to print as the row's title; blank titles fall back to the hostname. */
internal fun sourceTitle(hit: SearchHit): String {
    val title = hit.title.trim()
    if (title.isNotEmpty()) return title
    val host = hostOf(hit.url)
    return host.ifEmpty { "网页" }
}

private val WhitespaceRun = Regex("\\s+")
