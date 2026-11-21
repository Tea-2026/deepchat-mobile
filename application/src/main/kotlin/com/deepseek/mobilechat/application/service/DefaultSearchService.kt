package com.deepseek.mobilechat.application.service

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.SearchPort
import com.deepseek.mobilechat.application.SearchProviderKind
import com.deepseek.mobilechat.application.SearchResponse
import com.deepseek.mobilechat.application.SearchService
import com.deepseek.mobilechat.application.SettingsStore
import kotlinx.coroutines.flow.first

/**
 * Default [SearchService].
 *
 * The settings-selected source is tried first; if it is missing or not
 * configured, search falls back in a fixed order: [SearchProviderKind.DEEPSEEK]
 * (keyless — reuses the DeepSeek chat provider key), then
 * [SearchProviderKind.BING] (keyless RSS), then any other configured adapter.
 *
 * A source that reports [AppError.SecretMissing] is treated the same as an
 * unconfigured one, because that is what "configured" means at the port
 * boundary. Any other failure is surfaced immediately: a rate limit or a
 * protocol error must not be masked by a lower-quality fallback. Nothing is
 * cached and nothing is retried.
 */
class DefaultSearchService(
    private val adapters: Map<SearchProviderKind, SearchPort>,
    private val settingsStore: SettingsStore,
) : SearchService {

    override suspend fun webSearch(query: String): Outcome<SearchResponse> {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return Outcome.Err(AppError.Validation("search query must not be empty"))
        }

        val preferred = settingsStore.observe().first().activeSearchProvider
        val candidates = buildList {
            add(preferred)
            add(SearchProviderKind.DEEPSEEK)
            add(SearchProviderKind.BING)
            addAll(adapters.keys)
        }.distinct()

        for (kind in candidates) {
            val adapter = adapters[kind] ?: continue
            if (!adapter.isConfiguredSafely()) continue
            when (val outcome = adapter.search(trimmed)) {
                is Outcome.Ok -> return outcome
                is Outcome.Err -> {
                    if (outcome.error !is AppError.SecretMissing) return outcome
                    // A key that vanished between the check and the call: keep
                    // falling back, never show the user a search that did not run.
                }
            }
        }

        return Outcome.Err(
            AppError.Unsupported("web search is not configured: no search provider is available"),
        )
    }
}

/** A source that cannot answer "am I configured?" is treated as unconfigured. */
private suspend fun SearchPort.isConfiguredSafely(): Boolean = try {
    isConfigured()
} catch (t: kotlinx.coroutines.CancellationException) {
    throw t
} catch (t: Throwable) {
    false
}
