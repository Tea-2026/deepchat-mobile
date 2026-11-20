package com.deepseek.mobilechat.application.context

import com.deepseek.mobilechat.application.ModelBudgetLookup
import com.deepseek.mobilechat.domain.ModelRef

/**
 * Constant context-window table.
 *
 * The lookup is intentionally static for v1: no network, no catalog cache, so
 * context assembly stays pure and testable. A later implementation can back the
 * same port with a cached [com.deepseek.mobilechat.application.ModelCatalogPort]
 * snapshot without touching the assembler.
 *
 * Matching is `modelId.lowercase().contains(fragment)` over [knownModels],
 * first match wins; unknown models fall back to [DEFAULT_CONTEXT_WINDOW].
 */
class ConstantModelBudgetLookup(
    private val fallback: Int = DEFAULT_CONTEXT_WINDOW,
    private val knownModels: List<Entry> = DEFAULT_ENTRIES,
) : ModelBudgetLookup {

    override fun contextWindow(modelRef: ModelRef): Int {
        val modelId = modelRef.modelId.raw.lowercase()
        for (entry in knownModels) {
            if (modelId.contains(entry.fragment)) return entry.contextWindow
        }
        return fallback
    }

    /**
     * A model-id fragment mapped to its context window.
     * [fragment] must already be lowercase; put the most specific fragments
     * first, matching stops at the first hit.
     */
    data class Entry(val fragment: String, val contextWindow: Int)

    companion object {
        /** Context window assumed for everything the table does not know. */
        const val DEFAULT_CONTEXT_WINDOW: Int = 32_000

        val DEFAULT_ENTRIES: List<Entry> = listOf(
            Entry("gemini", 1_000_000),
            Entry("claude", 200_000),
            Entry("gpt-4o", 128_000),
            Entry("gpt-4.1", 128_000),
        )
    }
}
