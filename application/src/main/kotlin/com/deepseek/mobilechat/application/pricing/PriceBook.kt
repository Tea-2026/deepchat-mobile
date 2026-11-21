package com.deepseek.mobilechat.application.pricing

import com.deepseek.mobilechat.domain.Currency
import com.deepseek.mobilechat.domain.ModelPricing
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneOffset

/**
 * USD → CNY, fixed on purpose.
 *
 * The user's requirement is "never make me think about an exchange rate".
 * Pulling a live rate would make yesterday's estimate disagree with today's for
 * no benefit, so the rate is a constant and every USD figure the app shows is
 * labelled 约.
 */
object Fx {
    const val USD_TO_CNY: Double = 7.2

    fun toCny(amount: Double, currency: Currency): Double = when (currency) {
        Currency.CNY -> amount
        Currency.USD -> amount * USD_TO_CNY
    }
}

/**
 * Offline price book, in each provider's own currency.
 *
 * Two reasons this exists instead of trusting the catalog: provider `/models`
 * endpoints publish no prices, and DeepSeek's prices are quoted in CNY and
 * change by time of day, so converting them at the wrong moment is simply
 * wrong. Values last verified 2026-11-21 against
 * `api-docs.deepseek.com/zh-cn/quick_start/pricing`.
 *
 * Matching is longest-substring on the lower-cased model id, so
 * `gpt-4o-mini` wins over `gpt-4o`.
 */
object PriceBook {

    /**
     * One model family. [idle] price is the off-peak one; when a provider has no
     * time-of-day pricing the two are equal.
     */
    private data class Entry(
        val pattern: String,
        val peak: ModelPricing,
        val idle: ModelPricing = peak,
    )

    private fun usd(input: Double, output: Double, cached: Double? = null) = ModelPricing(
        inputPerMTok = input,
        outputPerMTok = output,
        cachedInputPerMTok = cached,
        currency = Currency.USD,
    )

    private fun cny(peakInput: Double, peakOutput: Double, peakCached: Double) = ModelPricing(
        inputPerMTok = peakInput,
        outputPerMTok = peakOutput,
        cachedInputPerMTok = peakCached,
        currency = Currency.CNY,
    )

    private fun deepSeekEntry(
        pattern: String,
        peakInput: Double,
        peakOutput: Double,
        peakCached: Double,
    ) = Entry(
        pattern = pattern,
        peak = cny(peakInput, peakOutput, peakCached),
        // DeepSeek halves every rate outside its peak window.
        idle = cny(peakInput / 2, peakOutput / 2, peakCached / 2),
    )

    private val entries: List<Entry> = listOf(
        deepSeekEntry("deepseek-v4-pro", 9.0, 27.0, 0.30),
        deepSeekEntry("deepseek-flash", 2.0, 8.0, 0.04),
        Entry("gpt-4o-mini", usd(0.15, 0.60, 0.075)),
        Entry("gpt-4o", usd(2.50, 10.00, 1.25)),
        Entry("gpt-4.1-nano", usd(0.10, 0.40, 0.025)),
        Entry("gpt-4.1-mini", usd(0.40, 1.60, 0.10)),
        Entry("gpt-4.1", usd(2.00, 8.00, 0.50)),
        Entry("o4-mini", usd(1.10, 4.40, 0.275)),
        Entry("o3-mini", usd(1.10, 4.40, 0.55)),
        Entry("o3", usd(2.00, 8.00, 0.50)),
        Entry("o1-mini", usd(3.00, 12.00, 1.50)),
        Entry("o1", usd(15.00, 60.00, 7.50)),
        Entry("claude-3-5-haiku", usd(0.80, 4.00, 0.08)),
        Entry("claude-3-5-sonnet", usd(3.00, 15.00, 0.30)),
        Entry("claude-haiku-4", usd(1.00, 5.00, 0.10)),
        Entry("claude-sonnet-4", usd(3.00, 15.00, 0.30)),
        Entry("claude-opus-4", usd(15.00, 75.00, 1.50)),
        Entry("gemini-2.5-pro", usd(1.25, 10.00, 0.31)),
        Entry("gemini-2.5-flash", usd(0.30, 2.50, 0.075)),
        Entry("gemini-2.0-flash", usd(0.10, 0.40, 0.025)),
    ).sortedByDescending { it.pattern.length }

    /**
     * Price for [modelId] at the moment [atMillis], or `null` when the book does
     * not know it. `null` is a real answer: the UI offers to let the user type a
     * price rather than showing a made-up number.
     */
    fun lookup(modelId: String, atMillis: Long): ModelPricing? {
        val id = modelId.lowercase()
        val hit = entries.firstOrNull { id.contains(it.pattern) } ?: return null
        return if (isDeepSeekPeak(atMillis)) hit.peak else hit.idle
    }

    /**
     * Attaches an estimated CNY cost to [usage] for [modelId] at [atMillis].
     *
     * Returns the usage unchanged when the book does not know the model: a
     * missing price must stay `null`, never become a confident ¥0.
     */
    fun priceUsage(
        modelId: String,
        usage: com.deepseek.mobilechat.domain.Usage,
        atMillis: Long,
    ): com.deepseek.mobilechat.domain.Usage {
        val pricing = lookup(modelId, atMillis) ?: return usage
        val cached = usage.cachedPromptTokens.coerceIn(0, usage.promptTokens)
        val fresh = usage.promptTokens - cached
        val cachedRate = pricing.cachedInputPerMTok ?: pricing.inputPerMTok
        val raw = fresh * pricing.inputPerMTok +
            cached * cachedRate +
            usage.completionTokens * pricing.outputPerMTok
        return usage.copy(cost = Fx.toCny(raw / 1_000_000.0, pricing.currency))
    }

    /**
     * DeepSeek's peak window: Beijing time, Monday–Friday, 09:00–12:00 and
     * 14:00–18:00, excluding Chinese public holidays (holidays are not modelled
     * — a holiday is billed at the cheaper idle rate, so treating it as peak
     * only over-estimates).
     */
    fun isDeepSeekPeak(atMillis: Long): Boolean {
        val beijing = Instant.ofEpochMilli(atMillis).atZone(ZoneOffset.ofHours(8))
        if (beijing.dayOfWeek == DayOfWeek.SATURDAY || beijing.dayOfWeek == DayOfWeek.SUNDAY) {
            return false
        }
        val hour = beijing.hour
        return hour in 9..11 || hour in 14..17
    }
}
