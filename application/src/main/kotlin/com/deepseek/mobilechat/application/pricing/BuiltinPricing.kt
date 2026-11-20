package com.deepseek.mobilechat.application.pricing

import com.deepseek.mobilechat.domain.ModelPricing

/**
 * Offline price table (USD per 1M tokens).
 *
 * Provider `/models` endpoints do not publish prices, so without a table the
 * per-message cost would always be null. Values are deliberately approximate:
 * the UI labels them as estimates and a future settings screen can override
 * them. Matching is a longest-substring match on the lower-cased model id, so
 * `gpt-4o-mini` wins over `gpt-4o`.
 */
object BuiltinPricing {

    private data class Entry(val pattern: String, val input: Double, val output: Double)

    private val entries: List<Entry> = listOf(
        Entry("gpt-4o-mini", 0.15, 0.60),
        Entry("gpt-4o", 2.50, 10.00),
        Entry("gpt-4.1-nano", 0.10, 0.40),
        Entry("gpt-4.1-mini", 0.40, 1.60),
        Entry("gpt-4.1", 2.00, 8.00),
        Entry("o4-mini", 1.10, 4.40),
        Entry("o3-mini", 1.10, 4.40),
        Entry("o3", 2.00, 8.00),
        Entry("o1-mini", 3.00, 12.00),
        Entry("o1", 15.00, 60.00),
        Entry("claude-3-5-haiku", 0.80, 4.00),
        Entry("claude-3-5-sonnet", 3.00, 15.00),
        Entry("claude-haiku-4", 1.00, 5.00),
        Entry("claude-sonnet-4", 3.00, 15.00),
        Entry("claude-opus-4", 15.00, 75.00),
        Entry("gemini-2.5-pro", 1.25, 10.00),
        Entry("gemini-2.5-flash", 0.30, 2.50),
        Entry("gemini-2.0-flash", 0.10, 0.40),
        Entry("deepseek-reasoner", 0.55, 2.19),
        Entry("deepseek-chat", 0.27, 1.10),
    ).sortedByDescending { it.pattern.length }

    fun lookup(modelId: String): ModelPricing? {
        val id = modelId.lowercase()
        val hit = entries.firstOrNull { id.contains(it.pattern) } ?: return null
        return ModelPricing(inputPerMTokUsd = hit.input, outputPerMTokUsd = hit.output)
    }
}
