package com.deepseek.mobilechat.application.service

import com.deepseek.mobilechat.application.ModelCatalogPort
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.pricing.Fx
import com.deepseek.mobilechat.application.pricing.PriceBook
import com.deepseek.mobilechat.domain.ModelDescriptor
import com.deepseek.mobilechat.domain.ModelId
import com.deepseek.mobilechat.domain.ProviderProfile
import com.deepseek.mobilechat.domain.Usage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-memory model catalog cache.
 *
 * Two jobs:
 *
 * 1. [descriptor] answers "what is this model?" without hitting the network on
 *    every streaming checkpoint. The first access per provider calls
 *    [ModelCatalogPort.listModels] and keeps the result for the process
 *    lifetime.
 * 2. [costCny] turns a [Usage] into money using the descriptor's pricing, so
 *    every assistant message can persist an estimated cost.
 *
 * Failure contract: the catalog is an optimisation, never a hard dependency.
 * A failed or throwing [ModelCatalogPort] lookup yields `null` and does *not*
 * poison the cache — the next call retries. Cancellation always propagates.
 */
class ModelCatalogCache(private val catalog: ModelCatalogPort) {

    private val lock = Mutex()
    private val byProvider = HashMap<String, List<ModelDescriptor>>()

    /** Descriptor for [modelId] under [profile], or `null` when unknown/unavailable. */
    suspend fun descriptor(profile: ProviderProfile, modelId: ModelId): ModelDescriptor? {
        val models = models(profile)
        return models.firstOrNull { it.id == modelId }
    }

    /**
     * Estimated cost in **CNY**, or `null` when neither the catalog nor the
     * offline book knows the model.
     *
     * Cache hits are priced separately: they are the reason a long conversation
     * costs a fraction of what a naive prompt-token count suggests. When the
     * provider has no cache-hit rate, hits are charged as misses.
     */
    fun costCny(
        descriptor: ModelDescriptor?,
        usage: Usage,
        atMillis: Long = System.currentTimeMillis(),
    ): Double? {
        val pricing = descriptor?.pricing
            ?: descriptor?.let { PriceBook.lookup(it.id.raw, atMillis) }
            ?: return null
        val cached = usage.cachedPromptTokens.coerceIn(0, usage.promptTokens)
        val fresh = usage.promptTokens - cached
        val cachedRate = pricing.cachedInputPerMTok ?: pricing.inputPerMTok
        val raw = fresh * pricing.inputPerMTok +
            cached * cachedRate +
            usage.completionTokens * pricing.outputPerMTok
        return Fx.toCny(raw / TOKENS_PER_MILLION, pricing.currency)
    }

    /** Drops the cached catalog of one provider (e.g. after editing its profile). */
    suspend fun invalidate(providerId: String) {
        lock.withLock { byProvider.remove(providerId) }
    }

    private suspend fun models(profile: ProviderProfile): List<ModelDescriptor> {
        val key = profile.id.raw
        byProvider[key]?.let { return it }
        return lock.withLock {
            byProvider[key]?.let { return@withLock it }
            val fetched = try {
                when (val outcome = catalog.listModels(profile)) {
                    is Outcome.Ok -> outcome.value
                    is Outcome.Err -> return@withLock emptyList()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                return@withLock emptyList()
            }
            byProvider[key] = fetched
            fetched
        }
    }

    companion object {
        private const val TOKENS_PER_MILLION: Double = 1_000_000.0
    }
}
