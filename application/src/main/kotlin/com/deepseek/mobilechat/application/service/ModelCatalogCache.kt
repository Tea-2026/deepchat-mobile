package com.deepseek.mobilechat.application.service

import com.deepseek.mobilechat.application.ModelCatalogPort
import com.deepseek.mobilechat.application.Outcome
import com.deepseek.mobilechat.application.pricing.BuiltinPricing
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
 * 2. [costUsd] turns a [Usage] into money using the descriptor's pricing, so
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

    /** Cost in USD, or `null` when neither the catalog nor the offline table knows the model. */
    fun costUsd(descriptor: ModelDescriptor?, usage: Usage): Double? {
        val pricing = descriptor?.pricing
            ?: descriptor?.let { BuiltinPricing.lookup(it.id.raw) }
            ?: return null
        return (usage.promptTokens * pricing.inputPerMTokUsd +
            usage.completionTokens * pricing.outputPerMTokUsd) / TOKENS_PER_MILLION
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
