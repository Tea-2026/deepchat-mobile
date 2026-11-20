package com.deepseek.mobilechat.application.service

import com.deepseek.mobilechat.application.AppError
import com.deepseek.mobilechat.application.service.testutil.FakeModelCatalogPort
import com.deepseek.mobilechat.application.service.testutil.descriptor
import com.deepseek.mobilechat.application.service.testutil.providerProfile
import com.deepseek.mobilechat.domain.ModelId
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

        assertNull(cache.costUsd(descriptor(inputPerMTok = null, outputPerMTok = null), usage))
        assertNull(cache.costUsd(null, usage))
    }

    @Test
    fun `cost uses per million token pricing`() {
        val cache = ModelCatalogCache(FakeModelCatalogPort())

        val cost = cache.costUsd(
            descriptor(inputPerMTok = 3.0, outputPerMTok = 15.0),
            Usage(promptTokens = 1_000_000, completionTokens = 1_000_000),
        )

        assertEquals(18.0, cost!!, 1e-9)
    }
}
