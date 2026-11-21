package com.deepseek.mobilechat.application.service

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.pricing.Fx
import com.deepseek.mobilechat.application.service.testutil.FakeModelCatalogPort
import com.deepseek.mobilechat.application.service.testutil.descriptor
import com.deepseek.mobilechat.application.service.testutil.providerProfile
import com.deepseek.mobilechat.domain.ModelDescriptor
import com.deepseek.mobilechat.domain.ModelId
import com.deepseek.mobilechat.domain.ModelPricing
import com.deepseek.mobilechat.domain.Usage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ModelCatalogCacheTest {

    private val profile = providerProfile()

    @Test
    fun `descriptor is fetched once and then served from memory`() = runTest {
        val catalog = FakeModelCatalogPort().apply { models = listOf(descriptor("model-1")) }
        val cache = ModelCatalogCache(catalog)

        assertNotNull(cache.descriptor(profile, ModelId("model-1")))
        assertNotNull(cache.descriptor(profile, ModelId("model-1")))

        assertEquals(1, catalog.listCalls)
    }

    @Test
    fun `unknown model id returns null`() = runTest {
        val catalog = FakeModelCatalogPort().apply { models = listOf(descriptor("model-1")) }
        val cache = ModelCatalogCache(catalog)

        assertNull(cache.descriptor(profile, ModelId("other")))
        assertEquals(1, catalog.listCalls)
    }

    @Test
    fun `catalog failure returns null and is not cached`() = runTest {
        val catalog = FakeModelCatalogPort().apply {
            listFailure = AppError.Network("offline")
        }
        val cache = ModelCatalogCache(catalog)

        assertNull(cache.descriptor(profile, ModelId("model-1")))
        assertEquals(1, catalog.listCalls)

        catalog.listFailure = null
        catalog.models = listOf(descriptor("model-1"))
        assertNotNull(cache.descriptor(profile, ModelId("model-1")))
        assertEquals(2, catalog.listCalls)
    }

    @Test
    fun `throwing catalog returns null instead of propagating`() = runTest {
        val catalog = FakeModelCatalogPort().apply {
            listThrows = IllegalStateException("boom")
        }
        val cache = ModelCatalogCache(catalog)

        assertNull(cache.descriptor(profile, ModelId("model-1")))
    }

    @Test
    fun `cost is null without pricing`() {
        val cache = ModelCatalogCache(FakeModelCatalogPort())
        val usage = Usage(promptTokens = 1_000, completionTokens = 2_000)

        assertNull(cache.costCny(descriptor(inputPerMTok = null, outputPerMTok = null), usage))
        assertNull(cache.costCny(null, usage))
        // Pricing absent from the catalog and the model unknown to the book.
        assertNull(
            cache.costCny(
                descriptor(id = "zzz-not-in-the-book", inputPerMTok = null, outputPerMTok = null),
                usage,
            ),
        )
    }

    @Test
    fun `cost uses per million token pricing converted to CNY`() {
        val cache = ModelCatalogCache(FakeModelCatalogPort())

        val cost = cache.costCny(
            descriptor(inputPerMTok = 3.0, outputPerMTok = 15.0),
            Usage(promptTokens = 1_000_000, completionTokens = 1_000_000),
        )

        // USD catalog price -> CNY at the fixed rate.
        assertEquals(18.0 * Fx.USD_TO_CNY, cost!!, 1e-9)
    }

    @Test
    fun `cache hits are billed at the discounted rate`() {
        val cache = ModelCatalogCache(FakeModelCatalogPort())
        val priced = ModelDescriptor(
            id = ModelId("cached-model"),
            displayName = "cached-model",
            pricing = ModelPricing(
                inputPerMTok = 10.0,
                outputPerMTok = 0.0,
                cachedInputPerMTok = 1.0,
            ),
        )

        // 1M prompt tokens of which 800k hit the cache: 200k*10 + 800k*1.
        val cost = cache.costCny(
            priced,
            Usage(promptTokens = 1_000_000, cachedPromptTokens = 800_000),
        )

        assertEquals(2.8 * Fx.USD_TO_CNY, cost!!, 1e-9)
    }

    @Test
    fun `cache hits cost the miss rate when the provider has no cache price`() {
        val cache = ModelCatalogCache(FakeModelCatalogPort())
        val priced = ModelDescriptor(
            id = ModelId("no-cache-price"),
            displayName = "no-cache-price",
            pricing = ModelPricing(inputPerMTok = 10.0, outputPerMTok = 0.0),
        )

        val cost = cache.costCny(priced, Usage(promptTokens = 1_000_000, cachedPromptTokens = 800_000))

        assertEquals(10.0 * Fx.USD_TO_CNY, cost!!, 1e-9)
    }

    @Test
    fun `deepseek prices come from the book and follow peak hours`() {
        val cache = ModelCatalogCache(FakeModelCatalogPort())
        val target = descriptor(id = "deepseek-flash", inputPerMTok = null, outputPerMTok = null)
        val usage = Usage(promptTokens = 1_000_000, completionTokens = 1_000_000)

        // 2026-11-23 is a Monday. 10:00 Beijing is peak; 20:00 is idle.
        val mondayPeak = java.time.OffsetDateTime.of(2026, 11, 23, 10, 0, 0, 0, java.time.ZoneOffset.ofHours(8))
        val mondayIdle = mondayPeak.withHour(20)

        val peak = cache.costCny(target, usage, mondayPeak.toInstant().toEpochMilli())
        val idle = cache.costCny(target, usage, mondayIdle.toInstant().toEpochMilli())

        // Peak: 1M input * ¥2 + 1M output * ¥8 = ¥10. Idle is exactly half.
        assertEquals(10.0, peak!!, 1e-9)
        assertEquals(5.0, idle!!, 1e-9)
    }
}
