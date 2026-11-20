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
 * The adapter is chosen per call from the current settings (not captured at
 * construction time), so switching providers in settings takes effect without
 * rebuilding the graph. A missing adapter is an expected failure
 * ([AppError.Unsupported]) — never a silent empty result.
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
        val kind = settingsStore.observe().first().activeSearchProvider
        val adapter = adapters[kind]
            ?: return Outcome.Err(AppError.Unsupported("no search adapter configured for $kind"))
        return adapter.search(trimmed)
    }
}
